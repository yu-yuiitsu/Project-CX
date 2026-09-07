package org.yu.projectcx.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseManagerTest {

    private static final String TEST_DB = "target/test_projectcx.db";
    private DatabaseManager dbManager;

    @BeforeEach
    void setUp() {
        // Delete test DB if existing
        new File(TEST_DB).delete();
        dbManager = new DatabaseManager(TEST_DB);
    }

    @AfterEach
    void tearDown() {
        new File(TEST_DB).delete();
    }

    @Test
    void testConnectionAndSchemaCreation() throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            assertNotNull(conn, "Connection should not be null");
            assertFalse(conn.isClosed(), "Connection should be open");

            // Verify settings table exists and works
            String insertSql = "INSERT INTO app_settings(key, value) VALUES (?, ?);";
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                pstmt.setString(1, "theme");
                pstmt.setString(2, "dark");
                int rows = pstmt.executeUpdate();
                assertEquals(1, rows, "Should have inserted 1 row");
            }

            String selectSql = "SELECT value FROM app_settings WHERE key = ?;";
            try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
                pstmt.setString(1, "theme");
                try (ResultSet rs = pstmt.executeQuery()) {
                    assertTrue(rs.next(), "Should have found the inserted record");
                    assertEquals("dark", rs.getString("value"));
                }
            }
        }
    }

    @Test
    void testDatabaseStatus() {
        String status = dbManager.getDatabaseStatus();
        assertNotNull(status);
        assertTrue(status.contains("Connected (SQLite v"), "Status should report connection and SQLite version");
    }
}
