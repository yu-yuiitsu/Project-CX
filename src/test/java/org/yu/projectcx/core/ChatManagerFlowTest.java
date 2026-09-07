package org.yu.projectcx.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yu.projectcx.db.DatabaseManager;
import org.yu.projectcx.model.FileTransfer;
import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.model.User;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 7 Tests: End-to-End Application Logic Flow
 * 
 * Verifies:
 * 1. Login GUI -> ChatManager.login() / register() -> UserRepository -> SQLite
 * 2. GUI SEND -> ChatManager.sendMessage() -> ChatSession -> MessageManager -> TextMessage -> SQLite
 * 3. Reactive ChatEventListener observer updates.
 */
class ChatManagerFlowTest {

    private String testDb;
    private DatabaseManager databaseManager;
    private ChatManager chatManager;

    @BeforeEach
    void setUp() {
        testDb = "target/chat_flow_" + UUID.randomUUID().toString().substring(0, 8) + ".db";
        databaseManager = new DatabaseManager(testDb);
        chatManager = new ChatManager(databaseManager);
    }

    @AfterEach
    void tearDown() {
        if (chatManager != null) {
            chatManager.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        if (testDb != null) {
            new File(testDb).delete();
            new File(testDb + "-wal").delete();
            new File(testDb + "-shm").delete();
        }
    }

    @Test
    void testAuthenticationFlowToSqlite() throws SQLException {
        // 1. Register User (GUI -> ChatManager.register() -> UserRepository -> SQLite)
        User registered = chatManager.register("alice_test", "securePassword", "Alice T");
        assertNotNull(registered.getUserId());
        assertEquals("alice_test", registered.getUsername());

        // 2. Login User (Login GUI -> ChatManager.login() -> UserRepository -> SQLite)
        Optional<User> loggedIn = chatManager.login("alice_test", "securePassword");
        assertTrue(loggedIn.isPresent(), "User should authenticate successfully against SQLite");
        assertSame(loggedIn.get(), chatManager.getCurrentUser());

        // 3. Failed Login
        Optional<User> failedAuth = chatManager.login("alice_test", "wrongPassword");
        assertFalse(failedAuth.isPresent());
    }

    @Test
    void testMessageFlowFromGuiThroughChatManagerToTextMessage() throws SQLException {
        // Authenticate User
        User user = chatManager.register("yu_user", "password", "Yu");
        chatManager.setCurrentUser(user);

        // Add Peer (GUI -> ChatManager.addPeer() -> PeerRepository -> SQLite)
        Peer alex = chatManager.addPeer("Alex", "192.168.1.15", 9001);
        chatManager.selectPeer(alex.getPeerId());
        assertEquals(alex.getPeerId(), chatManager.getActivePeerId());

        // Setup Listener to observe UI reactive callbacks
        List<TextMessage> dispatchedMessages = new ArrayList<>();
        chatManager.addEventListener(new ChatEventListener() {
            @Override
            public void onMessageDispatched(TextMessage message) {
                dispatchedMessages.add(message);
            }

            @Override
            public void onPayloadDispatched(NetworkPayload payload) {}

            @Override
            public void onPeerSelected(Peer peer) {}

            @Override
            public void onPeersUpdated(List<Peer> peers) {}
        });

        // -------------------------------------------------------------
        // Flow: User types "Hello" -> SEND button -> ChatManager.sendMessage()
        //       -> MessageManager creates TextMessage -> Persisted to SQLite -> Dispatched
        // -------------------------------------------------------------
        TextMessage createdMsg = chatManager.sendMessage("Hello Alex! Testing flow.");

        assertNotNull(createdMsg);
        assertTrue(user.getUserId().equals(createdMsg.getSenderId()) || user.getUsername().equals(createdMsg.getSenderId()));
        assertEquals(alex.getPeerId(), createdMsg.getRecipientId());
        assertEquals("Hello Alex! Testing flow.", createdMsg.getMessageContent());
        assertTrue(createdMsg.isDelivered());

        // Verify MessageManager inside active ChatSession holds the message (Composition)
        ChatSession session = chatManager.getOrCreateSession(alex.getPeerId());
        assertEquals(1, session.getMessageManager().getMessageCount());

        // Verify SQLite database holds the message
        List<NetworkPayload> dbHistory = chatManager.getConversationHistory(alex.getPeerId());
        assertEquals(1, dbHistory.size());
        assertEquals("Hello Alex! Testing flow.", ((TextMessage) dbHistory.get(0)).getMessageContent());

        // Verify Event Listener was triggered
        assertEquals(1, dispatchedMessages.size());
        assertEquals(createdMsg.getPayloadId(), dispatchedMessages.get(0).getPayloadId());
    }

    @Test
    void testFileTransferFlowThroughChatManager() throws SQLException {
        User user = chatManager.register("uploader", "pass", "Uploader");
        chatManager.setCurrentUser(user);

        Peer bob = chatManager.addPeer("Bob", "192.168.1.50", 9002);

        List<NetworkPayload> dispatchedPayloads = new ArrayList<>();
        chatManager.addEventListener(new ChatEventListener() {
            @Override
            public void onMessageDispatched(TextMessage message) {}

            @Override
            public void onPayloadDispatched(NetworkPayload payload) {
                dispatchedPayloads.add(payload);
            }

            @Override
            public void onPeerSelected(Peer peer) {}

            @Override
            public void onPeersUpdated(List<Peer> peers) {}
        });

        FileTransfer fileTx = chatManager.sendFile("document.pdf", 2097152L, "hash_abc", "application/pdf", bob.getPeerId());
        assertNotNull(fileTx);
        assertEquals("document.pdf", fileTx.getFileName());
        assertEquals("2.00 MB", fileTx.getFormattedFileSize());

        assertEquals(1, dispatchedPayloads.size());
        assertInstanceOf(FileTransfer.class, dispatchedPayloads.get(0));

        // Verify SQLite persistence
        List<NetworkPayload> conversation = chatManager.getConversationHistory(bob.getPeerId());
        assertEquals(1, conversation.size());
        assertInstanceOf(FileTransfer.class, conversation.get(0));
    }
}
