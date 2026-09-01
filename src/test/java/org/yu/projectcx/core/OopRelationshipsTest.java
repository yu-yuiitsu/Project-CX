package org.yu.projectcx.core;

import org.junit.jupiter.api.Test;
import org.yu.projectcx.model.FileTransfer;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.model.User;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 2 Tests: Object-Oriented Relationships
 * 
 * Verifies:
 * 1. Composition: ChatSession strictly owns and manages MessageManager and WebRTCManager.
 * 2. Aggregation: PeerManager aggregates independently existing Peer instances (Peer A, Peer B, Peer C).
 * 3. Association: User associates with Peer through ChatSession without shared ownership.
 */
class OopRelationshipsTest {

    @Test
    void testAggregationInPeerManager() {
        // Create independent Peer objects (Exist outside PeerManager)
        Peer peerA = new Peer("peer-001", "Alice", "192.168.1.10", 9001);
        Peer peerB = new Peer("peer-002", "Bob", "192.168.1.20", 9002);
        Peer peerC = new Peer("peer-003", "Charlie", "192.168.1.30", 9003);

        // Aggregation: PeerManager aggregates the peers
        PeerManager peerManager = new PeerManager();
        peerManager.addPeer(peerA);
        peerManager.addPeer(peerB);
        peerManager.addPeer(peerC);

        assertEquals(3, peerManager.getPeerCount());
        assertTrue(peerManager.containsPeer("peer-001"));
        assertTrue(peerManager.containsPeer("peer-002"));
        assertTrue(peerManager.containsPeer("peer-003"));

        // Status update
        peerManager.updatePeerStatus("peer-001", true);
        peerManager.updatePeerStatus("peer-002", true);
        List<Peer> onlinePeers = peerManager.getOnlinePeers();
        assertEquals(2, onlinePeers.size());

        // Peer removal
        peerManager.removePeer("peer-003");
        assertEquals(2, peerManager.getPeerCount());

        // Demonstrating Aggregation Lifecycle:
        // Even when PeerManager is cleared, peerA and peerB still exist independently!
        peerManager.clear();
        assertEquals(0, peerManager.getPeerCount());
        assertNotNull(peerA.getAlias()); // peerA is still fully alive and functional
        assertEquals("Alice", peerA.getAlias());
        assertEquals("Bob", peerB.getAlias());
    }

    @Test
    void testCompositionInChatSession() {
        User localUser = new User("usr-me", "my_handle", "Local User");
        Peer remotePeer = new Peer("peer-you", "Remote Peer", "10.0.0.5", 8080);

        // Composition: ChatSession creates its internal MessageManager and WebRTCManager
        ChatSession session = new ChatSession(localUser, remotePeer);
        assertNotNull(session.getMessageManager());
        assertNotNull(session.getWebrtcManager());
        assertEquals(0, session.getMessageManager().getMessageCount());

        session.connect();
        assertEquals(SessionState.ACTIVE, session.getState());

        // Send messages through session (stored in composed MessageManager)
        TextMessage textMsg = session.sendMessage("Hello via P2P!");
        assertNotNull(textMsg);
        assertEquals(1, session.getMessageManager().getMessageCount());
        assertEquals(1, session.getMessageManager().getTextMessages().size());

        FileTransfer fileTx = session.sendFile("chart.png", 1048576L, "sha256_hash", "image/png");
        assertNotNull(fileTx);
        assertEquals(2, session.getMessageManager().getMessageCount());
        assertEquals(1, session.getMessageManager().getFileTransfers().size());

        // Composition Lifecycle:
        // When session is closed, its internal MessageManager is cleared and disposed
        session.closeSession();
        assertEquals(SessionState.CLOSED, session.getState());
        assertEquals(0, session.getMessageManager().getMessageCount());
    }

    @Test
    void testAssociationBetweenUserAndPeer() {
        User localUser = new User("usr-1", "user_one", "User One");
        Peer remotePeer = new Peer("peer-1", "Peer One", "192.168.0.1", 5000);

        // Association: User and Peer are associated inside ChatSession
        ChatSession session = new ChatSession(localUser, remotePeer);
        assertSame(localUser, session.getLocalUser());
        assertSame(remotePeer, session.getRemotePeer());

        // Closing the session leaves both User and Peer objects intact and usable for another session
        session.closeSession();
        assertEquals("user_one", localUser.getUsername());
        assertEquals("Peer One", remotePeer.getAlias());

        // A new session can associate the same User and Peer again
        ChatSession newSession = new ChatSession(localUser, remotePeer);
        assertNotEquals(session.getSessionId(), newSession.getSessionId());
        assertSame(localUser, newSession.getLocalUser());
        assertSame(remotePeer, newSession.getRemotePeer());
    }

    @Test
    void testFullIntegratedChatSystemArchitecture() {
        // 1. Local User (Identity)
        User localUser = new User("usr_main", "yuiitsu", "Yu");

        // 2. Peer Directory (Aggregation)
        PeerManager peerManager = new PeerManager();
        Peer peerAlice = new Peer("peer_alice", "Alice", "192.168.1.55", 7001);
        Peer peerBob = new Peer("peer_bob", "Bob", "192.168.1.66", 7002);
        peerManager.addPeer(peerAlice);
        peerManager.addPeer(peerBob);

        // 3. Initiate ChatSession with Alice (Association + Composition)
        ChatSession sessionAlice = new ChatSession(localUser, peerAlice);
        sessionAlice.connect();
        sessionAlice.sendMessage("Hey Alice! Testing P2P DataChannel.");
        sessionAlice.sendMessage("Sending second packet.");

        // Verify MessageManager inside session (Composition)
        assertEquals(2, sessionAlice.getMessageManager().getMessageCount());
        assertEquals("Hey Alice! Testing P2P DataChannel.", sessionAlice.getMessageManager().getTextMessages().get(0).getMessageContent());

        // 4. Initiate parallel ChatSession with Bob
        ChatSession sessionBob = new ChatSession(localUser, peerBob);
        sessionBob.connect();
        sessionBob.sendFile("firmware.bin", 4194304L, "a1b2c3d4", "application/octet-stream");

        // Each session has its own independent MessageManager (Composition)
        assertEquals(1, sessionBob.getMessageManager().getMessageCount());
        assertEquals(1, sessionBob.getMessageManager().getFileTransfers().size());
        assertEquals("4.00 MB", sessionBob.getMessageManager().getFileTransfers().get(0).getFormattedFileSize());

        // 5. Clean up
        sessionAlice.closeSession();
        sessionBob.closeSession();
    }
}
