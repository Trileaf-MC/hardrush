package dev.hardrush.core.book;

import dev.hardrush.core.api.BookPort;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.api.OperationResult;
import dev.hardrush.core.api.OperationTag;
import dev.hardrush.core.record.BookClaim;
import dev.hardrush.core.record.LeaderboardEntry;
import dev.hardrush.core.record.ParticipantRecord;
import dev.hardrush.core.record.RoundHistory;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Async IO is supplied by the owner; the delivery adapter rechecks active scope on the game thread. */
public final class HistoryBookService {
    public static final long MANUAL_COOLDOWN_NANOS = 5_000_000_000L;
    private static final int MAX_TRACKED_PLAYERS = 4_096;

    public interface HistorySource {
        CompletionStage<Optional<RoundHistory>> latestHistoryBefore(long sequence);
        CompletionStage<Optional<RoundHistory>> roundHistory(UUID roundId);
        CompletionStage<Long> participantCount(UUID roundId);
        CompletionStage<List<ParticipantRecord>> participantRecords(UUID roundId, int limit, int offset);
        CompletionStage<Optional<ParticipantRecord>> participantRecord(UUID roundId, UUID playerId);
        CompletionStage<List<LeaderboardEntry>> leaderboard(String fingerprint, int limit, int offset);
    }

    public interface Claims {
        /** Existing delivered claims must never be reset by this operation. */
        CompletionStage<BookClaim> ensureAutomaticBookClaim(UUID playerId, UUID roundId, int edition, int volume);
        /** True also covers an already acknowledged delivery; false means the key was not acknowledged. */
        CompletionStage<Boolean> markBookDelivered(BookClaim claim);
    }

    public enum Status { DELIVERED, ALREADY_DELIVERED, INVENTORY_FULL, OFFLINE, NO_HISTORY, INVALID_VOLUME,
        RATE_LIMITED, BUSY, STALE_SCOPE, RETRYABLE_FAILURE }

    public record Result(Status status, Optional<BookPort.Book> book) {
        public Result { Objects.requireNonNull(status); Objects.requireNonNull(book); }
        public static Result of(Status status) { return new Result(status, Optional.empty()); }
    }

    private final HistorySource source;
    private final Claims claims;
    private final BookPort delivery;
    private final MonotonicClock clock;
    private final Set<UUID> inFlight = new HashSet<>();
    private final Map<UUID, Long> manualTimes = new HashMap<>();

    public HistoryBookService(HistorySource source, Claims claims, BookPort delivery, MonotonicClock clock) {
        this.source = Objects.requireNonNull(source); this.claims = Objects.requireNonNull(claims);
        this.delivery = Objects.requireNonNull(delivery); this.clock = Objects.requireNonNull(clock);
    }

    /** Every login/rotation requests only the newest preceding terminal round's main volume. */
    public CompletionStage<Result> automatic(UUID playerId, long beforeSequence, String currentRulesFingerprint,
            OperationTag scope) {
        Objects.requireNonNull(playerId); Objects.requireNonNull(scope);
        if (beforeSequence < 1) throw new IllegalArgumentException("Invalid current round sequence");
        return start(playerId, false, () -> source.latestHistoryBefore(beforeSequence).thenCompose(history -> {
            if (history.isEmpty()) return completed(Status.NO_HISTORY);
            RoundHistory round = history.orElseThrow();
            if (round.sequence() >= beforeSequence) return completed(Status.STALE_SCOPE);
            int edition = Math.toIntExact(round.edition());
            return claims.ensureAutomaticBookClaim(playerId, round.roundId(), edition, 1).thenCompose(claim -> {
                if (!claim.playerId().equals(playerId) || !claim.roundId().equals(round.roundId())
                        || claim.edition() != edition || claim.volume() != 1) return completed(Status.RETRYABLE_FAILURE);
                if (claim.delivered()) return completed(Status.ALREADY_DELIVERED);
                var tag = new OperationTag(scope.roundId(), automaticOperationId(claim), scope.stateVersion());
                return loadBook(round, 1, currentRulesFingerprint).thenCompose(book ->
                        delivery.deliver(tag, playerId, book).thenCompose(result -> {
                            Result outcome = interpret(tag, book, result);
                            if (outcome.status() != Status.DELIVERED) return CompletableFuture.completedFuture(outcome);
                            // The adapter has saved inventory before this durable acknowledgement is attempted.
                            return claims.markBookDelivered(claim).thenApply(acknowledged -> acknowledged
                                    ? outcome : Result.of(Status.RETRYABLE_FAILURE));
                        }));
            });
        }));
    }

