package org.yu.projectcx.network.signaling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.util.AsyncExecutor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client for connecting to a Hosted Signaling Server or directly to a remote peer.
 */
public class SignalingClient {

    private static final Logger logger = LoggerFactory.getLogger(SignalingClient.class);
    private static final int CONNECT_TIMEOUT_MS = 1500;

    private final List<SignalingEventListener> listeners;
    private final AtomicBoolean isConnected;

    private Socket persistentSocket;
    private BufferedWriter persistentWriter;
    private BufferedReader persistentReader;
    private String localPeerId;

    public SignalingClient() {
        this.listeners = new CopyOnWriteArrayList<>();
        this.isConnected = new AtomicBoolean(false);
    }

    /**
     * Connects to a Hosted Signaling Server and registers the local peerId.
     */
    public CompletableFuture<Void> connectToHostedServerAsync(String host, int port, String localPeerId) {
        return AsyncExecutor.supplyAsyncNetwork(() -> {
            try {
                connectToHostedServer(host, port, localPeerId);
                return null;
            } catch (IOException e) {
                logger.error("Failed to connect to hosted signaling server {}:{} - {}", host, port, e.getMessage());
                throw new RuntimeException("Signaling server connection failed: " + e.getMessage(), e);
            }
        });
    }

    public synchronized void connectToHostedServer(String host, int port, String localPeerId) throws IOException {
        disconnect();

        this.localPeerId = localPeerId;
        this.persistentSocket = new Socket();
        this.persistentSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        this.persistentWriter = new BufferedWriter(new OutputStreamWriter(persistentSocket.getOutputStream(), StandardCharsets.UTF_8));
        this.persistentReader = new BufferedReader(new InputStreamReader(persistentSocket.getInputStream(), StandardCharsets.UTF_8));
        this.isConnected.set(true);

        // Send REGISTER packet
        SignalingMessage regMsg = new SignalingMessage(SignalingType.REGISTER, localPeerId, "SERVER");
        sendPersistentMessage(regMsg);

        // Start background reader loop
        Thread readerThread = new Thread(this::persistentReadLoop, "SignalingClient-Reader-" + localPeerId);
        readerThread.setDaemon(true);
        readerThread.start();

        logger.info("Connected & registered [{}] on Hosted Signaling Server at {}:{}", localPeerId, host, port);
    }

    private void persistentReadLoop() {
        try {
            String line;
            while (isConnected.get() && persistentReader != null && (line = persistentReader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    SignalingMessage msg = SignalingMessage.deserialize(line);
                    dispatchMessage(msg);
                } catch (Exception ex) {
                    logger.warn("Could not deserialize packet from signaling server: {}", line, ex);
                }
            }
        } catch (IOException e) {
            if (isConnected.get()) {
                logger.info("Signaling connection closed by server.");
            }
        } finally {
            disconnect();
        }
    }

    private void dispatchMessage(SignalingMessage msg) {
        for (SignalingEventListener l : listeners) {
            try {
                switch (msg.getType()) {
                    case OFFER -> l.onOfferReceived(msg);
                    case ANSWER -> l.onAnswerReceived(msg);
                    case ICE_CANDIDATE -> l.onIceCandidateReceived(msg);
                    case PEER_JOINED, PEER_LIST -> l.onPeerConnected(msg.getSenderPeerId(), "HOSTED_SERVER");
                    case PEER_LEFT, BYE -> l.onPeerDisconnected(msg.getSenderPeerId());
                }
            } catch (Exception ex) {
                l.onSignalingError("Error in signaling dispatch: " + msg.getType(), ex);
            }
        }
    }

    /**
     * Sends a signaling message over the persistent connection to the hosted server.
     */
    public synchronized void sendPersistentMessage(SignalingMessage message) throws IOException {
        if (!isConnected.get() || persistentWriter == null) {
            throw new IOException("Not connected to Hosted Signaling Server.");
        }
        persistentWriter.write(message.serialize());
        persistentWriter.newLine();
        persistentWriter.flush();
    }

    /**
     * Sends a signaling message to a remote peer asynchronously.
     */
    public CompletableFuture<Void> sendMessageAsync(String host, int port, SignalingMessage message) {
        return AsyncExecutor.supplyAsyncNetwork(() -> {
            try {
                sendMessageDirect(host, port, message);
                return null;
            } catch (IOException e) {
                throw new RuntimeException("Failed to send signaling message: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Direct one-off socket message to a peer's local signaling server.
     */
    public void sendMessageDirect(String host, int port, SignalingMessage message) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(message.serialize());
                writer.newLine();
                writer.flush();
            }
        }
    }

    public synchronized void disconnect() {
        isConnected.set(false);
        try {
            if (persistentSocket != null && !persistentSocket.isClosed()) {
                persistentSocket.close();
            }
        } catch (IOException ignored) {}
    }

    public boolean isConnected() {
        return isConnected.get();
    }

    public void addListener(SignalingEventListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(SignalingEventListener listener) {
        listeners.remove(listener);
    }
}
