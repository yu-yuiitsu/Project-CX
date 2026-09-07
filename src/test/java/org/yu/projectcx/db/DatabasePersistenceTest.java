package org.yu.projectcx.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yu.projectcx.model.FileTransfer;
import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.model.TransferStatus;
import org.yu.projectcx.model.User;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 5 Tests: SQLite Database Persistence & Repository Layer.
 */
class DatabasePersistenceTest {

    private static final String TEST_DB_FILE = "target/chat_stage5_test.db";
    private DatabaseManager dbManager;
    private UserRepository userRepo;
    private PeerRepository peerRepo;
    private MessageRepository messageRepo;

    @BeforeEach
    void setUp() {
        new File(TEST_DB_FILE).delete();
        dbManager = new DatabaseManager(TEST_DB_FILE);
        userRepo = new UserRepository(dbManager);
        peerRepo = new PeerRepository(dbManager);
        messageRepo = new MessageRepository(dbManager);
    }

    @AfterEach
    void tearDown() {
        if (dbManager != null) {
            dbManager.close();
        }
        new File(TEST_DB_FILE).delete();
    }

    @Test
    void testUserRegistrationAndLogin() throws SQLException {
        User user = new User("usr_001", "alice", "Alice Wonder");
        user.setStatusMessage("Exploring P2P WebRTC");

        // 1. Save user with password
        userRepo.saveUser(user, "secretPass123");

        // 2. Successful Login
        Optional<User> loggedInUser = userRepo.login("alice", "secretPass123");
        assertTrue(loggedInUser.isPresent());
        assertEquals("usr_001", loggedInUser.get().getUserId());
        assertEquals("Alice Wonder", loggedInUser.get().getDisplayName());
        assertEquals("Exploring P2P WebRTC", loggedInUser.get().getStatusMessage());

        // 3. Failed Login (Wrong Password)
        Optional<User> failedAuth = userRepo.login("alice", "wrongPassword");
        assertFalse(failedAuth.isPresent());

        // 4. Failed Login (Non-existent user)
        Optional<User> nonExistent = userRepo.login("unknown_user", "password");
        assertFalse(nonExistent.isPresent());

        // 5. Update Status
        assertTrue(userRepo.updateStatusMessage("usr_001", "Online and coding"));
        Optional<User> updatedUser = userRepo.findById("usr_001");
        assertTrue(updatedUser.isPresent());
        assertEquals("Online and coding", updatedUser.get().getStatusMessage());
    }

    @Test
    void testPeerPersistence() throws SQLException {
        Peer peer = new Peer("peer_bob", "Bob", "192.168.1.100", 9001);
        peer.setOnline(false);

        // 1. Save Peer
        peerRepo.savePeer(peer);

        // 2. Retrieve Peer
        Optional<Peer> fetched = peerRepo.findById("peer_bob");
        assertTrue(fetched.isPresent());
        assertEquals("Bob", fetched.get().getAlias());
        assertEquals("192.168.1.100", fetched.get().getIpAddress());
        assertEquals(9001, fetched.get().getPort());
        assertFalse(fetched.get().isOnline());

        // 3. Update Online Status
        assertTrue(peerRepo.updateOnlineStatus("peer_bob", true));
        Optional<Peer> onlinePeer = peerRepo.findById("peer_bob");
        assertTrue(onlinePeer.isPresent());
        assertTrue(onlinePeer.get().isOnline());

        // 4. List All Peers
        Peer peer2 = new Peer("peer_charlie", "Charlie", "192.168.1.101", 9002);
        peerRepo.savePeer(peer2);
        List<Peer> allPeers = peerRepo.getAllPeers();
        assertEquals(2, allPeers.size());
    }

