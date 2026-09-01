package org.yu.projectcx.network.signaling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 9 Tests: SignalingManager Offer/Answer & ICE Candidate Exchange
 * Covers both Direct P2P Signaling and Hosted Signaling Server Routing.
 */
class SignalingManagerTest {

    private SignalingManager peerASignaling;
    private SignalingManager peerBSignaling;
    private HostedSignalingServer hostedServer;

    private static final int PORT_A = 19101;
    private static final int PORT_B = 19102;
    private static final int HOSTED_PORT = 19103;

    @BeforeEach
    void setUp() throws IOException {
        peerASignaling = new SignalingManager(PORT_A);
        peerBSignaling = new SignalingManager(PORT_B);
        hostedServer = new HostedSignalingServer(HOSTED_PORT);

        peerASignaling.start();
        peerBSignaling.start();
        hostedServer.start();
    }

    @AfterEach
    void tearDown() {
        if (peerASignaling != null) peerASignaling.stop();
        if (peerBSignaling != null) peerBSignaling.stop();
        if (hostedServer != null) hostedServer.stop();
    }

    @Test
    void testSerializationAndDeserialization() {
        SignalingMessage original = new SignalingMessage(
                SignalingType.OFFER,
                "peer_alice",
                "peer_bob",
                "v=0\r\no=- 12345 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=sendrecv"
        );

        String serialized = original.serialize();
        assertNotNull(serialized);

        SignalingMessage restored = SignalingMessage.deserialize(serialized);
        assertEquals(original.getMessageId(), restored.getMessageId());
        assertEquals(SignalingType.OFFER, restored.getType());
        assertEquals("peer_alice", restored.getSenderPeerId());
        assertEquals("peer_bob", restored.getRecipientPeerId());
        assertEquals(original.getSdp(), restored.getSdp());
    }

    @Test
    void testIceCandidateSerialization() {
        SignalingMessage original = new SignalingMessage(
                "peer_alice",
                "peer_bob",
                "data",
                0,
                "candidate:842163049 1 udp 1677729535 192.168.1.100 56788 typ srflx raddr 192.168.1.100 rport 56788"
        );

        String serialized = original.serialize();
        SignalingMessage restored = SignalingMessage.deserialize(serialized);

        assertEquals(SignalingType.ICE_CANDIDATE, restored.getType());
        assertEquals("peer_alice", restored.getSenderPeerId());
        assertEquals("data", restored.getSdpMid());
        assertEquals(0, restored.getSdpMLineIndex());
        assertEquals(original.getCandidateSdp(), restored.getCandidateSdp());
    }

    @Test
    void testDirectP2PSignalingNegotiation() throws Exception {
        String mockOfferSdp = "v=0\r\no=PeerA 1000 1 IN IP4 127.0.0.1\r\ns=WebRTC-Session\r\nm=application 9 DTLS/SCTP 5000";
        String mockAnswerSdp = "v=0\r\no=PeerB 2000 1 IN IP4 127.0.0.1\r\ns=WebRTC-Session\r\nm=application 9 DTLS/SCTP 5000";
        String mockIceCandidate = "candidate:1 1 UDP 2130706431 127.0.0.1 50000 typ host";

        CountDownLatch offerLatch = new CountDownLatch(1);
        CountDownLatch answerLatch = new CountDownLatch(1);
        CountDownLatch iceLatch = new CountDownLatch(1);

        AtomicReference<SignalingMessage> receivedOffer = new AtomicReference<>();
        AtomicReference<SignalingMessage> receivedAnswer = new AtomicReference<>();
        AtomicReference<SignalingMessage> receivedIce = new AtomicReference<>();

        peerBSignaling.addListener(new SignalingEventListener() {
            @Override
            public void onOfferReceived(SignalingMessage message) {
                receivedOffer.set(message);
                offerLatch.countDown();
            }

            @Override
            public void onAnswerReceived(SignalingMessage message) {}

            @Override
            public void onIceCandidateReceived(SignalingMessage message) {
                receivedIce.set(message);
                iceLatch.countDown();
            }

            @Override
            public void onPeerConnected(String remotePeerId, String remoteAddress) {}

            @Override
            public void onPeerDisconnected(String remotePeerId) {}

            @Override
            public void onSignalingError(String errorMessage, Throwable cause) {}
        });

        peerASignaling.addListener(new SignalingEventListener() {
            @Override
            public void onOfferReceived(SignalingMessage message) {}

            @Override
            public void onAnswerReceived(SignalingMessage message) {
                receivedAnswer.set(message);
                answerLatch.countDown();
            }

            @Override
            public void onIceCandidateReceived(SignalingMessage message) {}

            @Override
            public void onPeerConnected(String remotePeerId, String remoteAddress) {}

            @Override
            public void onPeerDisconnected(String remotePeerId) {}

            @Override
            public void onSignalingError(String errorMessage, Throwable cause) {}
        });

        // 1. Peer A sends OFFER to Peer B
        peerASignaling.sendOffer("127.0.0.1", PORT_B, "peer_a", "peer_b", mockOfferSdp).get(3, TimeUnit.SECONDS);
        assertTrue(offerLatch.await(3, TimeUnit.SECONDS));
        assertEquals(mockOfferSdp, receivedOffer.get().getSdp());

        // 2. Peer B sends ANSWER to Peer A
        peerBSignaling.sendAnswer("127.0.0.1", PORT_A, "peer_b", "peer_a", mockAnswerSdp).get(3, TimeUnit.SECONDS);
        assertTrue(answerLatch.await(3, TimeUnit.SECONDS));
        assertEquals(mockAnswerSdp, receivedAnswer.get().getSdp());

        // 3. Peer A sends ICE candidate to Peer B
        peerASignaling.sendIceCandidate("127.0.0.1", PORT_B, "peer_a", "peer_b", "0", 0, mockIceCandidate).get(3, TimeUnit.SECONDS);
        assertTrue(iceLatch.await(3, TimeUnit.SECONDS));
        assertEquals(mockIceCandidate, receivedIce.get().getCandidateSdp());
    }

