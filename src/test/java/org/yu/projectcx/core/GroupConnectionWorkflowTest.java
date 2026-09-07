package org.yu.projectcx.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yu.projectcx.db.DatabaseManager;
import org.yu.projectcx.model.ConnectionStatus;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.model.User;
import org.yu.projectcx.network.signaling.SignalingMessage;
import org.yu.projectcx.network.signaling.SignalingType;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test for Peer Connected-Network Awareness & Group Connection Workflow.
 */
class GroupConnectionWorkflowTest {

    private String testDb;
    private DatabaseManager databaseManager;
    private ChatManager chatManager;
    private User currentUser;

    @BeforeEach
    void setUp() throws SQLException {
        testDb = "target/group_workflow_" + UUID.randomUUID().toString().substring(0, 8) + ".db";
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
    void testPeerConnectedNetworkAwarenessModel() {
        Peer bob = new Peer("peer_bob", "Bob", "127.0.0.1", 9001);
        assertFalse(bob.hasConnectedPeers());
        assertEquals("", bob.getConnectedPeersSummary());

        bob.addConnectedPeerAlias("Alice");
        assertTrue(bob.hasConnectedPeers());
        assertEquals("Alice", bob.getConnectedPeersSummary());

        bob.setConnectedPeerAliases(Set.of("Alice", "Charlie"));
        assertTrue(bob.hasConnectedPeers());
        assertTrue(bob.getConnectedPeersSummary().contains("Alice"));
        assertTrue(bob.getConnectedPeersSummary().contains("Charlie"));
    }

    @Test
    void testIncomingNetworkQueryReturnsConnectedPeers() throws Exception {
        // Add Bob as CONNECTED
        Peer bob = new Peer("peer_bob", "Bob", "127.0.0.1", 9001);
        bob.setConnectionStatus(ConnectionStatus.CONNECTED);
        chatManager.getPeerManager().addPeer(bob);
        chatManager.getPeerRepository().savePeer(bob);

        // Simulate Charlie sending PEER_NETWORK_QUERY to Alice
        SignalingMessage query = new SignalingMessage(
                SignalingType.PEER_NETWORK_QUERY,
                "peer_charlie",
                currentUser.getUserId(),
                ""
        );

        // Handle incoming query - Alice should process without error
        assertDoesNotThrow(() -> chatManager.handleIncomingPeerNetworkQuery(query));
    }

    @Test
    void testIncomingNetworkResponsePopulatesTargetPeerConnections() {
        Peer bob = new Peer("peer_bob", "Bob", "127.0.0.1", 9001);
        chatManager.getPeerManager().addPeer(bob);

        SignalingMessage response = new SignalingMessage(
                SignalingType.PEER_NETWORK_RESPONSE,
                "peer_bob",
                currentUser.getUserId(),
                "peer_alice:Alice:127.0.0.1:8888,peer_dave:Dave:127.0.0.1:8890"
        );

        chatManager.handleIncomingPeerNetworkResponse(response);

        Optional<Peer> updatedBob = chatManager.getPeer("peer_bob");
        assertTrue(updatedBob.isPresent());
        assertTrue(updatedBob.get().hasConnectedPeers());
        assertTrue(updatedBob.get().getConnectedPeerAliases().contains("Alice"));
        assertTrue(updatedBob.get().getConnectedPeerAliases().contains("Dave"));
    }

    @Test
    void testGroupJoinRequestLifecycle() throws Exception {
        // Discovered peer Charlie sends GROUP_JOIN_REQUEST to Alice
        Peer charlie = new Peer("peer_charlie", "Charlie", "127.0.0.1", 9003);
        chatManager.onLanPeerDiscovered(charlie);

        SignalingMessage joinReq = new SignalingMessage(
                SignalingType.GROUP_JOIN_REQUEST,
                "peer_charlie",
                currentUser.getUserId(),
                "Charlie"
        );

        chatManager.handleIncomingGroupJoinRequest(joinReq);

        Optional<Peer> updatedCharlie = chatManager.getPeer("peer_charlie");
        assertTrue(updatedCharlie.isPresent());
        assertTrue(updatedCharlie.get().isGroupJoinRequested());
        assertEquals(ConnectionStatus.REQUEST_RECEIVED, updatedCharlie.get().getConnectionStatus());

        // Connect Bob so Alice has an active cluster member
        Peer bob = new Peer("peer_bob", "Bob", "127.0.0.1", 9002);
        bob.setConnectionStatus(ConnectionStatus.CONNECTED);
        chatManager.getPeerManager().addPeer(bob);
        chatManager.getPeerRepository().savePeer(bob);

        // Alice accepts Charlie's group join request
        chatManager.acceptGroupJoinRequest("peer_charlie").get();

        assertEquals(ConnectionStatus.CONNECTED, updatedCharlie.get().getConnectionStatus());
        assertFalse(updatedCharlie.get().isGroupJoinRequested());
    }

    @Test
    void testGroupIntroduceSpawnsDirectConnection() {
        // Bob introduces Charlie to Alice
        SignalingMessage intro = new SignalingMessage(
                SignalingType.GROUP_INTRODUCE,
                "peer_bob",
                currentUser.getUserId(),
                "peer_charlie:Charlie:127.0.0.1:9003"
        );

        chatManager.handleIncomingGroupIntroduce(intro);

        Optional<Peer> introducedPeer = chatManager.getPeer("peer_charlie");
        assertTrue(introducedPeer.isPresent());
        assertEquals("Charlie", introducedPeer.get().getAlias());
        assertEquals("127.0.0.1", introducedPeer.get().getIpAddress());
        assertEquals(9003, introducedPeer.get().getPort());
        // Introduced peer transitions to CONNECTED
        assertEquals(ConnectionStatus.CONNECTED, introducedPeer.get().getConnectionStatus());
    }

    @Test
    void testGroupChatSessionSyncAndBroadcast() throws Exception {
        // Connect Bob and Charlie to Alice
        Peer bob = new Peer("peer_bob", "Bob", "127.0.0.1", 9001);
        bob.setConnectionStatus(ConnectionStatus.CONNECTED);
        chatManager.getPeerManager().addPeer(bob);
        chatManager.getPeerRepository().savePeer(bob);

        Peer charlie = new Peer("peer_charlie", "Charlie", "127.0.0.1", 9002);
        charlie.setConnectionStatus(ConnectionStatus.CONNECTED);
        chatManager.getPeerManager().addPeer(charlie);
        chatManager.getPeerRepository().savePeer(charlie);

        GroupChatSession groupSession = chatManager.getGroupChatSession();
        assertNotNull(groupSession);
        assertEquals(2, groupSession.getMemberCount());
        assertTrue(groupSession.getMemberAliases().contains("Bob"));
        assertTrue(groupSession.getMemberAliases().contains("Charlie"));

        // Test group history tracking
        TextMessage incomingFromBob = new TextMessage("peer_bob", currentUser.getUserId(), "Hey all!");
        groupSession.addGroupPayload(incomingFromBob);
        assertEquals(1, groupSession.getGroupConversationHistory().size());
        assertEquals("Hey all!", ((TextMessage) groupSession.getGroupConversationHistory().get(0)).getMessageContent());

        // Test broadcast text message async
        CompletableFuture<List<TextMessage>> broadcastFuture = chatManager.sendGroupMessageAsync("Hello Bob and Charlie!");
        assertNotNull(broadcastFuture);
        List<TextMessage> dispatched = broadcastFuture.get();
        assertEquals(2, dispatched.size());

        // Verify sent message was added to group history
        assertTrue(groupSession.getGroupConversationHistory().size() >= 2);
    }

    @Test
    void testCreateCustomGroupChatWithDesignatedMembers() throws Exception {
        Peer bob = new Peer("peer_bob", "Bob", "127.0.0.1", 9001);
        bob.setConnectionStatus(ConnectionStatus.CONNECTED);
        chatManager.getPeerManager().addPeer(bob);

        Peer charlie = new Peer("peer_charlie", "Charlie", "127.0.0.1", 9002);
        charlie.setConnectionStatus(ConnectionStatus.CONNECTED);
        chatManager.getPeerManager().addPeer(charlie);

        Peer dave = new Peer("peer_dave", "Dave", "127.0.0.1", 9003);
        dave.setConnectionStatus(ConnectionStatus.DISCOVERED);
        chatManager.getPeerManager().addPeer(dave);

        // Create a custom named group chat with Bob and Charlie
        GroupChatSession session = chatManager.createGroupChat("Alpha Squad", List.of("peer_bob", "peer_charlie")).get();

        assertNotNull(session);
        assertEquals("Alpha Squad", session.getGroupName());
        assertEquals(2, session.getMemberCount());
        assertTrue(session.getMemberAliases().contains("Bob"));
        assertTrue(session.getMemberAliases().contains("Charlie"));
        assertFalse(session.getMemberAliases().contains("Dave"));
    }
}
