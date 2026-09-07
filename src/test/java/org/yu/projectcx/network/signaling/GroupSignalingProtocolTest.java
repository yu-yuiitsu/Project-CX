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
 * Tests for Group Signaling Protocol messages:
 * PEER_NETWORK_QUERY, PEER_NETWORK_RESPONSE, GROUP_JOIN_REQUEST, GROUP_JOIN_ACCEPT, GROUP_INTRODUCE.
 */
class GroupSignalingProtocolTest {

    private SignalingManager nodeA;
    private SignalingManager nodeB;

    private static final int PORT_A = 19201;
    private static final int PORT_B = 19202;

    @BeforeEach
    void setUp() throws IOException {
        nodeA = new SignalingManager(PORT_A);
        nodeB = new SignalingManager(PORT_B);
        nodeA.start();
        nodeB.start();
    }

    @AfterEach
    void tearDown() {
        if (nodeA != null) nodeA.stop();
        if (nodeB != null) nodeB.stop();
    }

    @Test
    void testSignalingMessageSerializationForGroupTypes() {
        // 1. PEER_NETWORK_QUERY
        SignalingMessage queryMsg = new SignalingMessage(
                SignalingType.PEER_NETWORK_QUERY,
                "charlie",
                "bob",
                ""
        );
        String sQuery = queryMsg.serialize();
        SignalingMessage rQuery = SignalingMessage.deserialize(sQuery);
        assertEquals(SignalingType.PEER_NETWORK_QUERY, rQuery.getType());
        assertEquals("charlie", rQuery.getSenderPeerId());
        assertEquals("bob", rQuery.getRecipientPeerId());

        // 2. PEER_NETWORK_RESPONSE
        SignalingMessage respMsg = new SignalingMessage(
                SignalingType.PEER_NETWORK_RESPONSE,
                "bob",
                "charlie",
                "alice:Alice:127.0.0.1:8888,dave:Dave:127.0.0.1:8890"
        );
        String sResp = respMsg.serialize();
        SignalingMessage rResp = SignalingMessage.deserialize(sResp);
        assertEquals(SignalingType.PEER_NETWORK_RESPONSE, rResp.getType());
        assertEquals("alice:Alice:127.0.0.1:8888,dave:Dave:127.0.0.1:8890", rResp.getSdp());

        // 3. GROUP_JOIN_REQUEST
        SignalingMessage joinReq = new SignalingMessage(
                SignalingType.GROUP_JOIN_REQUEST,
                "charlie",
                "bob",
                "Charlie"
        );
        String sJoin = joinReq.serialize();
        SignalingMessage rJoin = SignalingMessage.deserialize(sJoin);
        assertEquals(SignalingType.GROUP_JOIN_REQUEST, rJoin.getType());
        assertEquals("Charlie", rJoin.getSdp());

        // 4. GROUP_JOIN_ACCEPT
        SignalingMessage joinAcc = new SignalingMessage(
                SignalingType.GROUP_JOIN_ACCEPT,
                "bob",
                "charlie",
                "ACCEPTED"
        );
        String sAcc = joinAcc.serialize();
        SignalingMessage rAcc = SignalingMessage.deserialize(sAcc);
        assertEquals(SignalingType.GROUP_JOIN_ACCEPT, rAcc.getType());

        // 5. GROUP_INTRODUCE
        SignalingMessage introMsg = new SignalingMessage(
                SignalingType.GROUP_INTRODUCE,
                "bob",
                "alice",
                "charlie:Charlie:127.0.0.1:8891"
        );
        String sIntro = introMsg.serialize();
        SignalingMessage rIntro = SignalingMessage.deserialize(sIntro);
        assertEquals(SignalingType.GROUP_INTRODUCE, rIntro.getType());
        assertEquals("charlie:Charlie:127.0.0.1:8891", rIntro.getSdp());
    }

    @Test
    void testDirectNetworkQueryAndResponseExchange() throws Exception {
        CountDownLatch queryLatch = new CountDownLatch(1);
        CountDownLatch respLatch = new CountDownLatch(1);
        AtomicReference<SignalingMessage> receivedQuery = new AtomicReference<>();
        AtomicReference<SignalingMessage> receivedResp = new AtomicReference<>();

        nodeB.addListener(new SignalingEventListener() {
            @Override
            public void onPeerNetworkQueryReceived(SignalingMessage msg) {
                receivedQuery.set(msg);
                queryLatch.countDown();
                // Send back network response
                nodeB.sendPeerNetworkResponse("127.0.0.1", PORT_A, "bob", msg.getSenderPeerId(), "alice:Alice:127.0.0.1:8888");
            }
        });

        nodeA.addListener(new SignalingEventListener() {
            @Override
            public void onPeerNetworkResponseReceived(SignalingMessage msg) {
                receivedResp.set(msg);
                respLatch.countDown();
            }
        });

        nodeA.sendPeerNetworkQuery("127.0.0.1", PORT_B, "charlie", "bob");

        assertTrue(queryLatch.await(3, TimeUnit.SECONDS), "Node B should receive PEER_NETWORK_QUERY");
        assertEquals("charlie", receivedQuery.get().getSenderPeerId());

        assertTrue(respLatch.await(3, TimeUnit.SECONDS), "Node A should receive PEER_NETWORK_RESPONSE");
        assertEquals("alice:Alice:127.0.0.1:8888", receivedResp.get().getSdp());
    }

    @Test
    void testGroupJoinAndIntroduceExchange() throws Exception {
        CountDownLatch joinLatch = new CountDownLatch(1);
        CountDownLatch introLatch = new CountDownLatch(1);
        AtomicReference<SignalingMessage> receivedJoin = new AtomicReference<>();
        AtomicReference<SignalingMessage> receivedIntro = new AtomicReference<>();

        nodeB.addListener(new SignalingEventListener() {
            @Override
            public void onGroupJoinRequestReceived(SignalingMessage msg) {
                receivedJoin.set(msg);
                joinLatch.countDown();
                // Introduce to node A
                nodeB.sendGroupIntroduce("127.0.0.1", PORT_A, "bob", "alice", "charlie:Charlie:127.0.0.1:19203");
            }
        });

        nodeA.addListener(new SignalingEventListener() {
            @Override
            public void onGroupIntroduceReceived(SignalingMessage msg) {
                receivedIntro.set(msg);
                introLatch.countDown();
            }
        });

        nodeA.sendGroupJoinRequest("127.0.0.1", PORT_B, "charlie", "bob", "Charlie");

        assertTrue(joinLatch.await(3, TimeUnit.SECONDS), "Node B should receive GROUP_JOIN_REQUEST");
        assertEquals("Charlie", receivedJoin.get().getSdp());

        assertTrue(introLatch.await(3, TimeUnit.SECONDS), "Node A should receive GROUP_INTRODUCE");
        assertEquals("charlie:Charlie:127.0.0.1:19203", receivedIntro.get().getSdp());
    }
}