    /** Manual copies do not consume automatic pending claims and receive a fresh delivery operation ID. */
    public CompletionStage<Result> manual(UUID playerId, UUID sourceRound, int volume, String currentRulesFingerprint,
            OperationTag scope) {
        Objects.requireNonNull(playerId); Objects.requireNonNull(sourceRound); Objects.requireNonNull(scope);
        if (volume < 1 || volume > HistoryBookComposer.MAX_VOLUMES) return completed(Status.INVALID_VOLUME);
        return start(playerId, true, () -> source.roundHistory(sourceRound).thenCompose(history -> {
            if (history.isEmpty()) return completed(Status.NO_HISTORY);
            RoundHistory round = history.orElseThrow();
            if (!round.roundId().equals(sourceRound)) return completed(Status.STALE_SCOPE);
            return source.participantCount(sourceRound).thenCompose(count -> {
                if (volume > HistoryBookComposer.volumeCount(count)) return completed(Status.INVALID_VOLUME);
                var tag = new OperationTag(scope.roundId(), UUID.randomUUID(), scope.stateVersion());
                return loadBook(round, volume, currentRulesFingerprint, count).thenCompose(book ->
                        delivery.deliver(tag, playerId, book).thenApply(result -> interpret(tag, book, result)));
            });
        }));
    }

    /** Explicit library claim: persist the existing ledger key before touching inventory.
     * Pending copies reuse its operation ID, so a failed inventory save/ACK can be retried safely.
     * A previously delivered copy may be requested again with a fresh operation, still rate limited.
     */
    public CompletionStage<Result> claim(UUID playerId, UUID sourceRound, int volume, String fingerprint, OperationTag scope) {
        Objects.requireNonNull(playerId); Objects.requireNonNull(sourceRound); Objects.requireNonNull(scope);
        if (volume < 1 || volume > HistoryBookComposer.MAX_VOLUMES) return completed(Status.INVALID_VOLUME);
        return start(playerId, true, () -> source.roundHistory(sourceRound).thenCompose(history -> {
            if (history.isEmpty()) return completed(Status.NO_HISTORY);
            var round = history.orElseThrow();
            if (!round.roundId().equals(sourceRound)) return completed(Status.STALE_SCOPE);
            return source.participantCount(sourceRound).thenCompose(count -> {
                if (volume > HistoryBookComposer.volumeCount(count)) return completed(Status.INVALID_VOLUME);
                return claims.ensureAutomaticBookClaim(playerId, sourceRound, Math.toIntExact(round.edition()), volume).thenCompose(claim -> {
                    if (!claim.playerId().equals(playerId) || !claim.roundId().equals(sourceRound)
                            || claim.edition() != round.edition() || claim.volume() != volume) return completed(Status.STALE_SCOPE);
                    var tag = new OperationTag(scope.roundId(), claim.delivered() ? UUID.randomUUID() : automaticOperationId(claim), scope.stateVersion());
                    return loadBook(round, volume, fingerprint, count).thenCompose(book -> delivery.deliver(tag, playerId, book).thenCompose(delivered -> {
                        var result = interpret(tag, book, delivered);
                        if (result.status() != Status.DELIVERED) return CompletableFuture.completedFuture(result);
                        return claims.markBookDelivered(claim).thenApply(ack -> ack ? result : Result.of(Status.RETRYABLE_FAILURE));
                    }));
                });
            });
        }));
    }

