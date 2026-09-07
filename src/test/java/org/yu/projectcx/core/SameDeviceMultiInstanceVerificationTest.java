package org.yu.projectcx.core;

import dev.onvoid.webrtc.RTCDataChannelState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.db.DatabaseManager;
import org.yu.projectcx.db.UserRepository;
import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.model.User;
import org.yu.projectcx.network.signaling.SignalingManager;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end multi-instance verification test on the same host:
 * 1. Registers 2 temporary fake user profiles in the application database.
 * 2. Starts 2 separate ChatManager & Signaling instances on the same host.
 * 3. Connects the two instances via WebRTC P2P DataChannel.
 * 4. Transmits a message from Fake User A to Fake User B and verifies receipt.
 * 5. Deletes both fake users and their conversation data after the test.
 */
public class SameDeviceMultiInstanceVerificationTest {

    private static final Logger logger = LoggerFactory.getLogger(SameDeviceMultiInstanceVerificationTest.class);

    private static final String USER_A = "fake_test_user_a";
    private static final String USER_B = "fake_test_user_b";
    private static final String PASS_A = "FakePassA123!";
    private static final String PASS_B = "FakePassB123!";

    private static final String TEST_DB = "target/same_device_test.db";
    private static final int PORT_A = 19771;
    private static final int PORT_B = 19772;

    private DatabaseManager databaseManager;
    private UserRepository userRepository;
    private SignalingManager signalingA;
    private SignalingManager signalingB;
    private ChatManager chatManagerA;
    private ChatManager chatManagerB;
    private User userA;
    private User userB;

    @BeforeEach
    void setUp() throws SQLException, IOException {
        new File(TEST_DB).delete();
        databaseManager = new DatabaseManager(TEST_DB);
        userRepository = new UserRepository(databaseManager);

        // 1. Initialize two signaling servers on separate ports
        signalingA = new SignalingManager(PORT_A);
        signalingB = new SignalingManager(PORT_B);
        signalingA.start();
        signalingB.start();

        // 2. Initialize two separate ChatManagers
        chatManagerA = new ChatManager(databaseManager, signalingA);
        chatManagerB = new ChatManager(databaseManager, signalingB);

        // 3. Register two fake user profiles (with matching username & display name)
        userA = chatManagerA.register(USER_A, PASS_A, USER_A);
        userB = chatManagerB.register(USER_B, PASS_B, USER_B);

        assertNotNull(userA);
        assertNotNull(userB);
        logger.info("Registered fake users: [{}] and [{}] in database", USER_A, USER_B);

        chatManagerA.setCurrentUser(userA);
        chatManagerB.setCurrentUser(userB);
    }

    @AfterEach
    void tearDown() {
        logger.info("Cleaning up instances and deleting test DB...");

        if (chatManagerA != null) chatManagerA.shutdown();
        if (chatManagerB != null) chatManagerB.shutdown();
        if (signalingA != null) signalingA.stop();
        if (signalingB != null) signalingB.stop();
        if (databaseManager != null) databaseManager.close();

        new File(TEST_DB).delete();
        new File(TEST_DB + "-wal").delete();
        new File(TEST_DB + "-shm").delete();
    }

    @Test
    void testSameDeviceTwoInstancesMessageAndCleanup() throws Exception {
        logger.info("Testing same-device communication between [{}] and [{}]...", USER_A, USER_B);

        // Setup peers
        Peer peerBOnA = chatManagerA.addPeer(userB.getDisplayName(), "127.0.0.1", PORT_B);
        Peer peerAOnB = chatManagerB.addPeer(userA.getDisplayName(), "127.0.0.1", PORT_A);

        assertNotNull(peerBOnA);
        assertNotNull(peerAOnB);

        // Setup listener on Instance B to capture the incoming message
        CountDownLatch msgReceivedLatch = new CountDownLatch(1);
        AtomicReference<TextMessage> receivedMsgRef = new AtomicReference<>();

        chatManagerB.addListener(new ChatEventListener() {
            @Override
            public void onMessageDispatched(TextMessage message) {
                logger.info("Instance B received incoming message from [{}]: {}", message.getSenderId(), message.getMessageContent());
                receivedMsgRef.set(message);
                msgReceivedLatch.countDown();
            }

            @Override
            public void onPayloadDispatched(NetworkPayload payload) {}

            @Override
            public void onPeerSelected(Peer peer) {}

            @Override
            public void onPeersUpdated(List<Peer> peers) {}
        });

        // Setup ChatSession and WebRTC connection
        ChatSession sessionA = chatManagerA.getOrCreateSession(peerBOnA.getPeerId());
        chatManagerB.getOrCreateSession(peerAOnB.getPeerId());

        CountDownLatch dataChannelLatch = new CountDownLatch(1);
        sessionA.getWebrtcManager().addListener(new org.yu.projectcx.network.webrtc.WebRTCDataChannelListener() {
            @Override
            public void onDataChannelStateChange(RTCDataChannelState state) {
                logger.info("WebRTC DataChannel state: {}", state);
                if (state == RTCDataChannelState.OPEN) {
                    dataChannelLatch.countDown();
                }
            }

            @Override
            public void onDataPayloadReceived(NetworkPayload payload) {}

            @Override
            public void onWebRTCError(String errorMessage, Throwable cause) {}
        });

        // Trigger connection from A to B
        sessionA.connect();

        boolean channelOpened = dataChannelLatch.await(6, TimeUnit.SECONDS);
        assertTrue(channelOpened, "WebRTC DataChannel must reach OPEN state between instances on the same device");

        // Send message from Fake User A to Fake User B
        String testMessageText = "Hello Beta! Checking that two instances on the same device work perfectly!";
        CompletableFuture<TextMessage> sendFuture = chatManagerA.sendMessageAsync(testMessageText, peerBOnA.getPeerId());

        TextMessage sentMsg = sendFuture.get(3, TimeUnit.SECONDS);
        assertNotNull(sentMsg, "Sent message object must not be null");
        assertEquals(testMessageText, sentMsg.getMessageContent());

        // Wait for Fake User B to receive the message
        boolean messageReceived = msgReceivedLatch.await(5, TimeUnit.SECONDS);
        assertTrue(messageReceived, "Fake User B must receive the message dispatched by Fake User A");
        assertNotNull(receivedMsgRef.get());
        assertEquals(testMessageText, receivedMsgRef.get().getMessageContent());
        logger.info("Message successfully received by [{}]!", USER_B);
    }
}
