package dev.hardrush.core.lobby;

import static org.junit.jupiter.api.Assertions.*;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.lobby.plan.*;
import dev.hardrush.core.round.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LobbyFoundationTest {
    @Test void campPlanHasSafePurposeSeparationAndStableSpacedSlots() {
        var plan = CampPlanFixture.build(8, 4, 8);
        for (var purpose : Landing.values())
            for (var point : LandingRules.candidates(plan, purpose))
                assertEquals(purpose == Landing.RESTORE_READY, plan.inZone(LobbyFunction.ASSEMBLY, point.x(), point.y(), point.z(), false), purpose.name());
        var slots = plan.points(LobbyFunction.DISPLAY_SLOT);
        assertEquals(20, slots.stream().distinct().count());
        for (var a : slots) for (var b : slots) {
            if (!a.equals(b)) assertTrue(Math.hypot(a.x()-b.x(), a.z()-b.z()) >= 3);
            assertTrue(plan.bounds().contains(a.x(), a.y(), a.z()));
        }
        var pad = plan.zones(LobbyFunction.ALTAR_PAD).getFirst();
        var center = pad.center();
        assertFalse(pad.contains(center.x(), center.y(), center.z(), false), "The beacon is not a standing cell");
        for (var foot : LandingRules.candidates(plan, Landing.ALTAR))
            assertFalse(plan.inZone(LobbyFunction.ALTAR_PAD, foot.x(), foot.y() + 1, foot.z(), true), "Arrival never starts a rite");
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            double x = center.x() + dx, z = center.z() + dz;
            assertEquals(Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && (dx != 0 || dz != 0), pad.contains(x, center.y(), z, false));
            assertFalse(plan.inZone(LobbyFunction.ASSEMBLY, x, center.y() - 1, z, true));
            assertFalse(plan.inZone(LobbyFunction.COURTYARD, x, center.y() - 1, z, true));
        }
    }
    @Test void progressAndRecoveryBeatFacilityHintsAndExpireDeterministically() {
        var hints = new LobbyHints();
        hints.offer("facility", LobbyHints.Priority.FACILITY, "入口", 100);
        hints.offer("search", LobbyHints.Priority.PROGRESS, "搜索中", 20);
        hints.offer("target", LobbyHints.Priority.TARGET, "目标", 30);
        assertEquals("搜索中", hints.current(10).orElseThrow());
        hints.offer("recovery", LobbyHints.Priority.RECOVERY, "隔离", 15);
        assertEquals("隔离", hints.current(10).orElseThrow());
        assertEquals("目标", hints.current(20).orElseThrow());
        hints.clear("target"); assertEquals("入口", hints.current(21).orElseThrow());
        assertTrue(hints.current(100).isEmpty());
    }
    @Test void legacyGoldenBytesAndFingerprintSurviveCodecAndMigration() throws Exception {
        String encoded = new String(getClass().getResourceAsStream("/legacy-vote-rules.properties").readAllBytes(), StandardCharsets.UTF_8);
        var legacy = RulesConfig.decode(encoded);
        assertEquals(RulesConfig.HuntRule.LEGACY_VOTE, legacy.huntRule());
        assertEquals(encoded, legacy.encode());
        assertEquals("d7eda77f0c2f5621be197e1e6cece7ebc7d6921c3181ec1536055e63c50b6dad", legacy.fingerprint());
        var next = legacy.forNewRound();
        assertEquals(RulesConfig.HuntRule.PERSONAL_TARGET, next.huntRule());
        assertNotEquals(legacy.fingerprint(), next.fingerprint());
        // Every stored value is kept; only missing monster coin values are added.
        legacy.combatValues().forEach((key, value) -> assertEquals(value, next.combatValues().get(key)));
        assertEquals(6.0, next.combatValues().get("coin.starter"));
        var stored = snapshot(legacy, RoundPhase.RUNNING, null);
        assertEquals(stored, RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(stored)));
        assertEquals(encoded, stored.rules().encode());
    }
    @Test void everyLegacyActivePhaseIsQuarantinedEvenAfterCleanStopAndRecheck() {
        var old = RulesConfig.defaults().withHuntRule(RulesConfig.HuntRule.LEGACY_VOTE);
        for (var phase : new RoundPhase[] {RoundPhase.PREPARING, RoundPhase.WAITING, RoundPhase.COUNTDOWN, RoundPhase.RUNNING, RoundPhase.RESCUE, RoundPhase.RECOVERY_REQUIRED}) {
            var stored = snapshot(old, phase, null);
            var restored = RoundEngine.restore(stored, () -> 0).committed();
            assertEquals(RoundPhase.RECOVERY_REQUIRED, restored.phase());
            assertEquals(old.fingerprint(), restored.rules().fingerprint());
            assertEquals(stored.remainingPhaseNanos(), restored.remainingPhaseNanos());
            assertEquals(RoundPhase.RECOVERY_REQUIRED, RoundEngine.restore(restored, () -> 0).committed().phase());
        }
        var fresh = snapshot(RulesConfig.defaults(), RoundPhase.COUNTDOWN, null);
        assertEquals(RoundPhase.COUNTDOWN, RoundEngine.restore(fresh, () -> 0).committed().phase());
    }
    private RoundSnapshot snapshot(RulesConfig rules, RoundPhase phase, RoundResult result) {
        return new RoundSnapshot(UUID.randomUUID(), 1, 2, 3, rules, phase, PauseReason.NOT_RUNNING,
                123, 456, Map.of(), true, false, result);
    }
}
