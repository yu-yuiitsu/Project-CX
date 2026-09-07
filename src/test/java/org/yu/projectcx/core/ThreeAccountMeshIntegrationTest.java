package org.yu.projectcx.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.db.DatabaseManager;
import org.yu.projectcx.model.ConnectionStatus;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.model.User;
import org.yu.projectcx.network.signaling.SignalingManager;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test validating the system with 3 concurrent accounts (Alice, Bob, Charlie).
 * Verifies:
 * 1. 1-on-1 direct connection request & acceptance.
 * 2. Peer network awareness: third party (Charlie) can inspect and know that Bob is connected to Alice.
 * 3. Charlie asks to join the group connection with Bob.
 * 4. Bob accepts and introduces Charlie to Alice via GROUP_INTRODUCE.
 * 5. Full-mesh P2P topology is established among all 3 accounts.
 * 6. 1-on-1 direct messaging and Group Chat multi-peer broadcast messaging.
 */
public class ThreeAccountMeshIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(ThreeAccountMeshIntegrationTest.class);

    private static final String USER_ALICE = "mesh_alice";
    private static final String USER_BOB = "mesh_bob";
    private static final String USER_CHARLIE = "mesh_charlie";

    private static final int PORT_ALICE = 19811;
    private static final int PORT_BOB = 19812;
    private static final int PORT_CHARLIE = 19813;

    private String dbPathA;
    private String dbPathB;
    private String dbPathC;

    private DatabaseManager dbA;
    private DatabaseManager dbB;
    private DatabaseManager dbC;

    private SignalingManager signalingA;
    private SignalingManager signalingB;
    private SignalingManager signalingC;

    private ChatManager chatManagerA;
    private ChatManager chatManagerB;
    private ChatManager chatManagerC;

    private User alice;
    private User bob;
    private User charlie;

    @BeforeEach
    void setUp() throws SQLException, IOException {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        dbPathA = "target/mesh_test_a_" + runId + ".db";
        dbPathB = "target/mesh_test_b_" + runId + ".db";
        dbPathC = "target/mesh_test_c_" + runId + ".db";

        dbA = new DatabaseManager(dbPathA);
        dbB = new DatabaseManager(dbPathB);
        dbC = new DatabaseManager(dbPathC);

        signalingA = new SignalingManager(PORT_ALICE);
        signalingB = new SignalingManager(PORT_BOB);
        signalingC = new SignalingManager(PORT_CHARLIE);

        signalingA.start();
        signalingB.start();
        signalingC.start();

        chatManagerA = new ChatManager(dbA, signalingA);
        chatManagerB = new ChatManager(dbB, signalingB);
        chatManagerC = new ChatManager(dbC, signalingC);

        alice = chatManagerA.register(USER_ALICE, "Password123!", "Alice");
        bob = chatManagerB.register(USER_BOB, "Password123!", "Bob");
        charlie = chatManagerC.register(USER_CHARLIE, "Password123!", "Charlie");

        chatManagerA.login(USER_ALICE, "Password123!");
        chatManagerB.login(USER_BOB, "Password123!");
        chatManagerC.login(USER_CHARLIE, "Password123!");
    }

    @AfterEach
    void tearDown() {
        if (chatManagerA != null) chatManagerA.shutdown();
        if (chatManagerB != null) chatManagerB.shutdown();
        if (chatManagerC != null) chatManagerC.shutdown();

        if (signalingA != null) signalingA.stop();
        if (signalingB != null) signalingB.stop();
        if (signalingC != null) signalingC.stop();

        if (dbA != null) dbA.close();
        if (dbB != null) dbB.close();
        if (dbC != null) dbC.close();

        cleanupDbFiles(dbPathA);
        cleanupDbFiles(dbPathB);
        cleanupDbFiles(dbPathC);
    }

    private void cleanupDbFiles(String path) {
        if (path == null) return;
        new File(path).delete();
        new File(path + "-wal").delete();
        new File(path + "-shm").delete();
    }

    @Test
    void testThreeAccountCompleteWorkflow() throws Exception {
        logger.info("=== STEP 1: 1-on-1 Connection between Alice and Bob ===");

        // Alice discovers Bob and Bob discovers Alice
        Peer peerBobForAlice = new Peer(bob.getUsername(), "Bob", "127.0.0.1", PORT_BOB);
        chatManagerA.onLanPeerDiscovered(peerBobForAlice);

        Peer peerAliceForBob = new Peer(alice.getUsername(), "Alice", "127.0.0.1", PORT_ALICE);
        chatManagerB.onLanPeerDiscovered(peerAliceForBob);

        assertEquals(ConnectionStatus.DISCOVERED, chatManagerA.getPeer(bob.getUsername()).get().getConnectionStatus());
        assertEquals(ConnectionStatus.DISCOVERED, chatManagerB.getPeer(alice.getUsername()).get().getConnectionStatus());

        // Alice sends connection request to Bob
        CountDownLatch bobReqReceivedLatch = new CountDownLatch(1);
        chatManagerB.addEventListener(new ChatEventListener() {
            @Override
            public void onPeersUpdated(List<Peer> peers) {
                for (Peer p : peers) {
                    if (p.getPeerId().equals(alice.getUsername()) && p.getConnectionStatus() == ConnectionStatus.REQUEST_RECEIVED) {
                        bobReqReceivedLatch.countDown();
                    }
                }
            }
        });

        chatManagerA.sendConnectionRequest(bob.getUsername()).get(3, TimeUnit.SECONDS);

        assertTrue(bobReqReceivedLatch.await(3, TimeUnit.SECONDS), "Bob should receive connection request from Alice");
        assertEquals(ConnectionStatus.REQUEST_SENT, chatManagerA.getPeer(bob.getUsername()).get().getConnectionStatus());
        assertEquals(ConnectionStatus.REQUEST_RECEIVED, chatManagerB.getPeer(alice.getUsername()).get().getConnectionStatus());

        // Bob accepts connection request
        CountDownLatch aliceConnectedLatch = new CountDownLatch(1);
        chatManagerA.addEventListener(new ChatEventListener() {
            @Override
            public void onPeersUpdated(List<Peer> peers) {
                for (Peer p : peers) {
                    if (p.getPeerId().equals(bob.getUsername()) && p.getConnectionStatus() == ConnectionStatus.CONNECTED) {
                        aliceConnectedLatch.countDown();
                    }
                }
            }
        });

        chatManagerB.acceptConnectionRequest(alice.getUsername()).get(3, TimeUnit.SECONDS);

        assertTrue(aliceConnectedLatch.await(3, TimeUnit.SECONDS), "Alice should become CONNECTED with Bob");
        assertEquals(ConnectionStatus.CONNECTED, chatManagerA.getPeer(bob.getUsername()).get().getConnectionStatus());
        assertEquals(ConnectionStatus.CONNECTED, chatManagerB.getPeer(alice.getUsername()).get().getConnectionStatus());

        // Test 1-on-1 message between Alice and Bob
        TextMessage msgFromAlice = chatManagerA.sendMessage("Hello Bob from Alice!", bob.getUsername());
        assertNotNull(msgFromAlice);
        assertEquals("Hello Bob from Alice!", msgFromAlice.getMessageContent());

        logger.info("=== STEP 2: Peer Network Awareness (Charlie discovers Bob is connected to Alice) ===");

        // Charlie discovers Bob
        Peer peerBobForCharlie = new Peer(bob.getUsername(), "Bob", "127.0.0.1", PORT_BOB);
        chatManagerC.onLanPeerDiscovered(peerBobForCharlie);
        assertEquals(ConnectionStatus.DISCOVERED, chatManagerC.getPeer(bob.getUsername()).get().getConnectionStatus());

        // Charlie checks if Bob is connected to another peer by querying Bob's network
        CountDownLatch charlieNetworkAwarenessLatch = new CountDownLatch(1);
        chatManagerC.addEventListener(new ChatEventListener() {
            @Override
            public void onPeersUpdated(List<Peer> peers) {
                for (Peer p : peers) {
                    if (p.getPeerId().equals(bob.getUsername()) && p.hasConnectedPeers()) {
                        charlieNetworkAwarenessLatch.countDown();
                    }
                }
            }
        });

        // Charlie selects Bob, triggering queryPeerConnectedNetwork
        chatManagerC.queryPeerConnectedNetwork(bob.getUsername());

        assertTrue(charlieNetworkAwarenessLatch.await(3, TimeUnit.SECONDS), "Charlie should receive Bob's connected network response");

        Peer bobViewedByCharlie = chatManagerC.getPeer(bob.getUsername()).get();
        assertTrue(bobViewedByCharlie.hasConnectedPeers(), "Charlie should know Bob is connected to other peers");
        assertTrue(bobViewedByCharlie.getConnectedPeerAliases().contains("Alice"), "Charlie should know Bob is connected to Alice");
        assertTrue(bobViewedByCharlie.getConnectedPeersSummary().contains("Alice"), "Connected peers summary should include Alice");

        logger.info("=== STEP 3: Charlie asks to join the Group Connection with Bob ===");

        CountDownLatch bobGroupJoinReqLatch = new CountDownLatch(1);
        chatManagerB.addEventListener(new ChatEventListener() {
            @Override
            public void onPeersUpdated(List<Peer> peers) {
                for (Peer p : peers) {
                    if (p.getPeerId().equals(charlie.getUsername()) && p.isGroupJoinRequested()) {
                        bobGroupJoinReqLatch.countDown();
                    }
                }
            }
        });

        // Charlie clicks [👥 Ask to Join Group Connection]
        chatManagerC.sendGroupJoinRequest(bob.getUsername()).get(3, TimeUnit.SECONDS);

        assertTrue(bobGroupJoinReqLatch.await(3, TimeUnit.SECONDS), "Bob should receive Charlie's group join request");
        Peer charlieViewedByBob = chatManagerB.getPeer(charlie.getUsername()).get();
        assertTrue(charlieViewedByBob.isGroupJoinRequested(), "Bob should see Charlie requested a group join");
        assertEquals(ConnectionStatus.REQUEST_RECEIVED, charlieViewedByBob.getConnectionStatus());

        logger.info("=== STEP 4: Bob accepts Group Join & coordinates full-mesh GROUP_INTRODUCE to Alice ===");

        CountDownLatch aliceIntroLatch = new CountDownLatch(1);
        chatManagerA.addEventListener(new ChatEventListener() {
            @Override
            public void onPeersUpdated(List<Peer> peers) {
                for (Peer p : peers) {
                    if (p.getPeerId().equals(charlie.getUsername()) && p.getConnectionStatus() == ConnectionStatus.CONNECTED) {
                        aliceIntroLatch.countDown();
                    }
                }
            }
        });

        CountDownLatch charlieConnectedWithBobLatch = new CountDownLatch(1);
        chatManagerC.addEventListener(new ChatEventListener() {
            @Override
            public void onPeersUpdated(List<Peer> peers) {
                for (Peer p : peers) {
                    if (p.getPeerId().equals(bob.getUsername()) && p.getConnectionStatus() == ConnectionStatus.CONNECTED) {
                        charlieConnectedWithBobLatch.countDown();
                    }
                }
            }
        });

        // Bob accepts Charlie's group join request
        chatManagerB.acceptGroupJoinRequest(charlie.getUsername()).get(3, TimeUnit.SECONDS);

        assertTrue(charlieConnectedWithBobLatch.await(3, TimeUnit.SECONDS), "Charlie should become CONNECTED with Bob");
        assertTrue(aliceIntroLatch.await(3, TimeUnit.SECONDS), "Alice should receive GROUP_INTRODUCE and connect with Charlie");

        // Verify that Charlie and Alice also establish mutual CONNECTED status
        Optional<Peer> charlieInAlice = chatManagerA.getPeer(charlie.getUsername());
        assertTrue(charlieInAlice.isPresent());
        assertEquals(ConnectionStatus.CONNECTED, charlieInAlice.get().getConnectionStatus());

        Optional<Peer> aliceInCharlie = chatManagerC.getPeer(alice.getUsername());
        assertTrue(aliceInCharlie.isPresent());
        assertEquals(ConnectionStatus.CONNECTED, aliceInCharlie.get().getConnectionStatus());

        logger.info("=== STEP 5: Verify Full-Mesh Group Chat Sessions on all 3 Accounts ===");

        GroupChatSession groupSessionA = chatManagerA.getGroupChatSession();
        GroupChatSession groupSessionB = chatManagerB.getGroupChatSession();
        GroupChatSession groupSessionC = chatManagerC.getGroupChatSession();

        assertEquals(2, groupSessionA.getMemberCount(), "Alice should have 2 active group members (Bob, Charlie)");
        assertEquals(2, groupSessionB.getMemberCount(), "Bob should have 2 active group members (Alice, Charlie)");
        assertEquals(2, groupSessionC.getMemberCount(), "Charlie should have 2 active group members (Alice, Bob)");

        assertTrue(groupSessionA.getMemberAliases().contains("Bob"));
        assertTrue(groupSessionA.getMemberAliases().contains("Charlie"));

        assertTrue(groupSessionB.getMemberAliases().contains("Alice"));
        assertTrue(groupSessionB.getMemberAliases().contains("Charlie"));

        assertTrue(groupSessionC.getMemberAliases().contains("Alice"));
        assertTrue(groupSessionC.getMemberAliases().contains("Bob"));

        logger.info("=== STEP 6: Test Group Broadcast Messaging across all 3 Nodes ===");

        // Charlie broadcasts to the group
        List<TextMessage> charlieBroadcastMsgs = chatManagerC.sendGroupMessageAsync("Hello everyone in our 3-node group!").get(3, TimeUnit.SECONDS);
        assertEquals(2, charlieBroadcastMsgs.size(), "Charlie's message should broadcast to both Bob and Alice");

        // Verify Charlie's group history contains the broadcast message
        assertTrue(groupSessionC.getGroupConversationHistory().stream().anyMatch(p ->
                p instanceof TextMessage tm && tm.getMessageContent().contains("Hello everyone in our 3-node group!")
        ));

        // Alice broadcasts to the group
        List<TextMessage> aliceBroadcastMsgs = chatManagerA.sendGroupMessageAsync("Welcome Charlie to the group!").get(3, TimeUnit.SECONDS);
        assertEquals(2, aliceBroadcastMsgs.size(), "Alice's message should broadcast to both Bob and Charlie");

        assertTrue(groupSessionA.getGroupConversationHistory().stream().anyMatch(p ->
                p instanceof TextMessage tm && tm.getMessageContent().contains("Welcome Charlie to the group!")
        ));

        logger.info("=== SUCCESS: All 3 accounts and all connection methods verified! ===");
    }
}
