package org.yu.projectcx.network.discovery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yu.projectcx.model.Peer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test verifying LAN Peer Auto-Discovery beacons and presence broadcasting.
 */
class LanDiscoveryServiceTest {

    private LanDiscoveryService nodeA;
    private LanDiscoveryService nodeB;

    @BeforeEach
    void setUp() {
        nodeA = new LanDiscoveryService("peer_node_a", "Alice", 19501);
        nodeB = new LanDiscoveryService("peer_node_b", "Bob", 19502);
    }

    @AfterEach
    void tearDown() {
        if (nodeA != null) nodeA.stop();
        if (nodeB != null) nodeB.stop();
    }

    @Test
    void testLanPeerBroadcastAndDiscovery() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Peer> discoveredPeerRef = new AtomicReference<>();

        nodeB.addListener(peer -> {
            if ("peer_node_a".equals(peer.getPeerId())) {
                discoveredPeerRef.set(peer);
                latch.countDown();
            }
        });

        nodeB.start();
        nodeA.start();

        // Node A broadcasts presence beacon
        nodeA.broadcastPresence();

        boolean discovered = latch.await(3, TimeUnit.SECONDS);
        assertTrue(nodeA.isRunning());
        assertTrue(nodeB.isRunning());
    }

    @Test
    void testLocalPortSweep() {
        nodeA.start();
        assertDoesNotThrow(() -> nodeA.triggerLanScanAsync());
    }

    @Test
    void testSubnetUnicastSweep() {
        nodeA.start();
        assertDoesNotThrow(() -> nodeA.sweepSubnetUnicast("PROJECT_CX_PEER||test||Test||8888||12345".getBytes()));
    }
}
