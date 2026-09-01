package org.yu.projectcx.model;

import org.junit.jupiter.api.Test;
import org.yu.projectcx.core.PayloadDispatcher;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 3 Tests: Inheritance, Abstraction, Method Overriding, and Polymorphism.
 */
class InheritancePolymorphismTest {

    @Test
    void testAbstractionOnNetworkPayload() {
        // Verify NetworkPayload is an abstract class
        assertTrue(Modifier.isAbstract(NetworkPayload.class.getModifiers()), 
                "NetworkPayload must be an abstract class.");

        // Verify abstract methods exist on NetworkPayload
        assertDoesNotThrow(() -> NetworkPayload.class.getDeclaredMethod("serialize"));
        assertDoesNotThrow(() -> NetworkPayload.class.getDeclaredMethod("getSummary"));
        assertDoesNotThrow(() -> NetworkPayload.class.getDeclaredMethod("getEstimatedSize"));
        assertDoesNotThrow(() -> NetworkPayload.class.getDeclaredMethod("validate"));
    }

    @Test
    void testInheritanceAndMethodOverriding() {
        TextMessage textMsg = new TextMessage("alice", "bob", "Hello world");
        FileTransfer fileTx = new FileTransfer("alice", "bob", "photo.jpg", 102400L, "hash123", "image/jpeg");

        // Inherited methods from NetworkPayload
        assertEquals("alice", textMsg.getSenderId());
        assertEquals("bob", textMsg.getRecipientId());
        assertNotNull(textMsg.getTimestamp());
        assertTrue(textMsg.getHeaderInfo().contains("TEXT"));

        assertEquals("alice", fileTx.getSenderId());
        assertEquals("bob", fileTx.getRecipientId());
        assertNotNull(fileTx.getTimestamp());
        assertTrue(fileTx.getHeaderInfo().contains("FILE_TRANSFER"));

        // Overridden serialize() produce specialized formats
        String textSerialized = textMsg.serialize();
        String fileSerialized = fileTx.serialize();

        assertTrue(textSerialized.startsWith("TYPE:TEXT|"));
        assertTrue(textSerialized.contains("Hello world"));

        assertTrue(fileSerialized.startsWith("TYPE:FILE|"));
        assertTrue(fileSerialized.contains("photo.jpg"));
        assertTrue(fileSerialized.contains("102400"));

        // Overridden getSummary()
        assertTrue(textMsg.getSummary().startsWith("[Text Message]"));
        assertTrue(fileTx.getSummary().startsWith("[File Transfer]"));

        // Overridden getEstimatedSize()
        assertTrue(textMsg.getEstimatedSize() > 0);
        assertTrue(fileTx.getEstimatedSize() >= 102400L);
    }

    @Test
    void testPolymorphicReferenceAssignment() {
        // Core requirement example:
        // NetworkPayload payload;
        // payload = new TextMessage(...);
        // payload = new FileTransfer(...);
        NetworkPayload payload;

        payload = new TextMessage("sender1", "receiver1", "Polymorphic Text");
        assertEquals(PayloadType.TEXT, payload.getType());
        assertTrue(payload.getSummary().contains("Polymorphic Text"));
        assertTrue(payload.serialize().startsWith("TYPE:TEXT|"));

        payload = new FileTransfer("sender1", "receiver1", "document.docx", 500000L);
        assertEquals(PayloadType.FILE_TRANSFER, payload.getType());
        assertTrue(payload.getSummary().contains("document.docx"));
        assertTrue(payload.serialize().startsWith("TYPE:FILE|"));
    }

    @Test
    void testPolymorphicCollectionProcessing() {
        // Heterogeneous collection of payloads
        List<NetworkPayload> payloadQueue = new ArrayList<>();

        payloadQueue.add(new TextMessage("u1", "u2", "Message 1"));
        payloadQueue.add(new FileTransfer("u1", "u2", "archive.zip", 2097152L));
        payloadQueue.add(new TextMessage("u1", "u2", "Message 2"));
        payloadQueue.add(new FileTransfer("u1", "u2", "patch.tar.gz", 1048576L));

        PayloadDispatcher dispatcher = new PayloadDispatcher();
        List<String> wireRecords = new ArrayList<>();
        dispatcher.addListener((payload, wireData) -> wireRecords.add(wireData));

        // Process all heterogeneous payloads polymorphically
        for (NetworkPayload p : payloadQueue) {
            String wire = dispatcher.dispatch(p); // Dynamic dispatch
            assertNotNull(wire);
            assertFalse(wire.isEmpty());
        }

        assertEquals(4, wireRecords.size());
        assertTrue(wireRecords.get(0).startsWith("TYPE:TEXT|"));
        assertTrue(wireRecords.get(1).startsWith("TYPE:FILE|"));
        assertTrue(wireRecords.get(2).startsWith("TYPE:TEXT|"));
        assertTrue(wireRecords.get(3).startsWith("TYPE:FILE|"));
    }

    @Test
    void testPolymorphicSerializationAndDeserializationRoundtrip() {
        PayloadDispatcher dispatcher = new PayloadDispatcher();

        // 1. TextMessage roundtrip
        TextMessage originalText = new TextMessage("usr_A", "usr_B", "Testing polymorphic wire protocol!");
        String textWire = dispatcher.dispatch(originalText);

        NetworkPayload parsedText = dispatcher.ingest(textWire);
        assertInstanceOf(TextMessage.class, parsedText);
        TextMessage recoveredText = (TextMessage) parsedText;
        assertEquals(originalText.getMessageContent(), recoveredText.getMessageContent());
        assertEquals(originalText.getSenderId(), recoveredText.getSenderId());
        assertEquals(originalText.getRecipientId(), recoveredText.getRecipientId());

        // 2. FileTransfer roundtrip
        FileTransfer originalFile = new FileTransfer(
                "usr_A", 
                "usr_B", 
                "video.mp4", 
                15728640L, 
                "checksum_xyz", 
                "video/mp4"
        );
        originalFile.setTransferProgress(75.5);
        originalFile.setStatus(TransferStatus.IN_PROGRESS);

        String fileWire = dispatcher.dispatch(originalFile);

        NetworkPayload parsedFile = dispatcher.ingest(fileWire);
        assertInstanceOf(FileTransfer.class, parsedFile);
        FileTransfer recoveredFile = (FileTransfer) parsedFile;
        assertEquals(originalFile.getFileName(), recoveredFile.getFileName());
        assertEquals(originalFile.getFileSize(), recoveredFile.getFileSize());
        assertEquals(originalFile.getFileChecksum(), recoveredFile.getFileChecksum());
        assertEquals(originalFile.getTransferProgress(), recoveredFile.getTransferProgress(), 0.01);
        assertEquals(originalFile.getStatus(), recoveredFile.getStatus());
    }

    @Test
    void testSubclassSpecificValidations() {
        // TextMessage validation (exceeds length limit)
        TextMessage longMsg = new TextMessage("u1", "u2", "A".repeat(10001));
        assertThrows(IllegalArgumentException.class, longMsg::validate);

        TextMessage validMsg = new TextMessage("u1", "u2", "Hello");
        assertDoesNotThrow(validMsg::validate);

        // FileTransfer constructor and mutator validations
        assertThrows(IllegalArgumentException.class, () -> new FileTransfer("u1", "u2", "", 100));
        assertThrows(IllegalArgumentException.class, () -> new FileTransfer("u1", "u2", "file.txt", -50));

        FileTransfer validFile = new FileTransfer("u1", "u2", "file.txt", 1024);
        assertDoesNotThrow(validFile::validate);
    }
}
