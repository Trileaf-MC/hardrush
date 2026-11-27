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
        assertEquals(20, ByteBuffer.wrap(upgraded).getInt(4));
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
        return withTotems(totems, MonsterWallet.EMPTY);
    }

    private static RoundSnapshot withTotems(Map<UUID, LooseTotem> totems, MonsterWallet wallet) {
        var victim = new Participant(VICTIM, "Victim", ParticipantRole.MONSTER_LOBBY, true, false,
                10, 0, 2, 0, MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0, Map.of()).withWallet(wallet);
        var killer = new Participant(KILLER, "Killer", ParticipantRole.HUMAN_ACTIVE, true, false, 10, 0, 1, false);
        return new RoundSnapshot(ROUND, 1, 1, 3, RulesConfig.defaults(), RoundPhase.RUNNING, PauseReason.NONE,
                900_000, 0, Map.of(VICTIM, victim, KILLER, killer), false, false, null, totems);
    }

    @Test void schemaSixteenReadsNoCreeperTicketAndSeventeenKeepsIt() throws Exception {
        var wallet = MonsterWallet.EMPTY.credit(15).chooseRole(RulesConfig.defaults(), MonsterRole.ZOMBIE).park("parked-body");
        var old = withTotems(Map.of(), wallet);
        var migrated = RoundSnapshotCodec.decode(schemaSixteenFixture(old));
        assertEquals(old, migrated);
        assertEquals(CreeperTicket.NONE, migrated.participants().get(VICTIM).wallet().creeperTicket());

        var paid = withTotems(Map.of(), wallet.credit(40).unlockCreeper(40));
        byte[] encoded = RoundSnapshotCodec.encode(paid);
        assertEquals(20, ByteBuffer.wrap(encoded).getInt(4));
        var decoded = RoundSnapshotCodec.decode(encoded);
        assertEquals(paid, decoded);
        assertEquals(CreeperTicket.PLAIN, decoded.participants().get(VICTIM).wallet().creeperTicket());
    }

    @Test void schemaEighteenReadsNoInvestmentAndNineteenKeepsIt() {
        var piece = new MonsterWallet.GearPiece(0, MonsterWallet.GearSlot.HEAD, java.util.Set.of(MonsterRole.ZOMBIE), "helmet", 3);
        var wallet = new MonsterWallet(15, true, Map.of(), Map.of(), Map.of(), List.of(piece), List.of(), 1, Map.of(),
                "parked-body", CreeperTicket.NONE, 12_345);
        byte[] v19 = RoundSnapshotCodec.encode(withTotems(Map.of(), wallet));
        assertEquals(20, ByteBuffer.wrap(v19).getInt(4));
        var decoded = RoundSnapshotCodec.decode(v19).participants().get(VICTIM).wallet();
        assertEquals(wallet, decoded);
        assertEquals(12_345, decoded.sortieInvestment());
        assertEquals(3, decoded.gear().getFirst().paid());

        var migrated = RoundSnapshotCodec.decode(asSchemaEighteen(v19)).participants().get(VICTIM).wallet();
        assertEquals(0, migrated.sortieInvestment());
        assertEquals(0, migrated.gear().getFirst().paid());
        assertEquals(new MonsterWallet(15, true, Map.of(), Map.of(), Map.of(),
                List.of(new MonsterWallet.GearPiece(0, MonsterWallet.GearSlot.HEAD, java.util.Set.of(MonsterRole.ZOMBIE), "helmet")),
                List.of(), 1, Map.of(), "parked-body"), migrated);
    }

    @Test void schemaTwentyStoresEveryCreeperTicketGrade() {
        for (var ticket : CreeperTicket.values()) {
            var wallet = new MonsterWallet(15, true, Map.of(), Map.of(), Map.of(), List.of(), List.of(), 0, Map.of(),
                    "parked-body", ticket, 5_000);
            var snapshot = withTotems(Map.of(), wallet);
            byte[] bytes = RoundSnapshotCodec.encode(snapshot);
            assertEquals(20, ByteBuffer.wrap(bytes).getInt(4));
            assertEquals(snapshot, RoundSnapshotCodec.decode(bytes));
            assertEquals(ticket, RoundSnapshotCodec.decode(bytes).participants().get(VICTIM).wallet().creeperTicket());
        }
    }

    @Test void schemaNineteenTurnsAChargeHeldWithATicketIntoAChargedTicketAndDropsOneWithout() {
        var rules = RulesConfig.defaults();
        var plain = MonsterWallet.EMPTY.credit(55).chooseRole(rules, MonsterRole.CREEPER).unlockCreeper(35)
                .buy(rules, MonsterRole.CREEPER, ShopOffer.POISON, 8);
        var unpaid = plain.useCreeperTicket();
        for (int at : new int[] {IN_BOUGHT, IN_ORDER, IN_ACTIVE, IN_PARKED_GOODS}) {
            // The human's empty wallet gets the charge too, with no ticket: it is dropped there.
            assertEquals(withTotems(Map.of(), plain.struckByLightning()),
                    RoundSnapshotCodec.decode(asSchemaNineteen(RoundSnapshotCodec.encode(withTotems(Map.of(), plain)), at)));
            assertEquals(withTotems(Map.of(), unpaid),
                    RoundSnapshotCodec.decode(asSchemaNineteen(RoundSnapshotCodec.encode(withTotems(Map.of(), unpaid)), at)));
        }
        var held = RoundSnapshotCodec.decode(asSchemaNineteen(RoundSnapshotCodec.encode(withTotems(Map.of(), plain)), NOWHERE));
        assertEquals(CreeperTicket.PLAIN, held.participants().get(VICTIM).wallet().creeperTicket());
        assertEquals(1, held.participants().get(VICTIM).wallet().ordered(ShopOffer.POISON));
        assertEquals(withTotems(Map.of(), plain), held);
    }

    /** Where {@link #asSchemaNineteen} adds the retired charged offer: a wallet offer map, its parked goods, or nowhere. */
    private static final int NOWHERE = -1, IN_BOUGHT = 0, IN_ORDER = 1, IN_ACTIVE = 2, IN_PARKED_GOODS = 3;

    /** The current encoding rewritten in the schema-18 layout: no key price after each gear item, no sortie investment. */
    private static byte[] asSchemaEighteen(byte[] v20) { return asOlderSchema(v20, 18, NOWHERE); }

    /** The current encoding rewritten in the schema-19 layout, with one charged offer at {@code chargedAt} in each wallet. */
    private static byte[] asSchemaNineteen(byte[] v20, int chargedAt) { return asOlderSchema(v20, 19, chargedAt); }

    /**
     * The current encoding rewritten in the schema-18 or -19 layout: the creeper ticket as a flag followed by the parked
     * goods, and, before 19, no key price after each gear item and no sortie investment.
     */
    private static byte[] asOlderSchema(byte[] v20, int schema, int chargedAt) {
        var in = ByteBuffer.wrap(v20);
        var out = new ByteArrayOutputStream();
        copy(in, out, 4 + 4 + 16 + 8 + 8 + 8);
        for (int i = 0; i < 3; i++) copyText(in, out); // Rules, phase, pause reason.
        copy(in, out, 8 + 8 + 1 + 1);
        for (int participants = copyInt(in, out); participants > 0; participants--) {
            copy(in, out, 16); copyText(in, out); copyText(in, out);
            copy(in, out, 1 + 1 + 8 + 8 + 4 + 4);
            copyText(in, out); copyText(in, out);
            copy(in, out, 8 + 4);
            for (int skills = copyInt(in, out); skills > 0; skills--) { copyText(in, out); copy(in, out, 8); }
            copy(in, out, 4 + 1 + 4);
            for (int map = 0; map < 3; map++) {
                int n = in.getInt();
                writeInt(out, n + (map == chargedAt ? 1 : 0));
                for (; n > 0; n--) { copyText(in, out); copy(in, out, 4); }
                if (map == chargedAt) writeCharged(out);
            }
            for (int list = 0; list < 2; list++) for (int n = copyInt(in, out); n > 0; n--) {
                copy(in, out, 4); copyText(in, out); copy(in, out, 4); copyText(in, out);
                if (schema >= 19) copy(in, out, 4); else in.getInt(); // The key's price.
            }
            for (int n = copyInt(in, out); n > 0; n--) copy(in, out, 16 + 8 + 8 + 4);
            copyText(in, out);
            out.write(in.get() != 0 ? 1 : 0); // The ticket, as a flag.
            writeInt(out, chargedAt == IN_PARKED_GOODS ? 1 : 0);
            if (chargedAt == IN_PARKED_GOODS) writeCharged(out);
            if (schema >= 19) copy(in, out, 8); else in.getLong(); // The sortie investment.
            copy(in, out, 8);
        }
        copy(in, out, in.remaining());
        byte[] older = out.toByteArray();
        ByteBuffer.wrap(older).putInt(4, schema);
        return older;
    }

    private static void writeInt(ByteArrayOutputStream out, int value) { out.writeBytes(ByteBuffer.allocate(4).putInt(value).array()); }

    private static void writeCharged(ByteArrayOutputStream out) {
        byte[] id = "charged".getBytes(StandardCharsets.UTF_8);
        writeInt(out, id.length); out.writeBytes(id); writeInt(out, 1);
    }

    private static void copy(ByteBuffer in, ByteArrayOutputStream out, int length) {
        byte[] bytes = new byte[length];
        in.get(bytes);
        out.writeBytes(bytes);
    }

    private static int copyInt(ByteBuffer in, ByteArrayOutputStream out) {
        int value = in.getInt(in.position());
        copy(in, out, 4);
        return value;
    }

    private static void copyText(ByteBuffer in, ByteArrayOutputStream out) { copy(in, out, copyInt(in, out)); }

    /** The schema-16 layout for these fixtures: simple wallets, optional loose totems, and empty trailing tables. */
    private static byte[] schemaSixteenFixture(RoundSnapshot snapshot) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(0x48525331); out.writeInt(16);
        uuid(out, snapshot.roundId());
        out.writeLong(snapshot.sequence()); out.writeLong(snapshot.seed()); out.writeLong(snapshot.version());
        text(out, snapshot.rules().encode()); text(out, snapshot.phase().name()); text(out, snapshot.pauseReason().name());
        out.writeLong(snapshot.challengeNanos()); out.writeLong(snapshot.remainingPhaseNanos());
        out.writeBoolean(snapshot.cleanShutdown()); out.writeBoolean(snapshot.timingUncertain());
        out.writeInt(snapshot.participants().size());
        for (var id : snapshot.participants().keySet().stream().sorted().toList()) {
            var p = snapshot.participants().get(id);
            uuid(out, id); text(out, p.name()); text(out, p.role().name());
            out.writeBoolean(p.online()); out.writeBoolean(p.ready()); out.writeLong(p.humanNanos());
            out.writeLong(p.joinedAtChallengeNanos()); out.writeInt(p.lifeSequence()); out.writeInt(p.revivalCharges());
            text(out, p.monsterRole().name()); text(out, p.monsterDimension().name());
            out.writeLong(p.monsterCooldownNanos()); out.writeInt(p.monsterDeploymentSequence()); out.writeInt(0);
            out.writeInt(p.wallet().coins()); out.writeBoolean(p.wallet().roleChosen()); out.writeInt(p.wallet().nextGearSerial());
            for (int i = 0; i < 6; i++) out.writeInt(0); // Bought, ordered, carried, gear, carried gear, damage.
            text(out, p.wallet().parked());
            out.writeLong(p.lifeHumanNanos());
        }
        out.writeBoolean(false); // No round result.
        out.writeInt(snapshot.looseTotems().size());
        for (var entry : snapshot.looseTotems().entrySet()) {
            var totem = entry.getValue();
            uuid(out, entry.getKey()); uuid(out, totem.victim()); out.writeInt(totem.victimLife());
            text(out, totem.source().name()); out.writeBoolean(totem.killer().isPresent());
            if (totem.killer().isPresent()) uuid(out, totem.killer().get());
            out.writeLong(totem.droppedAtChallengeNanos());
        }
        for (int i = 0; i < 4; i++) out.writeInt(0); // Threats, hunt locks, witch records, quarries.
        out.writeBoolean(false); out.writeInt(0); // Admin pause and shelter waivers.
        return bytes.toByteArray();
    }

    /** The v16 tail: the administrator's pause flag and the shelter-waiver count. */
    private static final int ADMIN_TAIL = 1 + 4;

    /** The current encoding without its trailing v16 pause flag and waiver count, which an unpaused round writes as 0. */
    private static byte[] withoutAdmin(byte[] v16) {
        assertEquals(0, ByteBuffer.wrap(v16).getInt(v16.length - 4));
        assertEquals(0, v16[v16.length - ADMIN_TAIL]);
        return java.util.Arrays.copyOf(v16, v16.length - ADMIN_TAIL);
    }

    @Test void schemaFifteenAndEarlierReadNoAdminPauseOrWaiver() throws Exception {
        byte[] v15 = withoutAdmin(schemaSixteenFixture(withTotems(Map.of())));
        ByteBuffer.wrap(v15).putInt(4, 15);
        assertEquals(withTotems(Map.of()), RoundSnapshotCodec.decode(v15));
    }

    /** The current encoding without its v16 tail and v15 quarry count, which snapshots with no camp hunt out write as 0. */
    private static byte[] withoutQuarries(byte[] v16) {
        byte[] v15 = withoutAdmin(v16);
        assertEquals(0, ByteBuffer.wrap(v15).getInt(v15.length - 4));
        return java.util.Arrays.copyOf(v15, v15.length - 4);
    }

    /** The current encoding without its trailing v15 quarry and v14 witch record counts, which empty tables write as 0. */
    private static byte[] withoutWitches(byte[] v15) {
        byte[] v14 = withoutQuarries(v15);
        assertEquals(0, ByteBuffer.wrap(v14).getInt(v14.length - 4));
        return java.util.Arrays.copyOf(v14, v14.length - 4);
    }

    @Test void schemaFourteenAndEarlierReadNoQuarry() throws Exception {
        byte[] v14 = withoutQuarries(schemaSixteenFixture(withTotems(Map.of())));
        ByteBuffer.wrap(v14).putInt(4, 14);
        assertEquals(withTotems(Map.of()), RoundSnapshotCodec.decode(v14));
    }

    @Test void sortiesRoundTripAndOnlyAMonsterOutHuntingKnowsItsQuarry() {
        var hunter = new Participant(VICTIM, "Victim", ParticipantRole.MONSTER_DEPLOYED, true, false,
                10, 0, 2, 0, MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 1, Map.of()).withSortie(Sortie.onto(KILLER, 3));
        var killer = new Participant(KILLER, "Killer", ParticipantRole.HUMAN_ACTIVE, true, false, 10, 0, 1, false);
        var snapshot = new RoundSnapshot(ROUND, 1, 1, 3, RulesConfig.defaults(), RoundPhase.RUNNING, PauseReason.NONE,
                900_000, 0, Map.of(VICTIM, hunter, KILLER, killer), false, false, null);
        byte[] bytes = RoundSnapshotCodec.encode(snapshot);
        assertEquals(snapshot, RoundSnapshotCodec.decode(bytes));
        assertEquals(new Sortie(java.util.Optional.of(KILLER), 3, true), RoundSnapshotCodec.decode(bytes).participants().get(VICTIM).sortie());
        byte[] v18 = asSchemaEighteen(bytes);
        assertEquals(snapshot, RoundSnapshotCodec.decode(v18));
        // Schema 17 wrote no landing flag: an outing out on its quarry then reads as landed.
        int landingAt = v18.length - ADMIN_TAIL - 1;
        byte[] v17 = new byte[v18.length - 1];
        System.arraycopy(v18, 0, v17, 0, landingAt);
        System.arraycopy(v18, landingAt + 1, v17, landingAt, v18.length - landingAt - 1);
        ByteBuffer.wrap(v17).putInt(4, 17);
        assertEquals(new Sortie(java.util.Optional.of(KILLER), 3), RoundSnapshotCodec.decode(v17).participants().get(VICTIM).sortie());
        // Name the human as the hunter instead: a human is never out on a hunt.
        int idAt = bytes.length - ADMIN_TAIL - (16 + 4 + 1 + 16 + 1);
        ByteBuffer.wrap(bytes).putLong(idAt, KILLER.getMostSignificantBits());
        ByteBuffer.wrap(bytes).putLong(idAt + 8, KILLER.getLeastSignificantBits());
        assertThrows(IllegalArgumentException.class, () -> RoundSnapshotCodec.decode(bytes));
    }

    /** The current encoding without its trailing v13 hunt-lock count, which lock-free monsters write as 0. */
    private static byte[] withoutHuntLocks(byte[] v13) {
        assertEquals(0, ByteBuffer.wrap(v13).getInt(v13.length - 4));
        return java.util.Arrays.copyOf(v13, v13.length - 4);
    }

    /** The current encoding without its trailing v15, v14, v13 and v11 counts, which empty tables write as 0. */
    private static byte[] withoutThreats(byte[] v15) {
        byte[] v11 = withoutHuntLocks(withoutWitches(v15));
        assertEquals(0, ByteBuffer.wrap(v11).getInt(v11.length - 4));
        return java.util.Arrays.copyOf(v11, v11.length - 4);
    }

    @Test void schemaThirteenAndEarlierReadNoWitchRecord() throws Exception {
        byte[] v13 = withoutWitches(schemaSixteenFixture(withTotems(Map.of())));
        ByteBuffer.wrap(v13).putInt(4, 13);
        assertEquals(withTotems(Map.of()), RoundSnapshotCodec.decode(v13));
    }

    @Test void witchRecordsRoundTripAndRejectUnknownParticipants() {
        var record = WitchRecord.NONE.withGuideSeen().supplied(2, Map.of(ShopOffer.BONUS_CHEST, 1, ShopOffer.BREAD, 2)).withLanded();
        var killer = new Participant(KILLER, "Killer", ParticipantRole.HUMAN_ACTIVE, true, false, 10, 0, 2, false).withWitch(record);
        var snapshot = new RoundSnapshot(ROUND, 1, 1, 3, RulesConfig.defaults(), RoundPhase.RUNNING, PauseReason.NONE,
                900_000, 0, Map.of(KILLER, killer), false, false, null);
        byte[] bytes = RoundSnapshotCodec.encode(snapshot);
        assertEquals(snapshot, RoundSnapshotCodec.decode(bytes));
        assertEquals(record, RoundSnapshotCodec.decode(bytes).participants().get(KILLER).witch());
        int countAt = bytes.length - ADMIN_TAIL - (4 + 4 + 16 + 1 + 4 + 1 + 4 + (4 + "bonusChest".length() + 4) + (4 + "bread".length() + 4));
        assertEquals(1, ByteBuffer.wrap(bytes).getInt(countAt));
        int idAt = countAt + 4;
        ByteBuffer.wrap(bytes).putLong(idAt, 99);
        assertThrows(IllegalArgumentException.class, () -> RoundSnapshotCodec.decode(bytes));
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
        assertEquals(20, ByteBuffer.wrap(bytes).getInt(4));
        var decoded = RoundSnapshotCodec.decode(bytes);
        assertEquals(snapshot, decoded);
        assertEquals(List.of(low, high, unclaimed), List.copyOf(decoded.looseTotems().keySet()), "Encoded sorted by token");
        var reordered = new java.util.LinkedHashMap<UUID, LooseTotem>();
        reordered.put(unclaimed, totems.get(unclaimed)); reordered.put(low, totems.get(low)); reordered.put(high, totems.get(high));
        assertArrayEquals(bytes, RoundSnapshotCodec.encode(withTotems(reordered)), "Deterministic regardless of insertion order");
    }

    @Test void schemasTenAndElevenReadEveryEntryAsAHumansDropNamingItsKiller() throws Exception {
        var token = new UUID(1, 0);
        byte[] v12 = withoutHuntLocks(withoutWitches(schemaSixteenFixture(withTotems(Map.of(token, byKiller(2, 700))))));
        // Drop the source text and the killer flag, which schema 12 added between the life and the killer.
        int sourceAt = v12.length - 4 - HUMAN_ENTRY + 16 + 16 + 4;
        byte[] v11 = new byte[v12.length - (4 + "HUMAN".length() + 1)];
        System.arraycopy(v12, 0, v11, 0, sourceAt);
        System.arraycopy(v12, sourceAt + 4 + "HUMAN".length() + 1, v11, sourceAt, v12.length - sourceAt - (4 + "HUMAN".length() + 1));
        ByteBuffer.wrap(v11).putInt(4, 11);
        assertEquals(withTotems(Map.of(token, byKiller(2, 700))), RoundSnapshotCodec.decode(v11));
    }

    @Test void schemaNineAndEarlierReadAnEmptyRegistry() throws Exception {
        byte[] v9 = withoutLooseTotems(schemaSixteenFixture(withTotems(Map.of())));
        ByteBuffer.wrap(v9).putInt(4, 9);
        var migrated = RoundSnapshotCodec.decode(v9);
        assertTrue(migrated.looseTotems().isEmpty());
        assertEquals(withTotems(Map.of()), migrated);
        assertEquals(20, ByteBuffer.wrap(RoundSnapshotCodec.encode(migrated)).getInt(4));
        assertTrue(RoundSnapshotCodec.decode(schemaOneFixtureUnchecked()).looseTotems().isEmpty());
    }

    @Test void schemaTenAndEarlierReadNoThreat() throws Exception {
        byte[] v10 = withoutThreats(schemaSixteenFixture(withTotems(Map.of())));
        ByteBuffer.wrap(v10).putInt(4, 10);
        assertEquals(withTotems(Map.of()), RoundSnapshotCodec.decode(v10));
    }

    @Test void schemaTwelveAndEarlierReadNoHuntLock() throws Exception {
        byte[] v12 = withoutHuntLocks(withoutWitches(schemaSixteenFixture(withTotems(Map.of()))));
        ByteBuffer.wrap(v12).putInt(4, 12);
        assertEquals(withTotems(Map.of()), RoundSnapshotCodec.decode(v12));
    }

    @Test void huntLocksRoundTripDeterministicallyAndRejectCorruption() {
        var first = new java.util.LinkedHashMap<UUID, Long>();
        first.put(new UUID(9, 0), 5_000L); first.put(KILLER, 45_000_000_000L);
        var reordered = new java.util.LinkedHashMap<UUID, Long>();
        reordered.put(KILLER, 45_000_000_000L); reordered.put(new UUID(9, 0), 5_000L);
        var plain = withTotems(Map.of());
        var locked = withLocks(plain, first);
        byte[] bytes = RoundSnapshotCodec.encode(locked);
        assertEquals(locked, RoundSnapshotCodec.decode(bytes));
        assertArrayEquals(bytes, RoundSnapshotCodec.encode(withLocks(plain, reordered)), "Deterministic regardless of insertion order");
        // The last lock's remaining time must stay positive.
        byte[] spent = bytes.clone();
        ByteBuffer.wrap(spent).putLong(spent.length - ADMIN_TAIL - 4 - 4 - 8, 0);
        assertThrows(IllegalArgumentException.class, () -> RoundSnapshotCodec.decode(spent));
        // A holder with no lock is never written.
        byte[] empty = bytes.clone();
        ByteBuffer.wrap(empty).putInt(bytes.length - ADMIN_TAIL - 4 - 4 - 2 * (16 + 8) - 4, 0);
        assertThrows(IllegalArgumentException.class, () -> RoundSnapshotCodec.decode(empty));
    }

    private static RoundSnapshot withLocks(RoundSnapshot base, Map<UUID, Long> locks) {
        var players = new java.util.LinkedHashMap<>(base.participants());
        players.put(VICTIM, players.get(VICTIM).withHuntLocks(locks));
        return new RoundSnapshot(ROUND, 1, 1, 3, base.rules(), base.phase(), base.pauseReason(), base.challengeNanos(), 0,
                players, false, false, null, base.looseTotems());
    }

    @Test void threatsForUnknownParticipantsAreRejected() {
        var killer = new Participant(KILLER, "Killer", ParticipantRole.HUMAN_ACTIVE, true, false, 10, 0, 1, false)
                .withThreat(new HumanThreat(1, 1, java.util.Set.of()));
        var snapshot = new RoundSnapshot(ROUND, 1, 1, 3, RulesConfig.defaults(), RoundPhase.RUNNING, PauseReason.NONE,
                900_000, 0, Map.of(KILLER, killer), false, false, null);
        byte[] bytes = RoundSnapshotCodec.encode(snapshot);
        assertEquals(snapshot, RoundSnapshotCodec.decode(bytes));
        int idAt = bytes.length - ADMIN_TAIL - 4 - 4 - (16 + 16);
        assertEquals(KILLER.getMostSignificantBits(), ByteBuffer.wrap(bytes).getLong(idAt));
        ByteBuffer.wrap(bytes).putLong(idAt, 99);
        assertThrows(IllegalArgumentException.class, () -> RoundSnapshotCodec.decode(bytes));
    }

    @Test void corruptRegistryCountsAndDuplicatesAreRejected() {
        var token = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        byte[] one = RoundSnapshotCodec.encode(withTotems(Map.of(token, byKiller(1, 5))));
        int countAt = one.length - ADMIN_TAIL - (4 + 4 + HUMAN_ENTRY) - 4 - 4 - 4;
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
        ByteBuffer.wrap(negative).putLong(one.length - ADMIN_TAIL - 4 - 4 - 4 - 4 - 8, -1);
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
