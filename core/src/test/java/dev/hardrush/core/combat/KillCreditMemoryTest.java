package dev.hardrush.core.combat;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.round.ParticipantRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KillCreditMemoryTest {
    private final UUID round = UUID.randomUUID();
    private final UUID victim = UUID.randomUUID();

    private CombatOrigin origin(UUID owner) {
        return new CombatOrigin(round, UUID.randomUUID(), new CombatActor(owner, ParticipantRole.HUMAN_ACTIVE, 1, 0),
                AttackKind.MELEE, Optional.empty(), List.of());
    }

    @Test void unattributedDeathWithinTheVanillaWindowCreditsTheLastAttacker() {
        var memory = new KillCreditMemory();
        var hit = origin(UUID.randomUUID());
        memory.hurt(victim, hit, 1_000);
        assertEquals(Optional.of(hit), memory.died(victim, Optional.empty(), 1_000 + KillCreditMemory.WINDOW_TICKS));
    }

    @Test void memoryExpiresAfterOneHundredTicksAndIsForgottenAtDeath() {
        var memory = new KillCreditMemory();
        memory.hurt(victim, origin(UUID.randomUUID()), 0);
        assertEquals(Optional.empty(), memory.died(victim, Optional.empty(), KillCreditMemory.WINDOW_TICKS + 1));
        memory.hurt(victim, origin(UUID.randomUUID()), 0);
        assertTrue(memory.died(victim, Optional.empty(), 1).isPresent());
        assertEquals(Optional.empty(), memory.died(victim, Optional.empty(), 2), "A new body starts with no memory");
    }

    @Test void theLatestCreditedHitWinsAndTheLethalHitsOwnSourceComesFirst() {
        var memory = new KillCreditMemory();
        var first = origin(UUID.randomUUID());
        var second = origin(UUID.randomUUID());
        memory.hurt(victim, first, 0);
        memory.hurt(victim, second, 10);
        assertEquals(Optional.of(second), memory.died(victim, Optional.empty(), 20));
        memory.hurt(victim, first, 30);
        var lethal = origin(UUID.randomUUID());
        assertEquals(Optional.of(lethal), memory.died(victim, Optional.of(lethal), 40));
    }

    @Test void victimsAreRememberedSeparatelyAndForgetClearsOne() {
        var memory = new KillCreditMemory();
        var other = UUID.randomUUID();
        var hit = origin(UUID.randomUUID());
        memory.hurt(victim, hit, 0);
        memory.hurt(other, origin(UUID.randomUUID()), 0);
        memory.forget(other);
        assertEquals(Optional.empty(), memory.died(other, Optional.empty(), 1));
        assertEquals(Optional.of(hit), memory.died(victim, Optional.empty(), 1));
    }
}