    public static UUID automaticOperationId(BookClaim claim) {
        return UUID.nameUUIDFromBytes(("hardrush-auto-book/" + claim.playerId() + "/" + claim.roundId()
                + "/" + claim.edition() + "/" + claim.volume()).getBytes(StandardCharsets.UTF_8));
    }

    private CompletionStage<BookPort.Book> loadBook(RoundHistory round, int volume, String fingerprint) {
        return source.participantCount(round.roundId()).thenCompose(count -> loadBook(round, volume, fingerprint, count));
    }

    private CompletionStage<BookPort.Book> loadBook(RoundHistory round, int volume, String fingerprint, long count) {
        if (volume < 1 || volume > HistoryBookComposer.volumeCount(count)) throw new IllegalArgumentException("Invalid volume");
        int offset = Math.multiplyExact(volume - 1, HistoryBookComposer.PARTICIPANTS_PER_VOLUME);
        var roster = source.participantRecords(round.roundId(), HistoryBookComposer.PARTICIPANTS_PER_VOLUME, offset);
        CompletionStage<List<LeaderboardEntry>> leaders = volume == 1
                ? source.leaderboard(fingerprint, HistoryBookComposer.LEADERBOARD_SIZE, 0)
                : CompletableFuture.completedFuture(List.of());
        CompletionStage<Optional<ParticipantRecord>> winner = volume == 1 && round.result().winner() != null
                ? source.participantRecord(round.roundId(), round.result().winner())
                : CompletableFuture.completedFuture(Optional.empty());
        return roster.thenCombine(leaders, (players, entries) -> new Content(players, entries))
                .thenCombine(winner, (content, victor) -> HistoryBookComposer.compose(round, fingerprint,
                        volume, count, content.players(), content.leaders(), victor));
    }

    private CompletionStage<Result> start(UUID playerId, boolean manual, Supplier<CompletionStage<Result>> request) {
        synchronized (this) {
            if (inFlight.contains(playerId) || inFlight.size() >= MAX_TRACKED_PLAYERS) return completed(Status.BUSY);
            if (manual) {
                long now = clock.nanoTime();
                Long last = manualTimes.get(playerId);
                if (last != null && now - last < MANUAL_COOLDOWN_NANOS) return completed(Status.RATE_LIMITED);
                manualTimes.entrySet().removeIf(entry -> now - entry.getValue() >= MANUAL_COOLDOWN_NANOS);
                if (manualTimes.size() >= MAX_TRACKED_PLAYERS) return completed(Status.BUSY);
                manualTimes.put(playerId, now);
            }
            inFlight.add(playerId);
        }
        CompletionStage<Result> result;
        try { result = request.get(); }
        catch (RuntimeException failure) { result = completed(Status.RETRYABLE_FAILURE); }
        return result.handle((value, failure) -> failure == null ? value : Result.of(Status.RETRYABLE_FAILURE))
                .whenComplete((value, failure) -> { synchronized (this) { inFlight.remove(playerId); } });
    }

    private static Result interpret(OperationTag expected, BookPort.Book book, OperationResult<BookPort.Delivery> result) {
        if (!result.tag().equals(expected)) return Result.of(Status.STALE_SCOPE);
        if (result.outcome() != OperationResult.Outcome.VERIFIED || result.value().isEmpty()) {
            return Result.of(result.outcome() == OperationResult.Outcome.REJECTED ? Status.STALE_SCOPE : Status.RETRYABLE_FAILURE);
        }
        Status status = switch (result.value().orElseThrow()) {
            case DELIVERED -> Status.DELIVERED;
            case INVENTORY_FULL -> Status.INVENTORY_FULL;
            case OFFLINE -> Status.OFFLINE;
        };
        return new Result(status, Optional.of(book));
    }

    private static CompletionStage<Result> completed(Status status) { return CompletableFuture.completedFuture(Result.of(status)); }
    private record Content(List<ParticipantRecord> players, List<LeaderboardEntry> leaders) { }
}
