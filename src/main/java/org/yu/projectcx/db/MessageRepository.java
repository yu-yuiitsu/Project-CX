package org.yu.projectcx.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.model.FileTransfer;
import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.PayloadType;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.model.TransferStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Data Access Object (DAO) Repository for polymorphic NetworkPayload persistence in SQLite.
 * 
 * Demonstrates:
 * - Persistent message history for TextMessages and FileTransfers.
 * - Polymorphic database hydration from relational rows into concrete subtypes.
 * - Thread-safe, connection-safe JDBC query execution.
 */
public class MessageRepository {

    private static final Logger logger = LoggerFactory.getLogger(MessageRepository.class);
    private final DatabaseManager databaseManager;

    public MessageRepository() {
        this(DatabaseManager.getInstance());
    }

    public MessageRepository(DatabaseManager databaseManager) {
        if (databaseManager == null) {
            throw new IllegalArgumentException("DatabaseManager cannot be null.");
        }
        this.databaseManager = databaseManager;
    }

    /**
     * Polymorphically persists any NetworkPayload (TextMessage or FileTransfer) to SQLite.
     */
    public void saveMessage(NetworkPayload payload) throws SQLException {
        if (payload == null) {
            throw new IllegalArgumentException("Cannot save a null payload.");
        }

        String sql = """
            INSERT INTO messages (
                payload_id, payload_type, sender_id, recipient_id,
                message_content, file_name, file_size, file_checksum,
                mime_type, transfer_progress, transfer_status,
                is_delivered, is_read, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(payload_id) DO UPDATE SET
                message_content = excluded.message_content,
                file_name = excluded.file_name,
                file_size = excluded.file_size,
                file_checksum = excluded.file_checksum,
                mime_type = excluded.mime_type,
                transfer_progress = excluded.transfer_progress,
                transfer_status = excluded.transfer_status,
                is_delivered = excluded.is_delivered,
                is_read = excluded.is_read;
        """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, payload.getPayloadId());
            pstmt.setString(2, payload.getType().name());
            pstmt.setString(3, payload.getSenderId());
            pstmt.setString(4, payload.getRecipientId());

            if (payload instanceof TextMessage tm) {
                pstmt.setString(5, tm.getMessageContent());
                pstmt.setString(6, null);
                pstmt.setLong(7, 0L);
                pstmt.setString(8, null);
                pstmt.setString(9, null);
                pstmt.setDouble(10, 0.0);
                pstmt.setString(11, null);
                pstmt.setInt(12, tm.isDelivered() ? 1 : 0);
                pstmt.setInt(13, tm.isRead() ? 1 : 0);
            } else if (payload instanceof FileTransfer ft) {
                pstmt.setString(5, null);
                pstmt.setString(6, ft.getFileName());
                pstmt.setLong(7, ft.getFileSize());
                pstmt.setString(8, ft.getFileChecksum());
                pstmt.setString(9, ft.getMimeType());
                pstmt.setDouble(10, ft.getTransferProgress());
                pstmt.setString(11, ft.getStatus() != null ? ft.getStatus().name() : TransferStatus.PENDING.name());
                pstmt.setInt(12, 1);
                pstmt.setInt(13, 0);
            } else {
                pstmt.setString(5, payload.getSummary());
                pstmt.setString(6, null);
                pstmt.setLong(7, 0L);
                pstmt.setString(8, null);
                pstmt.setString(9, null);
                pstmt.setDouble(10, 0.0);
                pstmt.setString(11, null);
                pstmt.setInt(12, 1);
                pstmt.setInt(13, 0);
            }

            pstmt.setTimestamp(14, Timestamp.valueOf(
                    payload.getTimestamp() != null ? payload.getTimestamp() : LocalDateTime.now()
            ));

