package org.yu.projectcx.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.db.DatabaseManager;
import org.yu.projectcx.db.MessageRepository;
import org.yu.projectcx.db.PeerRepository;
import org.yu.projectcx.db.UserRepository;
import org.yu.projectcx.model.ConnectionStatus;
import org.yu.projectcx.model.FileTransfer;
import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.model.User;
import org.yu.projectcx.network.signaling.SignalingManager;
import org.yu.projectcx.util.AsyncExecutor;

import java.sql.SQLException;
import java.time.LocalDateTime;
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
                handlePeerExplicitDisconnected(remotePeerId);
            }

            @Override
            public void onChatMessageReceived(org.yu.projectcx.network.signaling.SignalingMessage msg) {
                handleIncomingSignalingPeer(msg.getSenderPeerId());
                boolean isGroup = GroupChatSession.GROUP_PEER_ID.equals(msg.getRecipientPeerId());
                receiveIncomingMessageAsync(msg.getSenderPeerId(), msg.getSdp(), isGroup);
            }

            @Override
            public void onConnectionRequestReceived(org.yu.projectcx.network.signaling.SignalingMessage msg) {
                handleIncomingConnectionRequest(msg.getSenderPeerId(), msg.getSdp());
            }

            @Override
            public void onConnectionAccepted(org.yu.projectcx.network.signaling.SignalingMessage msg) {
                handleIncomingConnectionAccepted(msg.getSenderPeerId(), msg.getSdp());
            }

            @Override
            public void onConnectionRejected(org.yu.projectcx.network.signaling.SignalingMessage msg) {
                handleIncomingConnectionRejected(msg.getSenderPeerId());
            }

            @Override
            public void onPeerNetworkQueryReceived(org.yu.projectcx.network.signaling.SignalingMessage msg) {
                handleIncomingPeerNetworkQuery(msg);
            }

            @Override
            public void onPeerNetworkResponseReceived(org.yu.projectcx.network.signaling.SignalingMessage msg) {
                handleIncomingPeerNetworkResponse(msg);
            }

            @Override
            public void onGroupJoinRequestReceived(org.yu.projectcx.network.signaling.SignalingMessage msg) {
                handleIncomingGroupJoinRequest(msg);
            }

            @Override
            public void onGroupIntroduceReceived(org.yu.projectcx.network.signaling.SignalingMessage msg) {
                handleIncomingGroupIntroduce(msg);
            }

            @Override
            public void onSignalingError(String errorMessage, Throwable cause) {}
        });
    }

    void handleIncomingConnectionRequest(String remotePeerId, String remoteAlias) {
        if (remotePeerId == null || remotePeerId.isEmpty()) return;
        if (currentUser != null && (
                remotePeerId.equals(currentUser.getUserId()) ||
                remotePeerId.equalsIgnoreCase(currentUser.getUsername()) ||
                remotePeerId.equalsIgnoreCase(currentUser.getDisplayName()) ||
                remotePeerId.equalsIgnoreCase("peer_" + currentUser.getUsername())
        )) {
            return;
        }

        String alias = (remoteAlias != null && !remoteAlias.trim().isEmpty()) ? remoteAlias.trim() : remotePeerId;
        int remotePort = 8888;
        if (alias.contains(":")) {
            int colonIdx = alias.lastIndexOf(':');
            String portStr = alias.substring(colonIdx + 1).trim();
            try {
                remotePort = Integer.parseInt(portStr);
                alias = alias.substring(0, colonIdx).trim();
            } catch (NumberFormatException ignored) {}
        }
        if (alias.isEmpty()) {
            alias = remotePeerId;
        }

        final String finalAlias = alias;
        final int finalRemotePort = remotePort;

        Optional<Peer> existing = peerManager.getAllPeers().stream()
                .filter(p -> p.getPeerId().equalsIgnoreCase(remotePeerId) 
                          || p.getAlias().equalsIgnoreCase(remotePeerId)
                          || p.getAlias().equalsIgnoreCase(finalAlias))
                .findFirst();

        Peer p;
        if (existing.isPresent()) {
            p = existing.get();
            p.setOnline(true);
            p.setConnectionStatus(ConnectionStatus.REQUEST_RECEIVED);
            if (!finalAlias.equals(remotePeerId) && !finalAlias.isEmpty()) p.setAlias(finalAlias);
            if (finalRemotePort > 0) p.setPort(finalRemotePort);
            peerManager.addPeer(p);
        } else {
            p = new Peer(remotePeerId, finalAlias, "127.0.0.1", finalRemotePort);
            p.setOnline(true);
            p.setConnectionStatus(ConnectionStatus.REQUEST_RECEIVED);
            peerManager.addPeer(p);
        }
        try {
            peerRepository.savePeer(p);
            peerRepository.updateConnectionStatus(p.getPeerId(), ConnectionStatus.REQUEST_RECEIVED);
        } catch (SQLException ignored) {}

        logger.info("Incoming connection request from peer: {} ({}) on port {}", p.getAlias(), p.getPeerId(), p.getPort());
        AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
    }

    void handleIncomingConnectionAccepted(String remotePeerId) {
        handleIncomingConnectionAccepted(remotePeerId, null);
    }

    void handleIncomingConnectionAccepted(String remotePeerId, String remotePortStr) {
        if (remotePeerId == null || remotePeerId.isEmpty()) return;
        int remotePort = -1;
        if (remotePortStr != null && !remotePortStr.trim().isEmpty()) {
            try {
                remotePort = Integer.parseInt(remotePortStr.trim());
            } catch (NumberFormatException ignored) {}
        }

        Optional<Peer> existing = peerManager.getAllPeers().stream()
                .filter(p -> p.getPeerId().equalsIgnoreCase(remotePeerId) || p.getAlias().equalsIgnoreCase(remotePeerId))
                .findFirst();

        Peer p;
        if (existing.isPresent()) {
            p = existing.get();
            p.setOnline(true);
            p.setConnectionStatus(ConnectionStatus.CONNECTED);
            if (remotePort > 0) {
                p.setPort(remotePort);
            }
            try {
                peerRepository.savePeer(p);
                peerRepository.updateConnectionStatus(p.getPeerId(), ConnectionStatus.CONNECTED);
            } catch (Exception ignored) {}
            logger.info("Connection accepted by remote peer: {} ({}) on port {}", p.getAlias(), p.getPeerId(), p.getPort());
        } else {
            int finalPort = remotePort > 0 ? remotePort : 8888;
            p = new Peer(remotePeerId, remotePeerId, "127.0.0.1", finalPort);
            p.setOnline(true);
            p.setConnectionStatus(ConnectionStatus.CONNECTED);
            peerManager.addPeer(p);
            try {
                peerRepository.savePeer(p);
            } catch (SQLException ignored) {}
            logger.info("Connection accepted by remote peer (registered new): {} ({}) on port {}", p.getAlias(), p.getPeerId(), finalPort);
        }
        connectToPeer(p.getPeerId());
        AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
    }

    void handleIncomingConnectionRejected(String remotePeerId) {
        if (remotePeerId == null || remotePeerId.isEmpty()) return;
        Optional<Peer> existing = peerManager.getAllPeers().stream()
                .filter(p -> p.getPeerId().equalsIgnoreCase(remotePeerId) || p.getAlias().equalsIgnoreCase(remotePeerId))
                .findFirst();

        if (existing.isPresent()) {
            Peer p = existing.get();
            p.setConnectionStatus(ConnectionStatus.REJECTED);
            try {
                peerRepository.updateConnectionStatus(p.getPeerId(), ConnectionStatus.REJECTED);
            } catch (Exception ignored) {}
            logger.info("Connection rejected by remote peer: {} ({})", p.getAlias(), p.getPeerId());
            AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
        }
    }

    void handleIncomingGroupJoinRequest(org.yu.projectcx.network.signaling.SignalingMessage msg) {
        String remotePeerId = msg.getSenderPeerId();
        String remoteAlias = msg.getSdp();
        if (remotePeerId == null || remotePeerId.isEmpty()) return;

        handleIncomingConnectionRequest(remotePeerId, remoteAlias);
        Optional<Peer> p = peerManager.getPeer(remotePeerId);
        p.ifPresent(peer -> peer.setGroupJoinRequested(true));
        logger.info("Incoming group join request from peer: {} ({})", remoteAlias, remotePeerId);
        AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
    }

    void handleIncomingGroupIntroduce(org.yu.projectcx.network.signaling.SignalingMessage msg) {
        String payload = msg.getSdp();
        if (payload == null || payload.trim().isEmpty()) return;
        String delimiter = payload.contains("||") ? "\\|\\|" : ":";
        String[] parts = payload.split(delimiter);
        if (parts.length < 4) return;

        String introducedId = parts[0].trim();
        String introducedAlias = parts[1].trim();
        String introducedIp = parts[2].trim();
        int portParsed;
        try {
            portParsed = Integer.parseInt(parts[3].trim());
        } catch (NumberFormatException e) {
            portParsed = 8888;
        }
        final int introducedPort = portParsed;

        if (currentUser != null && (
                introducedId.equalsIgnoreCase(currentUser.getUserId()) ||
                introducedId.equalsIgnoreCase(currentUser.getUsername()) ||
                introducedAlias.equalsIgnoreCase(currentUser.getUsername()) ||
                introducedAlias.equalsIgnoreCase(currentUser.getDisplayName()) ||
                introducedId.equalsIgnoreCase("peer_" + currentUser.getUsername())
        )) {
            return;
        }

        // Register introduced peer in directory as CONNECTED member of group
        Peer introducedPeer = peerManager.getPeer(introducedId).orElseGet(() -> {
            Peer np = new Peer(introducedId, introducedAlias, introducedIp, introducedPort);
            peerManager.addPeer(np);
            return np;
        });
        introducedPeer.setOnline(true);
        introducedPeer.setConnectionStatus(ConnectionStatus.CONNECTED);
        introducedPeer.setIpAddress(introducedIp);
        introducedPeer.setPort(introducedPort);
        if (!introducedAlias.isEmpty()) {
            introducedPeer.setAlias(introducedAlias);
        }
        try {
            peerRepository.savePeer(introducedPeer);
            peerRepository.updateConnectionStatus(introducedPeer.getPeerId(), ConnectionStatus.CONNECTED);
        } catch (SQLException ignored) {}

        // Add introduced peer to group chat session
        getGroupChatSession().addMember(introducedPeer);

        logger.info("Auto-connecting to introduced group peer: {} ({}:{})", introducedAlias, introducedIp, introducedPort);
        connectToPeer(introducedPeer.getPeerId());

        // Acknowledge connection back to introduced peer so both sides are CONNECTED
        String localId = currentUser != null ? currentUser.getUsername() : "local_user";
        try {
            signalingManager.sendConnectionAccept(introducedPeer.getIpAddress(), introducedPeer.getPort(), localId, introducedPeer.getPeerId());
        } catch (Exception ignored) {}

        AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
    }

    void handleIncomingPeerNetworkQuery(org.yu.projectcx.network.signaling.SignalingMessage msg) {
        String requesterId = msg.getSenderPeerId();
        Peer requester = peerManager.getPeer(requesterId).orElseGet(() -> {
            return peerManager.getAllPeers().stream()
                    .filter(p -> p.getPeerId().equalsIgnoreCase(requesterId) || p.getAlias().equalsIgnoreCase(requesterId))
                    .findFirst()
                    .orElse(null);
        });
        if (requester == null) return;

        String serializedConnected = peerManager.getAllPeers().stream()
                .filter(p -> p.getConnectionStatus() == ConnectionStatus.CONNECTED && p.isOnline() && !p.getPeerId().equalsIgnoreCase(requesterId))
                .map(p -> p.getPeerId() + ":" + p.getAlias() + ":" + p.getIpAddress() + ":" + p.getPort())
                .collect(java.util.stream.Collectors.joining(";"));

        String localId = currentUser != null ? currentUser.getUsername() : "local_user";
        try {
            signalingManager.sendPeerNetworkResponse(requester.getIpAddress(), requester.getPort(), localId, requester.getPeerId(), serializedConnected);
        } catch (Exception ignored) {}
    }

    void handleIncomingPeerNetworkResponse(org.yu.projectcx.network.signaling.SignalingMessage msg) {
        String remotePeerId = msg.getSenderPeerId();
        Peer remotePeer = peerManager.getPeer(remotePeerId).orElse(null);
        if (remotePeer == null) return;

        String payload = msg.getSdp();
        if (payload != null && !payload.trim().isEmpty()) {
            String[] entries = payload.split("[;,]");
            List<String> aliases = new ArrayList<>();
            for (String entry : entries) {
                String[] parts = entry.split(":");
                if (parts.length >= 2) {
                    aliases.add(parts[1].trim());
                } else if (parts.length == 1 && !parts[0].trim().isEmpty()) {
                    aliases.add(parts[0].trim());
                }
            }
            remotePeer.setConnectedPeerAliases(aliases);
            AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
        }
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
            // Do NOT create a stub peer here with hardcoded port 8888 — the correct port will arrive
            // via LAN discovery beacon or a CONNECT_REQUEST message which carries the real port.
            logger.debug("Received signaling from unknown peer '{}', waiting for LAN discovery to resolve endpoint.", remotePeerId);
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
            if (peerRepository != null && signalingManager != null) {
                try {
                    int localPort = signalingManager.getLocalPort();
                    peerRepository.updatePeerEndpoint(user.getUsername(), "127.0.0.1", localPort);
                    logger.info("Synchronized active local user peer endpoint to DB: {} -> 127.0.0.1:{}", user.getUsername(), localPort);
                } catch (Exception e) {
                    logger.warn("Could not synchronize self peer endpoint in DB: {}", e.getMessage());
                }
            }
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
            this.lanDiscoveryService.setConnectedPeersSupplier(() -> {
                return peerManager.getAllPeers().stream()
                        .filter(p -> p.getConnectionStatus() == ConnectionStatus.CONNECTED)
                        .map(Peer::getAlias)
                        .collect(java.util.stream.Collectors.joining(","));
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

        boolean stateChanged = false;
        if (existing.isPresent()) {
            Peer p = existing.get();
            if (!p.isOnline()) {
                p.setOnline(true);
                stateChanged = true;
            }
            if (!peer.getIpAddress().equals(p.getIpAddress()) || peer.getPort() != p.getPort()) {
                p.setIpAddress(peer.getIpAddress());
                p.setPort(peer.getPort());
                stateChanged = true;
            }
            if (!p.getAlias().equalsIgnoreCase(peer.getAlias())) {
                p.setAlias(peer.getAlias());
                stateChanged = true;
            }
            if (peer.hasConnectedPeers() && !peer.getConnectedPeerAliases().equals(p.getConnectedPeerAliases())) {
                p.setConnectedPeerAliases(peer.getConnectedPeerAliases());
                stateChanged = true;
            }
            p.setLastSeen(LocalDateTime.now());

            ChatSession session = activeSessions.get(p.getPeerId());
            if (session != null) {
                session.getPeer().setPort(p.getPort());
                session.getPeer().setIpAddress(p.getIpAddress());
                session.getPeer().setOnline(true);
            }
            if (stateChanged) {
                try {
                    peerRepository.savePeer(p);
                } catch (SQLException ignored) {}
            }
        } else {
            peer.setOnline(true);
            peer.setConnectionStatus(ConnectionStatus.DISCOVERED);
            peer.setLastSeen(LocalDateTime.now());
            peerManager.addPeer(peer);
            try {
                peerRepository.savePeer(peer);
            } catch (SQLException ignored) {}
            stateChanged = true;
        }
        if (stateChanged) {
            AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
        }
    }

    public void handlePeerExplicitDisconnected(String peerId) {
        if (peerId == null) return;
        boolean changed = false;
        for (Peer p : peerManager.getAllPeers()) {
            if (p.getPeerId().equalsIgnoreCase(peerId) || p.getAlias().equalsIgnoreCase(peerId) || p.getPeerId().equalsIgnoreCase("peer_" + peerId)) {
                if (p.getConnectionStatus() == ConnectionStatus.CONNECTED) {
                    p.setConnectionStatus(ConnectionStatus.DISCOVERED);
                    changed = true;
                    ChatSession session = activeSessions.remove(p.getPeerId());
                    if (session != null) {
                        try { session.closeSession(); } catch (Exception ignored) {}
                    }
                    final String pid = p.getPeerId();
                    AsyncExecutor.runAsyncDb(() -> {
                        try {
                            peerRepository.updateConnectionStatus(pid, ConnectionStatus.DISCOVERED);
                        } catch (Exception ignored) {}
                    });
                }
            }
        }
        if (changed) {
            AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
        }
    }

    public void onLanPeerOffline(String peerId) {
        if (peerId == null) return;
        boolean changed = false;
        for (Peer p : peerManager.getAllPeers()) {
            if (p.getPeerId().equalsIgnoreCase(peerId) || p.getAlias().equalsIgnoreCase(peerId) || p.getPeerId().equalsIgnoreCase("peer_" + peerId)) {
                if (p.isOnline()) {
                    p.setOnline(false);
                    changed = true;
                    final String pid = p.getPeerId();
                    AsyncExecutor.runAsyncDb(() -> {
                        try {
                            peerRepository.updateOnlineStatus(pid, false);
                        } catch (Exception ignored) {}
                    });
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
            String fullUid = currentUser.getUserId();
            // Mark self offline in shared DB so other instances detect departure via heartbeat
            if (peerRepository != null) {
                peerRepository.markPeerOffline(uid);
                peerRepository.markPeerOffline(fullUid);
            }
            // Broadcast BYE to every known CONNECTED peer so they immediately update their UI
            for (Peer p : peerManager.getAllPeers()) {
                if (p.getConnectionStatus() == ConnectionStatus.CONNECTED) {
                    try {
                        org.yu.projectcx.network.signaling.SignalingMessage byeMsg =
                                new org.yu.projectcx.network.signaling.SignalingMessage(
                                        org.yu.projectcx.network.signaling.SignalingType.BYE,
                                        uid,
                                        p.getPeerId()
                                );
                        new org.yu.projectcx.network.signaling.SignalingClient()
                                .sendMessageDirect(p.getIpAddress(), p.getPort(), byeMsg);
                    } catch (Exception ignored) {}
                }
            }
            // Close all active sessions
            for (ChatSession session : activeSessions.values()) {
                try { session.closeSession(); } catch (Exception ignored) {}
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

        Peer target = peerManager.getPeer(targetPeerId).orElse(null);
        if (target != null && target.getConnectionStatus() != ConnectionStatus.CONNECTED) {
            return CompletableFuture.failedFuture(new IllegalStateException("Cannot send message: Peer [" + target.getAlias() + "] is not connected yet (Status: " + target.getConnectionStatus() + ")."));
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
        Peer target = peerManager.getPeer(targetPeerId).orElse(null);
        if (target != null && target.getConnectionStatus() != ConnectionStatus.CONNECTED) {
            return CompletableFuture.failedFuture(new IllegalStateException("Cannot send payload: Peer [" + target.getAlias() + "] is not connected yet (Status: " + target.getConnectionStatus() + ")."));
        }
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
        Peer target = peerManager.getPeer(targetPeerId).orElse(null);
        if (target != null && target.getConnectionStatus() != ConnectionStatus.CONNECTED) {
            return CompletableFuture.failedFuture(new IllegalStateException("Cannot send file: Peer [" + target.getAlias() + "] is not connected yet (Status: " + target.getConnectionStatus() + ")."));
        }
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
        return receiveIncomingMessageAsync(senderPeerId, text, false);
    }

    public CompletableFuture<TextMessage> receiveIncomingMessageAsync(String senderPeerId, String text, boolean isGroup) {
        return AsyncExecutor.supplyAsyncNetwork(() -> {
            if (currentUser == null) return null;
            logger.info("Processing incoming message on thread: {}", Thread.currentThread().getName());

            ChatSession session = getOrCreateSession(senderPeerId);
            String recipientId = isGroup ? GroupChatSession.GROUP_PEER_ID : currentUser.getUsername();
            TextMessage incoming = new TextMessage(senderPeerId, recipientId, text);
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
        Peer target = peerManager.getPeer(targetPeerId).orElse(null);
        if (target != null && target.getConnectionStatus() != ConnectionStatus.CONNECTED) {
            throw new IllegalStateException("Cannot send message: Peer [" + target.getAlias() + "] is not connected yet (Status: " + target.getConnectionStatus() + ").");
        }
        ChatSession session = getOrCreateSession(targetPeerId);
        TextMessage message = session.sendMessage(text);
        messageRepository.saveMessage(message);
        notifyMessageDispatched(message);
        return message;
    }

    public NetworkPayload sendMessage(NetworkPayload payload, String targetPeerId) throws SQLException {
        Peer target = peerManager.getPeer(targetPeerId).orElse(null);
        if (target != null && target.getConnectionStatus() != ConnectionStatus.CONNECTED) {
            throw new IllegalStateException("Cannot send payload: Peer [" + target.getAlias() + "] is not connected yet (Status: " + target.getConnectionStatus() + ").");
        }
        ChatSession session = getOrCreateSession(targetPeerId);
        NetworkPayload dispatched = session.sendMessage(payload);
        messageRepository.saveMessage(dispatched);
        notifyPayloadDispatched(dispatched);
        return dispatched;
    }

    public FileTransfer sendFile(String fileName, long fileSize, String checksum, String mimeType, String targetPeerId) throws SQLException {
        Peer target = peerManager.getPeer(targetPeerId).orElse(null);
        if (target != null && target.getConnectionStatus() != ConnectionStatus.CONNECTED) {
            throw new IllegalStateException("Cannot send file: Peer [" + target.getAlias() + "] is not connected yet (Status: " + target.getConnectionStatus() + ").");
        }
        ChatSession session = getOrCreateSession(targetPeerId);
        FileTransfer fileTx = session.sendFile(fileName, fileSize, checksum, mimeType);
        messageRepository.saveMessage(fileTx);
        notifyPayloadDispatched(fileTx);
        return fileTx;
    }

    public TextMessage receiveIncomingMessage(String senderPeerId, String text) throws SQLException {
        return receiveIncomingMessage(senderPeerId, text, false);
    }

    public TextMessage receiveIncomingMessage(String senderPeerId, String text, boolean isGroup) throws SQLException {
        if (currentUser == null) return null;
        ChatSession session = getOrCreateSession(senderPeerId);
        String recipientId = isGroup ? GroupChatSession.GROUP_PEER_ID : currentUser.getUsername();
        TextMessage incoming = new TextMessage(senderPeerId, recipientId, text);
        session.receivePayload(incoming);
        return incoming;
    }

    // ==========================================
    // Peer & Session Operations
    // ==========================================

    public void selectPeer(String peerId) {
        this.activePeerId = peerId;
        Peer p = peerManager.getPeer(peerId).orElse(null);
        if (p != null) {
            notifyPeerSelected(p);
            if (p.getConnectionStatus() == ConnectionStatus.CONNECTED) {
                connectToPeer(peerId);
            }
            queryPeerConnectedNetwork(peerId);
        }
    }

    public CompletableFuture<Boolean> removePeerAsync(String peerId) {
        return AsyncExecutor.supplyAsyncDb(() -> {
            if (peerId == null) return false;
            peerManager.removePeer(peerId);
            ChatSession s = activeSessions.remove(peerId);
            if (s != null) {
                try {
                    s.closeSession();
                } catch (Exception ignored) {}
            }
            boolean deleted = peerRepository.deletePeer(peerId);
            AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
            return deleted;
        });
    }

    public synchronized ChatSession getOrCreateSession(String peerId) {
        if (peerId == null || peerId.trim().isEmpty()) {
            throw new IllegalArgumentException("peerId cannot be null or empty");
        }
        Peer peer = peerManager.getPeer(peerId).orElse(null);
        String canonicalId = (peer != null) ? peer.getPeerId() : peerId;

        ChatSession existing = activeSessions.get(canonicalId);
        if (existing == null) {
            existing = activeSessions.get(peerId);
        }
        if (existing == null && peer != null && peer.getAlias() != null) {
            existing = activeSessions.get(peer.getAlias());
        }

        if (existing != null) {
            activeSessions.put(canonicalId, existing);
            activeSessions.put(peerId, existing);
            if (peer != null && peer.getAlias() != null) {
                activeSessions.put(peer.getAlias(), existing);
            }
            return existing;
        }

        if (peer == null) {
            Peer fallback = new Peer(peerId, "Peer_" + peerId.substring(0, Math.min(peerId.length(), 6)));
            fallback.setConnectionStatus(ConnectionStatus.CONNECTED);
            peerManager.addPeer(fallback);
            try {
                peerRepository.savePeer(fallback);
            } catch (SQLException ignored) {}
            peer = fallback;
            canonicalId = peer.getPeerId();
        }

        ChatSession session = new ChatSession(currentUser, peer, signalingManager);
        session.setPayloadListener(new ChatSession.SessionPayloadListener() {
            @Override
            public void onPayloadReceived(NetworkPayload payload) {
                try {
                    messageRepository.saveMessage(payload);
                } catch (SQLException e) {
                    logger.error("Failed to persist incoming WebRTC payload to SQLite", e);
                }

                boolean isGroup = GroupChatSession.GROUP_PEER_ID.equals(payload.getRecipientId());
                if (isGroup && groupChatSession != null) {
                    groupChatSession.addGroupPayload(payload);
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

        activeSessions.put(canonicalId, session);
        activeSessions.put(peerId, session);
        if (peer.getAlias() != null) {
            activeSessions.put(peer.getAlias(), session);
        }
        return session;
    }

    public synchronized void connectToPeer(String peerId) {
        ChatSession session = getOrCreateSession(peerId);
        session.connect();
    }

    public CompletableFuture<Peer> addPeerAsync(String alias, String ipAddress, int port) {
        return addPeerAsync(alias, ipAddress, port, ConnectionStatus.CONNECTED);
    }

    public CompletableFuture<Peer> addPeerAsync(String alias, String ipAddress, int port, ConnectionStatus status) {
        return AsyncExecutor.supplyAsyncDb(() -> {
            try {
                return addPeer(alias, ipAddress, port, status);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to add peer: " + e.getMessage(), e);
            }
        });
    }

    public Peer addPeer(String alias, String ipAddress, int port) throws SQLException {
        return addPeer(alias, ipAddress, port, ConnectionStatus.CONNECTED);
    }

    public Peer addPeer(String alias, String ipAddress, int port, ConnectionStatus status) throws SQLException {
        String peerId = "peer_" + alias.trim().toLowerCase().replaceAll("\\s+", "_");
        Peer newPeer = new Peer(peerId, alias.trim(), ipAddress.trim(), port);
        newPeer.setOnline(true);
        newPeer.setConnectionStatus(status != null ? status : ConnectionStatus.CONNECTED);

        peerManager.addPeer(newPeer);
        peerRepository.savePeer(newPeer);

        AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
        return newPeer;
    }

    public CompletableFuture<Boolean> sendConnectionRequest(String peerId) {
        return AsyncExecutor.supplyAsyncNetwork(() -> {
            Peer target = peerManager.getPeer(peerId).orElse(null);
            if (target == null) {
                logger.warn("sendConnectionRequest: peer '{}' not found in peerManager", peerId);
                return false;
            }

            target.setConnectionStatus(ConnectionStatus.REQUEST_SENT);
            peerRepository.updateConnectionStatus(target.getPeerId(), ConnectionStatus.REQUEST_SENT);

            // Use username so the receiver can reliably map to discovered peer and DB records
            String localId = currentUser != null ? currentUser.getUsername() : "local_user";
            String localAlias = currentUser != null ? currentUser.getDisplayName() : localId;
            int localPort = signalingManager != null ? signalingManager.getLocalPort() : 8888;
            String targetIp = target.getIpAddress();
            int targetPort = target.getPort();

            logger.info("Sending connection request: local={}({}) -> target={}({}:{})",
                    localAlias, localPort, target.getAlias(), targetIp, targetPort);

            try {
                // Await the async future so errors surface immediately
                signalingManager.sendConnectionRequest(targetIp, targetPort, localId, target.getPeerId(), localAlias + ":" + localPort).get();
                logger.info("✅ Connection request delivered to {} ({}:{})", target.getAlias(), targetIp, targetPort);
            } catch (Exception e) {
                logger.error("❌ Failed to send connection request to {} ({}:{}) - {}", target.getAlias(), targetIp, targetPort, e.getMessage());
                // Don't revert status - user can retry
            }

            AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
            return true;
        });
    }

    public CompletableFuture<Boolean> acceptConnectionRequest(String peerId) {
        return AsyncExecutor.supplyAsyncNetwork(() -> {
            Peer target = peerManager.getPeer(peerId).orElse(null);
            if (target == null) {
                logger.warn("acceptConnectionRequest: peer '{}' not found", peerId);
                return false;
            }

            target.setConnectionStatus(ConnectionStatus.CONNECTED);
            peerRepository.updateConnectionStatus(target.getPeerId(), ConnectionStatus.CONNECTED);

            String localId = currentUser != null ? currentUser.getUsername() : "local_user";
            int localPort = signalingManager != null ? signalingManager.getLocalPort() : 8888;

            logger.info("Accepting connection request from {} ({}:{}) - sending ACCEPT with port {}",
                    target.getAlias(), target.getIpAddress(), target.getPort(), localPort);

            try {
                signalingManager.sendConnectionAccept(target.getIpAddress(), target.getPort(), localId, target.getPeerId(), localPort).get();
                logger.info("✅ Connection accept delivered to {} ({}:{})", target.getAlias(), target.getIpAddress(), target.getPort());
            } catch (Exception e) {
                logger.error("❌ Failed to send connection accept to {} ({}:{}) - {}", target.getAlias(), target.getIpAddress(), target.getPort(), e.getMessage());
            }

            getOrCreateSession(target.getPeerId());
            AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
            return true;
        });
    }

    public CompletableFuture<Boolean> rejectConnectionRequest(String peerId) {
        return AsyncExecutor.supplyAsyncNetwork(() -> {
            Peer target = peerManager.getPeer(peerId).orElse(null);
            if (target == null) return false;

            target.setConnectionStatus(ConnectionStatus.REJECTED);
            peerRepository.updateConnectionStatus(target.getPeerId(), ConnectionStatus.REJECTED);

            String localId = currentUser != null ? currentUser.getUsername() : "local_user";

            try {
                signalingManager.sendConnectionReject(target.getIpAddress(), target.getPort(), localId, target.getPeerId()).get();
                logger.info("Connection reject sent to {} ({}:{})", target.getAlias(), target.getIpAddress(), target.getPort());
            } catch (Exception e) {
                logger.error("❌ Failed to send connection reject to {} - {}", target.getAlias(), e.getMessage());
            }

            AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
            return true;
        });
    }

    public CompletableFuture<Boolean> disconnectPeer(String peerId) {
        return AsyncExecutor.supplyAsyncNetwork(() -> {
            Peer target = peerManager.getPeer(peerId).orElse(null);
            if (target == null) return false;

            target.setConnectionStatus(ConnectionStatus.DISCOVERED);
            peerRepository.updateConnectionStatus(target.getPeerId(), ConnectionStatus.DISCOVERED);

            ChatSession session = activeSessions.remove(target.getPeerId());
            if (session != null) {
                try { session.closeSession(); } catch (Exception ignored) {}
            }

            String localId = currentUser != null ? currentUser.getUsername() : "local_user";
            try {
                org.yu.projectcx.network.signaling.SignalingMessage byeMsg =
                        new org.yu.projectcx.network.signaling.SignalingMessage(
                                org.yu.projectcx.network.signaling.SignalingType.BYE,
                                localId,
                                target.getPeerId()
                        );
                new org.yu.projectcx.network.signaling.SignalingClient()
                        .sendMessageDirect(target.getIpAddress(), target.getPort(), byeMsg);
                logger.info("Sent disconnect (BYE) to peer: {} ({}:{})", target.getAlias(), target.getIpAddress(), target.getPort());
            } catch (Exception e) {
                logger.debug("Failed sending BYE to {}: {}", target.getAlias(), e.getMessage());
            }

            AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
            return true;
        });
    }

    public CompletableFuture<Boolean> sendGroupJoinRequest(String peerId) {
        return AsyncExecutor.supplyAsyncNetwork(() -> {
            Peer target = peerManager.getPeer(peerId).orElse(null);
            if (target == null) return false;

            target.setConnectionStatus(ConnectionStatus.REQUEST_SENT);
            target.setGroupJoinRequested(true);
            peerRepository.updateConnectionStatus(target.getPeerId(), ConnectionStatus.REQUEST_SENT);

            String localId = currentUser != null ? currentUser.getUsername() : "local_user";
            String localAlias = currentUser != null ? currentUser.getDisplayName() : localId;
            int localPort = signalingManager != null ? signalingManager.getLocalPort() : 8888;

            try {
                signalingManager.sendGroupJoinRequest(target.getIpAddress(), target.getPort(), localId, target.getPeerId(), localAlias + ":" + localPort);
                logger.info("Sent group join request to {} ({}:{})", target.getAlias(), target.getIpAddress(), target.getPort());
            } catch (Exception e) {
                logger.warn("Could not dispatch group join request to {}: {}", target.getEndpoint(), e.getMessage());
            }

            AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
            return true;
        });
    }

    public CompletableFuture<Boolean> acceptGroupJoinRequest(String peerId) {
        return AsyncExecutor.supplyAsyncNetwork(() -> {
            Peer requester = peerManager.getPeer(peerId).orElse(null);
            if (requester == null) return false;

            // 1. Accept requester locally
            acceptConnectionRequest(peerId).join();
            requester.setGroupJoinRequested(false);

            // 2. Introduce requester to all other currently connected peers, and vice-versa
            String localId = currentUser != null ? currentUser.getUsername() : "local_user";
            String requesterPayload = requester.getPeerId() + ":" + requester.getAlias() + ":" + requester.getIpAddress() + ":" + requester.getPort();

            for (Peer member : peerManager.getAllPeers()) {
                if (!member.getPeerId().equalsIgnoreCase(requester.getPeerId())
                        && member.getConnectionStatus() == ConnectionStatus.CONNECTED) {
                    try {
                        // Introduce new requester to existing member
                        signalingManager.sendGroupIntroduce(member.getIpAddress(), member.getPort(), localId, member.getPeerId(), requesterPayload);
                        logger.info("Introduced group peer {} to existing member {}", requester.getAlias(), member.getAlias());

                        // Introduce existing member to new requester
                        String memberPayload = member.getPeerId() + ":" + member.getAlias() + ":" + member.getIpAddress() + ":" + member.getPort();
                        signalingManager.sendGroupIntroduce(requester.getIpAddress(), requester.getPort(), localId, requester.getPeerId(), memberPayload);
                        logger.info("Introduced existing member {} to new group peer {}", member.getAlias(), requester.getAlias());
                    } catch (Exception e) {
                        logger.warn("Failed introducing {} with {}: {}", requester.getAlias(), member.getAlias(), e.getMessage());
                    }
                }
            }

            AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
            return true;
        });
    }

    public void queryPeerConnectedNetwork(String peerId) {
        Peer target = peerManager.getPeer(peerId).orElse(null);
        if (target == null) return;
        String localId = currentUser != null ? currentUser.getUsername() : "local_user";
        try {
            signalingManager.sendPeerNetworkQuery(target.getIpAddress(), target.getPort(), localId, target.getPeerId());
        } catch (Exception ignored) {}
    }

    private GroupChatSession groupChatSession;

    public synchronized GroupChatSession getGroupChatSession() {
        if (groupChatSession == null) {
            groupChatSession = new GroupChatSession(currentUser, this);
        }
        groupChatSession.syncMembers(peerManager.getAllPeers());
        return groupChatSession;
    }

    public CompletableFuture<GroupChatSession> createGroupChat(String groupName, List<String> peerIds) {
        return AsyncExecutor.supplyAsyncNetwork(() -> {
            GroupChatSession session = getGroupChatSession();
            if (groupName != null && !groupName.trim().isEmpty()) {
                session.setGroupName(groupName.trim());
            }

            List<Peer> designated = new ArrayList<>();
            for (String pid : peerIds) {
                Peer p = peerManager.getPeer(pid).orElse(null);
                if (p != null) {
                    designated.add(p);
                    if (p.getConnectionStatus() != ConnectionStatus.CONNECTED) {
                        sendConnectionRequest(p.getPeerId());
                    }
                }
            }

            // Introduce members to each other if 2 or more
            String localId = currentUser != null ? currentUser.getUsername() : "local_user";
            for (int i = 0; i < designated.size(); i++) {
                Peer p1 = designated.get(i);
                for (int j = i + 1; j < designated.size(); j++) {
                    Peer p2 = designated.get(j);
                    try {
                        String p2Payload = p2.getPeerId() + ":" + p2.getAlias() + ":" + p2.getIpAddress() + ":" + p2.getPort();
                        signalingManager.sendGroupIntroduce(p1.getIpAddress(), p1.getPort(), localId, p1.getPeerId(), p2Payload);
                        String p1Payload = p1.getPeerId() + ":" + p1.getAlias() + ":" + p1.getIpAddress() + ":" + p1.getPort();
                        signalingManager.sendGroupIntroduce(p2.getIpAddress(), p2.getPort(), localId, p2.getPeerId(), p1Payload);
                    } catch (Exception ignored) {}
                }
            }

            session.setMembers(designated);
            AsyncExecutor.runOnFxThread(() -> notifyPeersUpdated(peerManager.getAllPeers()));
            return session;
        });
    }

    public CompletableFuture<List<TextMessage>> sendGroupMessageAsync(String text) {
        return getGroupChatSession().broadcastTextMessageAsync(text);
    }

    public CompletableFuture<TextMessage> sendGroupPayloadToPeerAsync(TextMessage groupMsg, String targetPeerId) {
        Peer target = peerManager.getPeer(targetPeerId).orElse(null);
        if (target != null && target.getConnectionStatus() != ConnectionStatus.CONNECTED) {
            return CompletableFuture.failedFuture(new IllegalStateException("Cannot send group message: Peer [" + target.getAlias() + "] is not connected yet (Status: " + target.getConnectionStatus() + ")."));
        }
        return AsyncExecutor.supplyAsyncNetwork(() -> {
            logger.info("Executing sendGroupPayloadToPeerAsync to [{}] on thread: {}", targetPeerId, Thread.currentThread().getName());
            ChatSession session = getOrCreateSession(targetPeerId);
            session.sendMessage(groupMsg);
            return groupMsg;
        });
    }

    public CompletableFuture<List<NetworkPayload>> getGroupConversationHistoryAsync() {
        return AsyncExecutor.supplyAsyncDb(() -> messageRepository.getGroupConversation(GroupChatSession.GROUP_PEER_ID));
    }

    public CompletableFuture<Boolean> clearGroupChatHistory() {
        return AsyncExecutor.supplyAsyncDb(() -> {
            if (groupChatSession != null) {
                groupChatSession.clearHistory();
            }
            return messageRepository.clearGroupConversation(GroupChatSession.GROUP_PEER_ID);
        });
    }

    public void syncPeersFromDatabase() {
        List<Peer> dbPeers = peerRepository.getAllPeers();
        peerManager.clear();
        for (Peer p : dbPeers) {
            if (p.getAlias() != null && p.getAlias().startsWith("Peer:")) continue;
            if (p.getAlias() != null && (
                    p.getAlias().equalsIgnoreCase("alice")
                    || p.getAlias().equalsIgnoreCase("bob")
                    || p.getAlias().equalsIgnoreCase("charlie")
                    || p.getAlias().toLowerCase().startsWith("test_")
            )) {
                continue;
            }
            if (p.getPeerId() != null && (
                    p.getPeerId().equalsIgnoreCase("peer_alice")
                    || p.getPeerId().equalsIgnoreCase("peer_bob")
                    || p.getPeerId().equalsIgnoreCase("peer_charlie")
                    || p.getPeerId().toLowerCase().startsWith("test_")
            )) {
                continue;
            }
            if (currentUser != null && (
                    p.getPeerId().equals(currentUser.getUserId()) ||
                    p.getPeerId().equalsIgnoreCase(currentUser.getUsername()) ||
                    (p.getAlias() != null && p.getAlias().equalsIgnoreCase(currentUser.getUsername())) ||
                    (p.getAlias() != null && p.getAlias().equalsIgnoreCase(currentUser.getDisplayName())) ||
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
        if (peerId == null) return Optional.empty();
        Optional<Peer> direct = peerManager.getPeer(peerId);
        if (direct.isPresent()) return direct;
        try {
            Optional<User> u = userRepository.findByIdOrUsernameOrDisplayName(peerId);
            if (u.isPresent()) {
                Optional<Peer> byUser = peerManager.getPeer(u.get().getUsername());
                if (byUser.isPresent()) return byUser;
                byUser = peerManager.getPeer(u.get().getDisplayName());
                if (byUser.isPresent()) return byUser;
                Peer fallback = new Peer(u.get().getUsername(), u.get().getDisplayName(), "Offline", 0);
                return Optional.of(fallback);
            }
        } catch (Exception ignored) {}
        return Optional.empty();
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
        stopLanDiscovery();
        if (signalingManager != null) {
            try {
                signalingManager.stop();
            } catch (Exception ignored) {}
        }
        for (ChatSession session : activeSessions.values()) {
            try {
                session.closeSession();
            } catch (Exception ignored) {}
        }
        activeSessions.clear();
        eventListeners.clear();
    }

    public void notifyMessageDispatched(TextMessage message) {
        for (ChatEventListener listener : new ArrayList<>(eventListeners)) {
            listener.onMessageDispatched(message);
        }
    }

    public void notifyPayloadDispatched(NetworkPayload payload) {
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
