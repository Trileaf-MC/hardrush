package dev.hardrush.core.book;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.BookPort;
import dev.hardrush.core.api.OperationResult;
import dev.hardrush.core.api.OperationTag;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.record.BookClaim;
import dev.hardrush.core.record.LeaderboardEntry;
import dev.hardrush.core.record.ParticipantRecord;
import dev.hardrush.core.record.RoundHistory;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class HistoryBookTest {
    @Test void literalSanitizesNamesWithoutInterpretingPlayerText() {
        String name = "§cAdmin\n/run_command\u202e\ud800";
        String value = SafeBookText.literal(name, 64);
        assertFalse(value.contains("§")); assertFalse(value.contains("\n"));
        assertFalse(value.contains("\u202e")); assertFalse(value.contains("\ud800"));
        assertTrue(value.contains("/run_command"));
        assertEquals("未知", SafeBookText.literal("\u202e\n", 64));
        assertEquals("😀😀…", SafeBookText.literal("😀😀😀", 2));
    }

    @Test void paginationWrapsCjkAndKeepsUnicodePairsAndBounds() {
        var pages = SafeBookText.paginate(List.of("中😀文".repeat(300)));
        assertTrue(pages.size() > 1);
        String unwrapped = String.join("", pages).replace("\n", "");
        assertEquals("中😀文".repeat(300), unwrapped);
        pages.forEach(page -> {
            assertTrue(page.length() <= SafeBookText.MAX_PAGE_CHARACTERS);
            for (String line : page.split("\n")) assertTrue(line.codePointCount(0, line.length()) <= 24);
        });
        assertThrows(IllegalArgumentException.class, () -> SafeBookText.paginate(List.of("x".repeat(65_537))));
        assertThrows(IllegalArgumentException.class, () -> SafeBookText.paginate(List.of("unsafe\u202e")));
    }

    @Test void volumesContainResultCurrentRulesLeaderboardAndCompleteRosterSlices() {
        var fake = new Fake();
        var main = HistoryBookComposer.compose(fake.latest, fake.fingerprint, 1, 41,
                fake.roster.subList(0, 40), List.of(new LeaderboardEntry(1, fake.latest, "winner")),
                Optional.of(fake.roster.get(40)));
        String text = text(main);
        assertTrue(text.contains("上一轮结果")); assertTrue(text.contains("当前规则榜前 10"));
        assertTrue(text.replace("\n", "").contains("胜者：player40"));
        assertTrue(text.contains("战绩书屋"));
        assertFalse(text.contains("/hardrush"));
        assertTrue(main.pages().size() <= SafeBookText.MAX_PAGES);
        var second = HistoryBookComposer.compose(fake.latest, fake.fingerprint, 2, 41,
                fake.roster.subList(40, 41), List.of());
        assertTrue(text(second).replace("\n", "").contains("41. player40"));
        assertFalse(text(second).contains("当前规则榜"));
        assertEquals(2, HistoryBookComposer.volumeCount(41));
        assertEquals(1, HistoryBookComposer.volumeCount(0));
        assertThrows(IllegalArgumentException.class, () -> HistoryBookComposer.compose(fake.latest, fake.fingerprint,
                1, 41, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> HistoryBookComposer.volumeCount(400_001));
    }

    @Test void hostileMaximumNamesAndCountersStillFitOneBoundedVolume() {
        var fake = new Fake();
        var roster = new ArrayList<ParticipantRecord>();
        for (int i = 0; i < 40; i++) roster.add(new ParticipantRecord(UUID.randomUUID(), "\u0301".repeat(64), 0,
                Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE,
                ParticipantRole.HUMAN_ACTIVE, Set.of()));
        var book = HistoryBookComposer.compose(fake.latest, fake.fingerprint, 1, 40, roster, List.of());
        assertTrue(book.pages().size() <= SafeBookText.MAX_PAGES);
        assertTrue(book.pages().stream().allMatch(page -> page.length() <= SafeBookText.MAX_PAGE_CHARACTERS));
    }

    @Test void automaticDeduplicatesOnlyNewestClaimAndNeverReplaysOlderOfflineRounds() {
        var fake = new Fake();
        assertEquals(HistoryBookService.Status.DELIVERED, fake.auto().status());
        assertEquals(HistoryBookService.Status.ALREADY_DELIVERED, fake.auto().status());
        assertEquals(1, fake.deliveries); assertEquals(fake.latest.roundId(), fake.lastBook.roundId());
        assertEquals(HistoryBookService.automaticOperationId(fake.claims.values().iterator().next()), fake.lastTag.operationId());
        assertEquals(40, fake.lastLimit); assertEquals(0, fake.lastOffset);
        assertEquals(fake.fingerprint, fake.queriedFingerprint);
    }

    @Test void fullAndOfflineLeaveAutomaticClaimPending() {
        var fake = new Fake();
        fake.delivery = BookPort.Delivery.INVENTORY_FULL;
        assertEquals(HistoryBookService.Status.INVENTORY_FULL, fake.auto().status());
        assertFalse(fake.claims.values().iterator().next().delivered());
        fake.delivery = BookPort.Delivery.OFFLINE;
        assertEquals(HistoryBookService.Status.OFFLINE, fake.auto().status());
        assertFalse(fake.claims.values().iterator().next().delivered());
        fake.delivery = BookPort.Delivery.DELIVERED;
        assertEquals(HistoryBookService.Status.DELIVERED, fake.auto().status());
        assertTrue(fake.claims.values().iterator().next().delivered());
    }

    @Test void failedAckRetriesSameDeliveryOperationWithoutClaimConsumption() {
        var fake = new Fake(); fake.ack = false;
        assertEquals(HistoryBookService.Status.RETRYABLE_FAILURE, fake.auto().status());
        UUID operation = fake.lastTag.operationId();
        assertFalse(fake.claims.values().iterator().next().delivered());
        fake.ack = true;
        assertEquals(HistoryBookService.Status.DELIVERED, fake.auto().status());
        assertEquals(operation, fake.lastTag.operationId());
    }

    @Test void manualRepeatIsRateLimitedButNeverConsumesAutomaticClaim() {
        var fake = new Fake();
        assertEquals(HistoryBookService.Status.DELIVERED, fake.manual(2).status());
        UUID firstOperation = fake.lastTag.operationId();
        assertEquals(HistoryBookService.Status.RATE_LIMITED, fake.manual(2).status());
        fake.now += HistoryBookService.MANUAL_COOLDOWN_NANOS;
        assertEquals(HistoryBookService.Status.DELIVERED, fake.manual(2).status());
        assertNotEquals(firstOperation, fake.lastTag.operationId());
        assertTrue(fake.claims.isEmpty()); assertEquals(40, fake.lastOffset);
        fake.now += HistoryBookService.MANUAL_COOLDOWN_NANOS;
        assertEquals(HistoryBookService.Status.INVALID_VOLUME, fake.manual(3).status());
        assertEquals(2, fake.deliveries);
    }

    @Test void libraryPendingVolumePersistsBeforeInventoryAndRetriesOneOperationThenAllowsAnotherCopy() {
        var fake = new Fake(); fake.delivery = BookPort.Delivery.INVENTORY_FULL;
        assertEquals(HistoryBookService.Status.INVENTORY_FULL, fake.claim(2).status());
        UUID pendingOperation = fake.lastTag.operationId();
        assertFalse(fake.claims.values().iterator().next().delivered());
        fake.now += HistoryBookService.MANUAL_COOLDOWN_NANOS;
        fake.delivery = BookPort.Delivery.DELIVERED; fake.ack = false;
        assertEquals(HistoryBookService.Status.RETRYABLE_FAILURE, fake.claim(2).status());
        assertEquals(pendingOperation, fake.lastTag.operationId());
        fake.now += HistoryBookService.MANUAL_COOLDOWN_NANOS; fake.ack = true;
        assertEquals(HistoryBookService.Status.DELIVERED, fake.claim(2).status());
        assertEquals(pendingOperation, fake.lastTag.operationId());
        assertTrue(fake.claims.values().iterator().next().delivered());
        fake.now += HistoryBookService.MANUAL_COOLDOWN_NANOS;
        assertEquals(HistoryBookService.Status.DELIVERED, fake.claim(2).status());
        assertNotEquals(pendingOperation, fake.lastTag.operationId());
    }

    @Test void staleAdapterCallbackCannotAcknowledgeClaim() {
        var fake = new Fake(); fake.wrongTag = true;
        assertEquals(HistoryBookService.Status.STALE_SCOPE, fake.auto().status());
        assertFalse(fake.claims.values().iterator().next().delivered());
        fake.wrongTag = false; fake.rejected = true;
        assertEquals(HistoryBookService.Status.STALE_SCOPE, fake.auto().status());
        assertFalse(fake.claims.values().iterator().next().delivered());
    }

    @Test void concurrentRequestWaitsAndIoFailureReleasesFlightWithoutInventingDelivery() {
        var fake = new Fake();
        fake.pendingLatest = new CompletableFuture<>();
        var first = fake.service.automatic(fake.player, 3, fake.fingerprint, fake.scope);
        assertFalse(first.toCompletableFuture().isDone());
        assertEquals(HistoryBookService.Status.BUSY, fake.manual(1).status());
        fake.pendingLatest.completeExceptionally(new IllegalStateException("IO unavailable"));
        assertEquals(HistoryBookService.Status.RETRYABLE_FAILURE, first.toCompletableFuture().join().status());
        assertEquals(0, fake.deliveries);
        fake.pendingLatest = null;
        assertEquals(HistoryBookService.Status.DELIVERED, fake.auto().status());
    }

    @Test void noTerminalHistoryCreatesNoClaimAndWrongRulesCannotProduceRankedBook() {
        var fake = new Fake();
        assertEquals(HistoryBookService.Status.NO_HISTORY,
                fake.service.automatic(fake.player, 1, fake.fingerprint, fake.scope).toCompletableFuture().join().status());
        assertTrue(fake.claims.isEmpty());
        var other = new RoundHistory(UUID.randomUUID(), 2, 1, RulesConfig.defaults(), fake.latest.result(), 1,
                false, true, 1, Set.of());
        assertThrows(IllegalArgumentException.class, () -> HistoryBookComposer.compose(fake.latest, fake.fingerprint,
                1, 0, List.of(), List.of(new LeaderboardEntry(1, other, "name"))));
    }

    private static String text(BookPort.Book book) { return String.join("\n", book.pages()); }

    private static class Fake implements HistoryBookService.HistorySource, HistoryBookService.Claims, BookPort {
        final UUID player = UUID.randomUUID();
        final OperationTag scope = new OperationTag(UUID.randomUUID(), UUID.randomUUID(), 10);
        final List<ParticipantRecord> roster = new ArrayList<>();
        final RoundHistory latest;
        final String fingerprint;
        final Map<String, BookClaim> claims = new HashMap<>();
        final HistoryBookService service;
        long now;
        int deliveries, lastLimit, lastOffset;
        String queriedFingerprint;
        boolean ack = true, wrongTag, rejected;
        Delivery delivery = Delivery.DELIVERED;
        Book lastBook;
        OperationTag lastTag;
        CompletableFuture<Optional<RoundHistory>> pendingLatest;

        Fake() {
            for (int i = 0; i < 41; i++) roster.add(new ParticipantRecord(UUID.randomUUID(), "player" + i, 0,
                    123_000_000_000L, 2, 0, 7, i % 2 == 0 ? 5_000_000_000L : null, ParticipantRole.HUMAN_ACTIVE, Set.of()));
            var rules = RulesConfig.defaults().withMode(GameMode.NORMAL);
            fingerprint = rules.fingerprint();
            latest = new RoundHistory(UUID.randomUUID(), 2, 1, rules,
                    new RoundResult(RoundResult.Kind.COMPLETED, roster.get(40).playerId(), "first dragon"),
                    12_345_000_000L, false, true, 1, Set.of());
            service = new HistoryBookService(this, this, this, () -> now);
        }
        HistoryBookService.Result auto() { return service.automatic(player, 3, fingerprint, scope).toCompletableFuture().join(); }
        HistoryBookService.Result claim(int volume) { return service.claim(player, latest.roundId(), volume, fingerprint, scope).toCompletableFuture().join(); }
        HistoryBookService.Result manual(int volume) { return service.manual(player, latest.roundId(), volume, fingerprint, scope).toCompletableFuture().join(); }
        @Override public CompletionStage<Optional<RoundHistory>> latestHistoryBefore(long sequence) {
            return pendingLatest != null ? pendingLatest : CompletableFuture.completedFuture(sequence > 2 ? Optional.of(latest) : Optional.empty());
        }
        @Override public CompletionStage<Optional<RoundHistory>> roundHistory(UUID roundId) { return done(roundId.equals(latest.roundId()) ? Optional.of(latest) : Optional.empty()); }
        @Override public CompletionStage<Long> participantCount(UUID roundId) { return done((long) roster.size()); }
        @Override public CompletionStage<List<ParticipantRecord>> participantRecords(UUID roundId, int limit, int offset) {
            lastLimit = limit; lastOffset = offset;
            return done(List.copyOf(roster.subList(offset, Math.min(roster.size(), offset + limit))));
        }
        @Override public CompletionStage<Optional<ParticipantRecord>> participantRecord(UUID roundId, UUID playerId) {
            return done(roster.stream().filter(p -> p.playerId().equals(playerId)).findFirst());
        }
        @Override public CompletionStage<List<LeaderboardEntry>> leaderboard(String fingerprint, int limit, int offset) {
            assertEquals(10, limit); assertEquals(0, offset); queriedFingerprint = fingerprint;
            return done(List.of(new LeaderboardEntry(1, latest, "player40")));
        }
        @Override public CompletionStage<BookClaim> ensureAutomaticBookClaim(UUID playerId, UUID roundId, int edition, int volume) {
            return done(claims.computeIfAbsent(playerId + "/" + roundId + "/" + edition + "/" + volume,
                    key -> new BookClaim(playerId, roundId, edition, volume, false)));
        }
        @Override public CompletionStage<Boolean> markBookDelivered(BookClaim claim) {
            if (ack) claims.replaceAll((key, value) -> new BookClaim(value.playerId(), value.roundId(), value.edition(), value.volume(), true));
            return done(ack);
        }
        @Override public CompletionStage<OperationResult<Delivery>> deliver(OperationTag tag, UUID playerId, Book book) {
            deliveries++; lastBook = book; lastTag = tag;
            if (rejected) return done(OperationResult.failed(tag, OperationResult.Outcome.REJECTED, "scope changed"));
            return done(OperationResult.verified(wrongTag ? scope : tag, delivery));
        }
        private static <T> CompletionStage<T> done(T value) { return CompletableFuture.completedFuture(value); }
    }
}
