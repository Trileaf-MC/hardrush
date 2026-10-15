package dev.hardrush.persistence;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MonsterSnapshotPersistenceTest {
    @TempDir Path temporary;

    @Test void sqliteReopenKeepsMonsterSelectionRemainingCooldownEligibilityAndOutingLabels() throws Exception {
        UUID round = UUID.randomUUID(), id = UUID.randomUUID();
        var selected = new Participant(id, "Saved", ParticipantRole.MONSTER_LOBBY, true, false,
                123, 0, 2, true, true, MonsterRole.SKELETON, MonsterDimension.END, 19_000_000_000L, 7);
        var snapshot = new RoundSnapshot(round, 1, 123, 0, RulesConfig.defaults(), RoundPhase.RUNNING,
                PauseReason.SERVER_STOPPED, 123, 0, Map.of(id, selected), true, false, null);
        var path = temporary.toRealPath().resolve("monster.sqlite");
        try (var store = new SqliteRoundStore(path)) {
            assertEquals(CommitResult.APPLIED, store.commit(new SnapshotWrite(round, -1, 0,
                    RoundSnapshotCodec.encode(snapshot), UUID.randomUUID(), "MONSTER_STOP")));
        }
        var clock = new AtomicLong(999_000_000_000L);
        try (var store = new SqliteRoundStore(path)) {
            var stored = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
            assertEquals(snapshot, stored);
            var engine = RoundEngine.restore(stored, clock::get);
            assertEquals(selected.monsterCooldownNanos(), engine.view().participants().get(id).monsterCooldownNanos());
            var online = engine.proposeOnline(UUID.randomUUID(), id, "Renamed", true);
            persist(store, engine, online);
            clock.addAndGet(19_000_000_000L);
            var deploy = engine.proposeMonsterDeploy(UUID.randomUUID(), round, id, 2, 7);
            persist(store, engine, deploy);
            assertEquals(CommitResult.DUPLICATE, store.commit(write(deploy)));
            assertEquals(8, engine.committed().participants().get(id).monsterDeploymentSequence());
            var stop = engine.proposeCheckpoint(UUID.randomUUID(), true);
            persist(store, engine, stop);
        }
        clock.addAndGet(500_000_000_000L);
        try (var store = new SqliteRoundStore(path)) {
            var engine = RoundEngine.restore(RoundSnapshotCodec.decode(store.latest().orElseThrow().payload()), clock::get);
            var reconnect = engine.proposeOnline(UUID.randomUUID(), id, "Renamed", true);
            persist(store, engine, reconnect);
            Participant monster = engine.committed().participants().get(id);
            assertEquals(ParticipantRole.MONSTER_LOBBY, monster.role());
            assertEquals(30_000_000_000L, monster.monsterCooldownNanos());
            assertEquals(MonsterRole.SKELETON, monster.monsterRole());
            assertEquals(MonsterDimension.END, monster.monsterDimension());
            assertTrue(monster.revivalEntitled());
            assertEquals(123, monster.humanNanos());
            assertThrows(IllegalStateException.class, () -> engine.proposeMonsterDeath(UUID.randomUUID(), round, id, 2, 7));
        }
    }

    private static SnapshotWrite write(RoundTransition transition) {
        return new SnapshotWrite(transition.after().roundId(), transition.before().version(), transition.after().version(),
                RoundSnapshotCodec.encode(transition.after()), transition.eventId(), transition.eventType());
    }

    private static void persist(SqliteRoundStore store, RoundEngine engine, RoundTransition transition) throws Exception {
        assertEquals(CommitResult.APPLIED, store.commit(write(transition)));
        assertTrue(engine.accept(transition));
    }
}
