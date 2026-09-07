package org.yu.projectcx.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.db.DatabaseManager;
import org.yu.projectcx.model.ConnectionStatus;
import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.model.User;
import org.yu.projectcx.network.signaling.SignalingManager;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates strict isolation between 1-on-1 Direct Messages and Group/Mesh Chat:
 * 1. 1-on-1 DMs never appear in group chat history.
 * 2. Group messages never leak into 1-on-1 DM conversations in SQLite.
 * 3. Clearing group chat does not affect 1-on-1 DM history.
 * 4. Clearing 1-on-1 DM history does not affect group chat history.
 */
public class GroupChatIsolationTest {

    private static final Logger logger = LoggerFactory.getLogger(GroupChatIsolationTest.class);

    private static final String USER_ALICE = "iso_alice";
    private static final String USER_BOB = "iso_bob";
    private static final String USER_CHARLIE = "iso_charlie";

    private static final int PORT_ALICE = 19821;
    private static final int PORT_BOB = 19822;
    private static final int PORT_CHARLIE = 19823;

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
        String testId = UUID.randomUUID().toString().substring(0, 8);
        dbPathA = "target/iso_test_a_" + testId + ".db";
        dbPathB = "target/iso_test_b_" + testId + ".db";
        dbPathC = "target/iso_test_c_" + testId + ".db";

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

        alice = chatManagerA.register(USER_ALICE, "Pass1234!", "Alice");
        bob = chatManagerB.register(USER_BOB, "Pass1234!", "Bob");
        charlie = chatManagerC.register(USER_CHARLIE, "Pass1234!", "Charlie");

