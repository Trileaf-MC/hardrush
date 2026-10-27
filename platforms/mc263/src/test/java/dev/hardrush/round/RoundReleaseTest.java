package dev.hardrush.round;

import dev.hardrush.core.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RoundReleaseTest {
    private final OperationTag tag = new OperationTag(UUID.randomUUID(), UUID.randomUUID(), 7);

    @Test void disabledRecorderClosesWorldsWithoutClaimingASavedRecording() {
        var saved = new CompletableFuture<Void>();
        var calls = new AtomicInteger();
        var release = RoundRelease.release(Optional.empty(), tag, Runnable::run, () -> true,
                () -> { calls.incrementAndGet(); return saved; }).toCompletableFuture();
        assertEquals(1, calls.get()); assertFalse(release.isDone());
        saved.complete(null); assertDoesNotThrow(release::join);
    }

    @Test void installedRecorderMustVerifyThenWorldCloseRunsOnMainThreadAndFinishes() {
        var recording = new CompletableFuture<OperationResult<ReplayPort.SavedRecordings>>();
        var main = new ArrayDeque<Runnable>();
        var closed = new CompletableFuture<Void>();
        var calls = new AtomicInteger();
        var release = RoundRelease.release(Optional.of(recorder(recording)), tag, main::add, () -> true,
                () -> { calls.incrementAndGet(); return closed; }).toCompletableFuture();
        assertEquals(0, calls.get());
        recording.complete(OperationResult.verified(tag, new ReplayPort.SavedRecordings(tag.roundId(), List.of("saved/segment"))));
        assertEquals(0, calls.get()); assertFalse(release.isDone());
        main.remove().run(); assertEquals(1, calls.get()); assertFalse(release.isDone());
        closed.complete(null); assertDoesNotThrow(release::join);
    }

    @Test void unavailableOrUnverifiedRecordingRetainsWorlds() {
        for (var outcome : new OperationResult.Outcome[]{OperationResult.Outcome.UNAVAILABLE, OperationResult.Outcome.RETRYABLE_FAILURE,
                OperationResult.Outcome.REJECTED}) {
            var recording = CompletableFuture.completedFuture(OperationResult.<ReplayPort.SavedRecordings>failed(tag, outcome, "failure"));
            var calls = new AtomicInteger();
            var release = RoundRelease.release(Optional.of(recorder(recording)), tag, Runnable::run, () -> true,
                    () -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(null); }).toCompletableFuture();
            assertThrows(CompletionException.class, release::join); assertEquals(0, calls.get());
        }
    }

    @Test void wrongRoundOrExpiredCallbackNeverReleasesANewWorld() {
        for (boolean current : new boolean[]{true, false}) {
            var resultTag = current ? new OperationTag(UUID.randomUUID(), tag.operationId(), tag.stateVersion()) : tag;
            var recording = CompletableFuture.completedFuture(OperationResult.verified(resultTag,
                    new ReplayPort.SavedRecordings(resultTag.roundId(), List.of("saved/segment"))));
            var calls = new AtomicInteger();
            var release = RoundRelease.release(Optional.of(recorder(recording)), tag, Runnable::run, () -> current,
                    () -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(null); }).toCompletableFuture();
            assertThrows(CompletionException.class, release::join); assertEquals(0, calls.get());
        }
    }

    @Test void failedWorldSaveCannotSatisfyRotationBarrier() {
        var release = RoundRelease.release(Optional.empty(), tag, Runnable::run, () -> true,
                () -> CompletableFuture.failedFuture(new IllegalStateException("save failed"))).toCompletableFuture();
        assertThrows(CompletionException.class, release::join);
    }

    private ReplayPort recorder(CompletionStage<OperationResult<ReplayPort.SavedRecordings>> result) {
        return new ReplayPort() {
            @Override public CompletionStage<OperationResult<UUID>> startSegment(OperationTag tag, UUID playerId) {
                throw new AssertionError("Release must not start or claim a recording");
            }
            @Override public CompletionStage<OperationResult<SavedRecordings>> stopAndFlush(OperationTag requested) {
                assertEquals(tag, requested); return result;
            }
        };
    }

    @Test void shutdownCanDrainRecordingIoWithoutRunningAWorldReleaseCallback() {
        var recording = new CompletableFuture<OperationResult<ReplayPort.SavedRecordings>>();
        var ioBarrier = RoundRelease.stopRecordings(recorder(recording), tag).toCompletableFuture();
        var main = new ArrayDeque<Runnable>();
        var closes = new AtomicInteger();
        var release = RoundRelease.releaseAfterRecordings(ioBarrier, main::add, () -> true,
                () -> { closes.incrementAndGet(); return CompletableFuture.completedFuture(null); }).toCompletableFuture();
        recording.complete(OperationResult.verified(tag, new ReplayPort.SavedRecordings(tag.roundId(), List.of("saved/segment"))));
        assertDoesNotThrow(ioBarrier::join);
        assertFalse(release.isDone()); assertEquals(0, closes.get());
        main.remove().run(); assertDoesNotThrow(release::join); assertEquals(1, closes.get());
    }
}
