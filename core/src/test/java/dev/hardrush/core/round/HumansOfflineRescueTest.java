package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.record.RoundResultText;
import dev.hardrush.core.record.RoundTab;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * A live round with humans either has one of them connected or is waiting for them on a deadline,
 * whichever path led there: a departure, a restart, or a life that left while still entering.
 */
class HumansOfflineRescueTest {
    private static final long SECOND = 1_000_000_000L;
    private final UUID round = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID(), m = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong();

    private Participant human(UUID id, boolean online) {
        return new Participant(id, "Human" + id.toString().substring(0, 4), ParticipantRole.HUMAN_ACTIVE, online, false, 0, 0, 1, false);
    }
    private Participant monster(boolean online, boolean entitled) {
        return new Participant(m, "Monster", ParticipantRole.MONSTER_LOBBY, online, false, 0, 0, 1, entitled,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0, Map.of());
    }
    /** A cleanly restored round: everyone starts offline and the online ones then connect. */
    private RoundEngine engine(GameMode mode, Participant... participants) {
        var players = new LinkedHashMap<UUID, Participant>();
        for (var p : participants) players.put(p.id(), p);
        var stored = new RoundSnapshot(round, 1, 123, 0, RulesConfig.defaults().withMode(mode), RoundPhase.RUNNING,
                PauseReason.SERVER_STOPPED, 0, 0, players, true, false, null);
        var e = RoundEngine.restore(stored, clock::get);
        for (var p : participants) if (p.online()) apply(e, online(e, p.id(), true));
        return e;
    }
    private static void apply(RoundEngine e, RoundTransition t) { assertTrue(e.accept(t)); }
    private static RoundTransition online(RoundEngine e, UUID id, boolean value) {
        return e.proposeOnline(UUID.randomUUID(), id, e.committed().participants().get(id).name(), value);
    }
    private void seconds(long value) { clock.addAndGet(value * SECOND); }

    @Test void lastHumanLeavingStartsARescueThatExpiresIntoANewRound() {
        var e = engine(GameMode.HARDCORE, human(a, true), monster(true, false));
        apply(e, online(e, a, false));
        assertEquals(RoundPhase.RESCUE, e.view().phase());
        assertTrue(e.view().awaitingReconnect());
        assertEquals(180_000, e.view().remainingPhaseMillis(), "Waiting for a reconnect has its own, longer deadline");
        assertTrue(RoundTab.from(e.view(), 0, null).header().contains("人类全部离线"));
        seconds(30);
        apply(e, online(e, m, false)); // a monster leaving keeps the same deadline
        assertEquals(RoundPhase.RESCUE, e.view().phase());
        assertEquals(150_000, e.view().remainingPhaseMillis());
        assertTrue(RoundTab.from(e.view(), 0, null).footer().contains("人类 0（离线 1）· 怪物 0（离线 1）"));
        seconds(150);
        assertTrue(e.rescueExpiredAt(clock.get()));
        apply(e, e.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.SETTLING, e.view().phase());
        assertEquals("HUMANS_OFFLINE", e.view().result().reason());
        assertEquals("人类全部离线，救场时间内没有人回来", RoundResultText.reason(e.view().result()));
        seconds(e.view().remainingPhaseMillis() / 1000);
        apply(e, e.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.ROTATING, e.view().phase());
    }

    @Test void reconnectBeforeTheDeadlineResumesTheRaceButNotAfterIt() {
        var e = engine(GameMode.NORMAL, human(a, true), human(b, false));
        apply(e, online(e, a, false));
        assertEquals(RoundPhase.RESCUE, e.view().phase());
        seconds(179);
        apply(e, online(e, b, true));
        assertEquals(RoundPhase.RUNNING, e.view().phase());
        assertEquals(PauseReason.NONE, e.view().pauseReason());

        apply(e, online(e, b, false));
        assertEquals(180_000, e.view().remainingPhaseMillis(), "A new departure starts a fresh wait");
        seconds(181);
        apply(e, online(e, a, true));
        assertEquals(RoundPhase.RESCUE, e.view().phase(), "A late reconnect cannot reopen an expired rescue");
        apply(e, e.proposeTick(UUID.randomUUID()));
        assertEquals("HUMANS_OFFLINE", e.view().result().reason());
    }

    @Test void revivalAndMidRoundJoinAlsoEndTheWait() {
        var revived = engine(GameMode.HARDCORE, human(a, true), monster(true, true));
        apply(revived, online(revived, a, false));
        apply(revived, revived.proposeRevival(UUID.randomUUID(), round, m, 1, 0));
        assertEquals(RoundPhase.RUNNING, revived.view().phase());

        var joined = engine(GameMode.HARDCORE, human(a, true));
        apply(joined, online(joined, a, false));
        apply(joined, joined.proposeJoin(UUID.randomUUID(), b, "Late"));
        assertEquals(RoundPhase.RUNNING, joined.view().phase());
    }

