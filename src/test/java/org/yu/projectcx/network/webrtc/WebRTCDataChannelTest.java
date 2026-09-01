package org.yu.projectcx.network.webrtc;

import dev.onvoid.webrtc.RTCDataChannelState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yu.projectcx.model.FileTransfer;
import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.network.signaling.SignalingManager;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 10 Tests: Native WebRTC PeerConnection, DataChannel Negotiation & Live P2P Streaming.
 */
class WebRTCDataChannelTest {

    private SignalingManager signalingA;
    private SignalingManager signalingB;
    private WebRTCManager peerA;
    private WebRTCManager peerB;

    private static final int PORT_A = 19201;
    private static final int PORT_B = 19202;

    @BeforeEach
    void setUp() throws IOException {
        signalingA = new SignalingManager(PORT_A);
        signalingB = new SignalingManager(PORT_B);

        signalingA.start();
        signalingB.start();

        peerA = new WebRTCManager("peer_alice", "peer_bob", "127.0.0.1", PORT_B, signalingA);
        peerB = new WebRTCManager("peer_bob", "peer_alice", "127.0.0.1", PORT_A, signalingB);
    }

    @AfterEach
    void tearDown() {
        if (peerA != null) peerA.close();
        if (peerB != null) peerB.close();
        if (signalingA != null) signalingA.stop();
        if (signalingB != null) signalingB.stop();
    }

    @Test
    void testWebRTCDataChannelLifecycleAndMessageStreaming() throws Exception {
        CountDownLatch channelOpenLatchA = new CountDownLatch(1);
        CountDownLatch channelOpenLatchB = new CountDownLatch(1);
        CountDownLatch messageReceivedLatchB = new CountDownLatch(1);
        CountDownLatch fileReceivedLatchA = new CountDownLatch(1);

        AtomicReference<NetworkPayload> receivedByB = new AtomicReference<>();
        AtomicReference<NetworkPayload> receivedByA = new AtomicReference<>();

        peerA.addListener(new WebRTCDataChannelListener() {
            @Override
            public void onDataChannelStateChange(RTCDataChannelState state) {
                if (state == RTCDataChannelState.OPEN) {
                    channelOpenLatchA.countDown();
                }
            }

            @Override
            public void onDataPayloadReceived(NetworkPayload payload) {
                receivedByA.set(payload);
                fileReceivedLatchA.countDown();
            }

            @Override
            public void onWebRTCError(String errorMessage, Throwable cause) {}
        });

        peerB.addListener(new WebRTCDataChannelListener() {
            @Override
            public void onDataChannelStateChange(RTCDataChannelState state) {
                if (state == RTCDataChannelState.OPEN) {
                    channelOpenLatchB.countDown();
                }
            }

            @Override
            public void onDataPayloadReceived(NetworkPayload payload) {
                receivedByB.set(payload);
                messageReceivedLatchB.countDown();
            }

            @Override
            public void onWebRTCError(String errorMessage, Throwable cause) {}
        });

        // 1. Peer A initiates connection as Caller (creates DataChannel and sends SDP Offer)
        peerA.createConnectionAsCaller();

        // 2. Wait for DataChannel to open on both sides
        boolean openedA = channelOpenLatchA.await(5, TimeUnit.SECONDS);
        boolean openedB = channelOpenLatchB.await(5, TimeUnit.SECONDS);

        assertTrue(openedA, "Peer A DataChannel should transition to OPEN");
        assertTrue(openedB, "Peer B DataChannel should transition to OPEN");
        assertTrue(peerA.isDataChannelOpen());
        assertTrue(peerB.isDataChannelOpen());

        // 3. Peer A sends TextMessage over the live WebRTC DataChannel to Peer B
        TextMessage outgoingMsg = new TextMessage("peer_alice", "peer_bob", "Hello Bob! WebRTC P2P works!");
        boolean sentA = peerA.sendTextMessage(outgoingMsg);
        assertTrue(sentA, "Peer A should successfully dispatch message buffer over DataChannel");

        // 4. Peer B receives the TextMessage
        assertTrue(messageReceivedLatchB.await(3, TimeUnit.SECONDS), "Peer B should receive message over DataChannel");
        assertNotNull(receivedByB.get());
        assertInstanceOf(TextMessage.class, receivedByB.get());
        assertEquals("Hello Bob! WebRTC P2P works!", ((TextMessage) receivedByB.get()).getMessageContent());

        // 5. Peer B sends FileTransfer payload back to Peer A over the live DataChannel
        FileTransfer fileTx = new FileTransfer("peer_bob", "peer_alice", "p2p_photo.jpg", 1024000L, "hash123", "image/jpeg");
        boolean sentB = peerB.sendPayload(fileTx);
        assertTrue(sentB, "Peer B should successfully dispatch file payload over DataChannel");

        // 6. Peer A receives the FileTransfer
        assertTrue(fileReceivedLatchA.await(3, TimeUnit.SECONDS), "Peer A should receive file payload over DataChannel");
        assertNotNull(receivedByA.get());
        assertInstanceOf(FileTransfer.class, receivedByA.get());
        assertEquals("p2p_photo.jpg", ((FileTransfer) receivedByA.get()).getFileName());
    }
}
