package org.yu.projectcx.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yu.projectcx.core.ChatManager;
import org.yu.projectcx.db.DatabaseManager;
import org.yu.projectcx.model.FileTransfer;
import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.model.User;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 8 Tests: Multithreading & Asynchronous Task Separation.
 */
class MultithreadingTest {

    private String testDb;
    private DatabaseManager databaseManager;
    private ChatManager chatManager;

    @BeforeEach
    void setUp() {
        testDb = "chat_mt_" + UUID.randomUUID().toString().substring(0, 8) + ".db";
        databaseManager = new DatabaseManager(testDb);
        chatManager = new ChatManager(databaseManager);
    }

    @AfterEach
    void tearDown() {
        if (chatManager != null) {
            chatManager.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        if (testDb != null) {
            new File(testDb).delete();
            new File(testDb + "-journal").delete();
        }
    }

    @Test
    void testThreadSeparationAndPoolNaming() throws ExecutionException, InterruptedException, TimeoutException {
        // 1. Verify DB Thread executes on "DB-Worker" pool
        CompletableFuture<String> dbThreadName = AsyncExecutor.supplyAsyncDb(() -> Thread.currentThread().getName());
        String dbName = dbThreadName.get(2, TimeUnit.SECONDS);
        assertTrue(dbName.startsWith("DB-Worker-"), "DB task must execute on DB-Worker thread pool. Got: " + dbName);

        // 2. Verify Network Thread executes on "Network-Worker" pool
        CompletableFuture<String> netThreadName = AsyncExecutor.supplyAsyncNetwork(() -> Thread.currentThread().getName());
        String netName = netThreadName.get(2, TimeUnit.SECONDS);
        assertTrue(netName.startsWith("Network-Worker-"), "Network task must execute on Network-Worker thread pool. Got: " + netName);

        // 3. Confirm both are completely separate from the calling/main thread
        assertNotEquals(Thread.currentThread().getName(), dbName);
        assertNotEquals(Thread.currentThread().getName(), netName);
        assertNotEquals(dbName, netName);
    }

    @Test
    void testAsynchronousAuthenticationFlow() throws ExecutionException, InterruptedException, TimeoutException {
        // Register User Asynchronously
        CompletableFuture<User> registerFuture = chatManager.registerAsync("async_user", "asyncPass123", "Async User");
        User user = registerFuture.get(3, TimeUnit.SECONDS);
        assertNotNull(user);
        assertEquals("async_user", user.getUsername());

        // Login User Asynchronously
        CompletableFuture<Optional<User>> loginFuture = chatManager.loginAsync("async_user", "asyncPass123");
        Optional<User> loggedIn = loginFuture.get(3, TimeUnit.SECONDS);
        assertTrue(loggedIn.isPresent());
        assertEquals("async_user", loggedIn.get().getUsername());

        // Failed Login Asynchronously
        CompletableFuture<Optional<User>> failedFuture = chatManager.loginAsync("async_user", "wrongPassword");
        Optional<User> failedAuth = failedFuture.get(3, TimeUnit.SECONDS);
        assertFalse(failedAuth.isPresent());
    }

    @Test
    void testAsynchronousMessageDispatchAndPersistence() throws Exception {
        User user = chatManager.register("sender_user", "password", "Sender");
        chatManager.setCurrentUser(user);

        Peer peer = chatManager.addPeer("RecipientPeer", "192.168.1.75", 9005);

        AtomicReference<String> workerThread = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        // Dispatch Message Asynchronously (Off GUI Thread)
        CompletableFuture<TextMessage> sendFuture = chatManager.sendMessageAsync("Non-blocking P2P message!", peer.getPeerId());

        TextMessage sentMsg = sendFuture.get(4, TimeUnit.SECONDS);
        assertNotNull(sentMsg);
        assertEquals("Non-blocking P2P message!", sentMsg.getMessageContent());

        // Verify message persisted to SQLite asynchronously
        CompletableFuture<List<NetworkPayload>> historyFuture = chatManager.getConversationHistoryAsync(peer.getPeerId());
        List<NetworkPayload> history = historyFuture.get(2, TimeUnit.SECONDS);
        assertEquals(1, history.size());
        assertEquals("Non-blocking P2P message!", ((TextMessage) history.get(0)).getMessageContent());
    }

    @Test
    void testAsynchronousFileTransfer() throws Exception {
        User user = chatManager.register("file_user", "pass", "File User");
        chatManager.setCurrentUser(user);
        Peer peer = chatManager.addPeer("Receiver", "10.0.0.22", 8080);

        CompletableFuture<FileTransfer> fileFuture = chatManager.sendFileAsync(
                "large_dataset.zip", 
                10485760L, // 10 MB
                "sha256_mock", 
                "application/zip", 
                peer.getPeerId()
        );

        FileTransfer fileTx = fileFuture.get(3, TimeUnit.SECONDS);
        assertNotNull(fileTx);
        assertEquals("large_dataset.zip", fileTx.getFileName());
        assertEquals("10.00 MB", fileTx.getFormattedFileSize());

        // Verify history
        List<NetworkPayload> history = chatManager.getConversationHistoryAsync(peer.getPeerId()).get(2, TimeUnit.SECONDS);
        assertEquals(1, history.size());
        assertInstanceOf(FileTransfer.class, history.get(0));
    }

    @Test
    void testScheduledBackgroundTask() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean executed = new AtomicBoolean(false);

        AsyncExecutor.schedule(() -> {
            executed.set(true);
            latch.countDown();
        }, 300, TimeUnit.MILLISECONDS);

        assertFalse(executed.get(), "Task should not run immediately");
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Scheduled task should fire after delay");
        assertTrue(executed.get(), "Scheduled task must have executed");
    }
}
