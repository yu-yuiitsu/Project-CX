package org.yu.projectcx.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.model.ConnectionStatus;
import org.yu.projectcx.model.Peer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data Access Object (DAO) Repository for Peer persistence in SQLite.
 */
public class PeerRepository {

    private static final Logger logger = LoggerFactory.getLogger(PeerRepository.class);
    private final DatabaseManager databaseManager;

    public PeerRepository() {
        this(DatabaseManager.getInstance());
    }

    public PeerRepository(DatabaseManager databaseManager) {
        if (databaseManager == null) {
            throw new IllegalArgumentException("DatabaseManager cannot be null.");
        }
        this.databaseManager = databaseManager;
    }

    /**
     * Inserts or updates a peer in the database.
     */
    public void savePeer(Peer peer) throws SQLException {
        if (peer == null) {
            throw new IllegalArgumentException("Peer cannot be null.");
        }

        String sql = """
            INSERT INTO peers (peer_id, alias, ip_address, port, is_online, last_seen, connection_status)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
            ON CONFLICT(peer_id) DO UPDATE SET
                alias = excluded.alias,
                ip_address = excluded.ip_address,
                port = excluded.port,
                is_online = excluded.is_online,
                last_seen = CURRENT_TIMESTAMP,
                connection_status = CASE 
                    WHEN peers.connection_status IN ('CONNECTED', 'REQUEST_SENT', 'REQUEST_RECEIVED') 
                         AND excluded.connection_status = 'DISCOVERED' 
                    THEN peers.connection_status 
                    ELSE excluded.connection_status 
                END;
        """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, peer.getPeerId());
            pstmt.setString(2, peer.getAlias());
            pstmt.setString(3, peer.getIpAddress());
            pstmt.setInt(4, peer.getPort());
            pstmt.setInt(5, peer.isOnline() ? 1 : 0);
            pstmt.setString(6, peer.getConnectionStatus() != null ? peer.getConnectionStatus().name() : ConnectionStatus.DISCOVERED.name());

