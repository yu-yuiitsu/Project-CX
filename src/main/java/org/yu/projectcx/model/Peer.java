package org.yu.projectcx.model;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a remote peer node in the P2P network.
 * 
 * Demonstrates:
 * - Encapsulation via private fields and validated getters/setters.
 * - Constructor Overloading for different peer creation scenarios.
 */
public class Peer {

    // Encapsulated private fields
    private String peerId;
    private String alias;
    private String ipAddress;
    private int port;
    private boolean online;
    private LocalDateTime lastSeen;
    private ConnectionStatus connectionStatus;

    /**
     * Default constructor: assigns an auto-generated ID and default connection attributes.
     */
    public Peer() {
        this.peerId = UUID.randomUUID().toString();
        this.alias = "peer_" + peerId.substring(0, 6);
        this.ipAddress = "127.0.0.1";
        this.port = 8080;
        this.online = false;
        this.lastSeen = LocalDateTime.now();
        this.connectionStatus = ConnectionStatus.DISCOVERED;
    }

    /**
     * Overloaded constructor 1: creates a peer with ID and alias.
     */
    public Peer(String peerId, String alias) {
        this();
        setPeerId(peerId);
        setAlias(alias);
    }

    /**
     * Overloaded constructor 2: creates a peer with network endpoint configuration.
     */
    public Peer(String peerId, String alias, String ipAddress, int port) {
        this(peerId, alias);
        setIpAddress(ipAddress);
        setPort(port);
    }

    /**
     * Overloaded constructor 3: full parameter constructor.
     */
    public Peer(String peerId, String alias, String ipAddress, int port, boolean online, LocalDateTime lastSeen) {
        this(peerId, alias, ipAddress, port, online, lastSeen, ConnectionStatus.DISCOVERED);
    }

    /**
     * Overloaded constructor 4: full parameter constructor including ConnectionStatus.
     */
    public Peer(String peerId, String alias, String ipAddress, int port, boolean online, LocalDateTime lastSeen, ConnectionStatus connectionStatus) {
        setPeerId(peerId);
        setAlias(alias);
        setIpAddress(ipAddress);
        setPort(port);
        this.online = online;
        this.lastSeen = (lastSeen != null) ? lastSeen : LocalDateTime.now();
        this.connectionStatus = (connectionStatus != null) ? connectionStatus : ConnectionStatus.DISCOVERED;
    }

    // Getters and Setters with validation (Encapsulation)

    public String getPeerId() {
        return peerId;
    }

    public void setPeerId(String peerId) {
        if (peerId == null || peerId.trim().isEmpty()) {
            throw new IllegalArgumentException("Peer ID cannot be null or blank.");
        }
        this.peerId = peerId.trim();
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = (alias != null && !alias.trim().isEmpty()) ? alias.trim() : "Peer";
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        if (ipAddress == null || ipAddress.trim().isEmpty()) {
            this.ipAddress = "127.0.0.1";
        } else {
            this.ipAddress = ipAddress.trim();
        }
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port number must be between 1 and 65535. Given: " + port);
        }
        this.port = port;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public LocalDateTime getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(LocalDateTime lastSeen) {
        this.lastSeen = lastSeen;
    }

    public ConnectionStatus getConnectionStatus() {
        return connectionStatus != null ? connectionStatus : ConnectionStatus.DISCOVERED;
    }

    public void setConnectionStatus(ConnectionStatus connectionStatus) {
        this.connectionStatus = (connectionStatus != null) ? connectionStatus : ConnectionStatus.DISCOVERED;
    }

    private boolean groupJoinRequested = false;

    public boolean isGroupJoinRequested() {
        return groupJoinRequested;
    }

    public void setGroupJoinRequested(boolean groupJoinRequested) {
        this.groupJoinRequested = groupJoinRequested;
    }

    private final java.util.Set<String> connectedPeerAliases = new java.util.concurrent.CopyOnWriteArraySet<>();

    public java.util.Set<String> getConnectedPeerAliases() {
        return java.util.Collections.unmodifiableSet(connectedPeerAliases);
    }

    public void setConnectedPeerAliases(java.util.Collection<String> aliases) {
        this.connectedPeerAliases.clear();
        if (aliases != null) {
            for (String a : aliases) {
                if (a != null && !a.trim().isEmpty()) {
                    this.connectedPeerAliases.add(a.trim());
                }
            }
        }
    }

    public void addConnectedPeerAlias(String alias) {
        if (alias != null && !alias.trim().isEmpty()) {
            this.connectedPeerAliases.add(alias.trim());
        }
    }

    public void removeConnectedPeerAlias(String alias) {
        if (alias != null) {
            this.connectedPeerAliases.remove(alias.trim());
        }
    }

    public boolean hasConnectedPeers() {
        return !connectedPeerAliases.isEmpty();
    }

    public String getConnectedPeersSummary() {
        if (connectedPeerAliases.isEmpty()) return "";
        return String.join(", ", connectedPeerAliases);
    }

    /**
     * Helper method to format network endpoint (e.g. 192.168.1.50:9001).
     */
    public String getEndpoint() {
        return ipAddress + ":" + port;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Peer peer)) return false;
        return Objects.equals(peerId, peer.peerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(peerId);
    }

    @Override
    public String toString() {
        return "Peer{" +
                "peerId='" + peerId + '\'' +
                ", alias='" + alias + '\'' +
                ", endpoint='" + getEndpoint() + '\'' +
                ", online=" + online +
                ", lastSeen=" + lastSeen +
                ", connectionStatus=" + connectionStatus +
                '}';
    }
}
