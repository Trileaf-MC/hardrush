package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ParticipantIdentityTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LATE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final class Clock implements MonotonicClock {
        long now;
        public long nanoTime() { return now; }
        void millis(long duration) { now += duration * 1_000_000; }
    }
    private final Clock clock = new Clock();

    @Test void hardcoreDeathCommitsOneMonsterIdentityWithoutLosingTheHumanLifeHistory() {
        RoundEngine engine = running(GameMode.HARDCORE);
        accept(engine, engine.proposeJoin(UUID.randomUUID(), LATE, "Survivor"));
        clock.millis(3_500);
        RoundTransition death = death(engine, 1);
        clock.millis(7_000);
        assertEquals(ParticipantRole.HUMAN_ACTIVE, engine.view().participants().get(PLAYER).role());
        assertEquals(3_500, engine.view().challengeMillis());
        accept(engine, death);
        Participant monster = engine.view().participants().get(PLAYER);
        assertEquals(ParticipantRole.MONSTER_LOBBY, monster.role());
        assertEquals(3_500_000_000L, monster.humanNanos());
        assertEquals(0, monster.joinedAtChallengeNanos());
        assertEquals(1, monster.lifeSequence());
        assertFalse(monster.revivalEntitled());
        assertEquals(RoundPhase.RUNNING, engine.view().phase());
        assertThrows(IllegalStateException.class, () -> death(engine, 1));
        assertThrows(IllegalStateException.class, () -> engine.proposeAdmissionRespawn(UUID.randomUUID(), PLAYER, 1));
        assertFalse(engine.pending());
    }

    @Test void cleanRestartRenameReconnectAndRepeatedJoinCannotRestoreADeadHardcoreHuman() {
        RoundEngine engine = running(GameMode.HARDCORE);
        accept(engine, engine.proposeJoin(UUID.randomUUID(), LATE, "Survivor"));
        clock.millis(2_000);
        accept(engine, death(engine, 1));
        accept(engine, engine.proposeCheckpoint(UUID.randomUUID(), true));
        RoundSnapshot persisted = RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(engine.committed()));
        clock.millis(500_000);
        RoundEngine restored = RoundEngine.restore(persisted, clock);
        assertFalse(restored.view().participants().get(PLAYER).online());
        accept(restored, restored.proposeOnline(UUID.randomUUID(), PLAYER, "Renamed", true));
        assertThrows(IllegalStateException.class, () -> restored.proposeJoin(UUID.randomUUID(), PLAYER, "Renamed"));
        assertThrows(IllegalStateException.class, () -> restored.proposeAdmissionJoin(UUID.randomUUID(), PLAYER, "Renamed"));
        clock.millis(10_000);
        Participant monster = restored.view().participants().get(PLAYER);
        assertEquals(ParticipantRole.MONSTER_LOBBY, monster.role());
        assertEquals("Renamed", monster.name());
        assertEquals(2_000_000_000L, monster.humanNanos());
        assertEquals(2_000, restored.view().challengeMillis());
        assertEquals(2, restored.view().participants().size());
    }

    @Test void normalRespawnStartsANewLifeAndStaleLifeCannotDieAgain() {
        RoundEngine engine = running(GameMode.NORMAL);
        accept(engine, death(engine, 1));
        accept(engine, engine.proposeAdmissionRespawn(UUID.randomUUID(), PLAYER, 1));
        Participant respawned = engine.view().participants().get(PLAYER);
        assertEquals(2, respawned.lifeSequence());
        assertFalse(respawned.online());
        assertThrows(IllegalStateException.class, () -> death(engine, 1));
    }

    @Test void humansAccumulateTimeAndLateJoinStartsFromItsJoinTime() {
        RoundEngine engine = running(GameMode.HARDCORE);
        clock.millis(4_000);
        accept(engine, engine.proposeJoin(UUID.randomUUID(), LATE, "Late"));
        clock.millis(2_000);
        assertEquals(6_000, engine.view().challengeMillis());
        Participant late = engine.view().participants().get(LATE);
        assertEquals(4_000_000_000L, late.joinedAtChallengeNanos());
        assertEquals(2_000_000_000L, late.humanNanos());
        accept(engine, death(engine, 1));
        clock.millis(1_000);
        assertEquals(7_000, engine.view().challengeMillis());
    }

    @Test void staleLifeOrWrongRoleNeverCreatesADeathProposal() {
        RoundEngine normal = running(GameMode.NORMAL);
        accept(normal, death(normal, 1));
        assertThrows(IllegalStateException.class, () -> death(normal, 1));
        assertFalse(normal.pending());
        RoundEngine hardcore = running(GameMode.HARDCORE);
        assertThrows(IllegalStateException.class, () -> death(hardcore, 2));
        assertFalse(hardcore.pending());
    }

    @Test void failedDeathWriteRetainsTheCommittedHumanAndRequiresRecovery() {
        RoundEngine engine = running(GameMode.HARDCORE);
        RoundTransition death = death(engine, 1);
        assertTrue(engine.reject(death, "injected disk failure"));
        assertEquals(ParticipantRole.HUMAN_ACTIVE, engine.committed().participants().get(PLAYER).role());
        assertEquals(RoundPhase.RECOVERY_REQUIRED, engine.view().phase());
        assertThrows(IllegalStateException.class, () -> death(engine, 1));
        assertFalse(engine.accept(death));
    }

    private RoundEngine running(GameMode mode) {
        RoundEngine engine = RoundEngine.create(UUID.randomUUID(), 1, 123, RulesConfig.defaults().withMode(mode), clock);
        accept(engine, engine.proposeWorldReady(UUID.randomUUID()));
        accept(engine, engine.proposeJoin(UUID.randomUUID(), PLAYER, "Alice"));
        accept(engine, engine.proposeReady(UUID.randomUUID(), PLAYER, true));
        clock.millis(30_000);
        accept(engine, engine.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.RUNNING, engine.view().phase());
        return engine;
    }

    private static void accept(RoundEngine engine, RoundTransition transition) {
        assertTrue(engine.accept(transition));
    }

    private static RoundTransition death(RoundEngine engine, int lifeSequence) {
        return engine.proposeDeathBatch(UUID.randomUUID(), engine.committed().roundId(),
                List.of(new RoundDeath(PLAYER, lifeSequence, 0, Optional.empty())), null);
    }
}
