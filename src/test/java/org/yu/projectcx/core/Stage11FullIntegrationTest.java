package org.yu.projectcx.core;

import dev.onvoid.webrtc.RTCDataChannelState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yu.projectcx.db.DatabaseManager;
import org.yu.projectcx.model.FileTransfer;
import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.model.User;
import org.yu.projectcx.network.signaling.SignalingManager;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 11: End-to-End Complete Application Integration Test
 * 
 * Verifies the full chain:
 * GUI / ChatManager -> MessageManager & PeerManager -> DatabaseManager (SQLite) -> WebRTCManager -> DataChannel -> PEER
 */
class Stage11FullIntegrationTest {

    private static final String DB_A = "target/chat_stage11_a.db";
    private static final String DB_B = "target/chat_stage11_b.db";

    private static final int PORT_A = 19301;
    private static final int PORT_B = 19302;

    private DatabaseManager dbManagerA;
    private DatabaseManager dbManagerB;

    private SignalingManager signalingA;
    private SignalingManager signalingB;

    private ChatManager chatManagerA;
    private ChatManager chatManagerB;

    private User userAlice;
    private User userBob;

    @BeforeEach
    void setUp() throws SQLException, IOException {
        new File(DB_A).delete();
        new File(DB_B).delete();

        dbManagerA = new DatabaseManager(DB_A);
        dbManagerB = new DatabaseManager(DB_B);

        signalingA = new SignalingManager(PORT_A);
        signalingB = new SignalingManager(PORT_B);
        signalingA.start();
        signalingB.start();

        chatManagerA = new ChatManager(dbManagerA, signalingA);
        chatManagerB = new ChatManager(dbManagerB, signalingB);

        userAlice = chatManagerA.getUserRepository().findByUsername("alice")
                .orElseGet(() -> {
                    try {
                        return chatManagerA.register("alice", "pass123", "Alice");
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });

        userBob = chatManagerB.getUserRepository().findByUsername("bob")
                .orElseGet(() -> {
                    try {
                        return chatManagerB.register("bob", "pass456", "Bob");
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });

        chatManagerA.setCurrentUser(userAlice);
        chatManagerB.setCurrentUser(userBob);
    }

    @AfterEach
    void tearDown() {
        if (chatManagerA != null) chatManagerA.shutdown();
        if (chatManagerB != null) chatManagerB.shutdown();
        if (signalingA != null) signalingA.stop();
        if (signalingB != null) signalingB.stop();
        if (dbManagerA != null) dbManagerA.close();
        if (dbManagerB != null) dbManagerB.close();

        new File(DB_A).delete();
        new File(DB_A + "-wal").delete();
        new File(DB_A + "-shm").delete();
        new File(DB_B).delete();
        new File(DB_B + "-wal").delete();
        new File(DB_B + "-shm").delete();
    }

    @Test
    void testCompleteP2PChatPipeline() throws Exception {
        // 1. Setup Peer Directory on both nodes
        Peer peerBobOnA = chatManagerA.addPeer("Bob", "127.0.0.1", PORT_B);
        Peer peerAliceOnB = chatManagerB.addPeer("Alice", "127.0.0.1", PORT_A);

        assertNotNull(peerBobOnA);
        assertNotNull(peerAliceOnB);

        // 2. Prepare listeners on Bob's ChatManager to capture incoming WebRTC messages
        CountDownLatch bobReceivedMsgLatch = new CountDownLatch(1);
        AtomicReference<TextMessage> bobReceivedMsg = new AtomicReference<>();

        chatManagerB.addListener(new ChatEventListener() {
            @Override
            public void onMessageDispatched(TextMessage message) {
                if (peerAliceOnB.getPeerId().equals(message.getSenderId()) || "alice".equalsIgnoreCase(message.getSenderId())) {
                    bobReceivedMsg.set(message);
                    bobReceivedMsgLatch.countDown();
                }
            }

            @Override
            public void onPayloadDispatched(NetworkPayload payload) {}

            @Override
            public void onPeerSelected(Peer peer) {}

            @Override
            public void onPeersUpdated(List<Peer> peers) {}
        });

        // 3. Prepare listeners on Alice's ChatManager to capture Bob's response
        CountDownLatch aliceReceivedFileLatch = new CountDownLatch(1);
        AtomicReference<FileTransfer> aliceReceivedFile = new AtomicReference<>();

        chatManagerA.addListener(new ChatEventListener() {
            @Override
            public void onMessageDispatched(TextMessage message) {}

            @Override
            public void onPayloadDispatched(NetworkPayload payload) {
                if (payload instanceof FileTransfer ft && (peerBobOnA.getPeerId().equals(ft.getSenderId()) || "bob".equalsIgnoreCase(ft.getSenderId()))) {
                    aliceReceivedFile.set(ft);
                    aliceReceivedFileLatch.countDown();
                }
            }

            @Override
            public void onPeerSelected(Peer peer) {}

            @Override
            public void onPeersUpdated(List<Peer> peers) {}
        });

        // 4. Alice and Bob create ChatSessions
        ChatSession sessionA = chatManagerA.getOrCreateSession(peerBobOnA.getPeerId());
        ChatSession sessionB = chatManagerB.getOrCreateSession(peerAliceOnB.getPeerId());

        CountDownLatch dataChannelOpenLatch = new CountDownLatch(1);
        sessionA.getWebrtcManager().addListener(new org.yu.projectcx.network.webrtc.WebRTCDataChannelListener() {
            @Override
            public void onDataChannelStateChange(RTCDataChannelState state) {
                if (state == RTCDataChannelState.OPEN) {
                    dataChannelOpenLatch.countDown();
                }
            }

            @Override
            public void onDataPayloadReceived(NetworkPayload payload) {}

            @Override
            public void onWebRTCError(String errorMessage, Throwable cause) {}
        });

        // Trigger connection as caller
        sessionA.connect();

        // Wait for WebRTC SCTP DataChannel to transition to OPEN
        boolean connected = dataChannelOpenLatch.await(5, TimeUnit.SECONDS);
        assertTrue(connected, "WebRTC DataChannel between Alice and Bob should reach OPEN state");

        // 5. Alice dispatches a TextMessage asynchronously via ChatManager
        CompletableFuture<TextMessage> sendFuture = chatManagerA.sendMessageAsync(
                "Hello Bob! This message traversed the complete P2P WebRTC DataChannel!",
                peerBobOnA.getPeerId()
        );

        TextMessage sentMsg = sendFuture.get(3, TimeUnit.SECONDS);
        assertNotNull(sentMsg);
        assertEquals("Hello Bob! This message traversed the complete P2P WebRTC DataChannel!", sentMsg.getMessageContent());

        // 6. Bob receives the message live over DataChannel and it is saved to Bob's SQLite DB
        assertTrue(bobReceivedMsgLatch.await(5, TimeUnit.SECONDS), "Bob must receive Alice's message over DataChannel");
        assertNotNull(bobReceivedMsg.get());
        assertEquals(sentMsg.getMessageContent(), bobReceivedMsg.get().getMessageContent());

        // Verify Bob's SQLite database has the message
        List<NetworkPayload> bobHistory = chatManagerB.getConversationHistoryAsync(peerAliceOnB.getPeerId()).get(2, TimeUnit.SECONDS);
        assertFalse(bobHistory.isEmpty(), "Bob's SQLite history must contain Alice's message");
        assertEquals(sentMsg.getMessageContent(), ((TextMessage) bobHistory.get(0)).getMessageContent());

        // 7. Bob replies with a FileTransfer payload over the same active DataChannel
        CompletableFuture<FileTransfer> fileFuture = chatManagerB.sendFileAsync(
                "shared_doc.pdf",
                2048500L,
                "sha256_checksum_abc123",
                "application/pdf",
                peerAliceOnB.getPeerId()
        );

        FileTransfer sentFile = fileFuture.get(3, TimeUnit.SECONDS);
        assertNotNull(sentFile);

        // 8. Alice receives Bob's FileTransfer live over WebRTC DataChannel
        assertTrue(aliceReceivedFileLatch.await(5, TimeUnit.SECONDS), "Alice must receive Bob's file over DataChannel");
        assertNotNull(aliceReceivedFile.get());
        assertEquals("shared_doc.pdf", aliceReceivedFile.get().getFileName());

        // Verify Alice's SQLite database persisted the sent and received messages
        List<NetworkPayload> aliceHistory = chatManagerA.getConversationHistoryAsync(peerBobOnA.getPeerId()).get(2, TimeUnit.SECONDS);
        assertTrue(aliceHistory.size() >= 2, "Alice's SQLite history must have both the sent message and received file");
    }
}