    @Test
    void testMessagePersistenceAndPolymorphicRetrieval() throws SQLException {
        String aliceId = "usr_alice";
        String bobId = "peer_bob";

        // 1. Save TextMessage
        TextMessage textMsg1 = new TextMessage(aliceId, bobId, "Hello Bob! This is message 1.");
        messageRepo.saveMessage(textMsg1);

        TextMessage textMsg2 = new TextMessage(bobId, aliceId, "Hey Alice! Received your message.");
        messageRepo.saveMessage(textMsg2);

        // 2. Save FileTransfer polymorphically
        FileTransfer fileTx = new FileTransfer(aliceId, bobId, "presentation.pdf", 4194304L, "sha256_hash", "application/pdf");
        fileTx.setTransferProgress(100.0);
        fileTx.setStatus(TransferStatus.COMPLETED);
        messageRepo.saveMessage(fileTx);

        // 3. Retrieve Conversation History (3 items)
        List<NetworkPayload> conversation = messageRepo.getConversation(aliceId, bobId);
        assertEquals(3, conversation.size());

        // Polymorphic Type Verification
        assertInstanceOf(TextMessage.class, conversation.get(0));
        assertEquals("Hello Bob! This is message 1.", ((TextMessage) conversation.get(0)).getMessageContent());

        assertInstanceOf(TextMessage.class, conversation.get(1));
        assertEquals("Hey Alice! Received your message.", ((TextMessage) conversation.get(1)).getMessageContent());

        assertInstanceOf(FileTransfer.class, conversation.get(2));
        FileTransfer recoveredFile = (FileTransfer) conversation.get(2);
        assertEquals("presentation.pdf", recoveredFile.getFileName());
        assertEquals(4194304L, recoveredFile.getFileSize());
        assertEquals("4.00 MB", recoveredFile.getFormattedFileSize());
        assertEquals(TransferStatus.COMPLETED, recoveredFile.getStatus());

        // 4. Mark as Delivered and Read
        assertTrue(messageRepo.markAsDelivered(textMsg1.getPayloadId()));
        assertTrue(messageRepo.markAsRead(textMsg1.getPayloadId()));

        Optional<NetworkPayload> updatedMsg = messageRepo.findById(textMsg1.getPayloadId());
        assertTrue(updatedMsg.isPresent());
        assertTrue(((TextMessage) updatedMsg.get()).isDelivered());
        assertTrue(((TextMessage) updatedMsg.get()).isRead());
    }

    @Test
    void testDataSurvivesApplicationRestart() throws SQLException {
        String aliceId = "usr_survivor";
        String bobId = "peer_survivor";

        // Session 1: Write data to database
        userRepo.saveUser(new User(aliceId, "survivor_user", "Survivor"), "pass123");
        peerRepo.savePeer(new Peer(bobId, "Survivor Peer", "10.0.0.1", 5000));
        messageRepo.saveMessage(new TextMessage(aliceId, bobId, "I will survive the restart!"));

        // Simulate app shutdown: close first database instance
        dbManager.close();

        // Session 2: Launch fresh database instance pointing to same file
        DatabaseManager restartDbManager = new DatabaseManager(TEST_DB_FILE);
        UserRepository restartUserRepo = new UserRepository(restartDbManager);
        PeerRepository restartPeerRepo = new PeerRepository(restartDbManager);
        MessageRepository restartMsgRepo = new MessageRepository(restartDbManager);

        // Assert all data is preserved!
        Optional<User> recoveredUser = restartUserRepo.login("survivor_user", "pass123");
        assertTrue(recoveredUser.isPresent(), "User must survive app restart");
        assertEquals("Survivor", recoveredUser.get().getDisplayName());

        Optional<Peer> recoveredPeer = restartPeerRepo.findById(bobId);
        assertTrue(recoveredPeer.isPresent(), "Peer must survive app restart");
        assertEquals("Survivor Peer", recoveredPeer.get().getAlias());

        List<NetworkPayload> recoveredHistory = restartMsgRepo.getConversation(aliceId, bobId);
        assertEquals(1, recoveredHistory.size(), "Chat history must survive app restart");
        assertEquals("I will survive the restart!", ((TextMessage) recoveredHistory.get(0)).getMessageContent());

        restartDbManager.close();
    }

    @Test
    void testDeleteUserWithPassword() throws SQLException {
        User alice = new User("usr_del_alice", "del_alice", "Alice Deletable");
        userRepo.saveUser(alice, "correctPassword123");

        // Save some messages for Alice
        messageRepo.saveMessage(new TextMessage("usr_del_alice", "peer_test", "Hello before delete"));

        // 1. Verify scan all users finds alice
        List<User> users = userRepo.getAllUsers();
        assertTrue(users.stream().anyMatch(u -> u.getUsername().equals("del_alice")));

        // 2. Attempt delete with WRONG password -> rejected
        boolean wrongPassResult = userRepo.deleteUserWithPassword("del_alice", "wrongPassword");
        assertFalse(wrongPassResult, "Deletion must fail with wrong password");
        assertTrue(userRepo.findById("usr_del_alice").isPresent());

        // 3. Attempt delete with CORRECT password -> succeeds
        boolean correctPassResult = userRepo.deleteUserWithPassword("del_alice", "correctPassword123");
        assertTrue(correctPassResult, "Deletion must succeed with correct password");
        assertFalse(userRepo.findById("usr_del_alice").isPresent(), "User should no longer exist");

        // 4. Verify messages associated with Alice are also purged
        List<NetworkPayload> convo = messageRepo.getConversation("usr_del_alice", "peer_test");
        assertTrue(convo.isEmpty(), "Messages for deleted user must be purged");
    }
}
