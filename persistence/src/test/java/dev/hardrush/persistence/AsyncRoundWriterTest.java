package dev.hardrush.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AsyncRoundWriterTest {
    @TempDir Path temporaryDirectory;
    private Path database;
    private final UUID round = UUID.randomUUID();

    @BeforeEach
    void realDatabasePath() throws IOException { database = temporaryDirectory.toRealPath().resolve("rounds.sqlite"); }

    @Test
    void completionRetainsRoundEventAndVersionTagsAndReadsUseTheSameQueue() throws Exception {
        UUID event = UUID.randomUUID();
        try (var writer = new AsyncRoundWriter(new SqliteRoundStore(database))) {
            var completed = writer.submitTagged(new SnapshotWrite(round, -1, 0, bytes("snapshot"), event, "CREATE"))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(round, completed.roundId());
            assertEquals(event, completed.eventId());
            assertEquals(0, completed.version());
            assertEquals(CommitResult.APPLIED, completed.result());
            var loaded = writer.load(round).toCompletableFuture().get(5, TimeUnit.SECONDS).orElseThrow();
            assertArrayEquals(bytes("snapshot"), loaded.payload());
            assertEquals(round, writer.latest().toCompletableFuture().get(5, TimeUnit.SECONDS).orElseThrow().roundId());
            byte[] rules = bytes("extreme");
            var saved = writer.savePendingRules(rules, UUID.randomUUID(), "console");
            rules[0] = 0;
            assertEquals(CommitResult.APPLIED, saved.toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertArrayEquals(bytes("extreme"), writer.loadPendingRules().toCompletableFuture().get(5, TimeUnit.SECONDS).orElseThrow());
        }
    }

    @Test
    void failedWriteCompletesExceptionallyWithoutPoisoningTheQueueOrPublishingData() throws Exception {
        UUID event = UUID.randomUUID();
        try (var writer = new AsyncRoundWriter(new SqliteRoundStore(database))) {
            var original = new SnapshotWrite(round, -1, 0, bytes("before"), event, "CREATE");
            writer.submit(original).toCompletableFuture().get(5, TimeUnit.SECONDS);
            var bad = new SnapshotWrite(round, 0, 1, bytes("bad"), event, "CREATE");
            var failure = assertThrows(CompletionException.class, () -> writer.submit(bad).toCompletableFuture().join());
            assertInstanceOf(EventCollisionException.class, failure.getCause());
            assertArrayEquals(bytes("before"), writer.load(round).toCompletableFuture().get(5, TimeUnit.SECONDS).orElseThrow().payload());
            assertEquals(CommitResult.APPLIED, writer.submit(write(0, "after")).toCompletableFuture().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeDrainsEveryAcceptedWriteThenClosesStoreAndRejectsNewSubmissions() throws Exception {
        var store = new SqliteRoundStore(database);
        var writer = new AsyncRoundWriter(store);
        var writes = new ArrayList<CompletableFuture<CommitResult>>();
        for (int version = 0; version < 40; version++) writes.add(writer.submit(write(version - 1, "state " + version)).toCompletableFuture());
        writer.close();
        for (var write : writes) { assertTrue(write.isDone()); assertEquals(CommitResult.APPLIED, write.join()); }
        assertThrows(SQLException.class, () -> store.load(round));
        var failure = assertThrows(CompletionException.class, () -> writer.submit(write(39, "too late")).toCompletableFuture().join());
        assertInstanceOf(RejectedExecutionException.class, failure.getCause());
        writer.close();
        try (var reopened = new SqliteRoundStore(database)) {
            assertEquals(39, reopened.load(round).orElseThrow().version());
            assertArrayEquals(bytes("state 39"), reopened.load(round).orElseThrow().payload());
        }
    }

    @Test
    void boundedQueueRejectsExcessWorkAndNeverRunsDatabaseIoOnSubmittingThread() throws Exception {
        var workerEntered = new CountDownLatch(1);
        var releaseWorker = new CountDownLatch(1);
        try (var store = new SqliteRoundStore(database, 2_000);
             var writer = new AsyncRoundWriter(store, 1);
             var lock = DriverManager.getConnection("jdbc:sqlite:" + database);
             var sql = lock.createStatement()) {
            store.commit(write(-1, "initial"));
            lock.setAutoCommit(false);
            sql.executeUpdate("UPDATE round_snapshots SET payload=payload");
            String submittingThread = Thread.currentThread().getName();
            var callbackThread = new CompletableFuture<String>();
            var active = writer.submit(write(0, "active")).thenRun(() -> {
                callbackThread.complete(Thread.currentThread().getName());
                workerEntered.countDown();
                try { assertTrue(releaseWorker.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interruption) { Thread.currentThread().interrupt(); throw new CompletionException(interruption); }
            });
            lock.rollback();
            try {
                assertTrue(workerEntered.await(5, TimeUnit.SECONDS));
                assertNotEquals(submittingThread, callbackThread.get(5, TimeUnit.SECONDS));
                var accepted = writer.submit(write(1, "accepted"));
                var rejected = writer.submit(write(2, "rejected"));
                var failure = assertThrows(CompletionException.class, () -> rejected.toCompletableFuture().join());
                assertInstanceOf(RejectedExecutionException.class, failure.getCause());
                releaseWorker.countDown();
                active.toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(CommitResult.APPLIED, accepted.toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals(2, writer.load(round).toCompletableFuture().get(5, TimeUnit.SECONDS).orElseThrow().version());
            } finally { releaseWorker.countDown(); }
        }
    }

    @Test
    void closePreservesOwnerInterruptAndStillDrainsAcceptedWork() throws Exception {
        var writer = new AsyncRoundWriter(new SqliteRoundStore(database));
        var write = writer.submit(write(-1, "saved"));
        Thread.currentThread().interrupt();
        try {
            writer.close();
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(CommitResult.APPLIED, write.toCompletableFuture().join());
        } finally { Thread.interrupted(); }
    }

    @Test
    void asyncNativeWriteFailurePropagatesAndAllowsExplicitRetry() throws Exception {
        try (var writer = new AsyncRoundWriter(new SqliteRoundStore(database, 30));
             var lock = DriverManager.getConnection("jdbc:sqlite:" + database);
             var sql = lock.createStatement()) {
            writer.submit(write(-1, "before")).toCompletableFuture().get(5, TimeUnit.SECONDS);
            lock.setAutoCommit(false);
            sql.executeUpdate("UPDATE round_snapshots SET payload=payload");
            var next = write(0, "after");
            var failure = assertThrows(CompletionException.class, () -> writer.submit(next).toCompletableFuture().join());
            assertInstanceOf(SQLException.class, failure.getCause());
            lock.rollback();
            assertEquals(CommitResult.APPLIED, writer.submit(next).toCompletableFuture().get(5, TimeUnit.SECONDS));
        }
    }

    private SnapshotWrite write(long previousVersion, String payload) {
        return new SnapshotWrite(round, previousVersion, previousVersion + 1, bytes(payload), UUID.randomUUID(), "UPDATE");
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