        chatManagerA.login(USER_ALICE, "Pass1234!");
        chatManagerB.login(USER_BOB, "Pass1234!");
        chatManagerC.login(USER_CHARLIE, "Pass1234!");
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
    void testDirectMessageAndGroupChatIsolation() throws Exception {
        // Step 1: Connect Alice and Bob 1-on-1
        chatManagerA.onLanPeerDiscovered(new Peer(bob.getUsername(), "Bob", "127.0.0.1", PORT_BOB));
        chatManagerB.onLanPeerDiscovered(new Peer(alice.getUsername(), "Alice", "127.0.0.1", PORT_ALICE));

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

        // Step 2: Charlie joins the group via Bob
        chatManagerC.onLanPeerDiscovered(new Peer(bob.getUsername(), "Bob", "127.0.0.1", PORT_BOB));

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

        chatManagerC.sendGroupJoinRequest(bob.getUsername()).get(3, TimeUnit.SECONDS);
        assertTrue(bobGroupJoinReqLatch.await(3, TimeUnit.SECONDS), "Bob should receive group join request from Charlie");

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

        chatManagerB.acceptGroupJoinRequest(charlie.getUsername()).get(3, TimeUnit.SECONDS);

        assertTrue(charlieConnectedWithBobLatch.await(3, TimeUnit.SECONDS), "Charlie should become CONNECTED with Bob");
        assertTrue(aliceIntroLatch.await(3, TimeUnit.SECONDS), "Alice should receive GROUP_INTRODUCE and connect with Charlie");

        // Step 3: Send 1-on-1 Direct Message from Alice to Bob
        CountDownLatch dmArrivedLatch = new CountDownLatch(1);
        chatManagerB.addEventListener(new ChatEventListener() {
            @Override
            public void onMessageDispatched(TextMessage message) {
                if ("Secret 1-on-1 DM from Alice to Bob".equals(message.getMessageContent())) {
                    dmArrivedLatch.countDown();
                }
            }
        });

        chatManagerA.sendMessage("Secret 1-on-1 DM from Alice to Bob", bob.getUsername());
        assertTrue(dmArrivedLatch.await(3, TimeUnit.SECONDS), "Bob should receive 1-on-1 DM");

        // Verify DM is in Bob's 1-on-1 conversation with Alice
        List<NetworkPayload> bobDmWithAlice = chatManagerB.getMessageRepository()
                .getConversation(bob.getUserId(), alice.getUsername());
        assertTrue(bobDmWithAlice.stream().anyMatch(p -> p instanceof TextMessage tm && tm.getMessageContent().contains("Secret 1-on-1 DM")));

        // Critical Check 1: DM MUST NOT appear in Bob's group conversation history
        List<NetworkPayload> bobGroupHistory = chatManagerB.getMessageRepository()
                .getGroupConversation(GroupChatSession.GROUP_PEER_ID);
        assertFalse(bobGroupHistory.stream().anyMatch(p -> p instanceof TextMessage tm && tm.getMessageContent().contains("Secret 1-on-1 DM")),
                "1-on-1 DM must NOT exist in Bob's group conversation database table");

        // Step 4: Send Group Message from Charlie to the Mesh
        CountDownLatch groupArrivedAtAlice = new CountDownLatch(1);
        CountDownLatch groupArrivedAtBob = new CountDownLatch(1);

        chatManagerA.addEventListener(new ChatEventListener() {
            @Override
            public void onMessageDispatched(TextMessage message) {
                if ("Public announcement to all mesh peers".equals(message.getMessageContent())) {
                    groupArrivedAtAlice.countDown();
                }
            }
        });
        chatManagerB.addEventListener(new ChatEventListener() {
            @Override
            public void onMessageDispatched(TextMessage message) {
                if ("Public announcement to all mesh peers".equals(message.getMessageContent())) {
                    groupArrivedAtBob.countDown();
                }
            }
        });

        chatManagerC.sendGroupMessageAsync("Public announcement to all mesh peers").get(3, TimeUnit.SECONDS);

        assertTrue(groupArrivedAtAlice.await(3, TimeUnit.SECONDS), "Alice should receive group message");
        assertTrue(groupArrivedAtBob.await(3, TimeUnit.SECONDS), "Bob should receive group message");

        // Verify group message has recipientId = GROUP_PEER_ID
        List<NetworkPayload> aliceGroupHistory = chatManagerA.getMessageRepository().getGroupConversation(GroupChatSession.GROUP_PEER_ID);
        assertTrue(aliceGroupHistory.stream().anyMatch(p -> p instanceof TextMessage tm && tm.getMessageContent().contains("Public announcement")));

        // Critical Check 2: Group message MUST NOT appear in 1-on-1 DM history between Alice and Bob
        List<NetworkPayload> aliceDmWithBob = chatManagerA.getMessageRepository().getConversation(alice.getUserId(), bob.getUsername());
        assertFalse(aliceDmWithBob.stream().anyMatch(p -> p instanceof TextMessage tm && tm.getMessageContent().contains("Public announcement")),
                "Group message must NOT appear in Alice-Bob 1-on-1 DM conversation");

        // Critical Check 3: Group message MUST NOT appear in 1-on-1 DM history between Bob and Charlie
        List<NetworkPayload> bobDmWithCharlie = chatManagerB.getMessageRepository().getConversation(bob.getUserId(), charlie.getUsername());
        assertFalse(bobDmWithCharlie.stream().anyMatch(p -> p instanceof TextMessage tm && tm.getMessageContent().contains("Public announcement")),
                "Group message must NOT appear in Bob-Charlie 1-on-1 DM conversation");

        // Step 5: Test Clear Group History
        chatManagerB.clearGroupChatHistory().get(3, TimeUnit.SECONDS);
        List<NetworkPayload> bobGroupHistoryAfterClear = chatManagerB.getMessageRepository().getGroupConversation(GroupChatSession.GROUP_PEER_ID);
        assertTrue(bobGroupHistoryAfterClear.isEmpty(), "Group conversation in Bob's DB should be empty after clearGroupChatHistory");

        // 1-on-1 DM with Alice MUST STILL EXIST after clearing group chat
        List<NetworkPayload> bobDmWithAliceAfterClear = chatManagerB.getMessageRepository().getConversation(bob.getUserId(), alice.getUsername());
        assertTrue(bobDmWithAliceAfterClear.stream().anyMatch(p -> p instanceof TextMessage tm && tm.getMessageContent().contains("Secret 1-on-1 DM")),
                "1-on-1 DM must still exist after clearing group chat");

        logger.info("=== Isolation Test Passed Successfully! ===");
    }
}
