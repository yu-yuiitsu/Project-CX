package org.yu.projectcx.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.db.DatabaseManager;
import org.yu.projectcx.db.MessageRepository;
import org.yu.projectcx.db.PeerRepository;
import org.yu.projectcx.db.UserRepository;
import org.yu.projectcx.model.FileTransfer;
import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.model.User;
import org.yu.projectcx.network.signaling.SignalingManager;
import org.yu.projectcx.util.AsyncExecutor;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Central application manager coordinating GUI, Multithreading, Business Logic, and SQLite persistence.
 * 
 * Demonstrates:
 * - Multithreading & Asynchronous Task Separation:
 *   • Database queries run off-thread via AsyncExecutor.supplyAsyncDb(...)
 *   • Network transmissions run off-thread via AsyncExecutor.supplyAsyncNetwork(...)
 *   • UI callbacks dispatched safely via AsyncExecutor.runOnFxThread(...)
 */
public class ChatManager {

    private static final Logger logger = LoggerFactory.getLogger(ChatManager.class);
    private static ChatManager instance;

    private final DatabaseManager databaseManager;
    private final UserRepository userRepository;
    private final PeerRepository peerRepository;
    private final MessageRepository messageRepository;
    private final PeerManager peerManager;
    private final SignalingManager signalingManager;
    private final Map<String, ChatSession> activeSessions;
    private final List<ChatEventListener> eventListeners;

    private User currentUser;
    private String activePeerId;
    private org.yu.projectcx.network.discovery.LanDiscoveryService lanDiscoveryService;

    public ChatManager() {
        this(DatabaseManager.getInstance());
    }

    public ChatManager(DatabaseManager databaseManager) {
        this(databaseManager, new SignalingManager());
    }

    public ChatManager(DatabaseManager databaseManager, SignalingManager signalingManager) {
        if (databaseManager == null) {
            throw new IllegalArgumentException("DatabaseManager cannot be null.");
        }
        this.databaseManager = databaseManager;
        this.userRepository = new UserRepository(databaseManager);
        this.peerRepository = new PeerRepository(databaseManager);
        this.messageRepository = new MessageRepository(databaseManager);
        this.peerManager = new PeerManager();
        this.signalingManager = signalingManager != null ? signalingManager : new SignalingManager();
        this.activeSessions = new LinkedHashMap<>();
        this.eventListeners = new ArrayList<>();

        setupSignalingPeerListener();
        syncPeersFromDatabase();
        startSignalingServer();
    }

    private void setupSignalingPeerListener() {
        this.signalingManager.addListener(new org.yu.projectcx.network.signaling.SignalingEventListener() {
            @Override
            public void onOfferReceived(org.yu.projectcx.network.signaling.SignalingMessage msg) {
                handleIncomingSignalingPeer(msg.getSenderPeerId());
            }

            @Override
            public void onAnswerReceived(org.yu.projectcx.network.signaling.SignalingMessage msg) {
                handleIncomingSignalingPeer(msg.getSenderPeerId());
            }

            @Override
            public void onIceCandidateReceived(org.yu.projectcx.network.signaling.SignalingMessage msg) {
                handleIncomingSignalingPeer(msg.getSenderPeerId());
            }

            @Override
            public void onPeerConnected(String remotePeerId, String remoteAddress) {
                handleIncomingSignalingPeer(remotePeerId);
            }

            @Override
            public void onPeerDisconnected(String remotePeerId) {
                onLanPeerOffline(remotePeerId);
            }

            @Override
            public void onChatMessageReceived(org.yu.projectcx.network.signaling.SignalingMessage msg) {
                handleIncomingSignalingPeer(msg.getSenderPeerId());
                receiveIncomingMessageAsync(msg.getSenderPeerId(), msg.getSdp());
            }

            @Override
            public void onSignalingError(String errorMessage, Throwable cause) {}
        });
    }