            pstmt.executeUpdate();
            logger.info("Saved peer to database: {} ({}:{}, status: {})", peer.getAlias(), peer.getIpAddress(), peer.getPort(), peer.getConnectionStatus());
        }
    }

    /**
     * Retrieves a peer by its unique ID.
     */
    public Optional<Peer> findById(String peerId) {
        if (peerId == null) return Optional.empty();
        String sql = "SELECT peer_id, alias, ip_address, port, is_online, last_seen, connection_status FROM peers WHERE peer_id = ?;";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, peerId.trim());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToPeer(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to query peer by ID: " + peerId, e);
        }
        return Optional.empty();
    }

    public void saveHeartbeat(String peerId, String alias, String ip, int port) {
        String sql = """
            INSERT INTO peers (peer_id, alias, ip_address, port, is_online, last_seen, connection_status)
            VALUES (?, ?, ?, ?, 1, CURRENT_TIMESTAMP, 'DISCOVERED')
            ON CONFLICT(peer_id) DO UPDATE SET
                alias = excluded.alias,
                ip_address = excluded.ip_address,
                port = excluded.port,
                is_online = 1,
                last_seen = CURRENT_TIMESTAMP
                -- Note: connection_status is intentionally NOT reset here;
                -- it is managed by the connection handshake flow.
        """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, peerId);
            pstmt.setString(2, alias);
            pstmt.setString(3, ip);
            pstmt.setInt(4, port);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.debug("Failed saving peer heartbeat: {}", e.getMessage());
        }
    }

    public List<Peer> getActiveLivePeers(int maxAgeSeconds) {
        List<Peer> peers = new ArrayList<>();
        String sql = "SELECT peer_id, alias, ip_address, port, is_online, last_seen, connection_status FROM peers WHERE is_online = 1 AND last_seen >= datetime('now', '-' || ? || ' seconds') ORDER BY alias ASC;";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, maxAgeSeconds);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Peer p = mapResultSetToPeer(rs);
                    p.setOnline(true);
                    peers.add(p);
                }
            }
        } catch (SQLException e) {
            logger.debug("Failed querying active live peers", e);
        }
        return peers;
    }

    public void markPeerOffline(String identifier) {
        if (identifier == null) return;
        String sql = """
            UPDATE peers SET is_online = 0,
                connection_status = CASE 
                    WHEN connection_status = 'CONNECTED' THEN 'DISCOVERED' 
                    ELSE connection_status 
                END
            WHERE peer_id = ? OR alias = ? OR peer_id = ?;
        """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, identifier);
            pstmt.setString(2, identifier);
            pstmt.setString(3, "peer_" + identifier);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.debug("Failed marking peer offline: {}", e.getMessage());
        }
    }

    /**
     * Returns all registered peers.
     */
    public List<Peer> getAllPeers() {
        List<Peer> peers = new ArrayList<>();
        String sql = "SELECT peer_id, alias, ip_address, port, is_online, last_seen, connection_status FROM peers ORDER BY alias ASC;";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                peers.add(mapResultSetToPeer(rs));
            }
        } catch (SQLException e) {
            logger.error("Failed to query all peers", e);
        }
        return peers;
    }

    /**
     * Updates the connection status of a peer.
     */
    public boolean updateConnectionStatus(String identifier, ConnectionStatus status) {
        if (identifier == null || status == null) return false;
        String sql = "UPDATE peers SET connection_status = ? WHERE peer_id = ? OR alias = ? OR peer_id = ?;";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, status.name());
            pstmt.setString(2, identifier);
            pstmt.setString(3, identifier);
            pstmt.setString(4, "peer_" + identifier);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to update peer connection status: " + identifier, e);
            return false;
        }
    }

    /**
     * Updates the online status of a peer.
     */
    public boolean updateOnlineStatus(String identifier, boolean isOnline) {
        String sql = "UPDATE peers SET is_online = ?, last_seen = CURRENT_TIMESTAMP WHERE peer_id = ? OR alias = ? OR peer_id = ?;";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, isOnline ? 1 : 0);
            pstmt.setString(2, identifier);
            pstmt.setString(3, identifier);
            pstmt.setString(4, "peer_" + identifier);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to update peer online status: " + identifier, e);
            return false;
        }
    }

    /**
     * Updates the IP and listening port endpoint of a peer matching by peer_id or alias.
     */
    public boolean updatePeerEndpoint(String identifier, String ipAddress, int port) {
        if (identifier == null) return false;
        String sql = "UPDATE peers SET ip_address = ?, port = ?, is_online = 1, last_seen = ? WHERE peer_id = ? OR alias = ? OR peer_id = ?;";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, ipAddress);
            pstmt.setInt(2, port);
            pstmt.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
            pstmt.setString(4, identifier);
            pstmt.setString(5, identifier);
            pstmt.setString(6, "peer_" + identifier);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to update peer endpoint for: " + identifier, e);
            return false;
        }
    }

    /**
     * Deletes a peer by ID.
     */
    public boolean deletePeer(String peerId) {
        String sql = "DELETE FROM peers WHERE peer_id = ?;";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, peerId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to delete peer: " + peerId, e);
            return false;
        }
    }

    private Peer mapResultSetToPeer(ResultSet rs) throws SQLException {
        String peerId = rs.getString("peer_id");
        String alias = rs.getString("alias");
        String ipAddress = rs.getString("ip_address");
        int port = rs.getInt("port");
        boolean isOnline = rs.getInt("is_online") == 1;
        Timestamp ts = rs.getTimestamp("last_seen");
        LocalDateTime lastSeen = (ts != null) ? ts.toLocalDateTime() : LocalDateTime.now();

        ConnectionStatus connectionStatus = ConnectionStatus.DISCOVERED;
        try {
            String statusStr = rs.getString("connection_status");
            if (statusStr != null) {
                connectionStatus = ConnectionStatus.valueOf(statusStr);
            }
        } catch (Exception ignored) {}

        return new Peer(peerId, alias, ipAddress, port, isOnline, lastSeen, connectionStatus);
    }
}
