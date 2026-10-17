package dev.hardrush.core.lobby;

import static org.junit.jupiter.api.Assertions.*;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LobbyFoundationTest {
    @Test void layoutHasSafePurposeSeparationAndStableSpacedSlots() {
        for (var purpose : LobbyLayout.Landing.values()) {
            var point = LobbyLayout.landing(purpose);
            assertEquals(purpose == LobbyLayout.Landing.RESTORE_READY, LobbyLayout.ASSEMBLY.contains(point.x(), point.z()));
        }
        assertEquals(20, LobbyLayout.displaySlots().stream().distinct().count());
        for (var a : LobbyLayout.displaySlots()) for (var b : LobbyLayout.displaySlots()) {
            if (!a.equals(b)) assertTrue(Math.hypot(a.x()-b.x(), a.z()-b.z()) >= 3);
            assertTrue(a.x() >= LobbyLayout.MIN_X && a.x() <= LobbyLayout.MAX_X && a.z() >= LobbyLayout.MIN_Z && a.z() <= LobbyLayout.MAX_Z);
        }
        assertTrue(16 - LobbyLayout.ASSEMBLY.radius() >= 6);
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
        assertEquals("1b6d9b7877a9aba787c0929a912e59b86a56571fba739f0c44c264b8f7d09640", legacy.fingerprint());
        var next = legacy.forNewRound();
        assertEquals(RulesConfig.HuntRule.PERSONAL_TARGET, next.huntRule());
        assertNotEquals(legacy.fingerprint(), next.fingerprint());
        assertEquals(legacy.combatValues(), next.combatValues());
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
