package org.yu.projectcx.network.signaling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.util.AsyncExecutor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight TCP Signaling Server that listens for incoming SDP Offers, Answers, and ICE candidates.
 * Runs in a dedicated background worker thread off the JavaFX UI thread.
 */
public class SignalingServer {

    private static final Logger logger = LoggerFactory.getLogger(SignalingServer.class);

    private int port;
    private final List<SignalingEventListener> listeners;
    private final List<ClientHandler> activeConnections;
    private final AtomicBoolean isRunning;
    private ServerSocket serverSocket;
    private Thread serverThread;

    public SignalingServer(int port) {
        this.port = port;
        this.listeners = new CopyOnWriteArrayList<>();
        this.activeConnections = new CopyOnWriteArrayList<>();
        this.isRunning = new AtomicBoolean(false);
    }

    public synchronized void start() throws IOException {
        if (isRunning.get()) {
            logger.warn("SignalingServer is already running on port {}", port);
            return;
        }

        try {
            serverSocket = new ServerSocket(port);
        } catch (BindException be) {
            logger.warn("SignalingServer requested port {} is in use. Binding to dynamic available port...", port);
            serverSocket = new ServerSocket(0);
            this.port = serverSocket.getLocalPort();
        }
        isRunning.set(true);

        serverThread = new Thread(this::listenLoop, "Signaling-Server-Listener-" + getPort());
        serverThread.setDaemon(true);
        serverThread.start();

        logger.info("SignalingServer successfully started on port {}", getPort());
    }

    private void listenLoop() {
        while (isRunning.get() && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                logger.info("Accepted incoming signaling connection from: {}", clientSocket.getRemoteSocketAddress());

                ClientHandler handler = new ClientHandler(clientSocket);
                activeConnections.add(handler);
                AsyncExecutor.runAsyncNetwork(handler::run);
            } catch (SocketException se) {
                if (!isRunning.get()) {
                    break; // Normal shutdown
                }
                logger.error("SignalingServer socket error", se);
            } catch (IOException e) {
                if (isRunning.get()) {
                    logger.error("SignalingServer error accepting connection", e);
                }
            }
        }
    }

    public synchronized void stop() {
        if (!isRunning.compareAndSet(true, false)) {
            return;
        }

        logger.info("Stopping SignalingServer on port {}", getPort());
        for (ClientHandler handler : activeConnections) {
            handler.close();
        }
        activeConnections.clear();

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                logger.warn("Error closing SignalingServer socket", e);
            }
        }
    }

    public void addListener(SignalingEventListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(SignalingEventListener listener) {
        listeners.remove(listener);
    }

    public int getPort() {
        return (serverSocket != null && !serverSocket.isClosed()) ? serverSocket.getLocalPort() : port;
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    // ==========================================
    // Internal Client Handler
    // ==========================================

    private class ClientHandler {
        private final Socket socket;
        private BufferedReader reader;
        private BufferedWriter writer;
        private String peerIdentifier;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        public void run() {
            try {
                this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    try {
                        SignalingMessage message = SignalingMessage.deserialize(line);
                        this.peerIdentifier = message.getSenderPeerId();

                        // Notify listeners on local node
                        notifyMessageReceived(message);

                        // Acknowledge receipt
                        writer.write("{\"status\":\"OK\"}\n");
                        writer.flush();
                    } catch (Exception e) {
                        logger.error("Failed to parse incoming signaling message: {}", line, e);
                        writer.write("{\"status\":\"ERROR\",\"message\":\"" + e.getMessage() + "\"}\n");
                        writer.flush();
                    }
                }
            } catch (SocketException se) {
                // Connection reset/closed
            } catch (IOException e) {
                logger.debug("Signaling client connection ended: {}", socket.getRemoteSocketAddress());
            } finally {
                close();
            }
        }

        public void close() {
            activeConnections.remove(this);
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException ignored) {}
        }
    }

    private void notifyMessageReceived(SignalingMessage message) {
        for (SignalingEventListener listener : listeners) {
            switch (message.getType()) {
                case OFFER -> listener.onOfferReceived(message);
                case ANSWER -> listener.onAnswerReceived(message);
                case ICE_CANDIDATE -> listener.onIceCandidateReceived(message);
                case REGISTER, HEARTBEAT -> listener.onPeerConnected(message.getSenderPeerId(), message.getRecipientPeerId());
                case BYE -> listener.onPeerDisconnected(message.getSenderPeerId());
                case CHAT_MESSAGE -> listener.onChatMessageReceived(message);
            }
        }
    }
}
