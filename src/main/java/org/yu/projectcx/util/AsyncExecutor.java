package org.yu.projectcx.util;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Centralized asynchronous threading and ExecutorService manager.
 * 
 * Demonstrates:
 * - Thread separation: GUI Thread vs Database Thread vs Network Thread.
 * - ExecutorService management with named daemon threads.
 * - CompletableFuture-based asynchronous non-blocking programming.
 * - Thread-safe synchronization back to the JavaFX Application Thread (Platform.runLater).
 */
public class AsyncExecutor {

    private static final Logger logger = LoggerFactory.getLogger(AsyncExecutor.class);

    // Dedicated Thread Pool for SQLite Database operations
    private static final ExecutorService dbExecutor = Executors.newFixedThreadPool(3, new NamedThreadFactory("DB-Worker"));

    // Dedicated Thread Pool for Network & WebRTC operations
    private static final ExecutorService networkExecutor = Executors.newFixedThreadPool(4, new NamedThreadFactory("Network-Worker"));

    // Scheduled Thread Pool for heartbeats, timeouts, and periodic background tasks
    private static final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(2, new NamedThreadFactory("Scheduled-Worker"));

    /**
     * Executes a database task asynchronously on the DB thread pool.
     */
    public static CompletableFuture<Void> runAsyncDb(Runnable task) {
        return CompletableFuture.runAsync(task, dbExecutor);
    }

    /**
     * Executes a database task returning a value asynchronously.
     */
    public static <T> CompletableFuture<T> supplyAsyncDb(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, dbExecutor);
    }

    /**
     * Executes a network or WebRTC task asynchronously on the Network thread pool.
     */
    public static CompletableFuture<Void> runAsyncNetwork(Runnable task) {
        return CompletableFuture.runAsync(task, networkExecutor);
    }

    /**
     * Executes a network task returning a value asynchronously.
     */
    public static <T> CompletableFuture<T> supplyAsyncNetwork(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, networkExecutor);
    }

    /**
     * Schedules a task to run after a specified delay.
     */
    public static void schedule(Runnable task, long delay, TimeUnit timeUnit) {
        scheduledExecutor.schedule(task, delay, timeUnit);
    }

    /**
     * Dispatches a task to the JavaFX Application Thread safely.
     * If the JavaFX toolkit is not initialized (e.g. headless unit tests), runs directly.
     */
    public static void runOnFxThread(Runnable task) {
        if (task == null) return;
        try {
            if (Platform.isFxApplicationThread()) {
                task.run();
            } else {
                Platform.runLater(task);
            }
        } catch (IllegalStateException e) {
            // JavaFX Toolkit not initialized in headless test environment
            task.run();
        }
    }

    /**
     * Gracefully shuts down all background executor thread pools.
     */
    public static void shutdown() {
        logger.info("Shutting down AsyncExecutor thread pools...");
        dbExecutor.shutdown();
        networkExecutor.shutdown();
        scheduledExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(1, TimeUnit.SECONDS)) dbExecutor.shutdownNow();
            if (!networkExecutor.awaitTermination(1, TimeUnit.SECONDS)) networkExecutor.shutdownNow();
            if (!scheduledExecutor.awaitTermination(1, TimeUnit.SECONDS)) scheduledExecutor.shutdownNow();
        } catch (InterruptedException e) {
            dbExecutor.shutdownNow();
            networkExecutor.shutdownNow();
            scheduledExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("All AsyncExecutor worker threads terminated.");
    }

    /**
     * Custom ThreadFactory generating named daemon threads.
     */
    private static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        public NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + threadNumber.getAndIncrement());
            t.setDaemon(true); // Daemon threads allow JVM to exit cleanly
            return t;
        }
    }

    public static ExecutorService getDbExecutor() {
        return dbExecutor;
    }

    public static ExecutorService getNetworkExecutor() {
        return networkExecutor;
    }
}
