package org.yu.projectcx.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Manages SQLite database connections, schema migrations, and connection lifecycles.
 * 
 * Default Database: chat.db
 * Tables: users, messages, peers, app_settings
 */
public class DatabaseManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    public static final String DEFAULT_DB_FILE = "chat.db";

    private final String dbFilePath;
    private final String dbUrl;
    private static DatabaseManager instance;

    public DatabaseManager() {
        this(DEFAULT_DB_FILE);
    }

    public DatabaseManager(String dbFilePath) {
        this.dbFilePath = dbFilePath;
        this.dbUrl = "jdbc:sqlite:" + dbFilePath;
        createTables();
    }

    /**
     * Singleton instance provider using standard chat.db.
     */
    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager(DEFAULT_DB_FILE);
        }
        return instance;
    }

    /**
     * Obtains a new configured database connection.
     * Enforces WAL mode and Foreign Key constraints.
     */
    public Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(dbUrl);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("PRAGMA journal_mode = WAL;");
        }
        return conn;
    }

    /**
     * Creates all core persistent tables for the P2P application.
     */
    public void createTables() {
        logger.info("Verifying schema tables in SQLite database: {}", dbUrl);

        // 1. Users Table
        String createUsersTable = """
            CREATE TABLE IF NOT EXISTS users (
                user_id TEXT PRIMARY KEY,
                username TEXT UNIQUE NOT NULL,
                display_name TEXT NOT NULL,
                password_hash TEXT NOT NULL,
                status_message TEXT DEFAULT 'Available',
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            );
        """;

        // 2. Peers Table
        String createPeersTable = """
            CREATE TABLE IF NOT EXISTS peers (
                peer_id TEXT PRIMARY KEY,
                alias TEXT NOT NULL,
                ip_address TEXT DEFAULT '127.0.0.1',
                port INTEGER DEFAULT 8080,
                is_online INTEGER DEFAULT 0,
                last_seen DATETIME DEFAULT CURRENT_TIMESTAMP
            );
        """;

        // 3. Messages Table (Supports both TextMessage and FileTransfer payloads)
        String createMessagesTable = """
            CREATE TABLE IF NOT EXISTS messages (
                payload_id TEXT PRIMARY KEY,
                payload_type TEXT NOT NULL,
                sender_id TEXT NOT NULL,
                recipient_id TEXT NOT NULL,
                message_content TEXT,
                file_name TEXT,
                file_size INTEGER DEFAULT 0,
                file_checksum TEXT,
                mime_type TEXT,
                transfer_progress REAL DEFAULT 0.0,
                transfer_status TEXT,
                is_delivered INTEGER DEFAULT 0,
                is_read INTEGER DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            );
        """;

        // 4. App Settings Table
        String createSettingsTable = """
            CREATE TABLE IF NOT EXISTS app_settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
            );
        """;

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createUsersTable);
            stmt.execute(createPeersTable);
            stmt.execute(createMessagesTable);
            stmt.execute(createSettingsTable);
            stmt.execute("DELETE FROM peers WHERE alias LIKE 'Peer:%';");
            logger.info("All SQLite schema tables (users, peers, messages, app_settings) initialized successfully.");
        } catch (SQLException e) {
            logger.error("Failed to initialize database tables", e);
            throw new RuntimeException("Database table initialization failed", e);
        }
    }

    /**
     * Verifies SQLite health check and version.
     */
    public String getDatabaseStatus() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT sqlite_version() AS version")) {
            if (rs.next()) {
                return "Connected (SQLite v" + rs.getString("version") + " [" + dbFilePath + "])";
            }
        } catch (SQLException e) {
            logger.error("SQLite health check failed", e);
            return "Connection Failed: " + e.getMessage();
        }
        return "Unknown Status";
    }

    public String getDbFilePath() {
        return dbFilePath;
    }

    public String getDbUrl() {
        return dbUrl;
    }

    @Override
    public void close() {
        logger.info("DatabaseManager closed for: {}", dbUrl);
    }
}
