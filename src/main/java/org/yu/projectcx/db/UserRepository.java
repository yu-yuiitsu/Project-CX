package org.yu.projectcx.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.model.User;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Data Access Object (DAO) Repository for managing User entity persistence in SQLite.
 * 
 * Demonstrates:
 * - Database Integration with SQLite via JDBC.
 * - Parameterized PreparedStatements (SQL injection prevention).
 * - User registration, authentication (Login), lookup, and update.
 * - Resource safety with try-with-resources.
 */
public class UserRepository {

    private static final Logger logger = LoggerFactory.getLogger(UserRepository.class);
    private final DatabaseManager databaseManager;

    public UserRepository() {
        this(DatabaseManager.getInstance());
    }

    public UserRepository(DatabaseManager databaseManager) {
        if (databaseManager == null) {
            throw new IllegalArgumentException("DatabaseManager cannot be null.");
        }
        this.databaseManager = databaseManager;
    }

    /**
     * Inserts or updates a user profile with a password.
     */
    public void saveUser(User user, String password) throws SQLException {
        if (user == null) {
            throw new IllegalArgumentException("User cannot be null.");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password cannot be null or empty.");
        }

        String passwordHash = hashPassword(password);
        String sql = """
            INSERT INTO users (user_id, username, display_name, password_hash, status_message, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(user_id) DO UPDATE SET
                username = excluded.username,
                display_name = excluded.display_name,
                password_hash = excluded.password_hash,
                status_message = excluded.status_message;
        """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, user.getUserId());
            pstmt.setString(2, user.getUsername());
            pstmt.setString(3, user.getDisplayName());
            pstmt.setString(4, passwordHash);
            pstmt.setString(5, user.getStatusMessage());
            pstmt.setTimestamp(6, Timestamp.valueOf(
                    user.getCreatedAt() != null ? user.getCreatedAt() : LocalDateTime.now()
            ));

            pstmt.executeUpdate();
            logger.info("Saved user to database: {} ({})", user.getUsername(), user.getUserId());
        }
    }

    /**
     * Authenticates a user by username and password.
     * 
     * @return Optional containing the User if credentials match, or empty Optional.
     */
    public Optional<User> login(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }

        String passwordHash = hashPassword(password);
        String sql = "SELECT user_id, username, display_name, status_message, created_at FROM users WHERE username = ? AND password_hash = ?;";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, username.trim());
            pstmt.setString(2, passwordHash);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    User user = mapResultSetToUser(rs);
                    logger.info("User login successful: {}", username);
                    return Optional.of(user);
                }
            }
        } catch (SQLException e) {
            logger.error("Error during user login for username: " + username, e);
        }

        logger.warn("User login failed for username: {}", username);
        return Optional.empty();
    }

    /**
     * Retrieves a user by their unique User ID.
     */
    public Optional<User> findById(String userId) {
        if (userId == null) return Optional.empty();
        String sql = "SELECT user_id, username, display_name, status_message, created_at FROM users WHERE user_id = ?;";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, userId.trim());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToUser(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to query user by ID: " + userId, e);
        }
        return Optional.empty();
    }

    /**
     * Retrieves a user by their username.
     */
    public Optional<User> findByUsername(String username) {
        if (username == null) return Optional.empty();
        String sql = "SELECT user_id, username, display_name, status_message, created_at FROM users WHERE username = ?;";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, username.trim());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToUser(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to query user by username: " + username, e);
        }
        return Optional.empty();
    }

    /**
     * Updates the user's status message.
     */
    public boolean updateStatusMessage(String userId, String statusMessage) {
        String sql = "UPDATE users SET status_message = ? WHERE user_id = ?;";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, statusMessage != null ? statusMessage.trim() : "");
            pstmt.setString(2, userId);
            int affected = pstmt.executeUpdate();
            return affected > 0;
        } catch (SQLException e) {
            logger.error("Failed to update status for user: " + userId, e);
            return false;
        }
    }

    /**
     * Retrieves all registered local users.
     */
    public List<User> getAllUsers() {
        List<User> users = new ArrayList<>();
        String sql = "SELECT user_id, username, display_name, status_message, created_at FROM users ORDER BY created_at ASC;";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                users.add(mapResultSetToUser(rs));
            }
        } catch (SQLException e) {
            logger.error("Failed to query all users", e);
        }
        return users;
    }

    /**
     * Deletes a user from the database.
     */
    public boolean deleteUser(String userId) {
        String sql = "DELETE FROM users WHERE user_id = ?;";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, userId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to delete user: " + userId, e);
            return false;
        }
    }

    /**
     * Authenticates with password and deletes the user profile and their messages from SQLite.
     * 
     * @return true if password matched and user was deleted, false otherwise.
     */
    public boolean deleteUserWithPassword(String username, String password) {
        if (username == null || password == null) return false;
        Optional<User> userOpt = login(username, password);
        if (userOpt.isEmpty()) {
            logger.warn("Delete user rejected: Invalid password for username [{}]", username);
            return false;
        }

        User user = userOpt.get();
        String deleteMessagesSql = "DELETE FROM messages WHERE sender_id = ? OR recipient_id = ? OR sender_id = ? OR recipient_id = ?;";
        String deleteUserSql = "DELETE FROM users WHERE user_id = ?;";

        try (Connection conn = databaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement msgStmt = conn.prepareStatement(deleteMessagesSql);
                 PreparedStatement userStmt = conn.prepareStatement(deleteUserSql)) {

                msgStmt.setString(1, user.getUserId());
                msgStmt.setString(2, user.getUserId());
                msgStmt.setString(3, user.getUsername());
                msgStmt.setString(4, user.getUsername());
                msgStmt.executeUpdate();

                userStmt.setString(1, user.getUserId());
                int rows = userStmt.executeUpdate();

                conn.commit();
                logger.info("Successfully deleted user [{}] and their data from SQLite.", username);
                return rows > 0;
            } catch (SQLException ex) {
                conn.rollback();
                logger.error("Transaction rolled back while deleting user: " + username, ex);
                return false;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.error("Database error during user deletion: " + username, e);
            return false;
        }
    }

    /**
     * Clears all users from the database.
     */
    public boolean clearAllUsers() {
        String sql = "DELETE FROM users;";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            return pstmt.executeUpdate() >= 0;
        } catch (SQLException e) {
            logger.error("Failed to clear users table", e);
            return false;
        }
    }

    private User mapResultSetToUser(ResultSet rs) throws SQLException {
        String userId = rs.getString("user_id");
        String username = rs.getString("username");
        String displayName = rs.getString("display_name");
        String statusMessage = rs.getString("status_message");
        Timestamp ts = rs.getTimestamp("created_at");
        LocalDateTime createdAt = (ts != null) ? ts.toLocalDateTime() : LocalDateTime.now();

        return new User(userId, username, displayName, statusMessage, createdAt);
    }

    /**
     * Helper to compute SHA-256 hash for passwords.
     */
    public static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
