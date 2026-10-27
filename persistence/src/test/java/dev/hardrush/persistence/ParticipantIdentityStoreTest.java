package dev.hardrush.persistence;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.record.ProgressWrite;
import dev.hardrush.core.round.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParticipantIdentityStoreTest {
    @TempDir Path directory;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final class Clock implements MonotonicClock {
        long now;
        public long nanoTime() { return now; }
        void millis(long duration) { now += duration * 1_000_000; }
    }

    @Test void hardcoreDeathAndExitSurviveSqliteReopenWithoutRepeatingDeathsOrErasingHistory() throws Exception {
        Path database = directory.toRealPath().resolve("identity.sqlite");
        Clock clock = new Clock();
        RoundEngine engine = RoundEngine.create(UUID.randomUUID(), 1, 123, RulesConfig.defaults(), clock);
        UUID round = engine.committed().roundId();
        SnapshotWrite deathWrite;
        try (var store = new SqliteRoundStore(database)) {
            assertEquals(CommitResult.APPLIED, store.commit(new SnapshotWrite(round, -1, 0,
                    RoundSnapshotCodec.encode(engine.committed()), UUID.randomUUID(), "ROUND_CREATED")));
            commit(store, engine, engine.proposeWorldReady(UUID.randomUUID()));
            commit(store, engine, engine.proposeJoin(UUID.randomUUID(), PLAYER, "Alice"));
            commit(store, engine, engine.proposeReady(UUID.randomUUID(), PLAYER, true));
            clock.millis(30_000);
            commit(store, engine, engine.proposeTick(UUID.randomUUID()));
            commit(store, engine, engine.proposeLeaveProtection(UUID.randomUUID(), PLAYER, 1));
            clock.millis(2_000);
            assertEquals(CommitResult.APPLIED, store.recordProgress(new ProgressWrite(round, UUID.randomUUID(),
                    PLAYER, "Alice", Set.of("minecraft:story/mine_stone"), "a".repeat(64), true, 2_000_000_000L)));
            RoundTransition death = engine.proposeHardcoreDeath(UUID.randomUUID(), PLAYER, 1);
            deathWrite = write(death);
            commit(store, engine, death);
            assertEquals(CommitResult.DUPLICATE, store.commit(deathWrite));
            assertEquals(1, store.participantRecord(round, PLAYER).orElseThrow().deathCount());
            commit(store, engine, engine.proposeCheckpoint(UUID.randomUUID(), true));
        }
        clock.millis(500_000);
        try (var store = new SqliteRoundStore(database)) {
            RoundEngine restored = RoundEngine.restore(RoundSnapshotCodec.decode(store.load(round).orElseThrow().payload()), clock);
            commit(store, restored, restored.proposeOnline(UUID.randomUUID(), PLAYER, "Renamed", true));
            assertEquals(CommitResult.DUPLICATE, store.commit(deathWrite));
            assertThrows(IllegalStateException.class, () -> restored.proposeJoin(UUID.randomUUID(), PLAYER, "Renamed"));
            Participant monster = restored.view().participants().get(PLAYER);
            assertEquals(ParticipantRole.MONSTER_LOBBY, monster.role());
            assertTrue(monster.hasLeftProtection());
            assertEquals(1, monster.lifeSequence());
            var record = store.participantRecord(round, PLAYER).orElseThrow();
            assertEquals("Alice", record.playerName());
            assertEquals(0, record.joinedAtChallengeNanos());
            assertEquals(2_000_000_000L, record.humanNanos());
            assertEquals(1, record.deathCount());
            assertEquals(0, record.revivalCount());
            assertEquals(1, record.advancementCount());
            assertEquals(2_000_000_000L, record.firstEndNanos());
            assertEquals(ParticipantRole.MONSTER_LOBBY, record.finalRole());
            assertEquals(1, store.endEntrantCount(round));
            assertEquals(CommitResult.STALE, store.recordProgress(new ProgressWrite(round, UUID.randomUUID(),
                    PLAYER, "Renamed", Set.of("minecraft:story/upgrade_tools"), "a".repeat(64), true, 3_000_000_000L)));
        }
    }

    @Test void firstIndexOfAnAdmittedMonsterIncludesItsDeathAndLaterCheckpointsDoNotCountAgain() throws Exception {
        Path database = directory.toRealPath().resolve("imported.sqlite");
        UUID round = UUID.randomUUID();
        Participant monster = new Participant(PLAYER, "Alice", ParticipantRole.MONSTER_LOBBY, false, false,
                123, 0, 1, false, true);
        RoundSnapshot snapshot = new RoundSnapshot(round, 1, 123, 0, RulesConfig.defaults(), RoundPhase.RUNNING,
                PauseReason.NO_ONLINE_HUMANS, 123, 0, Map.of(PLAYER, monster), false, false, null);
        try (var store = new SqliteRoundStore(database)) {
            store.commit(new SnapshotWrite(round, -1, 0, RoundSnapshotCodec.encode(snapshot), UUID.randomUUID(), "ROUND_CREATED"));
            assertEquals(1, store.participantRecord(round, PLAYER).orElseThrow().deathCount());
            RoundSnapshot later = new RoundSnapshot(round, 1, 123, 1, snapshot.rules(), snapshot.phase(), snapshot.pauseReason(),
                    123, 0, snapshot.participants(), true, false, null);
            store.commit(new SnapshotWrite(round, 0, 1, RoundSnapshotCodec.encode(later), UUID.randomUUID(), "STOP"));
            assertEquals(1, store.participantRecord(round, PLAYER).orElseThrow().deathCount());
        }
    }

    private static SnapshotWrite write(RoundTransition transition) {
        return new SnapshotWrite(transition.after().roundId(), transition.before().version(), transition.after().version(),
                RoundSnapshotCodec.encode(transition.after()), transition.eventId(), transition.eventType());
    }

    private static void commit(SqliteRoundStore store, RoundEngine engine, RoundTransition transition) throws Exception {
        assertEquals(CommitResult.APPLIED, store.commit(write(transition)));
        assertTrue(engine.accept(transition));
    }
}
