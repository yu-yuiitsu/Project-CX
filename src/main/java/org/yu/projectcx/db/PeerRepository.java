package org.yu.projectcx.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            INSERT INTO peers (peer_id, alias, ip_address, port, is_online, last_seen)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(peer_id) DO UPDATE SET
                alias = excluded.alias,
                ip_address = excluded.ip_address,
                port = excluded.port,
                is_online = excluded.is_online,
                last_seen = excluded.last_seen;
        """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, peer.getPeerId());
            pstmt.setString(2, peer.getAlias());
            pstmt.setString(3, peer.getIpAddress());
            pstmt.setInt(4, peer.getPort());
            pstmt.setInt(5, peer.isOnline() ? 1 : 0);
            pstmt.setTimestamp(6, Timestamp.valueOf(
                    peer.getLastSeen() != null ? peer.getLastSeen() : LocalDateTime.now()
            ));

            pstmt.executeUpdate();
            logger.info("Saved peer to database: {} ({}:{})", peer.getAlias(), peer.getIpAddress(), peer.getPort());
        }
    }

    /**
     * Retrieves a peer by its unique ID.
     */
    public Optional<Peer> findById(String peerId) {
        if (peerId == null) return Optional.empty();
        String sql = "SELECT peer_id, alias, ip_address, port, is_online, last_seen FROM peers WHERE peer_id = ?;";

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
            INSERT INTO peers (peer_id, alias, ip_address, port, is_online, last_seen)
            VALUES (?, ?, ?, ?, 1, CURRENT_TIMESTAMP)
            ON CONFLICT(peer_id) DO UPDATE SET
                alias = excluded.alias,
                ip_address = excluded.ip_address,
                port = excluded.port,
                is_online = 1,
                last_seen = CURRENT_TIMESTAMP;
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
        String sql = "SELECT peer_id, alias, ip_address, port, is_online, last_seen FROM peers WHERE is_online = 1 AND last_seen >= datetime('now', '-' || ? || ' seconds') ORDER BY alias ASC;";
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
        String sql = "UPDATE peers SET is_online = 0 WHERE peer_id = ? OR alias = ? OR peer_id = ?;";
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
        String sql = "SELECT peer_id, alias, ip_address, port, is_online, last_seen FROM peers ORDER BY alias ASC;";

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
     * Updates the online status of a peer.
     */
    public boolean updateOnlineStatus(String peerId, boolean isOnline) {
        String sql = "UPDATE peers SET is_online = ?, last_seen = ? WHERE peer_id = ?;";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, isOnline ? 1 : 0);
            pstmt.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            pstmt.setString(3, peerId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to update peer online status: " + peerId, e);
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

        return new Peer(peerId, alias, ipAddress, port, isOnline, lastSeen);
    }
}
