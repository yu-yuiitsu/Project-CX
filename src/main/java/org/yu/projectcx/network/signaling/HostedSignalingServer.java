package org.yu.projectcx.network.signaling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Internet-Hosted Central Signaling Server for WebRTC P2P connection establishment.
 * 
 * Role & Distinction:
 * - This server ONLY routes SDP Offers, SDP Answers, ICE Candidates, and Peer Registrations.
 * - This server DOES NOT handle chat messages or file transfers; once peers negotiate SDP/ICE,
 *   direct WebRTC DataChannel takes over peer-to-peer!
 * 
 * Deployment:
 * Can run standalone on any cloud VPS (AWS, DigitalOcean, Oracle Cloud, Render, Railway, etc.):
 *   java -cp target/Project-CX-1.0-SNAPSHOT.jar org.yu.projectcx.network.signaling.HostedSignalingServer 8888
 */
public class HostedSignalingServer {

    private static final Logger logger = LoggerFactory.getLogger(HostedSignalingServer.class);
    public static final int DEFAULT_PORT = 8888;

    private final int port;
    private final Map<String, ClientConnection> registeredPeers;
    private final ExecutorService threadPool;
    private final AtomicBoolean isRunning;
    private ServerSocket serverSocket;

    public HostedSignalingServer() {
        this(DEFAULT_PORT);
    }

    public HostedSignalingServer(int port) {
        this.port = port;
        this.registeredPeers = new ConcurrentHashMap<>();
        this.threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "Signaling-Client-Worker");
            t.setDaemon(true);
            return t;
        });
        this.isRunning = new AtomicBoolean(false);
    }

    public synchronized void start() throws IOException {
        if (isRunning.get()) return;

        serverSocket = new ServerSocket(port);
        isRunning.set(true);

        Thread acceptThread = new Thread(this::acceptLoop, "Hosted-Signaling-Server-Listener-" + port);
        acceptThread.setDaemon(true);
        acceptThread.start();

        logger.info("==================================================================");
        logger.info("🚀 Hosted WebRTC Signaling Server active on port {}", port);
        logger.info("Ready to negotiate SDP offers/answers and ICE candidates for peers");
        logger.info("==================================================================");
    }

    private void acceptLoop() {
        while (isRunning.get() && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                logger.info("New signaling connection from: {}", socket.getRemoteSocketAddress());
                ClientConnection conn = new ClientConnection(socket);
                threadPool.submit(conn::run);
            } catch (SocketException se) {
                if (!isRunning.get()) break;
                logger.error("Signaling server socket exception", se);
            } catch (IOException e) {
                if (isRunning.get()) logger.error("Error accepting signaling socket", e);
            }
        }
    }

    public synchronized void stop() {
        if (!isRunning.compareAndSet(true, false)) return;

        logger.info("Shutting down Hosted Signaling Server...");
        for (ClientConnection conn : registeredPeers.values()) {
            conn.close();
        }
        registeredPeers.clear();

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
        threadPool.shutdownNow();
        logger.info("Hosted Signaling Server stopped.");
    }

    public int getPort() {
        return (serverSocket != null && serverSocket.isBound()) ? serverSocket.getLocalPort() : port;
    }

    public int getRegisteredPeerCount() {
        return registeredPeers.size();
    }

    public Map<String, ClientConnection> getRegisteredPeers() {
        return registeredPeers;
    }

    /**
     * Standalone main method to run this server on any cloud VPS or Docker container.
     */
    public static void main(String[] args) {
        int serverPort = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                serverPort = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }

        HostedSignalingServer server = new HostedSignalingServer(serverPort);
        try {
            server.start();
            System.out.println("Signaling server is running. Press Ctrl+C to terminate.");

            // Keep main thread alive
            Thread.currentThread().join();
        } catch (Exception e) {
            System.err.println("Fatal error starting Hosted Signaling Server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Represents a single connected peer client.
     */
    public class ClientConnection {
        private final Socket socket;
        private BufferedReader reader;
        private BufferedWriter writer;
        private String peerId;

        public ClientConnection(Socket socket) {
            this.socket = socket;
        }

        public void run() {
            try {
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;

                    SignalingMessage msg;
                    try {
                        msg = SignalingMessage.deserialize(line);
                    } catch (Exception ex) {
                        logger.warn("Malformed packet received: {}", line);
                        continue;
                    }

                    handleMessage(msg);
                }
            } catch (IOException e) {
                logger.debug("Signaling client disconnected: {}", socket.getRemoteSocketAddress());
            } finally {
                close();
            }
        }

        private void handleMessage(SignalingMessage msg) {
            switch (msg.getType()) {
                case REGISTER -> {
                    this.peerId = msg.getSenderPeerId();
                    registeredPeers.put(peerId, this);
                    logger.info("👤 Peer registered: [{}] from {}", peerId, socket.getRemoteSocketAddress());

                    // Send current online peers list back to new peer
                    String onlinePeersCsv = String.join(",", registeredPeers.keySet());
                    SignalingMessage listMsg = new SignalingMessage(SignalingType.PEER_LIST, "SERVER", peerId, onlinePeersCsv);
                    send(listMsg);

                    // Broadcast PEER_JOINED to all other peers
                    broadcast(new SignalingMessage(SignalingType.PEER_JOINED, peerId, null, peerId), peerId);
                }
                case OFFER, ANSWER, ICE_CANDIDATE, HEARTBEAT, BYE -> {
                    String recipient = msg.getRecipientPeerId();
                    if (recipient != null && registeredPeers.containsKey(recipient)) {
                        logger.info("Routing [{}] from [{}] -> [{}]", msg.getType(), msg.getSenderPeerId(), recipient);
                        registeredPeers.get(recipient).send(msg);
                    } else {
                        logger.warn("Cannot route [{}]: Recipient [{}] is not connected", msg.getType(), recipient);
                    }
                }
                default -> logger.warn("Unhandled message type: {}", msg.getType());
            }
        }

        public synchronized void send(SignalingMessage msg) {
            try {
                if (writer != null && !socket.isClosed()) {
                    writer.write(msg.serialize());
                    writer.newLine();
                    writer.flush();
                }
            } catch (IOException e) {
                logger.warn("Failed to write to peer [{}]: {}", peerId, e.getMessage());
            }
        }

        public void close() {
            if (peerId != null) {
                registeredPeers.remove(peerId);
                logger.info("Peer disconnected: [{}]", peerId);
                broadcast(new SignalingMessage(SignalingType.PEER_LEFT, peerId, null, peerId), peerId);
            }
            try {
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (IOException ignored) {}
        }
    }

    private void broadcast(SignalingMessage msg, String excludePeerId) {
        for (Map.Entry<String, ClientConnection> entry : registeredPeers.entrySet()) {
            if (!entry.getKey().equals(excludePeerId)) {
                entry.getValue().send(msg);
            }
        }
    }
}