    @Test
    void testHostedSignalingServerRoutingBetweenTwoPeers() throws Exception {
        SignalingClient clientA = new SignalingClient();
        SignalingClient clientB = new SignalingClient();

        CountDownLatch bReceivedOfferLatch = new CountDownLatch(1);
        CountDownLatch aReceivedAnswerLatch = new CountDownLatch(1);
        AtomicReference<String> offerFromA = new AtomicReference<>();
        AtomicReference<String> answerFromB = new AtomicReference<>();

        clientB.addListener(new SignalingEventListener() {
            @Override
            public void onOfferReceived(SignalingMessage message) {
                offerFromA.set(message.getSdp());
                bReceivedOfferLatch.countDown();
            }

            @Override
            public void onAnswerReceived(SignalingMessage message) {}

            @Override
            public void onIceCandidateReceived(SignalingMessage message) {}

            @Override
            public void onPeerConnected(String remotePeerId, String remoteAddress) {}

            @Override
            public void onPeerDisconnected(String remotePeerId) {}

            @Override
            public void onSignalingError(String errorMessage, Throwable cause) {}
        });

        clientA.addListener(new SignalingEventListener() {
            @Override
            public void onOfferReceived(SignalingMessage message) {}

            @Override
            public void onAnswerReceived(SignalingMessage message) {
                answerFromB.set(message.getSdp());
                aReceivedAnswerLatch.countDown();
            }

            @Override
            public void onIceCandidateReceived(SignalingMessage message) {}

            @Override
            public void onPeerConnected(String remotePeerId, String remoteAddress) {}

            @Override
            public void onPeerDisconnected(String remotePeerId) {}

            @Override
            public void onSignalingError(String errorMessage, Throwable cause) {}
        });

        // 1. Both connect and register on Hosted Signaling Server
        clientA.connectToHostedServer("127.0.0.1", HOSTED_PORT, "peer_alice");
        clientB.connectToHostedServer("127.0.0.1", HOSTED_PORT, "peer_bob");

        Thread.sleep(100);
        assertEquals(2, hostedServer.getRegisteredPeerCount());

        // 2. Peer Alice sends SDP Offer to Peer Bob via hosted server
        SignalingMessage offer = new SignalingMessage(SignalingType.OFFER, "peer_alice", "peer_bob", "sdp_alice_offer_content");
        clientA.sendPersistentMessage(offer);

        assertTrue(bReceivedOfferLatch.await(3, TimeUnit.SECONDS), "Peer Bob should receive offer routed through hosted server");
        assertEquals("sdp_alice_offer_content", offerFromA.get());

        // 3. Peer Bob sends SDP Answer to Peer Alice via hosted server
        SignalingMessage answer = new SignalingMessage(SignalingType.ANSWER, "peer_bob", "peer_alice", "sdp_bob_answer_content");
        clientB.sendPersistentMessage(answer);

        assertTrue(aReceivedAnswerLatch.await(3, TimeUnit.SECONDS), "Peer Alice should receive answer routed through hosted server");
        assertEquals("sdp_bob_answer_content", answerFromB.get());

        clientA.disconnect();
        clientB.disconnect();
    }
}
