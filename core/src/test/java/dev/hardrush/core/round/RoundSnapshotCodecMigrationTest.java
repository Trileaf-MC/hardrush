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

    @Test void schemaOnePreservesIdentityCountersAndGrantsAnInitialProtectionPermission() throws Exception {
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
        assertFalse(active.hasLeftProtection(), "P1 had no exit record; upgrading must not force a waiting human out of protection");
        assertFalse(migrated.participants().get(RESPAWNING).hasLeftProtection());
        assertFalse(migrated.participants().get(LOBBY).hasLeftProtection());
        byte[] upgraded = RoundSnapshotCodec.encode(migrated);
        assertEquals(4, ByteBuffer.wrap(upgraded).getInt(4));
        assertEquals(migrated, RoundSnapshotCodec.decode(upgraded));
    }

    @Test void schemaTwoRetainsDifferentExitPermissionsInTheSameSnapshot() {
        Participant left = new Participant(ACTIVE, "Left", ParticipantRole.HUMAN_ACTIVE, true, false,
                1, 0, 1, false, true);
        Participant fresh = new Participant(RESPAWNING, "Fresh", ParticipantRole.HUMAN_ACTIVE, false, false,
                1, 0, 2, false, false);
        RoundSnapshot snapshot = new RoundSnapshot(ROUND, 1, 123, 1, RulesConfig.defaults(),
                RoundPhase.RUNNING, PauseReason.NONE, 1, 0, Map.of(ACTIVE, left, RESPAWNING, fresh),
                false, false, null);
        assertEquals(snapshot, RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(snapshot)));
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
        assertTrue(monster.hasLeftProtection());
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

    /** The exact P1 wire layout, independent of the current encoder's schema and added fields. */
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
