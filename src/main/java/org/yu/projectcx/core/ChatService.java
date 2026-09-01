package org.yu.projectcx.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.model.User;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Central chat coordination service managing active sessions and message dispatching.
 * 
 * Demonstrates:
 * - Method Overloading:
 *   - sendMessage(String text)
 *   - sendMessage(String text, String peerId)
 *   - sendMessage(String text, Peer peer)
 *   - sendMessage(NetworkPayload payload)
 *   - sendMessage(NetworkPayload payload, String peerId)
 * - Constructor Overloading:
 *   - ChatService(User currentUser)
 *   - ChatService(User currentUser, PeerManager peerManager)
 */
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    private final User currentUser;
    private final PeerManager peerManager;
    private final Map<String, ChatSession> activeSessions;
    private String activePeerId;

    // ==========================================
    // Constructor Overloading
    // ==========================================

    /**
     * Overload 1: Creates ChatService with current user profile and default PeerManager.
     */
    public ChatService(User currentUser) {
        this(currentUser, new PeerManager());
    }

    /**
     * Overload 2: Creates ChatService with user profile and existing PeerManager.
     */
    public ChatService(User currentUser, PeerManager peerManager) {
        if (currentUser == null) {
            throw new IllegalArgumentException("Current user cannot be null in ChatService.");
        }
        this.currentUser = currentUser;
        this.peerManager = (peerManager != null) ? peerManager : new PeerManager();
        this.activeSessions = new LinkedHashMap<>();
    }

    // ==========================================
    // Method Overloading: sendMessage(...)
    // ==========================================

    /**
     * Overload 1: Dispatches text message to the currently active peer.
     */
    public TextMessage sendMessage(String text) {
        if (activePeerId == null) {
            throw new IllegalStateException("No active peer selected to send message to.");
        }
        return sendMessage(text, activePeerId);
    }

    /**
     * Overload 2: Dispatches text message to a specific peer identified by peer ID.
     */
    public TextMessage sendMessage(String text, String peerId) {
        if (peerId == null || peerId.trim().isEmpty()) {
            throw new IllegalArgumentException("Target peer ID cannot be null or blank.");
        }
        ChatSession session = getOrCreateSession(peerId);
        return session.sendMessage(text);
    }

    /**
     * Overload 3: Dispatches text message directly to a Peer object.
     */
    public TextMessage sendMessage(String text, Peer peer) {
        if (peer == null) {
            throw new IllegalArgumentException("Target peer cannot be null.");
        }
        peerManager.addPeer(peer);
        return sendMessage(text, peer.getPeerId());
    }

    /**
     * Overload 4: Dispatches any generic NetworkPayload to the currently active peer.
     */
    public NetworkPayload sendMessage(NetworkPayload payload) {
        if (activePeerId == null) {
            throw new IllegalStateException("No active peer selected to send payload to.");
        }
        return sendMessage(payload, activePeerId);
    }

    /**
     * Overload 5: Dispatches any generic NetworkPayload to a specific peer identified by peer ID.
     */
    public NetworkPayload sendMessage(NetworkPayload payload, String peerId) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload cannot be null.");
        }
        if (peerId == null || peerId.trim().isEmpty()) {
            throw new IllegalArgumentException("Target peer ID cannot be null or blank.");
        }
        ChatSession session = getOrCreateSession(peerId);
        return session.sendMessage(payload);
    }

    // ==========================================
    // Session & Peer Helpers
    // ==========================================

    /**
     * Obtains an existing session or establishes a new one with the target peer.
     */
    public synchronized ChatSession getOrCreateSession(String peerId) {
        if (activeSessions.containsKey(peerId)) {
            return activeSessions.get(peerId);
        }

        Peer peer = peerManager.getPeer(peerId)
                .orElseGet(() -> peerManager.addPeer(peerId, "Peer_" + peerId.substring(0, Math.min(peerId.length(), 6))));

        ChatSession newSession = new ChatSession(currentUser, peer);
        newSession.connect();
        activeSessions.put(peerId, newSession);
        this.activePeerId = peerId;
        return newSession;
    }

    public synchronized Optional<ChatSession> getSession(String peerId) {
        return Optional.ofNullable(activeSessions.get(peerId));
    }

    public synchronized Map<String, ChatSession> getActiveSessions() {
        return Collections.unmodifiableMap(activeSessions);
    }

    public User getCurrentUser() {
        return currentUser;
    }

    public PeerManager getPeerManager() {
        return peerManager;
    }

    public String getActivePeerId() {
        return activePeerId;
    }

    public void setActivePeerId(String activePeerId) {
        this.activePeerId = activePeerId;
    }
}
