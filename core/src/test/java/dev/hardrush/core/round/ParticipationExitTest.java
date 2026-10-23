package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ParticipationExitTest {
    private final UUID round = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID(), m = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong();
    private Participant human(UUID id) { return new Participant(id, "Human" + id.toString().substring(0, 4),
            ParticipantRole.HUMAN_ACTIVE, true, false, 70, 0, 2, 3, true,
            MonsterRole.CREEPER, MonsterDimension.NETHER, 0, 4, Map.of(MonsterSkill.ZOMBIE_SUMMON, 80_000L)); }
    private Participant monster() { return new Participant(m, "Monster", ParticipantRole.MONSTER_LOBBY,
            true, false, 50, 0, 1, 2, true, MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 3, Map.of()); }
    private RoundEngine engine(GameMode mode, RoundPhase phase, long remaining, Participant... participants) {
        var players = new LinkedHashMap<UUID, Participant>();
        for (var p : participants) players.put(p.id(), p);
        var stored = new RoundSnapshot(round, 1, 123, 0, RulesConfig.defaults().withMode(mode), phase,
                PauseReason.SERVER_STOPPED, 100, remaining, players, true, false, null);
        var e = RoundEngine.restore(stored, clock::get);
        for (var p : participants) if (p.online()) apply(e, e.proposeOnline(UUID.randomUUID(), p.id(), p.name(), true));
        return e;
    }
    private RoundTransition exit(RoundEngine e, UUID id, ParticipantRole destination) {
        var p = e.committed().participants().get(id);
        return e.proposeParticipationExit(UUID.randomUUID(), round, id, p.role(), p.lifeSequence(), p.monsterDeploymentSequence(), destination);
    }
    private static void apply(RoundEngine e, RoundTransition t) { assertTrue(e.accept(t)); }

    @Test void voluntaryExitStopsOnlyThatPlayersClockAndRetainsHistoryWithoutDeathRewards() {
        var e = engine(GameMode.HARDCORE, RoundPhase.RUNNING, 0, human(a), human(b), monster());
        clock.addAndGet(5_000_000_000L);
        var proposal = exit(e, a, ParticipantRole.WITHDRAWN);
        assertEquals(ParticipantRole.HUMAN_ACTIVE, e.committed().participants().get(a).role());
        apply(e, proposal);
        var exited = e.committed().participants().get(a);
        assertEquals(5_000_000_070L, exited.humanNanos());
        assertEquals(2, exited.lifeSequence()); assertEquals(4, exited.monsterDeploymentSequence());
        assertEquals(0, exited.revivalCharges()); assertTrue(exited.monsterSkillReadyAtMillis().isEmpty());
        assertEquals(3, e.committed().participants().get(b).revivalCharges());
        assertEquals(2, e.committed().participants().get(m).revivalCharges());
        assertEquals(1, e.view().onlineHumans()); assertFalse(e.view().timingUncertain());
        clock.addAndGet(2_000_000_000L);
        assertEquals(exited.humanNanos(), e.view().participants().get(a).humanNanos());
        assertEquals(7_000_000_070L, e.view().participants().get(b).humanNanos());
    }

    @Test void disqualificationRetainsOfflineIdentityAndMarksTheRoundForReview() {
        var e = engine(GameMode.NORMAL, RoundPhase.RUNNING, 0, human(a).withSession("Offline", false), human(b));
        apply(e, exit(e, a, ParticipantRole.DISQUALIFIED));
        assertTrue(e.view().timingUncertain());
        assertFalse(e.view().participants().get(a).online());
        assertEquals(ParticipantRole.DISQUALIFIED, e.view().participants().get(a).role());
        assertEquals(RoundPhase.RUNNING, e.view().phase());
    }

    @Test void excludedPlayersCannotReenterThroughAnyExistingLifeOrMonsterEntrance() {
        for (var role : new ParticipantRole[]{ParticipantRole.WITHDRAWN, ParticipantRole.DISQUALIFIED}) {
            var e = engine(GameMode.HARDCORE, RoundPhase.RUNNING, 0, human(a), human(b), monster());
            apply(e, exit(e, a, role));
            assertThrows(IllegalStateException.class, () -> e.proposeJoin(UUID.randomUUID(), a, "Again"));
            assertThrows(IllegalStateException.class, () -> e.proposeRevival(UUID.randomUUID(), round, a, 2, 4));
            assertThrows(IllegalStateException.class, () -> e.proposeAdminGrantRevival(UUID.randomUUID(), round, a, 2, 0, 1));
            assertThrows(IllegalStateException.class, () -> e.proposeNormalRespawn(UUID.randomUUID(), a, 2));
            assertFalse(e.pending());
        }
    }

    @Test void lastHumanExitUsesRescueAndRemovingTheRemainingReviverEndsTheRound() {
        var e = engine(GameMode.HARDCORE, RoundPhase.RUNNING, 0, human(a), monster());
        apply(e, exit(e, a, ParticipantRole.WITHDRAWN));
        assertEquals(RoundPhase.RESCUE, e.view().phase());
        assertEquals(60_000, e.view().remainingPhaseMillis());
        clock.addAndGet(7_000_000_000L);
        apply(e, exit(e, m, ParticipantRole.DISQUALIFIED));
        assertEquals(RoundPhase.SETTLING, e.view().phase());
        assertEquals("NO_ELIGIBLE_PARTICIPANTS", e.view().result().reason());
        assertEquals(RoundResult.Kind.FAILED, e.view().result().kind());
    }

    @Test void ordinaryModeCannotRemainPausedForeverAfterItsLastHumanWithdraws() {
        var e = engine(GameMode.NORMAL, RoundPhase.RUNNING, 0, human(a));
        apply(e, exit(e, a, ParticipantRole.WITHDRAWN));
        assertEquals(RoundPhase.SETTLING, e.view().phase());
        assertEquals(0, e.view().onlineHumans());
    }

    @Test void aPendingNormalRespawnCanBeCancelledWithoutCreatingAnotherLife() {
        var e = engine(GameMode.NORMAL, RoundPhase.RUNNING, 0, human(a).withRole(ParticipantRole.HUMAN_RESPAWNING), human(b));
        apply(e, exit(e, a, ParticipantRole.DISQUALIFIED));
        assertEquals(2, e.view().participants().get(a).lifeSequence());
        assertEquals(RoundPhase.RUNNING, e.view().phase());
    }

    @Test void cancellingTheLastPreparedPlayerReturnsToWaitingAndBlocksAssembly() {
        var lobby = new Participant(a, "Prepared", ParticipantRole.LOBBY, true, true, 0, -1, 0, false);
        var e = engine(GameMode.NORMAL, RoundPhase.COUNTDOWN, 20_000_000_000L, lobby);
        apply(e, exit(e, a, ParticipantRole.DISQUALIFIED));
        assertEquals(RoundPhase.WAITING, e.view().phase());
        assertFalse(e.view().participants().get(a).ready());
        assertThrows(IllegalStateException.class, () -> e.proposeAssembly(UUID.randomUUID(), round, a, "Prepared", true));
    }

    @Test void exitDoesNotResetAnExistingRescueDeadline() {
        var e = engine(GameMode.HARDCORE, RoundPhase.RESCUE, 30_000_000_000L, monster(),
                new Participant(a, "Visitor", ParticipantRole.LOBBY, true, false, 0, -1, 0, false));
        clock.addAndGet(5_000_000_000L);
        apply(e, exit(e, a, ParticipantRole.WITHDRAWN));
        assertEquals(25_000, e.view().remainingPhaseMillis());
    }

    @Test void sanctionsCanUpgradeWithdrawalButWithdrawalCannotEraseSanctions() {
        var e = engine(GameMode.NORMAL, RoundPhase.RUNNING, 0, human(a), human(b));
        apply(e, exit(e, a, ParticipantRole.WITHDRAWN));
        assertThrows(IllegalStateException.class, () -> exit(e, a, ParticipantRole.WITHDRAWN));
        apply(e, exit(e, a, ParticipantRole.DISQUALIFIED));
        assertThrows(IllegalStateException.class, () -> exit(e, a, ParticipantRole.WITHDRAWN));
        assertThrows(IllegalStateException.class, () -> exit(e, a, ParticipantRole.DISQUALIFIED));
    }

    @Test void staleIdentityAndUnknownExitKindsCannotAlterTheRoster() {
        var e = engine(GameMode.NORMAL, RoundPhase.RUNNING, 0, human(a), human(b));
        assertThrows(IllegalStateException.class, () -> e.proposeParticipationExit(UUID.randomUUID(), UUID.randomUUID(), a,
                ParticipantRole.HUMAN_ACTIVE, 2, 4, ParticipantRole.DISQUALIFIED));
        assertThrows(IllegalStateException.class, () -> e.proposeParticipationExit(UUID.randomUUID(), round, a,
                ParticipantRole.HUMAN_ACTIVE, 1, 4, ParticipantRole.DISQUALIFIED));
        assertThrows(IllegalArgumentException.class, () -> e.proposeParticipationExit(UUID.randomUUID(), round, a,
                ParticipantRole.HUMAN_ACTIVE, 2, 4, ParticipantRole.LOBBY));
        assertFalse(e.pending()); assertEquals(2, e.view().onlineHumans());
    }

    @Test void snapshotRestartKeepsExclusionsWhileTheNextRoundStartsFresh() {
        var e = engine(GameMode.NORMAL, RoundPhase.RUNNING, 0, human(a), human(b));
        apply(e, exit(e, a, ParticipantRole.DISQUALIFIED));
        apply(e, e.proposeCheckpoint(UUID.randomUUID(), true));
        var stored = RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(e.committed()));
        var restored = RoundEngine.restore(stored, clock::get);
        apply(restored, restored.proposeOnline(UUID.randomUUID(), a, "Reconnected", true));
        assertEquals(ParticipantRole.DISQUALIFIED, restored.view().participants().get(a).role());
        assertThrows(IllegalStateException.class, () -> restored.proposeJoin(UUID.randomUUID(), a, "Reconnected"));
        var next = RoundEngine.create(UUID.randomUUID(), 2, 123, stored.rules(), clock::get);
        apply(next, next.proposeWorldReady(UUID.randomUUID()));
        apply(next, next.proposeJoin(UUID.randomUUID(), a, "Reconnected"));
        assertEquals(ParticipantRole.LOBBY, next.view().participants().get(a).role());
    }
}
