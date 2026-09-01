package org.yu.projectcx.core;

import org.junit.jupiter.api.Test;
import org.yu.projectcx.model.FileTransfer;
import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.PayloadType;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.model.User;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 4 Tests: Method Overloading and Constructor Overloading.
 */
class MethodOverloadingTest {

    @Test
    void testChatServiceSendMessageOverloads() {
        User user = new User("usr_main", "yuiitsu", "Yu");
        PeerManager peerManager = new PeerManager();
        Peer peer1 = new Peer("peer-1", "Alice", "192.168.1.10", 8001);
        Peer peer2 = new Peer("peer-2", "Bob", "192.168.1.20", 8002);
        peerManager.addPeer(peer1);
        peerManager.addPeer(peer2);

        // Constructor Overloading: ChatService(User, PeerManager)
        ChatService service = new ChatService(user, peerManager);

        // Overload 1: sendMessage(String text, String peerId)
        TextMessage msg1 = service.sendMessage("Hello Alice by ID!", "peer-1");
        assertNotNull(msg1);
        assertEquals("Hello Alice by ID!", msg1.getMessageContent());
        assertEquals("peer-1", service.getActivePeerId());

        // Overload 2: sendMessage(String text) -> sends to active peer (peer-1)
        TextMessage msg2 = service.sendMessage("Second message to active peer!");
        assertEquals("Second message to active peer!", msg2.getMessageContent());
        assertEquals(2, service.getSession("peer-1").get().getMessageManager().getMessageCount());

        // Overload 3: sendMessage(String text, Peer peer)
        Peer peer3 = new Peer("peer-3", "Charlie", "192.168.1.30", 8003);
        TextMessage msg3 = service.sendMessage("Hello Charlie by Object!", peer3);
        assertEquals("Hello Charlie by Object!", msg3.getMessageContent());
        assertTrue(service.getPeerManager().containsPeer("peer-3"));
        assertEquals("peer-3", service.getActivePeerId());

        // Overload 4: sendMessage(NetworkPayload payload, String peerId)
        FileTransfer fileTx1 = new FileTransfer(user.getUserId(), "peer-2", "notes.pdf", 512000L);
        NetworkPayload dispatchedFile = service.sendMessage(fileTx1, "peer-2");
        assertSame(fileTx1, dispatchedFile);
        assertEquals(1, service.getSession("peer-2").get().getMessageManager().getFileTransfers().size());

        // Overload 5: sendMessage(NetworkPayload payload) -> sends to active peer (peer-2)
        TextMessage textPayload = new TextMessage(user.getUserId(), "peer-2", "Another payload");
        service.sendMessage(textPayload);
        assertEquals(2, service.getSession("peer-2").get().getMessageManager().getMessageCount());
    }

    @Test
    void testChatSessionOverloads() {
        User user = new User("alice");
        Peer peer = new Peer("bob_id", "Bob");
        ChatSession session = new ChatSession(user, peer);
        session.connect();

        // Overload: sendMessage(String text)
        TextMessage tm1 = session.sendMessage("Test basic text");
        assertTrue(tm1.isDelivered());

        // Overload: sendMessage(String text, boolean requireReceipt)
        TextMessage tm2 = session.sendMessage("Test unconfirmed text", false);
        assertFalse(tm2.isDelivered());

        // Overload: sendMessage(NetworkPayload payload)
        FileTransfer ftPayload = new FileTransfer(user.getUserId(), peer.getPeerId(), "image.png", 204800L);
        session.sendMessage(ftPayload);

        // Overload: sendFile(String name, long size)
        FileTransfer ft1 = session.sendFile("doc.pdf", 1024L);
        assertEquals("doc.pdf", ft1.getFileName());

        // Overload: sendFile(String name, long size, String checksum, String mime)
        FileTransfer ft2 = session.sendFile("bundle.zip", 4096L, "sha256_hash", "application/zip");
        assertEquals("bundle.zip", ft2.getFileName());
        assertEquals("sha256_hash", ft2.getFileChecksum());

        assertEquals(5, session.getMessageManager().getMessageCount());
    }

