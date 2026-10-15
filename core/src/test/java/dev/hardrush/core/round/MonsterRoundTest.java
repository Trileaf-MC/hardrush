package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.RulesConfig;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MonsterRoundTest {
    private final UUID round = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();
    private final Clock clock = new Clock();
    private static class Clock implements MonotonicClock {
        long now;
        public long nanoTime() { return now; }
        void seconds(long seconds) { now += seconds * 1_000_000_000L; }
    }
    private static void apply(RoundEngine engine, RoundTransition event) { assertTrue(engine.accept(event)); }
    private Participant player(RoundEngine engine) { return engine.view().participants().get(player); }
    private RoundEngine monster() {
        var engine = RoundEngine.create(round, 1, 123, RulesConfig.defaults(), clock);
        apply(engine, engine.proposeWorldReady(UUID.randomUUID()));
        apply(engine, engine.proposeJoin(UUID.randomUUID(), player, "Monster"));
        apply(engine, engine.proposeReady(UUID.randomUUID(), player, true));
        clock.seconds(30);
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        clock.seconds(4);
        apply(engine, engine.proposeHardcoreDeath(UUID.randomUUID(), player, 1));
        return engine;
    }
    private void deploy(RoundEngine engine, int expected) {
        apply(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, player, 1, expected));
    }

    @Test void firstDeathStartsFullWaitAndMonsterWaitDoesNotResumeHumanTiming() {
        var engine = monster();
        assertEquals(30_000_000_000L, player(engine).monsterCooldownNanos());
        assertEquals(MonsterRole.ZOMBIE, player(engine).monsterRole());
        assertEquals(MonsterDimension.OVERWORLD, player(engine).monsterDimension());
        clock.seconds(29);
        assertEquals(1_000_000_000L, player(engine).monsterCooldownNanos());
        assertThrows(IllegalStateException.class, () -> deploy(engine, 0));
        clock.seconds(1);
        deploy(engine, 0);
        assertEquals(ParticipantRole.MONSTER_DEPLOYED, player(engine).role());
        assertEquals(1, player(engine).monsterDeploymentSequence());
        assertEquals(4_000_000_000L, player(engine).humanNanos());
        assertEquals(4_000_000_000L, engine.view().challengeNanos());
        assertEquals(PauseReason.NO_ONLINE_HUMANS, engine.view().pauseReason());
    }

    @Test void choicesAndQualificationSurviveOutingsReturnsDeathsAndSessionUpdates() {
        var engine = monster();
        apply(engine, engine.proposeMonsterSelection(UUID.randomUUID(), round, player, 1, 0,
                MonsterRole.SKELETON, MonsterDimension.END));
        clock.seconds(30);
        deploy(engine, 0);
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterSelection(UUID.randomUUID(), round,
                player, 1, 1, MonsterRole.CREEPER, MonsterDimension.NETHER));
        apply(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, player, 1, 1));
        assertEquals(30_000_000_000L, player(engine).monsterCooldownNanos());
        clock.seconds(30);
        deploy(engine, 1);
        apply(engine, engine.proposeMonsterDeath(UUID.randomUUID(), round, player, 1, 2));
        Participant current = player(engine);
        assertEquals(MonsterRole.SKELETON, current.monsterRole());
        assertEquals(MonsterDimension.END, current.monsterDimension());
        assertEquals(2, current.monsterDeploymentSequence());
        var entitled = new Participant(player, "Entitled", current.role(), true, false, current.humanNanos(),
                0, 1, true, true, current.monsterRole(), current.monsterDimension(), current.monsterCooldownNanos(), 2);
        assertTrue(entitled.withSession("Renamed", false).revivalEntitled());
        assertEquals(MonsterRole.SKELETON, entitled.withRole(ParticipantRole.MONSTER_DEPLOYED).monsterRole());
        assertEquals(MonsterDimension.END, entitled.accrue(1).monsterDimension());
        assertEquals(2, entitled.start(0).monsterDeploymentSequence());
    }

    @Test void currentRoundLifeOutingOnlineAndPhaseAllGateEveryMutation() {
        var engine = monster();
        clock.seconds(30);
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterDeploy(UUID.randomUUID(), UUID.randomUUID(), player, 1, 0));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterDeploy(UUID.randomUUID(), round, player, 2, 0));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterDeploy(UUID.randomUUID(), round, player, 1, 3));
        deploy(engine, 0);
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterReturn(UUID.randomUUID(), round, player, 1, 0));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterDeath(UUID.randomUUID(), round, player, 1, 0));
        apply(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, player, 1, 1));
        apply(engine, engine.proposeOnline(UUID.randomUUID(), player, "Offline", false));
        clock.seconds(30);
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterSelection(UUID.randomUUID(), round, player, 1, 1,
                MonsterRole.CREEPER, MonsterDimension.NETHER));
        assertThrows(IllegalStateException.class, () -> deploy(engine, 1));
        apply(engine, engine.proposeFinish(UUID.randomUUID(), new RoundResult(RoundResult.Kind.FAILED, null, "fixture")));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterDeploy(UUID.randomUUID(), round, player, 1, 1));
        assertFalse(engine.pending());
    }

    @Test void outsideDisconnectReturnsToLobbyAndOfflineWaitFreezesUntilReconnect() {
        var engine = monster();
        clock.seconds(30);
        deploy(engine, 0);
        apply(engine, engine.proposeOnline(UUID.randomUUID(), player, "Outside", false));
        assertEquals(ParticipantRole.MONSTER_LOBBY, player(engine).role());
        clock.seconds(90);
        assertEquals(30_000_000_000L, player(engine).monsterCooldownNanos());
        apply(engine, engine.proposeOnline(UUID.randomUUID(), player, "Returned", true));
        clock.seconds(7);
        assertEquals(23_000_000_000L, player(engine).monsterCooldownNanos());
    }

    @Test void cleanRestartPreservesLobbyRemainderAndOutsideReconnectStartsThirtySeconds() {
        var engine = monster();
        clock.seconds(11);
        apply(engine, engine.proposeCheckpoint(UUID.randomUUID(), true));
        clock.seconds(100_000);
        var restored = RoundEngine.restore(RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(engine.committed())), clock);
        assertEquals(19_000_000_000L, player(restored).monsterCooldownNanos());
        apply(restored, restored.proposeOnline(UUID.randomUUID(), player, "Monster", true));
        clock.seconds(19);
        deploy(restored, 0);
        apply(restored, restored.proposeCheckpoint(UUID.randomUUID(), true));
        clock.seconds(100_000);
        restored = RoundEngine.restore(restored.committed(), clock);
        assertEquals(ParticipantRole.MONSTER_DEPLOYED, player(restored).role());
        apply(restored, restored.proposeOnline(UUID.randomUUID(), player, "Monster", true));
        assertEquals(ParticipantRole.MONSTER_LOBBY, player(restored).role());
        assertEquals(30_000_000_000L, player(restored).monsterCooldownNanos());
    }

    @Test void failedOrPendingDurableAdmissionCannotExposeOutsideIdentity() {
        var engine = monster();
        clock.seconds(30);
        var proposal = engine.proposeMonsterDeploy(UUID.randomUUID(), round, player, 1, 0);
        assertEquals(ParticipantRole.MONSTER_LOBBY, player(engine).role());
        assertThrows(IllegalStateException.class, () -> deploy(engine, 0));
        assertTrue(engine.reject(proposal, "disk failure"));
        assertEquals(RoundPhase.RECOVERY_REQUIRED, engine.view().phase());
        assertEquals(0, player(engine).monsterDeploymentSequence());
    }

    @Test void safetyCancellationRefundsCooldownButCannotCancelTheReplacementOuting() {
        var engine = monster();
        clock.seconds(30);
        deploy(engine, 0);
        apply(engine, engine.proposeMonsterDeployCancelled(UUID.randomUUID(), round, player, 1, 1));
        assertEquals(ParticipantRole.MONSTER_LOBBY, player(engine).role());
        assertEquals(0, player(engine).monsterCooldownNanos());
        deploy(engine, 1);
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterDeployCancelled(UUID.randomUUID(), round, player, 1, 1));
        assertEquals(2, player(engine).monsterDeploymentSequence());
    }

    @Test void committedSelectionCodecAndCopiesRetainQualificationAndCounters() {
        Participant chosen = new Participant(player, "Chosen", ParticipantRole.MONSTER_LOBBY, true, false,
                99, 5, 7, true, true, MonsterRole.CREEPER, MonsterDimension.NETHER, 9_999, 4);
        var snapshot = new RoundSnapshot(round, 1, 2, 3, RulesConfig.defaults(), RoundPhase.RUNNING,
                PauseReason.NO_ONLINE_HUMANS, 99, 0, Map.of(player, chosen), false, false, null);
        assertEquals(snapshot, RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(snapshot)));
        assertEquals(chosen.monsterRole(), chosen.withReady(false).monsterRole());
        assertEquals(chosen.monsterDimension(), chosen.withSession("Other", false).monsterDimension());
        assertEquals(chosen.monsterCooldownNanos(), chosen.leaveProtection().monsterCooldownNanos());
        assertTrue(chosen.monsterLobby(30).revivalEntitled());
        assertTrue(chosen.deployMonster().revivalEntitled());
    }
}
