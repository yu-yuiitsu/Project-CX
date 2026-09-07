package org.yu.projectcx.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.model.Peer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manages the collection of discovered and registered network peers.
 * 
 * Demonstrates:
 * - Aggregation: Aggregates independent Peer objects.
 * - Method Overloading:
 *   - addPeer(Peer peer)
 *   - addPeer(String peerId, String alias)
 *   - addPeer(String peerId, String alias, String ipAddress, int port)
 *   - getPeer(String peerId)
 *   - getPeer(String ipAddress, int port)
 */
public class PeerManager {

    private static final Logger logger = LoggerFactory.getLogger(PeerManager.class);
    private final Map<String, Peer> peerDirectory;

    public PeerManager() {
        this.peerDirectory = new LinkedHashMap<>();
    }

    // ==========================================
    // Method Overloading: addPeer(...)
    // ==========================================

    /**
     * Overload 1: Adds a pre-instantiated Peer object.
     */
    public synchronized void addPeer(Peer peer) {
        if (peer == null) {
            throw new IllegalArgumentException("Cannot add a null Peer.");
        }
        peerDirectory.put(peer.getPeerId(), peer);
        logger.info("Peer registered: {} ({})", peer.getAlias(), peer.getEndpoint());
    }

    /**
     * Overload 2: Constructs and adds a peer by ID and alias.
     */
    public synchronized Peer addPeer(String peerId, String alias) {
        Peer peer = new Peer(peerId, alias);
        addPeer(peer);
        return peer;
    }

    /**
     * Overload 3: Constructs and adds a peer with full network endpoint details.
     */
    public synchronized Peer addPeer(String peerId, String alias, String ipAddress, int port) {
        Peer peer = new Peer(peerId, alias, ipAddress, port);
        addPeer(peer);
        return peer;
    }

    // ==========================================
    // Method Overloading: getPeer(...)
    // ==========================================

    /**
     * Overload 1: Retrieves peer by unique peer ID.
     * Falls back to alias-based linear search if direct key lookup misses
     * (handles cases where peer is stored by alias but looked up by UUID).
     */
    public synchronized Optional<Peer> getPeer(String peerId) {
        if (peerId == null) return Optional.empty();
        // Direct key lookup first (fast path)
        Peer direct = peerDirectory.get(peerId);
        if (direct != null) return Optional.of(direct);
        // Fallback: search by alias or any stored peerId matching (case-insensitive)
        final String targetId = peerId.toLowerCase();
        final String cleanTarget = targetId.startsWith("peer_") ? targetId.substring(5) : targetId;
        final String prefixTarget = "peer_" + cleanTarget;

        return peerDirectory.values().stream()
                .filter(p -> {
                    String pId = p.getPeerId() != null ? p.getPeerId().toLowerCase() : "";
                    String pAlias = p.getAlias() != null ? p.getAlias().toLowerCase() : "";
                    String cleanPId = pId.startsWith("peer_") ? pId.substring(5) : pId;
                    String cleanPAlias = pAlias.startsWith("peer_") ? pAlias.substring(5) : pAlias;

                    return pId.equals(targetId) || pAlias.equals(targetId)
                            || pId.equals(prefixTarget) || pAlias.equals(prefixTarget)
                            || cleanPId.equals(cleanTarget) || cleanPAlias.equals(cleanTarget);
                })
                .findFirst();
    }

    /**
     * Overload 2: Retrieves peer matching specific IP and port endpoint.
     */
    public synchronized Optional<Peer> getPeer(String ipAddress, int port) {
        if (ipAddress == null) return Optional.empty();
        return peerDirectory.values().stream()
                .filter(p -> p.getIpAddress().equalsIgnoreCase(ipAddress.trim()) && p.getPort() == port)
                .findFirst();
    }

    /**
     * Removes a peer from the directory by peer ID.
     */
    public synchronized Optional<Peer> removePeer(String peerId) {
        if (peerId == null) return Optional.empty();
        Peer removed = peerDirectory.remove(peerId);
        if (removed != null) {
            logger.info("Peer removed from PeerManager: {}", removed.getAlias());
        }
        return Optional.ofNullable(removed);
    }

    /**
     * Returns an unmodifiable list of all registered peers.
     */
    public synchronized List<Peer> getAllPeers() {
        return Collections.unmodifiableList(new ArrayList<>(peerDirectory.values()));
    }

    /**
     * Returns all currently active / online peers.
     */
    public synchronized List<Peer> getOnlinePeers() {
        List<Peer> onlineList = new ArrayList<>();
        for (Peer p : peerDirectory.values()) {
            if (p.isOnline()) {
                onlineList.add(p);
            }
        }
        return Collections.unmodifiableList(onlineList);
    }

    /**
     * Updates the online status and last seen timestamp of a peer.
     */
    public synchronized boolean updatePeerStatus(String peerId, boolean online) {
        Peer peer = peerDirectory.get(peerId);
        if (peer != null) {
            peer.setOnline(online);
            peer.setLastSeen(LocalDateTime.now());
            return true;
        }
        return false;
    }

    /**
     * Checks if a peer is registered in the directory.
     */
    public synchronized boolean containsPeer(String peerId) {
        return peerId != null && peerDirectory.containsKey(peerId);
    }

    /**
     * Returns the total count of registered peers.
     */
    public synchronized int getPeerCount() {
        return peerDirectory.size();
    }

    /**
     * Clears the directory.
     */
    public synchronized void clear() {
        peerDirectory.clear();
        logger.info("PeerManager directory cleared.");
    }
}
