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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test verifying real TCP signaling connection request & acceptance lifecycle:
 * 1. Peer A discovers Peer B (Status: DISCOVERED).
 * 2. Peer A attempts message transmission -> blocked with exception.
 * 3. Peer A dispatches sendConnectionRequest(Peer B) over TCP socket.
 * 4. Peer B receives request event (Status: REQUEST_RECEIVED).
 * 5. Peer B accepts request via acceptConnectionRequest(Peer A) over TCP socket.
 * 6. Peer A receives acceptance event (Status: CONNECTED).
 * 7. Peer B is also CONNECTED.
 * 8. Real-time text messaging between A and B succeeds without blocking.
 * 9. Peer B tests connection rejection on another peer.
 */
public class LivePeerConnectionAndRequestTest {

    private static final Logger logger = LoggerFactory.getLogger(LivePeerConnectionAndRequestTest.class);

    private static final int PORT_A = 19821;
    private static final int PORT_B = 19822;

    private String dbPathA;
    private String dbPathB;

    private DatabaseManager dbA;
    private DatabaseManager dbB;

    private SignalingManager signalingA;
    private SignalingManager signalingB;

    private ChatManager chatManagerA;
    private ChatManager chatManagerB;

    private User userA;
    private User userB;

    @BeforeEach
    void setUp() throws SQLException, IOException {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        dbPathA = "target/live_conn_a_" + runId + ".db";
        dbPathB = "target/live_conn_b_" + runId + ".db";

        dbA = new DatabaseManager(dbPathA);
        dbB = new DatabaseManager(dbPathB);

        signalingA = new SignalingManager(PORT_A);
        signalingB = new SignalingManager(PORT_B);

        signalingA.start();
        signalingB.start();

        chatManagerA = new ChatManager(dbA, signalingA);
        chatManagerB = new ChatManager(dbB, signalingB);

        userA = chatManagerA.register("user_a", "Pass123!", "Alice");
        userB = chatManagerB.register("user_b", "Pass123!", "Bob");

        chatManagerA.login("user_a", "Pass123!");
        chatManagerB.login("user_b", "Pass123!");
    }

    @AfterEach
    void tearDown() {
        if (chatManagerA != null) chatManagerA.shutdown();
        if (chatManagerB != null) chatManagerB.shutdown();
        if (signalingA != null) signalingA.stop();
        if (signalingB != null) signalingB.stop();
        if (dbA != null) dbA.close();
        if (dbB != null) dbB.close();

        new File(dbPathA).delete();
        new File(dbPathA + "-wal").delete();
        new File(dbPathA + "-shm").delete();
        new File(dbPathB).delete();
        new File(dbPathB + "-wal").delete();
        new File(dbPathB + "-shm").delete();
    }

