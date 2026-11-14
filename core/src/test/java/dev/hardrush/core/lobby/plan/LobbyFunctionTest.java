package dev.hardrush.core.lobby.plan;

import static dev.hardrush.core.lobby.plan.LobbyFunction.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class LobbyFunctionTest {
    @Test void requiredFunctionsAreEitherEnforcedAtPublishOrHaveAFallback() {
        var enforced = EnumSet.of(ASSEMBLY, COURTYARD, ALTAR_PAD, LANDING_CENTRAL, LANDING_MONSTER, DISPLAY_SLOT);
        for (var f : LobbyFunction.values()) {
            if (!f.required()) continue;
            if (f.fallback() == Fallback.NONE) assertTrue(enforced.contains(f), f.id());
            else assertFalse(f.publishEnforced(), f.id());
        }
        assertEquals(enforced, EnumSet.copyOf(Arrays.stream(values()).filter(LobbyFunction::publishEnforced).toList()));
        assertEquals(20, values().length);
    }
    @Test void idsAreUniqueAndRoundTrip() {
        var ids = new HashSet<String>();
        for (var f : values()) { assertTrue(ids.add(f.id())); assertEquals(Optional.of(f), byId(f.id())); assertTrue(f.min() <= f.max()); }
        assertTrue(byId("nope").isEmpty());
    }
    @Test void blockRequirementsMatchTheTable() {
        assertTrue(VAULT.block().orElseThrow().matches("minecraft:vault", Map.of("ominous", "false", "facing", "north")));
        assertFalse(VAULT.block().orElseThrow().matches("minecraft:vault", Map.of("ominous", "true")));
        assertTrue(OMINOUS_VAULT.block().orElseThrow().matches("minecraft:vault", Map.of("ominous", "true")));
        assertTrue(ALTAR_LIGHT.block().orElseThrow().matches("minecraft:tinted_glass", Map.of()));
        assertTrue(STATUS.block().isEmpty() && CREDITS.block().isEmpty());
        assertEquals(Set.of(Binding.MODEL, Binding.CLICK), MERCHANT.bindings());
        assertFalse(LANDING_ALTAR.required());
        assertEquals(Integer.MAX_VALUE, RULES.max());
    }
}
