package org.yu.projectcx.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yu.projectcx.db.DatabaseManager;
import org.yu.projectcx.model.ConnectionStatus;
import org.yu.projectcx.model.FileTransfer;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.model.User;

import java.io.File;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit and integration tests for Peer Connection Request & Accept Workflow.
 * Verifies the full connection lifecycle:
 * DISCOVERED -> REQUEST_SENT / REQUEST_RECEIVED -> CONNECTED / REJECTED
 * along with message and file transfer security guards.
 */
class PeerConnectionWorkflowTest {

    private String testDb;
    private DatabaseManager databaseManager;
    private ChatManager chatManager;
    private User currentUser;

    @BeforeEach
    void setUp() throws SQLException {
        testDb = "target/peer_workflow_" + UUID.randomUUID().toString().substring(0, 8) + ".db";
        databaseManager = new DatabaseManager(testDb);
        chatManager = new ChatManager(databaseManager);

        currentUser = chatManager.register("alice", "pass123", "Alice");
        chatManager.login("alice", "pass123");
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
    void testDiscoveredPeerBlocksMessagesUntilConnected() throws SQLException {
        Peer bob = new Peer("peer_bob", "Bob", "127.0.0.1", 9001);
        chatManager.onLanPeerDiscovered(bob);

        Optional<Peer> peerOpt = chatManager.getPeer("peer_bob");
        assertTrue(peerOpt.isPresent());
        assertEquals(ConnectionStatus.DISCOVERED, peerOpt.get().getConnectionStatus());

        Optional<Peer> dbPeer = chatManager.getPeerRepository().findById("peer_bob");
        assertTrue(dbPeer.isPresent());
        assertEquals(ConnectionStatus.DISCOVERED, dbPeer.get().getConnectionStatus());

        // Attempting to send a message must fail
        ExecutionException textEx = assertThrows(ExecutionException.class, () ->
                chatManager.sendMessageAsync("Hello Bob", "peer_bob").get()
        );
        assertTrue(textEx.getCause().getMessage().contains("not connected yet"));

        // Attempting to send a file must fail
        ExecutionException fileEx = assertThrows(ExecutionException.class, () ->
                chatManager.sendFileAsync("doc.txt", 128, "chk", "text/plain", "peer_bob").get()
        );
        assertTrue(fileEx.getCause().getMessage().contains("not connected yet"));
    }

    @Test
    void testOutgoingRequestAndAcceptanceLifecycle() throws Exception {
        Peer bob = new Peer("peer_bob", "Bob", "127.0.0.1", 9001);
        chatManager.onLanPeerDiscovered(bob);

        // 1. Send connection request
        boolean requestSent = chatManager.sendConnectionRequest("peer_bob").get();
        assertTrue(requestSent);

        assertEquals(ConnectionStatus.REQUEST_SENT, chatManager.getPeer("peer_bob").get().getConnectionStatus());
        assertEquals(ConnectionStatus.REQUEST_SENT, chatManager.getPeerRepository().findById("peer_bob").get().getConnectionStatus());

        // Still blocked while in REQUEST_SENT
        assertThrows(ExecutionException.class, () ->
                chatManager.sendMessageAsync("Early message", "peer_bob").get()
        );

        // 2. Simulate remote acceptance
        chatManager.handleIncomingConnectionAccepted("peer_bob");

        assertEquals(ConnectionStatus.CONNECTED, chatManager.getPeer("peer_bob").get().getConnectionStatus());
        assertEquals(ConnectionStatus.CONNECTED, chatManager.getPeerRepository().findById("peer_bob").get().getConnectionStatus());

        // Now sending message succeeds without throwing not-connected exception
        TextMessage msg = chatManager.sendMessageAsync("Hello Bob, we are connected!", "peer_bob").get();
        assertNotNull(msg);
        assertEquals("Hello Bob, we are connected!", msg.getMessageContent());
    }

    @Test
    void testIncomingRequestAndLocalAcceptanceLifecycle() throws Exception {
        // 1. Incoming connection request arrives from Charlie
        chatManager.handleIncomingConnectionRequest("peer_charlie", "Charlie");

        Optional<Peer> charlieOpt = chatManager.getPeer("peer_charlie");
        assertTrue(charlieOpt.isPresent());
        assertEquals(ConnectionStatus.REQUEST_RECEIVED, charlieOpt.get().getConnectionStatus());
        assertEquals(ConnectionStatus.REQUEST_RECEIVED, chatManager.getPeerRepository().findById("peer_charlie").get().getConnectionStatus());

        // 2. Local user accepts the request
        boolean accepted = chatManager.acceptConnectionRequest("peer_charlie").get();
        assertTrue(accepted);

        assertEquals(ConnectionStatus.CONNECTED, chatManager.getPeer("peer_charlie").get().getConnectionStatus());
        assertEquals(ConnectionStatus.CONNECTED, chatManager.getPeerRepository().findById("peer_charlie").get().getConnectionStatus());

        // Sending message succeeds
        TextMessage msg = chatManager.sendMessageAsync("Hi Charlie!", "peer_charlie").get();
        assertNotNull(msg);
        assertEquals("Hi Charlie!", msg.getMessageContent());
    }

    @Test
    void testIncomingRequestAndLocalRejectionLifecycle() throws Exception {
        chatManager.handleIncomingConnectionRequest("peer_dave", "Dave");
        assertEquals(ConnectionStatus.REQUEST_RECEIVED, chatManager.getPeer("peer_dave").get().getConnectionStatus());

        boolean rejected = chatManager.rejectConnectionRequest("peer_dave").get();
        assertTrue(rejected);

        assertEquals(ConnectionStatus.REJECTED, chatManager.getPeer("peer_dave").get().getConnectionStatus());
        assertEquals(ConnectionStatus.REJECTED, chatManager.getPeerRepository().findById("peer_dave").get().getConnectionStatus());

        // Blocked from sending messages
        ExecutionException ex = assertThrows(ExecutionException.class, () ->
                chatManager.sendMessageAsync("Hi Dave", "peer_dave").get()
        );
        assertTrue(ex.getCause().getMessage().contains("not connected yet"));
    }

    @Test
    void testRemoteRejectionOfOutgoingRequest() throws Exception {
        Peer eve = new Peer("peer_eve", "Eve", "127.0.0.1", 9002);
        chatManager.onLanPeerDiscovered(eve);

        chatManager.sendConnectionRequest("peer_eve").get();
        assertEquals(ConnectionStatus.REQUEST_SENT, chatManager.getPeer("peer_eve").get().getConnectionStatus());

        // Remote peer rejects our request
        chatManager.handleIncomingConnectionRejected("peer_eve");

        assertEquals(ConnectionStatus.REJECTED, chatManager.getPeer("peer_eve").get().getConnectionStatus());
        assertEquals(ConnectionStatus.REJECTED, chatManager.getPeerRepository().findById("peer_eve").get().getConnectionStatus());
    }

    @Test
    void testExplicitAddPeerDefaultsToConnected() throws SQLException {
        // Manually adding a peer directly defaults to CONNECTED
        Peer directPeer = chatManager.addPeer("Frank", "127.0.0.1", 9005);
        assertEquals(ConnectionStatus.CONNECTED, directPeer.getConnectionStatus());

        // Can also specify status explicitly
        Peer discoveredPeer = chatManager.addPeer("Grace", "127.0.0.1", 9006, ConnectionStatus.DISCOVERED);
        assertEquals(ConnectionStatus.DISCOVERED, discoveredPeer.getConnectionStatus());
    }
}
