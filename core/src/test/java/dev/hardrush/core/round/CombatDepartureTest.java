package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CombatDepartureTest {
    final UUID round = UUID.randomUUID(), human = UUID.randomUUID(), attacker = UUID.randomUUID(), monster = UUID.randomUUID();

    RoundSnapshot snapshot(GameMode mode, RoundPhase phase, ParticipantRole role) {
        var players = new LinkedHashMap<UUID, Participant>();
        players.put(human, new Participant(human, "Alice", role, true, false, 0, 0, 1, false));
        players.put(attacker, new Participant(attacker, "Bob", ParticipantRole.HUMAN_ACTIVE, true, false, 0, 0, 1, false));
        players.put(monster, new Participant(monster, "Carol", ParticipantRole.MONSTER_DEPLOYED, true, false,
                0, 0, 1, false, MonsterRole.SKELETON, MonsterDimension.OVERWORLD, 0, 1));
        return new RoundSnapshot(round, 1, 99, 0, RulesConfig.defaults().withMode(mode), phase,
                phase == RoundPhase.RUNNING || phase == RoundPhase.RESCUE ? PauseReason.NONE : PauseReason.NOT_RUNNING,
                0, 0, players, true, false, null);
    }

    RoundSnapshot playing() { return snapshot(GameMode.HARDCORE, RoundPhase.RUNNING, ParticipantRole.HUMAN_ACTIVE); }

    Optional<CombatOrigin> by(UUID player, ParticipantRole role, UUID roundId) {
        var actor = new CombatActor(player, role, 1, role == ParticipantRole.MONSTER_DEPLOYED ? 1 : 0);
        return Optional.of(new CombatOrigin(roundId, UUID.randomUUID(), actor, AttackKind.MELEE, Optional.empty(), List.of()));
    }

    @Test void aHumanOrMonsterCreditMakesLeavingLethal() {
        assertTrue(CombatDeparture.countsAsLethal(playing(), human, by(attacker, ParticipantRole.HUMAN_ACTIVE, round)));
        assertTrue(CombatDeparture.countsAsLethal(playing(), human, by(monster, ParticipantRole.MONSTER_DEPLOYED, round)));
        assertTrue(CombatDeparture.countsAsLethal(snapshot(GameMode.HARDCORE, RoundPhase.RESCUE, ParticipantRole.HUMAN_ACTIVE),
                human, by(attacker, ParticipantRole.HUMAN_ACTIVE, round)));
    }

    @Test void noCreditSelfHarmOrAnotherRoundLeavesAsBefore() {
        assertFalse(CombatDeparture.countsAsLethal(playing(), human, Optional.empty()));
        assertFalse(CombatDeparture.countsAsLethal(playing(), human, by(human, ParticipantRole.HUMAN_ACTIVE, round)));
        assertFalse(CombatDeparture.countsAsLethal(playing(), human, by(attacker, ParticipantRole.HUMAN_ACTIVE, UUID.randomUUID())));
    }

    @Test void onlyAHardcoreHumanInPlayIsCovered() {
        var credit = by(attacker, ParticipantRole.HUMAN_ACTIVE, round);
        assertFalse(CombatDeparture.countsAsLethal(snapshot(GameMode.NORMAL, RoundPhase.RUNNING, ParticipantRole.HUMAN_ACTIVE), human, credit));
        assertFalse(CombatDeparture.countsAsLethal(snapshot(GameMode.HARDCORE, RoundPhase.WAITING, ParticipantRole.HUMAN_ACTIVE), human, credit));
        assertFalse(CombatDeparture.countsAsLethal(snapshot(GameMode.HARDCORE, RoundPhase.RUNNING, ParticipantRole.MONSTER_DEPLOYED), human, credit));
        assertFalse(CombatDeparture.countsAsLethal(playing(), UUID.randomUUID(), credit));
    }

    @Test void theRuleBookWindowIsVanillasFiveSeconds() { assertEquals(5, CombatDeparture.windowSeconds()); }
}