    @Test
    void testFullConnectionRequestAndAcceptanceWorkflow() throws Exception {
        logger.info("Starting live connection request and acceptance test between Alice and Bob...");

        // 1. Initial Discovery: Alice knows Bob is at PORT_B, Bob knows Alice is at PORT_A
        Peer bobPeerOnA = new Peer("user_b", "Bob", "127.0.0.1", PORT_B);
        bobPeerOnA.setConnectionStatus(ConnectionStatus.DISCOVERED);
        chatManagerA.onLanPeerDiscovered(bobPeerOnA);

        Peer alicePeerOnB = new Peer("user_a", "Alice", "127.0.0.1", PORT_A);
        alicePeerOnB.setConnectionStatus(ConnectionStatus.DISCOVERED);
        chatManagerB.onLanPeerDiscovered(alicePeerOnB);

        assertEquals(ConnectionStatus.DISCOVERED, chatManagerA.getPeer("user_b").get().getConnectionStatus());
        assertEquals(ConnectionStatus.DISCOVERED, chatManagerB.getPeer("user_a").get().getConnectionStatus());

        // 2. Messaging must be blocked while in DISCOVERED
        ExecutionException ex = assertThrows(ExecutionException.class, () ->
                chatManagerA.sendMessageAsync("Pre-connection message", "user_b").get(2, TimeUnit.SECONDS)
        );
        assertTrue(ex.getCause().getMessage().contains("not connected yet"));

        // 3. Alice sends Connection Request to Bob over TCP Signaling
        CountDownLatch bobReceivedReqLatch = new CountDownLatch(1);
        chatManagerB.addEventListener(new ChatEventListener() {
            @Override
            public void onPeersUpdated(List<Peer> peers) {
                for (Peer p : peers) {
                    if (p.getPeerId().equals("user_a") && p.getConnectionStatus() == ConnectionStatus.REQUEST_RECEIVED) {
                        bobReceivedReqLatch.countDown();
                    }
                }
            }
        });

        boolean reqSent = chatManagerA.sendConnectionRequest("user_b").get(3, TimeUnit.SECONDS);
        assertTrue(reqSent, "sendConnectionRequest should return true");
        assertEquals(ConnectionStatus.REQUEST_SENT, chatManagerA.getPeer("user_b").get().getConnectionStatus());

        // Bob receives request via TCP
        assertTrue(bobReceivedReqLatch.await(4, TimeUnit.SECONDS), "Bob must receive connection request from Alice");
        assertEquals(ConnectionStatus.REQUEST_RECEIVED, chatManagerB.getPeer("user_a").get().getConnectionStatus());

        // 4. Bob accepts Connection Request from Alice over TCP Signaling
        CountDownLatch aliceConnectedLatch = new CountDownLatch(1);
        chatManagerA.addEventListener(new ChatEventListener() {
            @Override
            public void onPeersUpdated(List<Peer> peers) {
                for (Peer p : peers) {
                    if (p.getPeerId().equals("user_b") && p.getConnectionStatus() == ConnectionStatus.CONNECTED) {
                        aliceConnectedLatch.countDown();
                    }
                }
            }
        });

        boolean accepted = chatManagerB.acceptConnectionRequest("user_a").get(3, TimeUnit.SECONDS);
        assertTrue(accepted, "acceptConnectionRequest should return true");
        assertEquals(ConnectionStatus.CONNECTED, chatManagerB.getPeer("user_a").get().getConnectionStatus());

        // Alice receives accept via TCP
        assertTrue(aliceConnectedLatch.await(4, TimeUnit.SECONDS), "Alice must receive connection acceptance from Bob");
        assertEquals(ConnectionStatus.CONNECTED, chatManagerA.getPeer("user_b").get().getConnectionStatus());

        // 5. Verification: Messages can now flow bidirectionally
        TextMessage msgFromAlice = chatManagerA.sendMessageAsync("Hello Bob! We are connected!", "user_b").get(3, TimeUnit.SECONDS);
        assertNotNull(msgFromAlice);
        assertEquals("Hello Bob! We are connected!", msgFromAlice.getMessageContent());

        TextMessage msgFromBob = chatManagerB.sendMessageAsync("Hi Alice! Nice to meet you!", "user_a").get(3, TimeUnit.SECONDS);
        assertNotNull(msgFromBob);
        assertEquals("Hi Alice! Nice to meet you!", msgFromBob.getMessageContent());

        logger.info("Connection request and acceptance lifecycle verified successfully!");
    }

    @Test
    void testConnectionRequestRejectionWorkflow() throws Exception {
        logger.info("Starting connection request rejection test...");

        Peer bobPeerOnA = new Peer("user_b", "Bob", "127.0.0.1", PORT_B);
        chatManagerA.onLanPeerDiscovered(bobPeerOnA);

        Peer alicePeerOnB = new Peer("user_a", "Alice", "127.0.0.1", PORT_A);
        chatManagerB.onLanPeerDiscovered(alicePeerOnB);

        // Alice sends request
        CountDownLatch bobReceivedReqLatch = new CountDownLatch(1);
        chatManagerB.addEventListener(new ChatEventListener() {
            @Override
            public void onPeersUpdated(List<Peer> peers) {
                for (Peer p : peers) {
                    if (p.getPeerId().equals("user_a") && p.getConnectionStatus() == ConnectionStatus.REQUEST_RECEIVED) {
                        bobReceivedReqLatch.countDown();
                    }
                }
            }
        });

        chatManagerA.sendConnectionRequest("user_b").get(3, TimeUnit.SECONDS);
        assertTrue(bobReceivedReqLatch.await(4, TimeUnit.SECONDS));

        // Bob rejects request
        CountDownLatch aliceRejectedLatch = new CountDownLatch(1);
        chatManagerA.addEventListener(new ChatEventListener() {
            @Override
            public void onPeersUpdated(List<Peer> peers) {
                for (Peer p : peers) {
                    if (p.getPeerId().equals("user_b") && p.getConnectionStatus() == ConnectionStatus.REJECTED) {
                        aliceRejectedLatch.countDown();
                    }
                }
            }
        });

        boolean rejected = chatManagerB.rejectConnectionRequest("user_a").get(3, TimeUnit.SECONDS);
        assertTrue(rejected);
        assertEquals(ConnectionStatus.REJECTED, chatManagerB.getPeer("user_a").get().getConnectionStatus());

        assertTrue(aliceRejectedLatch.await(4, TimeUnit.SECONDS), "Alice should receive rejection notification");
        assertEquals(ConnectionStatus.REJECTED, chatManagerA.getPeer("user_b").get().getConnectionStatus());

        // Messaging remains blocked
        ExecutionException ex = assertThrows(ExecutionException.class, () ->
                chatManagerA.sendMessageAsync("Blocked message", "user_b").get(2, TimeUnit.SECONDS)
        );
        assertTrue(ex.getCause().getMessage().contains("not connected yet"));
    }
}
