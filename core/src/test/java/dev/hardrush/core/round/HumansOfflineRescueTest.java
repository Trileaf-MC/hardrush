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

/** When every remaining human leaves, the round waits like a rescue instead of idling forever. */
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
        assertEquals(60_000, e.view().remainingPhaseMillis());
        assertTrue(RoundTab.from(e.view(), 0, null).header().contains("人类全部离线"));
        seconds(30);
        apply(e, online(e, m, false)); // a monster leaving keeps the same deadline
        assertEquals(RoundPhase.RESCUE, e.view().phase());
        assertEquals(30_000, e.view().remainingPhaseMillis());
        seconds(30);
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
        seconds(59);
        apply(e, online(e, b, true));
        assertEquals(RoundPhase.RUNNING, e.view().phase());
        assertEquals(PauseReason.NONE, e.view().pauseReason());

        apply(e, online(e, b, false));
        assertEquals(60_000, e.view().remainingPhaseMillis(), "A new departure starts a fresh wait");
        seconds(61);
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
        apply(e, e.proposeNormalDeath(UUID.randomUUID(), a, 1));
        assertEquals(ParticipantRole.HUMAN_RESPAWNING, e.view().participants().get(a).role());
        apply(e, online(e, b, false));
        assertEquals(RoundPhase.RUNNING, e.view().phase());
    }

    @Test void restoredRoundWithEveryoneOfflineKeepsWaitingForHumans() {
        var e = engine(GameMode.HARDCORE, human(a, false), monster(false, false));
        apply(e, online(e, m, true));
        seconds(600);
        apply(e, e.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.RUNNING, e.view().phase());
        assertEquals(PauseReason.NO_ONLINE_HUMANS, e.view().pauseReason());
    }

    @Test void tabCountsOnlyConnectedMonstersAsOnline() {
        var e = engine(GameMode.HARDCORE, human(a, true), monster(true, false));
        assertTrue(RoundTab.from(e.view(), 0, null).footer().contains("人类 1 · 怪物 1\n"));
        apply(e, online(e, m, false));
        assertTrue(RoundTab.from(e.view(), 0, null).footer().contains("人类 1 · 怪物 0（离线 1）"));
    }
}