    private void handleIncomingSignalingPeer(String remotePeerId) {
        if (remotePeerId == null || remotePeerId.isEmpty()) return;
        if (currentUser != null && (
                remotePeerId.equals(currentUser.getUserId()) ||
                remotePeerId.equalsIgnoreCase(currentUser.getUsername()) ||
                remotePeerId.equalsIgnoreCase(currentUser.getDisplayName()) ||
                remotePeerId.equalsIgnoreCase("peer_" + currentUser.getUsername())
        )) {
            return;
        }

        Optional<Peer> existing = peerManager.getAllPeers().stream()
                .filter(p -> p.getPeerId().equalsIgnoreCase(remotePeerId) || p.getAlias().equalsIgnoreCase(remotePeerId))
                .findFirst();

        if (existing.isPresent()) {
            existing.get().setOnline(true);
        } else {
            String alias = remotePeerId.startsWith("peer_") ? remotePeerId.substring(5) : remotePeerId;
            Peer newPeer = new Peer(remotePeerId, alias, "127.0.0.1", 8888);
            newPeer.setOnline(true);
            peerManager.addPeer(newPeer);
            try {
                peerRepository.savePeer(newPeer);
            } catch (SQLException ignored) {}
        }

        AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
    }

    private void startSignalingServer() {
        try {
            if (!this.signalingManager.isRunning()) {
                this.signalingManager.start();
                logger.info("ChatManager automatically started local SignalingServer on port {}", this.signalingManager.getLocalPort());
            }
        } catch (Exception e) {
            logger.warn("SignalingServer could not be auto-started: {}", e.getMessage());
        }
    }

    public static synchronized ChatManager getInstance() {
        if (instance == null) {
            instance = new ChatManager(DatabaseManager.getInstance());
        }
        return instance;
    }

    // ==========================================
    // Asynchronous Authentication (Off-GUI Thread)
    // ==========================================

    /**
     * Authenticates user asynchronously on a background database thread.
     */
    public CompletableFuture<Optional<User>> loginAsync(String username, String password) {
        return AsyncExecutor.supplyAsyncDb(() -> {
            logger.info("Executing loginAsync on thread: {}", Thread.currentThread().getName());
            Optional<User> userOpt = userRepository.login(username, password);
            userOpt.ifPresent(this::setCurrentUser);
            return userOpt;
        });
    }

