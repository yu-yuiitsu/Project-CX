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
import java.util.List;
import java.util.Optional;

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

        String sql = """
            SELECT * FROM messages
            WHERE (sender_id = ? AND recipient_id = ?)
               OR (sender_id = ? AND recipient_id = ?)
            ORDER BY created_at ASC
            LIMIT ?;
        """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, participantA);
            pstmt.setString(2, participantB);
            pstmt.setString(3, participantB);
            pstmt.setString(4, participantA);
            pstmt.setInt(5, limit);

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
     * Retrieves conversation history for a given peer ID or username flexibly.
     */
    public List<NetworkPayload> getConversationForPeer(String userId, String username, String peerId, int limit) {
        List<NetworkPayload> conversation = new ArrayList<>();
        if (peerId == null || limit <= 0) return conversation;

        String cleanPeer = peerId.replace("peer_", "");
        String sql = """
            SELECT * FROM messages
            WHERE sender_id = ? OR recipient_id = ?
               OR sender_id = ? OR recipient_id = ?
               OR sender_id LIKE ? OR recipient_id LIKE ?
            ORDER BY created_at ASC
            LIMIT ?;
        """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, peerId);
            pstmt.setString(2, peerId);
            pstmt.setString(3, cleanPeer);
            pstmt.setString(4, cleanPeer);
            pstmt.setString(5, "%" + cleanPeer + "%");
            pstmt.setString(6, "%" + cleanPeer + "%");
            pstmt.setInt(7, limit);

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
        String sql = """
            DELETE FROM messages
            WHERE (sender_id = ? AND recipient_id = ?)
               OR (sender_id = ? AND recipient_id = ?);
        """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, participantA);
            pstmt.setString(2, participantB);
            pstmt.setString(3, participantB);
            pstmt.setString(4, participantA);
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
}
