package dev.hardrush.revival;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.revival.TotemProjection.Holder;
import dev.hardrush.revival.TotemProjection.Identity;
import dev.hardrush.revival.TotemProjection.State;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TotemProjectionTest {
    private static final UUID ROUND = UUID.randomUUID(), PLAYER = UUID.randomUUID();

    private static Participant participant(ParticipantRole role, boolean online, int life, int charges, int sortie, boolean entering) {
        return new Participant(PLAYER, "Holder", role, online, false, 0, role == ParticipantRole.MONSTER_LOBBY ? -1 : 0, life,
                charges, MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, sortie, Map.of(), MonsterWallet.EMPTY, 0, entering);
    }

    private static Participant human(int charges) { return participant(ParticipantRole.HUMAN_ACTIVE, true, 2, charges, 1, false); }

    private static RoundSnapshot round(RoundPhase phase, GameMode mode, Participant participant) {
        RoundResult result = switch (phase) {
            case SETTLING, CELEBRATING, ROTATING -> new RoundResult(RoundResult.Kind.FAILED, null, "fixture");
            default -> null;
        };
        return new RoundSnapshot(ROUND, 1, 1, 0, RulesConfig.defaults().withMode(mode), phase, PauseReason.NONE, 0, 0,
                participant == null ? Map.of() : Map.of(PLAYER, participant), false, false, result);
    }

    private static TotemProjection.Decision decide(RoundSnapshot round, boolean locked, boolean body) {
        return TotemProjection.decide(round, PLAYER, false, locked, body, 0);
    }

    @Test void anEligibleHumanSeesTheCommittedBalanceLessInFlightSaves() {
        var decision = TotemProjection.decide(round(RoundPhase.RUNNING, GameMode.HARDCORE, human(5)), PLAYER, false, false, true, 2);
        assertEquals(State.PROJECT, decision.state());
        assertEquals(3, decision.charges());
        assertEquals(new Identity(ROUND, ParticipantRole.HUMAN_ACTIVE, 2, 1), decision.identity());
        assertEquals(Holder.HUMAN, decision.identity().holder());
        assertEquals(0, TotemProjection.decide(round(RoundPhase.RESCUE, GameMode.HARDCORE, human(1)), PLAYER, false, false, true, 4).charges());
    }

    @Test void aCampMonsterIsProjectedAsItsOwnHolder() {
        var monster = participant(ParticipantRole.MONSTER_LOBBY, true, 2, 4, 3, false);
        var decision = decide(round(RoundPhase.RUNNING, GameMode.HARDCORE, monster), false, true);
        assertEquals(State.PROJECT, decision.state());
        assertEquals(Holder.CAMP_MONSTER, decision.identity().holder());
        assertEquals(3, decision.identity().sortie());
    }

    @Test void theSameLifeOnlyBrieflyIneligibleIsFrozen() {
        var identity = new Identity(ROUND, ParticipantRole.HUMAN_ACTIVE, 2, 1);
        for (var decision : new TotemProjection.Decision[] {
                decide(round(RoundPhase.RUNNING, GameMode.HARDCORE, human(3)), true, true),
                decide(round(RoundPhase.RUNNING, GameMode.HARDCORE, human(3)), false, false),
                decide(round(RoundPhase.RUNNING, GameMode.HARDCORE, participant(ParticipantRole.HUMAN_ACTIVE, false, 2, 3, 1, false)), false, true),
                decide(round(RoundPhase.RUNNING, GameMode.HARDCORE, participant(ParticipantRole.HUMAN_ACTIVE, false, 2, 3, 1, true)), false, true),
                decide(round(RoundPhase.RECOVERY_REQUIRED, GameMode.HARDCORE, human(3)), false, true)}) {
            assertEquals(State.FREEZE, decision.state());
            assertEquals(identity, decision.identity());
        }
    }

    @Test void aRealChangeOfIdentityClears() {
        assertEquals(State.CLEAR, decide(null, false, true).state());
        assertEquals(State.CLEAR, decide(round(RoundPhase.RUNNING, GameMode.HARDCORE, null), false, true).state());
        assertEquals(State.CLEAR, decide(round(RoundPhase.RUNNING, GameMode.NORMAL, human(3)), false, true).state());
        for (var role : new ParticipantRole[] {ParticipantRole.HUMAN_RESPAWNING, ParticipantRole.MONSTER_DEPLOYED,
                ParticipantRole.WITHDRAWN, ParticipantRole.DISQUALIFIED, ParticipantRole.LOBBY}) {
            var p = participant(role, true, role == ParticipantRole.LOBBY ? 0 : 2, 3, 1, false);
            assertEquals(State.CLEAR, decide(round(RoundPhase.RUNNING, GameMode.HARDCORE, p), false, true).state(), role.name());
        }
        for (var phase : new RoundPhase[] {RoundPhase.SETTLING, RoundPhase.CELEBRATING, RoundPhase.ROTATING, RoundPhase.WAITING}) {
            assertEquals(State.CLEAR, decide(round(phase, GameMode.HARDCORE, human(3)), false, true).state(), phase.name());
        }
        assertEquals(State.CLEAR, TotemProjection.decide(round(RoundPhase.RUNNING, GameMode.HARDCORE, human(3)), PLAYER, true, false, true, 0).state(),
                "A spectator body (an observing camp monster) holds nothing");
    }

    @Test void identityFollowsLifeAndSortie() {
        var before = decide(round(RoundPhase.RUNNING, GameMode.HARDCORE, participant(ParticipantRole.MONSTER_LOBBY, true, 2, 1, 3, false)), false, true);
        var after = decide(round(RoundPhase.RUNNING, GameMode.HARDCORE, participant(ParticipantRole.MONSTER_LOBBY, true, 2, 1, 4, false)), false, true);
        assertNotEquals(before.identity(), after.identity(), "A camp stay after another sortie is a new identity");
        assertThrows(IllegalArgumentException.class, () -> new Identity(ROUND, ParticipantRole.MONSTER_DEPLOYED, 1, 1));
    }
}
