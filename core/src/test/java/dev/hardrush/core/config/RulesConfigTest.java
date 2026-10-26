package dev.hardrush.core.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RulesConfigTest {
    @TempDir Path directory;

    @Test void defaultsFollowCurrentNativeRoleDecisionAndPhaseDurations() {
        RulesConfig rules = RulesConfig.defaults();
        assertEquals(GameMode.HARDCORE, rules.mode());
        assertEquals(Difficulty.HARD, rules.difficulty());
        assertEquals("26.3", rules.minecraftVersion());
        assertEquals("normal", rules.generationRule());
        assertEquals(32, rules.protectionRadius());
        assertEquals(30_000, rules.startCountdownMillis());
        assertEquals(90_000, rules.successCelebrationMillis());
        assertEquals(30_000, rules.failureDisplayMillis());
        assertEquals(20, rules.combatValues().get("zombie.maxHealth"));
        assertEquals(20, rules.combatValues().get("skeleton.maxHealth"));
        assertEquals(30, rules.combatValues().get("creeper.fuseTicks"));
        assertTrue(rules.combatValues().keySet().stream().noneMatch(key -> key.startsWith("zombie.summon")));
        assertEquals(20_000, rules.combatValues().get("skeleton.burstCooldownMillis"));
    }

    @Test void retiredSummonRuleKeysStillDecodeAndRoundTripFromHistoricalSnapshots() throws IOException {
        try (var input = getClass().getResourceAsStream("/legacy-vote-rules.properties")) {
            assertNotNull(input);
            var legacy = RulesConfig.decode(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(2, legacy.combatValues().get("zombie.summonCount"));
            assertEquals(90_000, legacy.combatValues().get("zombie.summonCooldownMillis"));
            assertEquals(2, legacy.combatValues().get("zombie.summonMinDistance"));
            assertEquals(4, legacy.combatValues().get("zombie.summonMaxDistance"));
            assertEquals(legacy, RulesConfig.decode(legacy.encode()));
            assertEquals(legacy.fingerprint(), RulesConfig.decode(legacy.encode()).fingerprint());
        }
    }

    @Test void roundsRetainIndependentImmutableSnapshotsWhenPendingConfigChanges() {
        RulesConfig original = RulesConfig.defaults();
        var pending = new RulesConfigFile(original);
        RulesConfig active = pending.snapshotForNextRound();
        String originalFingerprint = active.fingerprint();
        pending.setPending(original.withMode(GameMode.NORMAL));
        assertEquals(GameMode.HARDCORE, active.mode());
        assertEquals(originalFingerprint, active.fingerprint());
        assertEquals(GameMode.NORMAL, pending.snapshotForNextRound().mode());
        assertNotEquals(active.fingerprint(), pending.snapshotForNextRound().fingerprint());
        assertThrows(UnsupportedOperationException.class, () -> active.combatValues().put("zombie.maxHealth", 1.0));
    }

    @Test void callerMapAndInsertionOrderCannotChangeSnapshotOrFingerprint() {
        var numbers = new LinkedHashMap<String, Double>();
        numbers.put("zombie.maxHealth", 20.0);
        numbers.put("zombie.armor", 2.0);
        RulesConfig first = withCombat(numbers);
        var reverse = new LinkedHashMap<String, Double>();
        reverse.put("zombie.armor", 2.0);
        reverse.put("zombie.maxHealth", 20.0);
        RulesConfig second = withCombat(reverse);
        numbers.put("zombie.maxHealth", 99.0);
        assertEquals(20.0, first.combatValues().get("zombie.maxHealth"));
        assertEquals(first.encode(), second.encode());
        assertEquals(first.fingerprint(), second.fingerprint());
        assertTrue(first.fingerprint().matches("[0-9a-f]{64}"));
    }

    @Test void canonicalEncodingNormalizesNumberSpellingAndZero() {
        RulesConfig original = RulesConfig.defaults();
        RulesConfig read = RulesConfig.decode(original.encode().replace("protectionRadius=32.0", "protectionRadius=3.2E1")
                .replace("combat.skeleton.armor=0.0", "combat.skeleton.armor=-0.0"));
        assertEquals(original, read);
        assertEquals(original.fingerprint(), read.fingerprint());
    }

    @Test void codecRoundTripsEscapedGenerationAndRejectsAmbiguousFields() {
        RulesConfig source = rules("26.3", " \t\f生成\\配置\nseed=normal", RulesConfig.defaults().combatValues(),
                32, 30_000, 600_000, 30_000);
        assertEquals(source, RulesConfig.decode(source.encode()));
        assertThrows(IllegalArgumentException.class, () -> RulesConfig.decode(source.encode() + "mode=NORMAL\n"));
        assertThrows(IllegalArgumentException.class, () -> RulesConfig.decode(source.encode() + "modTypo=foo\n"));
        assertThrows(IllegalArgumentException.class, () -> RulesConfig.decode(source.encode().replace("mode=HARDCORE\n", "")));
    }

    @Test void everyLeaderboardRelevantFieldChangesFingerprint() {
        RulesConfig defaults = RulesConfig.defaults();
        var combat = new HashMap<>(defaults.combatValues());
        combat.put("zombie.maxHealth", 21.0);
        RulesConfig[] alternatives = {
                defaults.withMode(GameMode.NORMAL),
                rules("26.2", "normal", defaults.combatValues(), 32, 30_000, 600_000, 30_000),
                new RulesConfig(1, GameMode.HARDCORE, "26.3", Difficulty.NORMAL, "normal", defaults.combatValues(), 32, 30_000, 600_000, 30_000),
                rules("26.3", "custom", defaults.combatValues(), 32, 30_000, 600_000, 30_000),
                withCombat(combat),
                rules("26.3", "normal", defaults.combatValues(), 33, 30_000, 600_000, 30_000),
                rules("26.3", "normal", defaults.combatValues(), 32, 31_000, 600_000, 30_000),
                rules("26.3", "normal", defaults.combatValues(), 32, 30_000, 601_000, 30_000),
                rules("26.3", "normal", defaults.combatValues(), 32, 30_000, 600_000, 31_000)
        };
        for (RulesConfig alternative : alternatives) assertNotEquals(defaults.fingerprint(), alternative.fingerprint());
    }

    @Test void unsupportedVersionAndSchemaCannotSilentlyUseCurrentRules() {
        for (String version : new String[] {"", " ", "1.21.1", "26.1", "26.3.1", " 26.3"}) {
            assertThrows(IllegalArgumentException.class, () -> rules(version, "normal", Map.of("health", 20.0), 32, 30_000, 600_000, 30_000));
        }
        for (String version : new String[] {"26.1.2", "26.2", "26.3"}) {
            assertDoesNotThrow(() -> rules(version, "normal", Map.of("health", 20.0), 32, 30_000, 600_000, 30_000));
        }
        assertThrows(IllegalArgumentException.class, () -> RulesConfig.decode(RulesConfig.defaults().encode().replace("schemaVersion=2", "schemaVersion=3")));
    }

    @Test void invalidCombatRadiusAndTimersAreRejectedBeforeApplication() {
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1}) {
            assertThrows(IllegalArgumentException.class, () -> withCombat(Map.of("zombie.maxHealth", invalid)));
        }
        assertThrows(IllegalArgumentException.class, () -> withCombat(Map.of("zombie.maxHealth", 0.0)));
        assertThrows(IllegalArgumentException.class, () -> withCombat(Map.of("health", 0.0)));
        assertThrows(IllegalArgumentException.class, () -> withCombat(Map.of("zombie.summonCount", 1.5)));
        assertThrows(IllegalArgumentException.class, () -> withCombat(Map.of("zombie.summonMinDistance", 4.0, "zombie.summonMaxDistance", 2.0)));
        assertThrows(IllegalArgumentException.class, () -> withCombat(Map.of("zombie.summonCooldownMillis", 0.0)));
        assertThrows(IllegalArgumentException.class, () -> withCombat(Map.of("bad\nkey", 20.0)));
        assertThrows(IllegalArgumentException.class, () -> withCombat(Map.of()));
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1, 0, 30_000_001}) {
            assertThrows(IllegalArgumentException.class, () -> rules("26.3", "normal", Map.of("health", 20.0), invalid, 30_000, 600_000, 30_000));
        }
        for (long invalid : new long[] {-1, 0, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> rules("26.3", "normal", Map.of("health", 20.0), 32, invalid, 600_000, 30_000));
            assertThrows(IllegalArgumentException.class, () -> rules("26.3", "normal", Map.of("health", 20.0), 32, 30_000, invalid, 30_000));
            assertThrows(IllegalArgumentException.class, () -> rules("26.3", "normal", Map.of("health", 20.0), 32, 30_000, 600_000, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> rules("26.3", " ", Map.of("health", 20.0), 32, 30_000, 600_000, 30_000));
        assertThrows(IllegalArgumentException.class, () -> rules("26.3", "bad\uD800", Map.of("health", 20.0), 32, 30_000, 600_000, 30_000));
    }

    @Test void fileReloadOnlyAppliesValidatedNextRoundRules() throws IOException {
        RulesConfig active = RulesConfig.defaults();
        var pending = new RulesConfigFile(active);
        Path file = directory.resolve("rules.properties");
        Files.writeString(file, active.withMode(GameMode.NORMAL).encode());
        pending.reload(file);
        assertEquals(GameMode.NORMAL, pending.snapshotForNextRound().mode());
        assertEquals(GameMode.HARDCORE, active.mode());
        String validFingerprint = pending.snapshotForNextRound().fingerprint();
        Files.writeString(file, active.encode().replace("protectionRadius=32.0", "protectionRadius=NaN"));
        assertThrows(IOException.class, () -> pending.reload(file));
        assertEquals(validFingerprint, pending.snapshotForNextRound().fingerprint());
    }

    private static RulesConfig withCombat(Map<String, Double> combat) {
        return rules("26.3", "normal", combat, 32, 30_000, 600_000, 30_000);
    }

    private static RulesConfig rules(String version, String generation, Map<String, Double> combat,
            double radius, long countdown, long celebration, long failure) {
        return new RulesConfig(1, GameMode.HARDCORE, version, Difficulty.HARD, generation, combat, radius,
                countdown, celebration, failure);
    }
}
