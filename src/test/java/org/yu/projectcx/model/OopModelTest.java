package org.yu.projectcx.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying Stage 1 OOP concepts:
 * - Classes & objects
 * - Private fields & Encapsulation
 * - Getters and Setters with validations
 * - Basic constructors and Constructor Overloading
 * - Flow: User -> Peer -> TextMessage / FileTransfer
 */
class OopModelTest {

    @Test
    void testUserEncapsulationAndConstructorOverloading() {
        // Default Constructor
        User defaultUser = new User();
        assertNotNull(defaultUser.getUserId());
        assertNotNull(defaultUser.getUsername());
        assertEquals("Available", defaultUser.getStatusMessage());

        // Overloaded Constructor 1: Username
        User alice = new User("alice_99");
        assertEquals("alice_99", alice.getUsername());
        assertEquals("alice_99", alice.getDisplayName());

        // Overloaded Constructor 2: UserID, Username, DisplayName
        User bob = new User("usr-123", "bob_dev", "Bob The Builder");
        assertEquals("usr-123", bob.getUserId());
        assertEquals("bob_dev", bob.getUsername());
        assertEquals("Bob The Builder", bob.getDisplayName());

        // Encapsulation validation
        assertThrows(IllegalArgumentException.class, () -> alice.setUsername(""));
        assertThrows(IllegalArgumentException.class, () -> alice.setUserId(null));

        // Mutators / Getters
        alice.setDisplayName("Alice Wonderland");
        alice.setStatusMessage("Coding in Java 21");
        assertEquals("Alice Wonderland", alice.getDisplayName());
        assertEquals("Coding in Java 21", alice.getStatusMessage());
    }

    @Test
    void testPeerEncapsulationAndConstructorOverloading() {
        // Default Constructor
        Peer defaultPeer = new Peer();
        assertNotNull(defaultPeer.getPeerId());
        assertEquals("127.0.0.1:8080", defaultPeer.getEndpoint());
        assertFalse(defaultPeer.isOnline());

        // Overloaded Constructor 1: ID & Alias
        Peer peer1 = new Peer("peer-abc", "Charlie");
        assertEquals("peer-abc", peer1.getPeerId());
        assertEquals("Charlie", peer1.getAlias());

        // Overloaded Constructor 2: Endpoint setup
        Peer peer2 = new Peer("peer-xyz", "David", "192.168.1.100", 9050);
        assertEquals("192.168.1.100:9050", peer2.getEndpoint());

        // Encapsulation & Validation
        assertThrows(IllegalArgumentException.class, () -> peer2.setPort(99999)); // Invalid port
        assertThrows(IllegalArgumentException.class, () -> peer2.setPort(0));     // Invalid port
        assertThrows(IllegalArgumentException.class, () -> peer2.setPeerId(""));

        peer2.setOnline(true);
        assertTrue(peer2.isOnline());
    }

    @Test
    void testTextMessageModelAndInheritance() {
        User alice = new User("alice_id", "alice", "Alice");
        Peer bob = new Peer("bob_peer_id", "Bob", "192.168.1.5", 7000);

        // Constructor Overloading: Basic Text Message
        TextMessage msg = new TextMessage(alice.getUserId(), bob.getPeerId(), "Hello Bob, are we connected via WebRTC?");
        assertNotNull(msg.getPayloadId());
        assertEquals(PayloadType.TEXT, msg.getType());
        assertEquals(alice.getUserId(), msg.getSenderId());
        assertEquals(bob.getPeerId(), msg.getRecipientId());
        assertEquals("Hello Bob, are we connected via WebRTC?", msg.getMessageContent());
        assertFalse(msg.isDelivered());
        assertFalse(msg.isRead());

        // Encapsulation & Mutators
        msg.setDelivered(true);
        msg.setRead(true);
        assertTrue(msg.isDelivered());
        assertTrue(msg.isRead());
    }

    @Test
    void testFileTransferModel() {
        User alice = new User("alice");
        Peer bob = new Peer("bob_id", "Bob");

        // Overloaded Constructor with Checksum & MIME type
        FileTransfer fileTx = new FileTransfer(
                alice.getUserId(),
                bob.getPeerId(),
                "project_spec.pdf",
                2048576L, // ~1.95 MB
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "application/pdf"
        );

        assertEquals(PayloadType.FILE_TRANSFER, fileTx.getType());
        assertEquals("project_spec.pdf", fileTx.getFileName());
        assertEquals(2048576L, fileTx.getFileSize());
        assertEquals("1.95 MB", fileTx.getFormattedFileSize());
        assertEquals(TransferStatus.PENDING, fileTx.getStatus());
        assertEquals(0.0, fileTx.getTransferProgress());

        // Progress update and validation
        fileTx.setTransferProgress(55.5);
        fileTx.setStatus(TransferStatus.IN_PROGRESS);
        assertEquals(55.5, fileTx.getTransferProgress());
        assertEquals(TransferStatus.IN_PROGRESS, fileTx.getStatus());

        assertThrows(IllegalArgumentException.class, () -> fileTx.setTransferProgress(105.0));
        assertThrows(IllegalArgumentException.class, () -> fileTx.setFileSize(-10));
    }

    @Test
    void testUserToPeerToMessageFlow() {
        // 1. Create local User
        User localUser = new User("usr_alpha", "yuiitsu", "Yu");
        localUser.setStatusMessage("Ready to connect");

        // 2. Discover / Create remote Peer
        Peer remotePeer = new Peer("peer_beta", "Alex", "10.0.0.12", 8443);
        remotePeer.setOnline(true);

        // 3. User dispatches TextMessage to Peer
        TextMessage chatMsg = new TextMessage(localUser.getUserId(), remotePeer.getPeerId(), "Welcome to Project-CX P2P Chat!");

        // 4. User initiates FileTransfer to Peer
        FileTransfer fileTx = new FileTransfer(localUser.getUserId(), remotePeer.getPeerId(), "sample.png", 512000L);

        // Assert relationship & values
        assertEquals(localUser.getUserId(), chatMsg.getSenderId());
        assertEquals(remotePeer.getPeerId(), chatMsg.getRecipientId());
        assertEquals(localUser.getUserId(), fileTx.getSenderId());
        assertEquals(remotePeer.getPeerId(), fileTx.getRecipientId());
        assertEquals("500.00 KB", fileTx.getFormattedFileSize());
    }
}
