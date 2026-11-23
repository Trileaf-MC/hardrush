package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoundSnapshotCodecMigrationTest {
    private static final UUID ROUND = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACTIVE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RESPAWNING = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID LOBBY = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test void oldSuccessTimersKeepElapsedTimeAndAreMigratedOnlyOnce() {
        var defaults = RulesConfig.defaults();
        var rules = RulesConfig.decode(defaults.encode().replace("successCelebrationMillis=" + defaults.successCelebrationMillis(), "successCelebrationMillis=600000"));
        for (var phase : List.of(RoundPhase.SETTLING, RoundPhase.CELEBRATING)) {
            for (long remaining : new long[]{600_000, 480_000, 420_000, 10_000}) {
                var snapshot = new RoundSnapshot(ROUND, 1, 1, 7, rules, phase, PauseReason.SETTLED, 123, remaining * 1_000_000,
                        Map.of(), true, false, new RoundResult(RoundResult.Kind.COMPLETED, null, "verified old finish"));
                // Without participants v4 has the same layout up to the result, but the old timer semantics.
                var bytes = withoutLooseTotems(RoundSnapshotCodec.encode(snapshot));
                ByteBuffer.wrap(bytes).putInt(4, 4);
                var migrated = RoundSnapshotCodec.decode(bytes);
                assertEquals(Math.max(0, remaining - 420_000), migrated.remainingPhaseMillis());
                assertEquals(123, migrated.challengeNanos());
                assertEquals(rules, migrated.rules());
                assertEquals(migrated, RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(migrated)));
            }
        }
    }

    @Test void legacyTimerMigrationCannotTurnCorruptNegativeTimeIntoAValidSnapshot() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(0x48525331); out.writeInt(4);
        uuid(out, ROUND); out.writeLong(1); out.writeLong(1); out.writeLong(7);
        text(out, RulesConfig.defaults().encode()); text(out, "CELEBRATING"); text(out, "SETTLED");
        out.writeLong(123); out.writeLong(-1); out.writeBoolean(true); out.writeBoolean(false);
        out.writeInt(0); out.writeBoolean(true); text(out, "COMPLETED"); out.writeBoolean(false); text(out, "old finish");
        assertThrows(IllegalArgumentException.class, () -> RoundSnapshotCodec.decode(bytes.toByteArray()));
    }

    @Test void schemaOnePreservesIdentityCounters() throws Exception {
        RoundSnapshot migrated = RoundSnapshotCodec.decode(schemaOneFixture());
        assertEquals(ROUND, migrated.roundId());
        assertEquals(7, migrated.version());
        assertEquals(123_456, migrated.challengeNanos());
        Participant active = migrated.participants().get(ACTIVE);
        assertEquals(ParticipantRole.HUMAN_ACTIVE, active.role());
        assertEquals(98_765, active.humanNanos());
        assertEquals(25, active.joinedAtChallengeNanos());
        assertEquals(3, active.lifeSequence());
        assertTrue(active.monsterSkillReadyAtMillis().isEmpty());
        byte[] upgraded = RoundSnapshotCodec.encode(migrated);
        assertEquals(12, ByteBuffer.wrap(upgraded).getInt(4));
        assertEquals(migrated, RoundSnapshotCodec.decode(upgraded));
    }

    @Test void schemaTwoMonsterUpgradeRetainsEligibilityAndRequiresItsInitialWait() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(0x48525331); out.writeInt(2);
        uuid(out, ROUND); out.writeLong(4); out.writeLong(123); out.writeLong(7);
        text(out, RulesConfig.defaults().encode()); text(out, "RUNNING"); text(out, "NO_ONLINE_HUMANS");
        out.writeLong(123_456); out.writeLong(0); out.writeBoolean(true); out.writeBoolean(false);
        out.writeInt(1);
        uuid(out, ACTIVE); text(out, "Monster"); text(out, "MONSTER_LOBBY");
        out.writeBoolean(false); out.writeBoolean(false); out.writeLong(98_765); out.writeLong(25);
        out.writeInt(3); out.writeBoolean(true); out.writeBoolean(true); out.writeBoolean(false);
        Participant monster = RoundSnapshotCodec.decode(bytes.toByteArray()).participants().get(ACTIVE);
        assertTrue(monster.revivalEntitled());
        assertEquals(3, monster.lifeSequence());
        assertEquals(98_765, monster.humanNanos());
        assertEquals(MonsterRole.ZOMBIE, monster.monsterRole());
        assertEquals(MonsterDimension.OVERWORLD, monster.monsterDimension());
        assertEquals(30_000_000_000L, monster.monsterCooldownNanos());
        assertEquals(0, monster.monsterDeploymentSequence());
        assertTrue(monster.monsterSkillReadyAtMillis().isEmpty());
    }

    @Test void schemaThreeMonsterStateUpgradesWithoutInventingSkillCooldowns() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(0x48525331); out.writeInt(3);
        uuid(out, ROUND); out.writeLong(4); out.writeLong(123); out.writeLong(7);
        text(out, RulesConfig.defaults().encode()); text(out, "RUNNING"); text(out, "NO_ONLINE_HUMANS");
        out.writeLong(123_456); out.writeLong(0); out.writeBoolean(true); out.writeBoolean(false);
        out.writeInt(1);
        uuid(out, ACTIVE); text(out, "Monster"); text(out, "MONSTER_DEPLOYED");
        out.writeBoolean(false); out.writeBoolean(false); out.writeLong(98_765); out.writeLong(25);
        out.writeInt(3); out.writeBoolean(true); out.writeBoolean(true);
        text(out, "SKELETON"); text(out, "NETHER"); out.writeLong(0); out.writeInt(9);
        out.writeBoolean(false);
        var migrated = RoundSnapshotCodec.decode(bytes.toByteArray());
        Participant monster = migrated.participants().get(ACTIVE);
        assertEquals(MonsterRole.SKELETON, monster.monsterRole());
        assertEquals(MonsterDimension.NETHER, monster.monsterDimension());
        assertEquals(9, monster.monsterDeploymentSequence());
        assertTrue(monster.monsterSkillReadyAtMillis().isEmpty());
        assertEquals(migrated, RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(migrated)));
    }

    @Test void schemaFourAndFiveBooleanEntitlementsUpgradeToZeroOrOneCredit() throws Exception {
        for (int schema : new int[]{4, 5}) for (boolean entitled : List.of(false, true)) {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(0x48525331); out.writeInt(schema);
            uuid(out, ROUND); out.writeLong(4); out.writeLong(123); out.writeLong(7);
            text(out, RulesConfig.defaults().encode()); text(out, "RUNNING"); text(out, "NO_ONLINE_HUMANS");
            out.writeLong(123_456); out.writeLong(0); out.writeBoolean(true); out.writeBoolean(false);
            out.writeInt(1);
            uuid(out, ACTIVE); text(out, "Monster"); text(out, "MONSTER_LOBBY");
            out.writeBoolean(false); out.writeBoolean(false); out.writeLong(98_765); out.writeLong(25);
            out.writeInt(3); out.writeBoolean(entitled); out.writeBoolean(true);
            text(out, "SKELETON"); text(out, "NETHER"); out.writeLong(0); out.writeInt(9);
            out.writeInt(1); text(out, MonsterSkill.SKELETON_BURST.id()); out.writeLong(123_000);
            out.writeBoolean(false);
            var migrated = RoundSnapshotCodec.decode(bytes.toByteArray());
            assertEquals(entitled ? 1 : 0, migrated.participants().get(ACTIVE).revivalCharges());
            assertEquals(migrated, RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(migrated)));
        }
    }

    @Test void schemaSixRejectsNegativeRevivalCredits() {
        var player = new Participant(ACTIVE, "Monster", ParticipantRole.MONSTER_LOBBY, true, false,
                0, 0, 1, 123456789, MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0, Map.of());
        var snapshot = new RoundSnapshot(ROUND, 1, 1, 0, RulesConfig.defaults(), RoundPhase.RUNNING,
                PauseReason.NONE, 0, 0, Map.of(ACTIVE, player), true, false, null);
        byte[] bytes = RoundSnapshotCodec.encode(snapshot);
        assertEquals(123456789, RoundSnapshotCodec.decode(bytes).participants().get(ACTIVE).revivalCharges());
        var buffer = ByteBuffer.wrap(bytes);
        boolean corrupted = false;
        for (int i = 8; i <= bytes.length - 4; i++) if (buffer.getInt(i) == 123456789) {
            buffer.putInt(i, -1); corrupted = true; break;
        }
        assertTrue(corrupted);
        assertThrows(IllegalArgumentException.class, () -> RoundSnapshotCodec.decode(bytes));
    }

    private static final UUID VICTIM = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID KILLER = UUID.fromString("00000000-0000-0000-0000-000000000005");

    private static RoundSnapshot withTotems(Map<UUID, LooseTotem> totems) {
        var victim = new Participant(VICTIM, "Victim", ParticipantRole.MONSTER_LOBBY, true, false,
                10, 0, 2, 0, MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0, Map.of());
        var killer = new Participant(KILLER, "Killer", ParticipantRole.HUMAN_ACTIVE, true, false, 10, 0, 1, false);
        return new RoundSnapshot(ROUND, 1, 1, 3, RulesConfig.defaults(), RoundPhase.RUNNING, PauseReason.NONE,
                900_000, 0, Map.of(VICTIM, victim, KILLER, killer), false, false, null, totems);
    }

    /** The current encoding without its trailing v11 threat count, which threat-free lives write as 0. */
    private static byte[] withoutThreats(byte[] v11) {
        assertEquals(0, ByteBuffer.wrap(v11).getInt(v11.length - 4));
        return java.util.Arrays.copyOf(v11, v11.length - 4);
    }

    /** The current encoding without its trailing v10 loose-totem count, which an empty registry writes as 0. */
    private static byte[] withoutLooseTotems(byte[] v11) {
        byte[] v10 = withoutThreats(v11);
        assertEquals(0, ByteBuffer.wrap(v10).getInt(v10.length - 4));
        return java.util.Arrays.copyOf(v10, v10.length - 4);
    }

    private static LooseTotem byKiller(int life, long at) {
        return new LooseTotem(VICTIM, life, LooseTotem.Source.HUMAN, java.util.Optional.of(KILLER), at);
    }

    /** One registered HUMAN entry in the current layout: token, victim, life, source text, killer flag, killer, time. */
    private static final int HUMAN_ENTRY = 16 + 16 + 4 + (4 + "HUMAN".length()) + 1 + 16 + 8;

    @Test void theLooseTotemRegistryRoundTripsInTokenOrderWithItsSourceAndKiller() {
        var high = new UUID(2, 0);
        var low = new UUID(1, 0);
        var unclaimed = new UUID(3, 0);
        var totems = new java.util.LinkedHashMap<UUID, LooseTotem>();
        totems.put(high, byKiller(2, 800_000));
        totems.put(low, byKiller(1, 100));
        totems.put(unclaimed, new LooseTotem(VICTIM, 3, LooseTotem.Source.UNCLAIMED, java.util.Optional.empty(), 900_000));
        var snapshot = withTotems(totems);
        byte[] bytes = RoundSnapshotCodec.encode(snapshot);
        assertEquals(12, ByteBuffer.wrap(bytes).getInt(4));
        var decoded = RoundSnapshotCodec.decode(bytes);
        assertEquals(snapshot, decoded);
        assertEquals(List.of(low, high, unclaimed), List.copyOf(decoded.looseTotems().keySet()), "Encoded sorted by token");
        var reordered = new java.util.LinkedHashMap<UUID, LooseTotem>();
        reordered.put(unclaimed, totems.get(unclaimed)); reordered.put(low, totems.get(low)); reordered.put(high, totems.get(high));
        assertArrayEquals(bytes, RoundSnapshotCodec.encode(withTotems(reordered)), "Deterministic regardless of insertion order");
    }

    @Test void schemasTenAndElevenReadEveryEntryAsAHumansDropNamingItsKiller() {
        var token = new UUID(1, 0);
        byte[] v12 = RoundSnapshotCodec.encode(withTotems(Map.of(token, byKiller(2, 700))));
        // Drop the source text and the killer flag, which schema 12 added between the life and the killer.
        int sourceAt = v12.length - 4 - HUMAN_ENTRY + 16 + 16 + 4;
        byte[] v11 = new byte[v12.length - (4 + "HUMAN".length() + 1)];
        System.arraycopy(v12, 0, v11, 0, sourceAt);
        System.arraycopy(v12, sourceAt + 4 + "HUMAN".length() + 1, v11, sourceAt, v12.length - sourceAt - (4 + "HUMAN".length() + 1));
        ByteBuffer.wrap(v11).putInt(4, 11);
        assertEquals(withTotems(Map.of(token, byKiller(2, 700))), RoundSnapshotCodec.decode(v11));
    }

    @Test void schemaNineAndEarlierReadAnEmptyRegistry() {
        byte[] v9 = withoutLooseTotems(RoundSnapshotCodec.encode(withTotems(Map.of())));
        ByteBuffer.wrap(v9).putInt(4, 9);
        var migrated = RoundSnapshotCodec.decode(v9);
        assertTrue(migrated.looseTotems().isEmpty());
        assertEquals(withTotems(Map.of()), migrated);
        assertEquals(12, ByteBuffer.wrap(RoundSnapshotCodec.encode(migrated)).getInt(4));
        assertTrue(RoundSnapshotCodec.decode(schemaOneFixtureUnchecked()).looseTotems().isEmpty());
    }

    @Test void schemaTenAndEarlierReadNoThreat() {
        byte[] v10 = withoutThreats(RoundSnapshotCodec.encode(withTotems(Map.of())));
        ByteBuffer.wrap(v10).putInt(4, 10);
        assertEquals(withTotems(Map.of()), RoundSnapshotCodec.decode(v10));
    }

    @Test void threatsForUnknownParticipantsAreRejected() {
        var killer = new Participant(KILLER, "Killer", ParticipantRole.HUMAN_ACTIVE, true, false, 10, 0, 1, false)
                .withThreat(new HumanThreat(1, 1, 0, java.util.Set.of()));
        var snapshot = new RoundSnapshot(ROUND, 1, 1, 3, RulesConfig.defaults(), RoundPhase.RUNNING, PauseReason.NONE,
                900_000, 0, Map.of(KILLER, killer), false, false, null);
        byte[] bytes = RoundSnapshotCodec.encode(snapshot);
        assertEquals(snapshot, RoundSnapshotCodec.decode(bytes));
        int idAt = bytes.length - (16 + 16);
        assertEquals(KILLER.getMostSignificantBits(), ByteBuffer.wrap(bytes).getLong(idAt));
        ByteBuffer.wrap(bytes).putLong(idAt, 99);
        assertThrows(IllegalArgumentException.class, () -> RoundSnapshotCodec.decode(bytes));
    }

    @Test void corruptRegistryCountsAndDuplicatesAreRejected() {
        var token = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        byte[] one = RoundSnapshotCodec.encode(withTotems(Map.of(token, byKiller(1, 5))));
        int countAt = one.length - (4 + HUMAN_ENTRY) - 4;
        assertEquals(1, ByteBuffer.wrap(one).getInt(countAt));
        for (int count : new int[]{-1, LooseTotem.MAX_REGISTERED + 1, 2}) {
            byte[] corrupt = one.clone();
            ByteBuffer.wrap(corrupt).putInt(countAt, count);
            assertThrows(IllegalArgumentException.class, () -> RoundSnapshotCodec.decode(corrupt), String.valueOf(count));
        }
        // The same token twice.
        byte[] entry = java.util.Arrays.copyOfRange(one, countAt + 4, one.length);
        byte[] duplicate = java.util.Arrays.copyOf(one, one.length + entry.length);
        System.arraycopy(entry, 0, duplicate, one.length, entry.length);
        ByteBuffer.wrap(duplicate).putInt(countAt, 2);
        assertThrows(IllegalArgumentException.class, () -> RoundSnapshotCodec.decode(duplicate));
        // A negative drop time is not a valid entry either.
        byte[] negative = one.clone();
        ByteBuffer.wrap(negative).putLong(one.length - 4 - 8, -1);
        assertThrows(IllegalArgumentException.class, () -> RoundSnapshotCodec.decode(negative));
    }

    @Test void theRegistryBoundIsEnforcedOnConstructionToo() {
        var totems = new java.util.LinkedHashMap<UUID, LooseTotem>();
        for (int i = 0; i <= LooseTotem.MAX_REGISTERED; i++) totems.put(new UUID(0, i), byKiller(1, 0));
        assertThrows(IllegalArgumentException.class, () -> withTotems(totems));
    }

    private static byte[] schemaOneFixtureUnchecked() {
        try { return schemaOneFixture(); } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    /** The exact schema-1 wire layout, independent of the current encoder's schema and added fields. */
    private static byte[] schemaOneFixture() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(0x48525331); out.writeInt(1);
        uuid(out, ROUND); out.writeLong(4); out.writeLong(123); out.writeLong(7);
        text(out, RulesConfig.defaults().withMode(GameMode.NORMAL).encode());
        text(out, "RUNNING"); text(out, "NO_ONLINE_HUMANS");
        out.writeLong(123_456); out.writeLong(0); out.writeBoolean(true); out.writeBoolean(false);
        out.writeInt(3);
        List<Participant> players = List.of(
                new Participant(ACTIVE, "Active", ParticipantRole.HUMAN_ACTIVE, false, false, 98_765, 25, 3, false),
                new Participant(RESPAWNING, "Dead", ParticipantRole.HUMAN_RESPAWNING, false, false, 1, 0, 1, false),
                new Participant(LOBBY, "Lobby", ParticipantRole.LOBBY, false, false, 0, -1, 0, false));
        for (Participant p : players) {
            uuid(out, p.id()); text(out, p.name()); text(out, p.role().name());
            out.writeBoolean(p.online()); out.writeBoolean(p.ready()); out.writeLong(p.humanNanos());
            out.writeLong(p.joinedAtChallengeNanos()); out.writeInt(p.lifeSequence()); out.writeBoolean(p.revivalEntitled());
        }
        out.writeBoolean(false);
        return bytes.toByteArray();
    }

    private static void uuid(DataOutputStream out, UUID value) throws Exception {
        out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits());
    }

    private static void text(DataOutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }
}
