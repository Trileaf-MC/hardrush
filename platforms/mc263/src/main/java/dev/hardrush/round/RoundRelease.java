package dev.hardrush.round;

import dev.hardrush.core.api.OperationResult;
import dev.hardrush.core.api.OperationTag;
import dev.hardrush.core.api.ReplayPort;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Recording verification precedes world save/release. Disabled recording makes no saved-recording claim. */
public final class RoundRelease {
    private RoundRelease() {}

    public static CompletionStage<Void> release(Optional<ReplayPort> recorder, OperationTag tag, Executor mainThread,
            BooleanSupplier stillCurrent, Supplier<CompletionStage<Void>> saveWorlds) {
        if (recorder.isPresent()) {
            return releaseAfterRecordings(stopRecordings(recorder.orElseThrow(), tag), mainThread, stillCurrent, saveWorlds);
        }
        var completion = new CompletableFuture<Void>();
        closeWorlds(stillCurrent, saveWorlds, completion);
        return completion.minimalCompletionStage();
    }

    /** Independent IO completion: normal shutdown may await this without a queued main-thread callback. */
    public static CompletionStage<Void> stopRecordings(ReplayPort recorder, OperationTag tag) {
        try {
            return recorder.stopAndFlush(tag).thenApply(result -> {
                if (result == null || !result.tag().equals(tag) || result.outcome() != OperationResult.Outcome.VERIFIED
                        || !result.value().orElseThrow().roundId().equals(tag.roundId())) {
                    throw new IllegalStateException("旧轮录像未完成可信保存，保留世界并停止轮换");
                }
                return (Void) null;
            });
        } catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }

    public static CompletionStage<Void> releaseAfterRecordings(CompletionStage<Void> recordingBarrier, Executor mainThread,
            BooleanSupplier stillCurrent, Supplier<CompletionStage<Void>> saveWorlds) {
        var completion = new CompletableFuture<Void>();
        recordingBarrier.whenComplete((ignored, error) -> mainThread.execute(() -> {
            if (error != null) completion.completeExceptionally(error);
            else closeWorlds(stillCurrent, saveWorlds, completion);
        }));
        return completion.minimalCompletionStage();
    }

    private static void closeWorlds(BooleanSupplier stillCurrent, Supplier<CompletionStage<Void>> saveWorlds,
            CompletableFuture<Void> completion) {
        try {
            if (!stillCurrent.getAsBoolean()) throw new IllegalStateException("旧轮释放回调已过期，拒绝改变新轮");
            saveWorlds.get().whenComplete((ignored, error) -> {
                if (error == null) completion.complete(null); else completion.completeExceptionally(error);
            });
        } catch (RuntimeException error) { completion.completeExceptionally(error); }
    }
}