    @Test void lastConnectedHumanDyingOrWithdrawingWaitsForTheOfflineOnes() {
        var died = engine(GameMode.HARDCORE, human(a, true), human(b, false));
        var lethal = new RoundDeath(a, 1, 0, Optional.empty());
        apply(died, died.proposeDeathBatch(UUID.randomUUID(), round, List.of(lethal), null));
        assertEquals(RoundPhase.RESCUE, died.view().phase());
        assertTrue(died.view().awaitingReconnect());

        var withdrew = engine(GameMode.NORMAL, human(a, true), human(b, false));
        apply(withdrew, withdrew.proposeParticipationExit(UUID.randomUUID(), round, a, ParticipantRole.HUMAN_ACTIVE, 1, 0,
                ParticipantRole.WITHDRAWN));
        assertEquals(RoundPhase.RESCUE, withdrew.view().phase());
        apply(withdrew, withdrew.proposeParticipationExit(UUID.randomUUID(), round, b, ParticipantRole.HUMAN_ACTIVE, 1, 0,
                ParticipantRole.DISQUALIFIED));
        assertEquals(RoundPhase.SETTLING, withdrew.view().phase(), "Once nobody can return the round ends as before");
        assertEquals("NO_ELIGIBLE_PARTICIPANTS", withdrew.view().result().reason());
    }