    /**
     * Registers a new user asynchronously on a background database thread.
     */
    public CompletableFuture<User> registerAsync(String username, String password, String displayName) {
        return AsyncExecutor.supplyAsyncDb(() -> {
            try {
                logger.info("Executing registerAsync on thread: {}", Thread.currentThread().getName());
                User newUser = new User(username);
                if (displayName != null && !displayName.trim().isEmpty()) {
                    newUser.setDisplayName(displayName.trim());
                }
                newUser.setStatusMessage("Ready to connect");
                userRepository.saveUser(newUser, password);
                return newUser;
            } catch (SQLException e) {
                logger.error("Failed to register user asynchronously", e);
                throw new RuntimeException("Registration failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Scans and retrieves all registered local users asynchronously.
     */
    public CompletableFuture<List<User>> getAllUsersAsync() {
        return AsyncExecutor.supplyAsyncDb(() -> {
            logger.info("Scanning all local users from database on thread: {}", Thread.currentThread().getName());
            return userRepository.getAllUsers();
        });
    }

    /**
     * Authenticates with password and deletes user and their conversation data asynchronously.
     */
    public CompletableFuture<Boolean> deleteUserWithPasswordAsync(String username, String password) {
        return AsyncExecutor.supplyAsyncDb(() -> {
            logger.info("Executing deleteUserWithPasswordAsync for user [{}] on thread: {}", username, Thread.currentThread().getName());
            return userRepository.deleteUserWithPassword(username, password);
        });
    }

    // Synchronous fallback
    public Optional<User> login(String username, String password) {
        Optional<User> userOpt = userRepository.login(username, password);
        userOpt.ifPresent(this::setCurrentUser);
        return userOpt;
    }

    public User register(String username, String password, String displayName) throws SQLException {
        User newUser = new User(username);
        if (displayName != null && !displayName.trim().isEmpty()) {
            newUser.setDisplayName(displayName.trim());
        }
        newUser.setStatusMessage("Ready to connect");
        userRepository.saveUser(newUser, password);
        return newUser;
    }

    public void setCurrentUser(User user) {
        this.currentUser = user;
        this.activeSessions.clear();
        if (user != null) {
            logger.info("Active user set in ChatManager: {}", user.getUsername());
            syncPeersFromDatabase();
            startLanDiscovery(user);
        } else {
            stopLanDiscovery();
        }
    }

    private synchronized void startLanDiscovery(User user) {
        stopLanDiscovery();
        try {
            this.lanDiscoveryService = new org.yu.projectcx.network.discovery.LanDiscoveryService(
                    user.getUsername(),
                    user.getDisplayName(),
                    signalingManager.getLocalPort(),
                    peerRepository
            );
            this.lanDiscoveryService.addListener(new org.yu.projectcx.network.discovery.LanDiscoveryService.PeerDiscoveryListener() {
                @Override
                public void onPeerDiscovered(Peer peer) {
                    onLanPeerDiscovered(peer);
                }

                @Override
                public void onPeerOffline(String peerId) {
                    onLanPeerOffline(peerId);
                }
            });
            this.lanDiscoveryService.start();
        } catch (Exception e) {
            logger.warn("Could not initialize LAN discovery: {}", e.getMessage());
        }
    }

    private synchronized void stopLanDiscovery() {
        if (this.lanDiscoveryService != null) {
            this.lanDiscoveryService.stop();
            this.lanDiscoveryService = null;
        }
    }

    public void onLanPeerDiscovered(Peer peer) {
        if (peer == null) return;
        if (currentUser != null && (
                peer.getPeerId().equals(currentUser.getUserId()) ||
                peer.getPeerId().equalsIgnoreCase(currentUser.getUsername()) ||
                peer.getAlias().equalsIgnoreCase(currentUser.getUsername()) ||
                peer.getAlias().equalsIgnoreCase(currentUser.getDisplayName()) ||
                peer.getPeerId().equalsIgnoreCase("peer_" + currentUser.getUsername())
        )) {
            return;
        }
        if (peer.getAlias().startsWith("Peer:")) return;

        Optional<Peer> existing = peerManager.getAllPeers().stream()
                .filter(p -> p.getPeerId().equalsIgnoreCase(peer.getPeerId()) || p.getAlias().equalsIgnoreCase(peer.getAlias()))
                .findFirst();

        if (existing.isPresent()) {
            Peer p = existing.get();
            p.setOnline(true);
            p.setIpAddress(peer.getIpAddress());
            p.setPort(peer.getPort());
            p.setAlias(peer.getAlias());

            ChatSession session = activeSessions.get(p.getPeerId());
            if (session != null) {
                session.getPeer().setPort(peer.getPort());
                session.getPeer().setIpAddress(peer.getIpAddress());
                session.getPeer().setOnline(true);
            }
            try {
                peerRepository.savePeer(p);
            } catch (SQLException ignored) {}
        } else {
            peerManager.addPeer(peer);
            try {
                peerRepository.savePeer(peer);
            } catch (SQLException ignored) {}
        }
        AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
    }

    public void onLanPeerOffline(String peerId) {
        if (peerId == null) return;
        boolean changed = false;
        for (Peer p : peerManager.getAllPeers()) {
            if (p.getPeerId().equalsIgnoreCase(peerId) || p.getAlias().equalsIgnoreCase(peerId) || p.getPeerId().equalsIgnoreCase("peer_" + peerId)) {
                if (p.isOnline()) {
                    p.setOnline(false);
                    changed = true;
                }
            }
        }
        if (changed) {
            AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
        }
    }

    public void triggerLanScan() {
        if (lanDiscoveryService != null) {
            lanDiscoveryService.triggerLanScanAsync();
        }
    }

    public User getCurrentUser() {
        return currentUser;
    }

    public void logout() {
        if (currentUser != null) {
            String uid = currentUser.getUsername();
            if (peerRepository != null) {
                peerRepository.markPeerOffline(uid);
            }
            for (ChatSession session : activeSessions.values()) {
                try {
                    Peer p = session.getRemotePeer();
                    org.yu.projectcx.network.signaling.SignalingMessage byeMsg = 
                            new org.yu.projectcx.network.signaling.SignalingMessage(
                                    org.yu.projectcx.network.signaling.SignalingType.BYE, 
                                    uid, 
                                    p.getPeerId()
                            );
                    new org.yu.projectcx.network.signaling.SignalingClient().sendMessageDirect(p.getIpAddress(), p.getPort(), byeMsg);
                } catch (Exception ignored) {}
            }
        }
        stopLanDiscovery();
        this.currentUser = null;
        this.activePeerId = null;
        this.activeSessions.clear();
        logger.info("User logged out.");
    }

    // ==========================================
    // Asynchronous Messaging (Off-GUI Thread)
    // ==========================================

    /**
     * Dispatches a text message asynchronously:
     * - Network transmission on Network-Worker thread.
     * - SQLite database persistence on DB-Worker thread.
     * - UI notification synchronized to JavaFX Application Thread.
     */
    public CompletableFuture<TextMessage> sendMessageAsync(String text, String targetPeerId) {
        if (currentUser == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("User is not authenticated."));
        }
        if (targetPeerId == null || targetPeerId.trim().isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Target peer ID cannot be blank."));
        }

        return AsyncExecutor.supplyAsyncNetwork(() -> {
            logger.info("Executing sendMessageAsync (network) on thread: {}", Thread.currentThread().getName());
            ChatSession session = getOrCreateSession(targetPeerId);
            TextMessage message = session.sendMessage(text);

            // Persist to database asynchronously
            try {
                messageRepository.saveMessage(message);
            } catch (SQLException e) {
                logger.error("Failed to persist message asynchronously", e);
            }

            // Synchronize back to JavaFX thread for UI updates
            AsyncExecutor.runOnFxThread(() -> notifyMessageDispatched(message));
            return message;
        });
    }

    /**
     * Dispatches any generic payload asynchronously.
     */
    public CompletableFuture<NetworkPayload> sendMessageAsync(NetworkPayload payload, String targetPeerId) {
        return AsyncExecutor.supplyAsyncNetwork(() -> {
            logger.info("Executing generic payload transmission on thread: {}", Thread.currentThread().getName());
            ChatSession session = getOrCreateSession(targetPeerId);
            NetworkPayload dispatched = session.sendMessage(payload);

            try {
                messageRepository.saveMessage(dispatched);
            } catch (SQLException e) {
                logger.error("Failed to persist payload", e);
            }

            AsyncExecutor.runOnFxThread(() -> notifyPayloadDispatched(dispatched));
            return dispatched;
        });
    }

    /**
     * Dispatches a file transfer asynchronously.
     */
    public CompletableFuture<FileTransfer> sendFileAsync(String fileName, long fileSize, String checksum, String mimeType, String targetPeerId) {
        return AsyncExecutor.supplyAsyncNetwork(() -> {
            logger.info("Executing sendFileAsync on thread: {}", Thread.currentThread().getName());
            ChatSession session = getOrCreateSession(targetPeerId);
            FileTransfer fileTx = session.sendFile(fileName, fileSize, checksum, mimeType);

            try {
                messageRepository.saveMessage(fileTx);
            } catch (SQLException e) {
                logger.error("Failed to persist file transfer", e);
            }

            AsyncExecutor.runOnFxThread(() -> notifyPayloadDispatched(fileTx));
            return fileTx;
        });
    }

    /**
     * Receives an incoming message on a background thread and updates database/UI.
     */
    public CompletableFuture<TextMessage> receiveIncomingMessageAsync(String senderPeerId, String text) {
        return AsyncExecutor.supplyAsyncNetwork(() -> {
            if (currentUser == null) return null;
            logger.info("Processing incoming message on thread: {}", Thread.currentThread().getName());

            ChatSession session = getOrCreateSession(senderPeerId);
            TextMessage incoming = new TextMessage(senderPeerId, currentUser.getUserId(), text);
            session.receivePayload(incoming);
            return incoming;
        });
    }

    /**
     * Loads conversation history asynchronously from SQLite off the UI thread.
     */
    public CompletableFuture<List<NetworkPayload>> getConversationHistoryAsync(String peerId) {
        return AsyncExecutor.supplyAsyncDb(() -> {
            logger.info("Loading conversation history on thread: {}", Thread.currentThread().getName());
            if (currentUser == null || peerId == null) return Collections.emptyList();
            return messageRepository.getConversationForPeer(currentUser.getUserId(), currentUser.getUsername(), peerId, Integer.MAX_VALUE);
        });
    }

    // Synchronous Methods for backward compatibility
    public TextMessage sendMessage(String text) throws SQLException {
        if (activePeerId == null) throw new IllegalStateException("No active peer.");
        return sendMessage(text, activePeerId);
    }

    public TextMessage sendMessage(String text, String targetPeerId) throws SQLException {
        ChatSession session = getOrCreateSession(targetPeerId);
        TextMessage message = session.sendMessage(text);
        messageRepository.saveMessage(message);
        notifyMessageDispatched(message);
        return message;
    }

    public NetworkPayload sendMessage(NetworkPayload payload, String targetPeerId) throws SQLException {
        ChatSession session = getOrCreateSession(targetPeerId);
        NetworkPayload dispatched = session.sendMessage(payload);
        messageRepository.saveMessage(dispatched);
        notifyPayloadDispatched(dispatched);
        return dispatched;
    }

    public FileTransfer sendFile(String fileName, long fileSize, String checksum, String mimeType, String targetPeerId) throws SQLException {
        ChatSession session = getOrCreateSession(targetPeerId);
        FileTransfer fileTx = session.sendFile(fileName, fileSize, checksum, mimeType);
        messageRepository.saveMessage(fileTx);
        notifyPayloadDispatched(fileTx);
        return fileTx;
    }

    public TextMessage receiveIncomingMessage(String senderPeerId, String text) throws SQLException {
        if (currentUser == null) return null;
        ChatSession session = getOrCreateSession(senderPeerId);
        TextMessage incoming = new TextMessage(senderPeerId, currentUser.getUserId(), text);
        session.receivePayload(incoming);
        messageRepository.saveMessage(incoming);
        notifyMessageDispatched(incoming);
        return incoming;
    }

    // ==========================================
    // Peer & Session Operations
    // ==========================================

    public void selectPeer(String peerId) {
        this.activePeerId = peerId;
        peerManager.getPeer(peerId).ifPresent(this::notifyPeerSelected);
    }

    public synchronized ChatSession getOrCreateSession(String peerId) {
        if (activeSessions.containsKey(peerId)) {
            return activeSessions.get(peerId);
        }

        Peer peer = peerManager.getPeer(peerId).orElseGet(() -> {
            Peer fallback = new Peer(peerId, "Peer_" + peerId.substring(0, Math.min(peerId.length(), 6)));
            peerManager.addPeer(fallback);
            try {
                peerRepository.savePeer(fallback);
            } catch (SQLException ignored) {}
            return fallback;
        });

        ChatSession session = new ChatSession(currentUser, peer, signalingManager);
        session.setPayloadListener(new ChatSession.SessionPayloadListener() {
            @Override
            public void onPayloadReceived(NetworkPayload payload) {
                try {
                    messageRepository.saveMessage(payload);
                } catch (SQLException e) {
                    logger.error("Failed to persist incoming WebRTC payload to SQLite", e);
                }

                if (payload instanceof TextMessage tm) {
                    AsyncExecutor.runOnFxThread(() -> notifyMessageDispatched(tm));
                } else {
                    AsyncExecutor.runOnFxThread(() -> notifyPayloadDispatched(payload));
                }
            }

            @Override
            public void onSessionStateChanged(SessionState state) {
                AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
            }
        });

        activeSessions.put(peerId, session);
        return session;
    }

    public synchronized void connectToPeer(String peerId) {
        ChatSession session = getOrCreateSession(peerId);
        session.connect();
    }

    public CompletableFuture<Peer> addPeerAsync(String alias, String ipAddress, int port) {
        return AsyncExecutor.supplyAsyncDb(() -> {
            try {
                return addPeer(alias, ipAddress, port);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to add peer: " + e.getMessage(), e);
            }
        });
    }

    public Peer addPeer(String alias, String ipAddress, int port) throws SQLException {
        String peerId = "peer_" + alias.trim().toLowerCase().replaceAll("\\s+", "_");
        Peer newPeer = new Peer(peerId, alias.trim(), ipAddress.trim(), port);
        newPeer.setOnline(true);

        peerManager.addPeer(newPeer);
        peerRepository.savePeer(newPeer);

        AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
        return newPeer;
    }

    public void syncPeersFromDatabase() {
        List<Peer> dbPeers = peerRepository.getAllPeers();
        peerManager.clear();
        for (Peer p : dbPeers) {
            if (p.getAlias().startsWith("Peer:")) continue;
            if (currentUser != null && (
                    p.getPeerId().equals(currentUser.getUserId()) ||
                    p.getPeerId().equalsIgnoreCase(currentUser.getUsername()) ||
                    p.getAlias().equalsIgnoreCase(currentUser.getUsername()) ||
                    p.getAlias().equalsIgnoreCase(currentUser.getDisplayName()) ||
                    p.getPeerId().equalsIgnoreCase("peer_" + currentUser.getUsername())
            )) {
                continue;
            }
            p.setOnline(false); // On app boot, contacts start offline until live LAN/WebRTC presence is received
            peerManager.addPeer(p);
        }
    }

    public List<Peer> getAllPeers() {
        return peerManager.getAllPeers();
    }

    public Optional<Peer> getPeer(String peerId) {
        return peerManager.getPeer(peerId);
    }

    public String getActivePeerId() {
        return activePeerId;
    }

    public List<NetworkPayload> getConversationHistory(String peerId) {
        if (currentUser == null || peerId == null) return Collections.emptyList();
        return messageRepository.getConversation(currentUser.getUserId(), peerId);
    }

    // ==========================================
    // Event Observer Mechanism
    // ==========================================

    public void addEventListener(ChatEventListener listener) {
        if (listener != null && !eventListeners.contains(listener)) {
            eventListeners.add(listener);
        }
    }

    public void addListener(ChatEventListener listener) {
        addEventListener(listener);
    }

    public void removeEventListener(ChatEventListener listener) {
        eventListeners.remove(listener);
    }

    public void removeListener(ChatEventListener listener) {
        removeEventListener(listener);
    }

    public synchronized void shutdown() {
        logger.info("Shutting down ChatManager and disposing active sessions...");
        for (ChatSession session : activeSessions.values()) {
            try {
                session.closeSession();
            } catch (Exception ignored) {}
        }
        activeSessions.clear();
        eventListeners.clear();
    }

    private void notifyMessageDispatched(TextMessage message) {
        for (ChatEventListener listener : new ArrayList<>(eventListeners)) {
            listener.onMessageDispatched(message);
        }
    }

    private void notifyPayloadDispatched(NetworkPayload payload) {
        for (ChatEventListener listener : new ArrayList<>(eventListeners)) {
            listener.onPayloadDispatched(payload);
        }
    }

    private void notifyPeerSelected(Peer peer) {
        for (ChatEventListener listener : new ArrayList<>(eventListeners)) {
            listener.onPeerSelected(peer);
        }
    }

    private void notifyPeersUpdated(List<Peer> peers) {
        for (ChatEventListener listener : new ArrayList<>(eventListeners)) {
            listener.onPeersUpdated(peers);
        }
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public UserRepository getUserRepository() {
        return userRepository;
    }

    public PeerRepository getPeerRepository() {
        return peerRepository;
    }

    public MessageRepository getMessageRepository() {
        return messageRepository;
    }

    public PeerManager getPeerManager() {
        return peerManager;
    }

    public SignalingManager getSignalingManager() {
        return signalingManager;
    }

    public Map<String, ChatSession> getActiveSessions() {
        return Collections.unmodifiableMap(activeSessions);
    }
}
