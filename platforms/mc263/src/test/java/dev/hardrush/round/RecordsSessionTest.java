package dev.hardrush.round;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.*;
import dev.hardrush.core.config.*;
import dev.hardrush.core.record.*;
import dev.hardrush.core.round.*;
import dev.hardrush.persistence.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQLite + asynchronous session, with a fake clock and explicit world-release adapter seam. */
final class RecordsSessionTest {
    @TempDir Path directory;
    private final UUID player = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong();
    private final LinkedBlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();

    @Test void tenSessionRoundsAndCleanReopensNeverMixStatisticsOrHistoricalBookClaims() throws Exception {
        Path database = seed("ten-rounds.sqlite");
        var rounds = new ArrayList<UUID>();
        for (int number = 1; number <= 10; number++) {
            try (var session = open(database)) {
                var initial = session.snapshot().orElseThrow();
                assertEquals(number, initial.sequence());
                assertEquals(0, session.endEntrants());
                assertTrue(initial.participants().isEmpty());
                session.requestJoin(player, "Human" + number, result -> assertTrue(result.success(), result.message()));
                pump(() -> !session.mutationPending());
                session.onOnline(player, "Human" + number, true);
                pump(() -> !session.mutationPending());
                session.requestReady(player, true, result -> assertTrue(result.success(), result.message()));
                pump(() -> !session.mutationPending());
                clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
                session.tick();
                pump(() -> !session.mutationPending());
                assertEquals(RoundPhase.RUNNING, session.snapshot().orElseThrow().phase());
                session.onOnline(player, "Human" + number, true); // Verified profile unlock seam.
                pump(() -> !session.mutationPending());
                assertTrue(session.snapshot().orElseThrow().participants().get(player).online());
                clock.addAndGet(TimeUnit.SECONDS.toNanos(number));
                var live = session.view().orElseThrow();
                rounds.add(live.roundId());
                var progress = new ProgressWrite(live.roundId(), UUID.randomUUID(), player, "Human" + number,
                        Set.of("minecraft:story/mine_stone"), "a".repeat(64), true, live.challengeNanos());
                session.recordProgress(progress);
                // FINISH is accepted before the progress callback runs; the worker must retain the earlier progress.
                UUID dragon = UUID.randomUUID();
                var origin = new CombatOrigin(live.roundId(), UUID.randomUUID(),
                        new CombatActor(player, ParticipantRole.HUMAN_ACTIVE, 1, 0), AttackKind.MELEE, Optional.of(player), List.of());
                var lethal = new CombatEvent(live.roundId(), UUID.randomUUID(), UUID.randomUUID(), 1, dragon,
                        Optional.of(origin), CombatEvent.Attribution.HUMAN, "minecraft:player_attack", Optional.of(player),
                        Optional.of(player), 1, true, clock.get());
                session.captureDragonDeath(lethal, dragon);
                session.finishDamageBatch(live.roundId(), lethal.batchId());
                pump(() -> session.snapshot().orElseThrow().result() != null && !session.mutationPending());
                assertEquals(1, session.endEntrants());
                var history = await(session.history(live.roundId())).orElseThrow();
                assertTrue(history.leaderboardEligible());
                assertEquals(number * 1000L, history.challengeMillis());
                var roster = await(session.participants(live.roundId(), 20, 0));
                assertEquals(1, roster.size());
                assertEquals("Human" + number, roster.getFirst().playerName());
                assertEquals(1, roster.getFirst().advancementCount());
                assertEquals(live.challengeNanos(), roster.getFirst().firstEndNanos());
                assertEquals(0, roster.getFirst().deathCount());
                var claim = await(session.ensureBook(player, live.roundId(), 1, 1));
                assertFalse(claim.delivered());
                if (number % 2 == 0) assertTrue(await(session.markBookDelivered(player, live.roundId(), 1, 1)));
                clock.addAndGet(TimeUnit.SECONDS.toNanos(601));
                session.tick();
                pump(() -> !session.mutationPending());
                assertEquals(RoundPhase.ROTATING, session.snapshot().orElseThrow().phase());
                session.rotationReady(live.roundId()); // No claim of physical world closure in this test.
                pump(() -> !session.mutationPending());
                assertEquals(number + 1, session.snapshot().orElseThrow().sequence());
                assertEquals(0, session.endEntrants());
                assertTrue(session.snapshot().orElseThrow().participants().isEmpty());
                session.recordProgress(progress); // Late old-round callback cannot seed the new round.
            }
            clock.addAndGet(TimeUnit.DAYS.toNanos(2)); // Offline time must not enter results or countdowns.
        }
        try (var store = new SqliteRoundStore(database)) {
            assertEquals(10, store.history(20, 0).size());
            assertEquals(10, store.leaderboard(RulesConfig.defaults().withMode(GameMode.NORMAL).fingerprint(), 10).size());
            for (int i = 0; i < rounds.size(); i++) {
                assertEquals(1, store.endEntrantCount(rounds.get(i)));
                assertEquals((i + 1) % 2 == 0, store.ensureAutomaticBookClaim(player, rounds.get(i), 1, 1).delivered());
            }
            assertEquals(0, store.endEntrantCount(store.latest().orElseThrow().roundId()));
        }
    }

    @Test void failedBookWriteCannotReceiveCleanStopMarkerBeforeItsCallbackRuns() throws Exception {
        Path database = seed("book-fail.sqlite");
        var session = open(database);
        try (var lock = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = lock.createStatement()) {
            sql.execute("CREATE TRIGGER fail_book BEFORE INSERT ON book_deliveries BEGIN SELECT RAISE(ABORT,'fixture'); END");
            // A missing source result is also rejected; either failure is a durable write failure.
            var request = session.ensureBook(player, UUID.randomUUID(), 1, 1);
            assertThrows(IllegalStateException.class, session::close);
            assertFalse(request.isDone(), "Main-thread callback was deliberately not drained during shutdown");
        }
        try (var store = new SqliteRoundStore(database)) {
            assertFalse(RoundSnapshotCodec.decode(store.latest().orElseThrow().payload()).cleanShutdown());
        }
    }

    private Path seed(String name) throws Exception {
        Path database = directory.toRealPath().resolve(name);
        var rules = RulesConfig.defaults().withMode(GameMode.NORMAL);
        try (var store = new SqliteRoundStore(database)) {
            store.savePendingRules(rules.encode().getBytes(StandardCharsets.UTF_8), UUID.randomUUID(), "fixture");
        }
        return database;
    }
    private RoundSession open(Path database) throws Exception {
        callbacks.clear();
        var session = new RoundSession(database, clock::get, callbacks::add);
        pump(() -> session.state() == RoundSession.State.OPEN);
        pump(() -> !session.mutationPending());
        session.adaptersReady(session.snapshot().orElseThrow().roundId());
        pump(() -> !session.mutationPending());
        return session;
    }
    private <T> T await(CompletableFuture<T> future) throws Exception { pump(future::isDone); return future.join(); }
    private void pump(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < until) {
            var task = callbacks.poll(20, TimeUnit.MILLISECONDS);
            if (task != null) task.run();
        }
        assertTrue(condition.getAsBoolean(), "Async SQLite callback timed out");
    }
}