    @Test void humanOnTheRespawnScreenStillKeepsTheRaceRunning() {
        var e = engine(GameMode.NORMAL, human(a, true), human(b, true));
        apply(e, e.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(new RoundDeath(a, 1, 0, Optional.empty())), null));
        assertEquals(ParticipantRole.HUMAN_RESPAWNING, e.view().participants().get(a).role());
        apply(e, online(e, b, false));
        assertEquals(RoundPhase.RUNNING, e.view().phase());
    }

    @Test void restoredRoundStaysPausedUntilSomebodyReturnsAndThenWaitsForItsHumans() {
        var e = engine(GameMode.HARDCORE, human(a, false), monster(false, false));
        seconds(600);
        apply(e, e.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.RUNNING, e.view().phase(), "An empty server after a restart ends nothing");
        assertEquals(PauseReason.NO_ONLINE_HUMANS, e.view().pauseReason());

        apply(e, online(e, m, true));
        assertEquals(RoundPhase.RESCUE, e.view().phase(), "A returning monster is not left waiting forever");
        assertTrue(e.view().awaitingReconnect());
        assertEquals(180_000, e.view().remainingPhaseMillis());
        seconds(180);
        apply(e, e.proposeTick(UUID.randomUUID()));
        assertEquals("HUMANS_OFFLINE", e.view().result().reason());

        var returned = engine(GameMode.HARDCORE, human(a, false), monster(true, false));
        assertEquals(RoundPhase.RESCUE, returned.view().phase());
        seconds(170);
        apply(returned, online(returned, a, true));
        assertEquals(RoundPhase.RUNNING, returned.view().phase());
        assertEquals(PauseReason.NONE, returned.view().pauseReason());
    }

    @Test void waitInterruptedByARestartBeginsAgainInFull() {
        var players = new LinkedHashMap<UUID, Participant>();
        players.put(a, human(a, false));
        players.put(m, monster(false, false));
        var stored = new RoundSnapshot(round, 1, 123, 0, RulesConfig.defaults().withMode(GameMode.HARDCORE), RoundPhase.RESCUE,
                PauseReason.SERVER_STOPPED, 0, 5 * SECOND, players, true, false, null);
        var e = RoundEngine.restore(stored, clock::get);
        apply(e, e.proposeCheckpoint(UUID.randomUUID(), false));
        seconds(600);
        assertEquals(RoundPhase.RUNNING, e.view().phase(), "Nobody could reconnect while the server was down");
        apply(e, online(e, m, true));
        assertEquals(180_000, e.view().remainingPhaseMillis());
    }

    private RoundEngine restoredWait(long remainingSeconds, Participant... participants) {
        var players = new LinkedHashMap<UUID, Participant>();
        for (var p : participants) players.put(p.id(), p);
        var stored = new RoundSnapshot(round, 1, 123, 0, RulesConfig.defaults().withMode(GameMode.HARDCORE), RoundPhase.RESCUE,
                PauseReason.SERVER_STOPPED, 0, remainingSeconds * SECOND, players, true, false, null);
        return RoundEngine.restore(stored, clock::get);
    }

    @Test void waitThatExpiredBeforeAStopStaysExpiredAfterTheRestart() {
        var expired = restoredWait(0, human(a, false), monster(false, false));
        apply(expired, online(expired, a, true));
        assertEquals(RoundPhase.RESCUE, expired.view().phase(), "A late reconnect cannot reopen the round across a restart");
        assertTrue(expired.rescueExpiredAt(clock.get()));
        apply(expired, expired.proposeTick(UUID.randomUUID()));
        assertEquals("HUMANS_OFFLINE", expired.view().result().reason());

        var open = restoredWait(1, human(a, false), monster(false, false));
        apply(open, online(open, a, true));
        assertEquals(RoundPhase.RUNNING, open.view().phase(), "One second left is still an open wait");
        assertEquals(PauseReason.NONE, open.view().pauseReason());
    }

    @Test void onlyCompetitorsReturningStartTheWaitAfterARestart() {
        var spectator = new Participant(b, "Spectator", ParticipantRole.WITHDRAWN, false, false, 0, 0, 1, false);
        var lobbyOnly = new Participant(UUID.randomUUID(), "Lobby", ParticipantRole.LOBBY, false, false, 0, -1, 0, false);
        var e = engine(GameMode.HARDCORE, human(a, false), monster(false, false), spectator, lobbyOnly);
        apply(e, online(e, b, true));
        apply(e, online(e, lobbyOnly.id(), true));
        seconds(600);
        apply(e, e.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.RUNNING, e.view().phase(), "A spectator or a lobby-only entry cannot fail the round");
        assertEquals(PauseReason.NO_ONLINE_HUMANS, e.view().pauseReason());
        apply(e, online(e, b, false));
        assertEquals(RoundPhase.RUNNING, e.view().phase(), "Nor can one leaving again");
        apply(e, online(e, m, true));
        assertEquals(RoundPhase.RESCUE, e.view().phase(), "A returning monster still starts the wait");
    }

    @Test void waitTurningIntoATotemRescueNeverRunsLongerThanARescue() {
        var e = engine(GameMode.HARDCORE, human(a, true), monster(true, true));
        apply(e, online(e, a, false));
        seconds(10);
        apply(e, e.proposeParticipationExit(UUID.randomUUID(), round, a, ParticipantRole.HUMAN_ACTIVE, 1, 0, ParticipantRole.DISQUALIFIED));
        assertEquals(RoundPhase.RESCUE, e.view().phase());
        assertFalse(e.view().awaitingReconnect());
        assertEquals(60_000, e.view().remainingPhaseMillis(), "Capped at the rescue length");

        var late = engine(GameMode.HARDCORE, human(a, true), monster(true, true));
        apply(late, online(late, a, false));
        seconds(150);
        apply(late, late.proposeParticipationExit(UUID.randomUUID(), round, a, ParticipantRole.HUMAN_ACTIVE, 1, 0, ParticipantRole.WITHDRAWN));
        assertEquals(30_000, late.view().remainingPhaseMillis(), "A sanction never extends the wait either");
    }

    @Test void lifeThatLeavesWhileStillEnteringStartsTheWaitToo() {
        var revived = engine(GameMode.HARDCORE, human(a, true), monster(true, true));
        apply(revived, online(revived, a, false));
        seconds(100);
        apply(revived, revived.proposeAdmissionRevival(UUID.randomUUID(), round, m, 1, 0));
        var entering = revived.view().participants().get(m);
        assertFalse(entering.online());
        assertTrue(entering.connected(), "Present, though the profile adapter has not admitted the body yet");
        assertEquals(RoundPhase.RUNNING, revived.view().phase());
        assertTrue(RoundTab.from(revived.view(), 0, null).footer().contains("人类 1（离线 1）· 怪物 0"));
        apply(revived, online(revived, m, false));
        assertEquals(RoundPhase.RESCUE, revived.view().phase());
        assertEquals(180_000, revived.view().remainingPhaseMillis());

        var joined = engine(GameMode.HARDCORE, human(a, true));
        apply(joined, online(joined, a, false));
        apply(joined, joined.proposeAdmissionJoin(UUID.randomUUID(), b, "Late"));
        assertEquals(RoundPhase.RUNNING, joined.view().phase());
        apply(joined, online(joined, b, false));
        assertEquals(RoundPhase.RESCUE, joined.view().phase());
        apply(joined, online(joined, b, true));
        assertEquals(RoundPhase.RUNNING, joined.view().phase());
        assertEquals(PauseReason.NONE, joined.view().pauseReason());
    }

    @Test void soleStarterLeavingBeforeAdmissionDoesNotStrandTheRound() {
        var e = RoundEngine.create(round, 1, 123, RulesConfig.defaults().withMode(GameMode.HARDCORE), clock::get);
        apply(e, e.proposeWorldReady(UUID.randomUUID()));
        apply(e, e.proposeJoin(UUID.randomUUID(), a, "Solo"));
        apply(e, e.proposeReady(UUID.randomUUID(), a, true));
        apply(e, e.proposeAdmissionAdminStart(UUID.randomUUID()));
        assertEquals(RoundPhase.RUNNING, e.view().phase());
        apply(e, online(e, a, false));
        assertEquals(RoundPhase.RESCUE, e.view().phase());
        assertTrue(e.view().awaitingReconnect());
    }

    @Test void tabCountsOnlyConnectedMonstersAsOnline() {
        var e = engine(GameMode.HARDCORE, human(a, true), monster(true, false));
        assertTrue(RoundTab.from(e.view(), 0, null).footer().contains("人类 1 · 怪物 1\n"));
        apply(e, online(e, m, false));
        assertTrue(RoundTab.from(e.view(), 0, null).footer().contains("人类 1 · 怪物 0（离线 1）"));
    }
}