            pstmt.executeUpdate();
            logger.info("Persisted payload [{}] to SQLite messages table: {}", payload.getType(), payload.getPayloadId());
        }
    }

    /**
     * Retrieves a message by payload ID and polymorphically instantiates the concrete subtype.
     */
    public Optional<NetworkPayload> findById(String payloadId) {
        if (payloadId == null) return Optional.empty();
        String sql = "SELECT * FROM messages WHERE payload_id = ?;";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, payloadId.trim());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToPayload(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to find message by ID: " + payloadId, e);
        }
        return Optional.empty();
    }

    /**
     * Retrieves the complete chronological conversation history between two participants.
     */
    public List<NetworkPayload> getConversation(String participantA, String participantB) {
        return getConversation(participantA, participantB, Integer.MAX_VALUE);
    }

    /**
     * Retrieves the most recent N messages exchanged between two participants.
     */
    public List<NetworkPayload> getConversation(String participantA, String participantB, int limit) {
        List<NetworkPayload> conversation = new ArrayList<>();
        if (participantA == null || participantB == null || limit <= 0) {
            return conversation;
        }

        Set<String> aIds = collectPeerIdentifiers(participantA);
        Set<String> bIds = collectPeerIdentifiers(participantB);
        if (aIds.isEmpty() || bIds.isEmpty()) return conversation;

        String placeholdersA = makePlaceholders(aIds.size());
        String placeholdersB = makePlaceholders(bIds.size());

        String sql = "SELECT * FROM messages WHERE recipient_id != 'group_all_connected' AND (" +
                     " (sender_id IN (" + placeholdersA + ") AND recipient_id IN (" + placeholdersB + "))" +
                     " OR (sender_id IN (" + placeholdersB + ") AND recipient_id IN (" + placeholdersA + "))" +
                     ") ORDER BY created_at ASC LIMIT ?;";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            int idx = 1;
            for (String id : aIds) pstmt.setString(idx++, id);
            for (String id : bIds) pstmt.setString(idx++, id);
            for (String id : bIds) pstmt.setString(idx++, id);
            for (String id : aIds) pstmt.setString(idx++, id);
            pstmt.setInt(idx, limit);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    conversation.add(mapResultSetToPayload(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to retrieve conversation between " + participantA + " and " + participantB, e);
        }
        return conversation;
    }

    /**
     * Retrieves the chronological group conversation history.
     */
    public List<NetworkPayload> getGroupConversation(String groupId, int limit) {
        List<NetworkPayload> conversation = new ArrayList<>();
        if (limit <= 0) return conversation;
        String targetGroup = (groupId != null && !groupId.trim().isEmpty()) ? groupId.trim() : "group_all_connected";

        String sql = """
            SELECT * FROM messages
            WHERE recipient_id = ?
            ORDER BY created_at ASC
            LIMIT ?;
        """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, targetGroup);
            pstmt.setInt(2, limit);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    conversation.add(mapResultSetToPayload(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to retrieve group conversation for " + targetGroup, e);
        }
        return conversation;
    }

    public List<NetworkPayload> getGroupConversation(String groupId) {
        return getGroupConversation(groupId, Integer.MAX_VALUE);
    }

    /**
     * Clears group conversation history from database.
     */
    public boolean clearGroupConversation(String groupId) {
        String targetGroup = (groupId != null && !groupId.trim().isEmpty()) ? groupId.trim() : "group_all_connected";
        String sql = "DELETE FROM messages WHERE recipient_id = ?;";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, targetGroup);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to clear group conversation for " + targetGroup, e);
            return false;
        }
    }

    /**
     * Retrieves conversation history for a given peer ID or username flexibly.
     */
    public List<NetworkPayload> getConversationForPeer(String userId, String username, String peerId, int limit) {
        List<NetworkPayload> conversation = new ArrayList<>();
        if (peerId == null || limit <= 0) return conversation;

        Set<String> userIds = new LinkedHashSet<>();
        if (userId != null && !userId.trim().isEmpty()) {
            userIds.addAll(collectPeerIdentifiers(userId));
        }
        if (username != null && !username.trim().isEmpty()) {
            userIds.addAll(collectPeerIdentifiers(username));
        }
        Set<String> peerIds = collectPeerIdentifiers(peerId);

        if (userIds.isEmpty() || peerIds.isEmpty()) return conversation;

        String placeholdersUser = makePlaceholders(userIds.size());
        String placeholdersPeer = makePlaceholders(peerIds.size());

        String sql = "SELECT * FROM messages WHERE recipient_id != 'group_all_connected' AND (" +
                     " (sender_id IN (" + placeholdersUser + ") AND recipient_id IN (" + placeholdersPeer + "))" +
                     " OR (sender_id IN (" + placeholdersPeer + ") AND recipient_id IN (" + placeholdersUser + "))" +
                     ") ORDER BY created_at ASC LIMIT ?;";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            int idx = 1;
            for (String id : userIds) pstmt.setString(idx++, id);
            for (String id : peerIds) pstmt.setString(idx++, id);
            for (String id : peerIds) pstmt.setString(idx++, id);
            for (String id : userIds) pstmt.setString(idx++, id);
            pstmt.setInt(idx, limit);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    conversation.add(mapResultSetToPayload(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to retrieve conversation for peer: " + peerId, e);
        }
        return conversation;
    }

    /**
     * Retrieves only text messages between two participants.
     */
    public List<TextMessage> getTextHistory(String participantA, String participantB) {
        List<TextMessage> textList = new ArrayList<>();
        for (NetworkPayload p : getConversation(participantA, participantB)) {
            if (p instanceof TextMessage tm) {
                textList.add(tm);
            }
        }
        return textList;
    }

    /**
     * Retrieves only file transfers between two participants.
     */
    public List<FileTransfer> getFileTransferHistory(String participantA, String participantB) {
        List<FileTransfer> fileList = new ArrayList<>();
        for (NetworkPayload p : getConversation(participantA, participantB)) {
            if (p instanceof FileTransfer ft) {
                fileList.add(ft);
            }
        }
        return fileList;
    }

    /**
     * Marks a text message as delivered.
     */
    public boolean markAsDelivered(String payloadId) {
        String sql = "UPDATE messages SET is_delivered = 1 WHERE payload_id = ?;";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, payloadId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to mark message as delivered: " + payloadId, e);
            return false;
        }
    }

    /**
     * Marks a text message as read.
     */
    public boolean markAsRead(String payloadId) {
        String sql = "UPDATE messages SET is_read = 1 WHERE payload_id = ?;";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, payloadId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to mark message as read: " + payloadId, e);
            return false;
        }
    }

    /**
     * Clears all conversation history between two participants.
     */
    public boolean clearConversation(String participantA, String participantB) {
        if (participantA == null || participantB == null) return false;

        Set<String> aIds = collectPeerIdentifiers(participantA);
        Set<String> bIds = collectPeerIdentifiers(participantB);
        if (aIds.isEmpty() || bIds.isEmpty()) return false;

        String placeholdersA = makePlaceholders(aIds.size());
        String placeholdersB = makePlaceholders(bIds.size());

        String sql = "DELETE FROM messages WHERE recipient_id != 'group_all_connected' AND (" +
                     " (sender_id IN (" + placeholdersA + ") AND recipient_id IN (" + placeholdersB + "))" +
                     " OR (sender_id IN (" + placeholdersB + ") AND recipient_id IN (" + placeholdersA + "))" +
                     ");";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            int idx = 1;
            for (String id : aIds) pstmt.setString(idx++, id);
            for (String id : bIds) pstmt.setString(idx++, id);
            for (String id : bIds) pstmt.setString(idx++, id);
            for (String id : aIds) pstmt.setString(idx++, id);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to clear conversation between " + participantA + " and " + participantB, e);
            return false;
        }
    }

    private NetworkPayload mapResultSetToPayload(ResultSet rs) throws SQLException {
        String payloadId = rs.getString("payload_id");
        String payloadType = rs.getString("payload_type");
        String senderId = rs.getString("sender_id");
        String recipientId = rs.getString("recipient_id");
        Timestamp ts = rs.getTimestamp("created_at");
        LocalDateTime createdAt = (ts != null) ? ts.toLocalDateTime() : LocalDateTime.now();

        if (PayloadType.FILE_TRANSFER.name().equalsIgnoreCase(payloadType)) {
            String fileName = rs.getString("file_name");
            long fileSize = rs.getLong("file_size");
            String fileChecksum = rs.getString("file_checksum");
            String mimeType = rs.getString("mime_type");
            double progress = rs.getDouble("transfer_progress");
            String statusStr = rs.getString("transfer_status");
            TransferStatus status = (statusStr != null) ? TransferStatus.valueOf(statusStr) : TransferStatus.PENDING;

            return new FileTransfer(payloadId, senderId, recipientId, fileName, fileSize, fileChecksum, mimeType, progress, status, createdAt);
        } else {
            String content = rs.getString("message_content");
            boolean delivered = rs.getInt("is_delivered") == 1;
            boolean read = rs.getInt("is_read") == 1;

            return new TextMessage(payloadId, senderId, recipientId, content, createdAt, delivered, read);
        }
    }

    public Set<String> collectPeerIdentifiers(String idOrName) {
        Set<String> set = new LinkedHashSet<>();
        if (idOrName == null || idOrName.trim().isEmpty()) return set;
        String raw = idOrName.trim();
        String clean = raw.replace("peer_", "");
        set.add(raw);
        set.add(clean);
        set.add("peer_" + clean);

        String sqlUser = "SELECT user_id, username, display_name FROM users WHERE user_id = ? OR username = ? OR display_name = ?;";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sqlUser)) {
            pstmt.setString(1, raw);
            pstmt.setString(2, clean);
            pstmt.setString(3, clean);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String uid = rs.getString("user_id");
                    String uname = rs.getString("username");
                    String dname = rs.getString("display_name");
                    if (uid != null) set.add(uid);
                    if (uname != null) set.add(uname);
                    if (dname != null) set.add(dname);
                }
            }
        } catch (SQLException ignored) {}

        String sqlPeer = "SELECT peer_id, alias FROM peers WHERE peer_id = ? OR alias = ? OR peer_id = ? OR alias = ?;";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sqlPeer)) {
            pstmt.setString(1, raw);
            pstmt.setString(2, raw);
            pstmt.setString(3, clean);
            pstmt.setString(4, clean);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String pid = rs.getString("peer_id");
                    String alias = rs.getString("alias");
                    if (pid != null) set.add(pid);
                    if (alias != null) set.add(alias);
                }
            }
        } catch (SQLException ignored) {}

        return set;
    }

    private String makePlaceholders(int count) {
        if (count <= 0) return "''";
        return String.join(",", Collections.nCopies(count, "?"));
    }
}
