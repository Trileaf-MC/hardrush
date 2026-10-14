package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DragonDeathArbiterTest {
    private final UUID round = UUID.randomUUID(), player = UUID.randomUUID(), dragon = UUID.randomUUID();

    @Test void humanFinalHitWinsUsingCreationIdentityAfterSameBatchDeathAndDisconnect() {
        var death = event(round, player, ParticipantRole.HUMAN_ACTIVE, 1);
        for (var currentRole : List.of(ParticipantRole.HUMAN_ACTIVE, ParticipantRole.HUMAN_RESPAWNING,
                ParticipantRole.MONSTER_LOBBY, ParticipantRole.MONSTER_DEPLOYED)) {
            var result = DragonDeathArbiter.adjudicate(snapshot(currentRole, 1, false, null), death, dragon);
            assertEquals(RoundResult.Kind.COMPLETED, result.kind());
            assertEquals(player, result.winner());
        }
    }

    @Test void humanArrowFromEarlierLifeRemainsHumanAfterRespawn() {
        assertEquals(player, DragonDeathArbiter.adjudicate(snapshot(ParticipantRole.HUMAN_ACTIVE, 2, true, null),
                event(round, player, ParticipantRole.HUMAN_ACTIVE, 1), dragon).winner());
    }

    @Test void monsterCreationIdentityCannotWinEvenWhenOwnerIsCurrentlyHuman() {
        var result = DragonDeathArbiter.adjudicate(snapshot(ParticipantRole.HUMAN_ACTIVE, 2, true, null),
                event(round, player, ParticipantRole.MONSTER_DEPLOYED, 1), dragon);
        assertNull(result.winner()); assertTrue(result.reason().contains("MONSTER_SOURCE"));
    }

    @Test void staleRoundCannotWinEvenForSameParticipant() {
        var result = DragonDeathArbiter.adjudicate(snapshot(ParticipantRole.HUMAN_ACTIVE, 1, true, null),
                event(UUID.randomUUID(), player, ParticipantRole.HUMAN_ACTIVE, 1), dragon);
        assertNull(result.winner()); assertTrue(result.reason().contains("STALE_ROUND_SOURCE"));
    }

    @Test void unknownActualDeathCompletesButRetainsPendingAttribution() {
        var death = new CombatEvent(round, UUID.randomUUID(), UUID.randomUUID(), 9, dragon, Optional.empty(),
                CombatEvent.Attribution.PENDING_VERIFICATION, "minecraft:explosion", Optional.empty(),
                Optional.empty(), 1, true, -200);
        var result = DragonDeathArbiter.adjudicate(snapshot(ParticipantRole.HUMAN_ACTIVE, 1, true, null), death, dragon);
        assertEquals(RoundResult.Kind.COMPLETED, result.kind()); assertNull(result.winner());
        assertTrue(result.reason().contains("PENDING_VERIFICATION"));
    }

    @Test void unadmittedOrFutureLifeSourceIsPendingRatherThanInventingWinner() {
        for (var source : List.of(event(round, UUID.randomUUID(), ParticipantRole.HUMAN_ACTIVE, 1),
                event(round, player, ParticipantRole.HUMAN_ACTIVE, 2))) {
            assertNull(DragonDeathArbiter.adjudicate(snapshot(ParticipantRole.HUMAN_ACTIVE, 1, true, null), source, dragon).winner());
        }
    }

    @Test void wrongDragonNonlethalOrWrongRoundEventIsRejected() {
        var death = event(round, player, ParticipantRole.HUMAN_ACTIVE, 1);
        var snapshot = snapshot(ParticipantRole.HUMAN_ACTIVE, 1, true, null);
        assertThrows(IllegalArgumentException.class, () -> DragonDeathArbiter.adjudicate(snapshot, death, UUID.randomUUID()));
        var nonlethal = new CombatEvent(round, death.eventId(), death.batchId(), death.sequence(), dragon, death.origin(),
                death.attribution(), death.rawDamageType(), death.rawDirectEntityId(), death.rawCausingEntityId(), 1, false, 50);
        assertThrows(IllegalArgumentException.class, () -> DragonDeathArbiter.adjudicate(snapshot, nonlethal, dragon));
        var oldEvent = new CombatEvent(UUID.randomUUID(), death.eventId(), death.batchId(), death.sequence(), dragon,
                Optional.empty(), CombatEvent.Attribution.PENDING_VERIFICATION, "unknown", Optional.empty(), Optional.empty(), 1, true, 50);
        assertThrows(IllegalArgumentException.class, () -> DragonDeathArbiter.adjudicate(snapshot, oldEvent, dragon));
    }

    @Test void alreadyTerminalResultCannotBeAdjudicatedAgain() {
        var result = new RoundResult(RoundResult.Kind.COMPLETED, player, "original");
        assertThrows(IllegalStateException.class, () -> DragonDeathArbiter.adjudicate(
                snapshot(ParticipantRole.HUMAN_ACTIVE, 1, true, result), event(round, player, ParticipantRole.HUMAN_ACTIVE, 1), dragon));
    }

    private RoundSnapshot snapshot(ParticipantRole role, int life, boolean online, RoundResult result) {
        return new RoundSnapshot(round, 1, 123, 1, RulesConfig.defaults().withMode(GameMode.NORMAL),
                result == null ? RoundPhase.RUNNING : RoundPhase.SETTLING,
                result == null ? PauseReason.NONE : PauseReason.SETTLED, 100, 0,
                Map.of(player, new Participant(player, "Alice", role, online, false, 100, 0, life, false)),
                false, false, result);
    }

    private CombatEvent event(UUID sourceRound, UUID owner, ParticipantRole identity, long life) {
        var origin = new CombatOrigin(sourceRound, UUID.randomUUID(), new CombatActor(owner, identity, life,
                identity.isMonster() ? 1 : 0), AttackKind.ARROW, Optional.of(UUID.randomUUID()), List.of());
        return new CombatEvent(round, UUID.randomUUID(), UUID.randomUUID(), 9, dragon, Optional.of(origin),
                CombatEvent.classify(round, Optional.of(origin)), "minecraft:arrow", origin.directEntityId(),
                Optional.of(owner), 1, true, 50);
    }
}