    @Test
    void testPeerManagerOverloads() {
        PeerManager manager = new PeerManager();

        // Overload 1: addPeer(Peer peer)
        Peer p1 = new Peer("p-1", "Dave");
        manager.addPeer(p1);

        // Overload 2: addPeer(String id, String alias)
        Peer p2 = manager.addPeer("p-2", "Eve");
        assertEquals("p-2", p2.getPeerId());
        assertEquals("Eve", p2.getAlias());

        // Overload 3: addPeer(String id, String alias, String ip, int port)
        Peer p3 = manager.addPeer("p-3", "Frank", "10.0.0.99", 9999);
        assertEquals("10.0.0.99:9999", p3.getEndpoint());

        assertEquals(3, manager.getPeerCount());

        // Overload 1: getPeer(String peerId)
        assertTrue(manager.getPeer("p-3").isPresent());

        // Overload 2: getPeer(String ip, int port)
        assertTrue(manager.getPeer("10.0.0.99", 9999).isPresent());
        assertEquals("Frank", manager.getPeer("10.0.0.99", 9999).get().getAlias());
    }

    @Test
    void testMessageManagerOverloads() {
        MessageManager mm = new MessageManager();

        // Overload 1: addMessage(NetworkPayload)
        mm.addMessage(new TextMessage("u1", "u2", "Msg 1"));
        mm.addMessage(new FileTransfer("u1", "u2", "f1.txt", 100));

        // Overload 2: addMessage(String sender, String recipient, String text)
        mm.addMessage("u1", "u2", "Msg 2");
        mm.addMessage("u1", "u2", "Msg 3");

        assertEquals(4, mm.getMessageCount());

        // Overload 1: getMessages(int limit)
        List<NetworkPayload> recent2 = mm.getMessages(2);
        assertEquals(2, recent2.size());
        assertEquals("Msg 2", ((TextMessage) recent2.get(0)).getMessageContent());
        assertEquals("Msg 3", ((TextMessage) recent2.get(1)).getMessageContent());

        // Overload 2: getMessages(PayloadType type)
        List<NetworkPayload> allText = mm.getMessages(PayloadType.TEXT);
        assertEquals(3, allText.size());

        List<NetworkPayload> allFiles = mm.getMessages(PayloadType.FILE_TRANSFER);
        assertEquals(1, allFiles.size());

        // Overload 3: getMessages(PayloadType type, int limit)
        List<NetworkPayload> recentText1 = mm.getMessages(PayloadType.TEXT, 1);
        assertEquals(1, recentText1.size());
        assertEquals("Msg 3", ((TextMessage) recentText1.get(0)).getMessageContent());
    }

    @Test
    void testConstructorOverloadingAcrossModels() {
        // User Constructor Overloads (4 overloads)
        User u1 = new User();
        User u2 = new User("custom_user");
        User u3 = new User("u-3", "user_three", "Three");
        User u4 = new User("u-4", "user_four", "Four", "Away", java.time.LocalDateTime.now());

        assertNotNull(u1.getUserId());
        assertEquals("custom_user", u2.getUsername());
        assertEquals("Three", u3.getDisplayName());
        assertEquals("Away", u4.getStatusMessage());

        // Peer Constructor Overloads (4 overloads)
        Peer peer1 = new Peer();
        Peer peer2 = new Peer("p-2", "PeerTwo");
        Peer peer3 = new Peer("p-3", "PeerThree", "127.0.0.1", 3000);
        Peer peer4 = new Peer("p-4", "PeerFour", "127.0.0.1", 4000, true, java.time.LocalDateTime.now());

        assertNotNull(peer1.getPeerId());
        assertEquals("PeerTwo", peer2.getAlias());
        assertEquals("127.0.0.1:3000", peer3.getEndpoint());
        assertTrue(peer4.isOnline());

        // ChatService Constructor Overloads (2 overloads)
        ChatService cs1 = new ChatService(u1);
        ChatService cs2 = new ChatService(u2, new PeerManager());
        assertNotNull(cs1.getPeerManager());
        assertNotNull(cs2.getPeerManager());
    }
}
