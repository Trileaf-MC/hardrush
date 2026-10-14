package dev.hardrush.persistence;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One bounded IO queue. Completion handlers run on their chosen executor; no game state is touched. */
public final class AsyncRoundWriter implements AutoCloseable {
    private final SqliteRoundStore store;
    private final ThreadPoolExecutor executor;
    private final Object lifecycle = new Object();
    private volatile Thread workerThread;
    private boolean closed;

    public AsyncRoundWriter(SqliteRoundStore store) { this(store, 1_024); }

    public AsyncRoundWriter(SqliteRoundStore store, int queueCapacity) {
        this.store = Objects.requireNonNull(store, "store");
        if (queueCapacity < 1) throw new IllegalArgumentException("Queue capacity must be positive");
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    var thread = new Thread(runnable, "hardrush-sqlite-writer");
                    thread.setDaemon(true);
                    workerThread = thread;
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public CompletionStage<CommitResult> submit(SnapshotWrite write) {
        Objects.requireNonNull(write, "write");
        return enqueue(() -> store.commit(write));
    }

    public CompletionStage<WriteCompletion> submitTagged(SnapshotWrite write) {
        Objects.requireNonNull(write, "write");
        return submit(write).thenApply(result -> new WriteCompletion(write.roundId(), write.eventId(), write.newVersion(), result));
    }

    public CompletionStage<Optional<SnapshotData>> load(UUID roundId) {
        Objects.requireNonNull(roundId, "roundId");
        return enqueue(() -> store.load(roundId));
    }

    public CompletionStage<Optional<SnapshotData>> latest() { return enqueue(store::latest); }

    public CompletionStage<CommitResult> savePendingRules(byte[] canonicalRules, UUID eventId, String actor) {
        byte[] payload = Objects.requireNonNull(canonicalRules, "canonicalRules").clone();
        Objects.requireNonNull(eventId, "eventId");
        String auditActor = StoreValues.text(actor, "actor", 256);
        return enqueue(() -> store.savePendingRules(payload, eventId, auditActor));
    }

    public CompletionStage<Optional<byte[]>> loadPendingRules() { return enqueue(store::loadPendingRules); }

    public CompletionStage<List<OperationData>> pendingOperations(UUID roundId) {
        Objects.requireNonNull(roundId, "roundId");
        return enqueue(() -> store.pendingOperations(roundId));
    }

    public CompletionStage<OperationAdvanceResult> advanceOperation(UUID roundId, UUID operationId,
            long expectedOperationVersion, OperationStage target) {
        Objects.requireNonNull(roundId, "roundId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(target, "target");
        return enqueue(() -> store.advanceOperation(roundId, operationId, expectedOperationVersion, target));
    }

    private <T> CompletionStage<T> enqueue(StoreTask<T> task) {
        var future = new CompletableFuture<T>();
        synchronized (lifecycle) {
            if (closed) {
                future.completeExceptionally(new RejectedExecutionException("Round writer is closed"));
                return future;
            }
            try {
                executor.execute(() -> {
                    try { future.complete(task.run()); }
                    catch (Throwable failure) {
                        future.completeExceptionally(failure);
                        if (failure instanceof Error error) throw error;
                    }
                });
            } catch (RejectedExecutionException fullQueue) {
                future.completeExceptionally(fullQueue);
            }
        }
        return future;
    }

    /** Drains accepted tasks before closing SQLite. Invoke from the owner/shutdown thread. */
    @Override
    public void close() throws SQLException {
        if (Thread.currentThread() == workerThread) {
            throw new IllegalStateException("Round writer cannot close from its IO completion thread");
        }
        synchronized (lifecycle) {
            if (!closed) {
                closed = true;
                executor.shutdown();
            }
        }
        boolean interrupted = false;
        try {
            while (!executor.isTerminated()) {
                try { executor.awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException interruption) { interrupted = true; }
            }
            store.close();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface StoreTask<T> { T run() throws SQLException; }
}
