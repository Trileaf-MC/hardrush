package dev.hardrush.persistence;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.book.HistoryBookComposer;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.combat.CombatEventCodec;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.record.AdminAudit;
import dev.hardrush.core.record.HistoryRevision;
import dev.hardrush.core.round.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AdminHistoryStoreTest {
    @TempDir Path directory;
    private final UUID round = UUID.randomUUID(), player = UUID.randomUUID(), dragon = UUID.randomUUID();
    private Path database() throws Exception { return directory.toRealPath().resolve("admin.sqlite"); }

    @Test void snapshotAuditIsAtomicIdempotentAndOperationIdentityIncludesEveryAuditField() throws Exception {
        Path db = database(); UUID operation = UUID.randomUUID();
        try (var store = new SqliteRoundStore(db)) {
            create(store); var before = RoundSnapshotCodec.decode(store.load(round).orElseThrow().payload());
            var after = snapshot(before, 1, null, 200, false);
            var write = new SnapshotWrite(round, 0, 1, RoundSnapshotCodec.encode(after), operation, "ADMIN_CHECKPOINT");
            var audit = audit(operation, "CHECKPOINT", "0", "1");
            assertEquals(CommitResult.APPLIED, store.commitAdmin(write, audit));
            assertEquals(CommitResult.DUPLICATE, store.commitAdmin(write, audit));
            assertEquals(List.of(audit), store.audits(round, 10, 0));
            for (AdminAudit conflict : List.of(
                    new AdminAudit(operation, round, "another-admin", audit.action(), audit.reason(), "0", "1", 1000),
                    new AdminAudit(operation, round, audit.actor(), "START", audit.reason(), "0", "1", 1000),
                    new AdminAudit(operation, round, audit.actor(), audit.action(), "different reason", "0", "1", 1000),
                    new AdminAudit(operation, round, audit.actor(), audit.action(), audit.reason(), "bad-before", "1", 1000),
                    new AdminAudit(operation, round, audit.actor(), audit.action(), audit.reason(), "0", "bad-after", 1000),
                    new AdminAudit(operation, round, audit.actor(), audit.action(), audit.reason(), "0", "1", 1001))) {
                assertThrows(EventCollisionException.class, () -> store.commitAdmin(write, conflict));
            }
            var stale = new SnapshotWrite(round, 0, 1, write.payload(), UUID.randomUUID(), "ADMIN_CHECKPOINT");
            assertEquals(CommitResult.STALE, store.commitAdmin(stale, audit(stale.eventId(), "CHECKPOINT", "0", "1")));
            assertEquals(1, store.audits(null, 10, 0).size());
        }
        try (var store = new SqliteRoundStore(db)) { assertEquals(1, store.audits(round, 10, 0).size()); }
    }

    @Test void auditFailureRollsBackSnapshotPreparedOperationsAndRulesWithRetry() throws Exception {
        Path db = database();
        try (var store = new SqliteRoundStore(db); var raw = DriverManager.getConnection("jdbc:sqlite:" + db); var sql = raw.createStatement()) {
            create(store); var before = RoundSnapshotCodec.decode(store.load(round).orElseThrow().payload());
            UUID operation = UUID.randomUUID();
            var write = new SnapshotWrite(round, 0, 1, RoundSnapshotCodec.encode(snapshot(before, 1, null, 200, false)), operation,
                    "ADMIN_CHECKPOINT", List.of(new PreparedOperation(UUID.randomUUID(), "SAVE", 1, new byte[]{7})));
            var audit = audit(operation, "CHECKPOINT", "0", "1");
            store.savePendingRules(new byte[]{1}, UUID.randomUUID(), "old-admin");
            UUID rulesOperation = UUID.randomUUID(); var rulesAudit = audit(rulesOperation, "RULES", "old", "new");
            sql.execute("CREATE TRIGGER fail_audit BEFORE INSERT ON admin_audits BEGIN SELECT RAISE(ABORT,'injected'); END");
            assertThrows(SQLException.class, () -> store.commitAdmin(write, audit));
            assertEquals(0, store.load(round).orElseThrow().version()); assertTrue(store.operations(round).isEmpty());
            assertThrows(SQLException.class, () -> store.savePendingRulesAdmin(new byte[]{2}, rulesOperation, rulesAudit));
            assertArrayEquals(new byte[]{1}, store.loadPendingRules().orElseThrow()); assertTrue(store.audits(null, 10, 0).isEmpty());
            sql.execute("DROP TRIGGER fail_audit");
            assertEquals(CommitResult.APPLIED, store.commitAdmin(write, audit));
            assertEquals(CommitResult.APPLIED, store.savePendingRulesAdmin(new byte[]{2}, rulesOperation, rulesAudit));
            assertEquals(CommitResult.DUPLICATE, store.savePendingRulesAdmin(new byte[]{2}, rulesOperation, rulesAudit));
            assertThrows(EventCollisionException.class, () -> store.savePendingRulesAdmin(new byte[]{3}, rulesOperation, rulesAudit));
            assertEquals(2, store.audits(null, 10, 0).size()); assertEquals(1, store.operations(round).size());
        }
    }

    @Test void standaloneAuditUsesSharedIdentityNamespaceAndReadOnlyBoundedQueries() throws Exception {
        Path db = database();
        try (var store = new SqliteRoundStore(db); var raw = DriverManager.getConnection("jdbc:sqlite:" + db); var sql = raw.createStatement()) {
            create(store); UUID operation = UUID.randomUUID(); var audit = audit(operation, "VERIFY_READ", "unchanged", "unchanged");
            assertEquals(CommitResult.APPLIED, store.recordAdminAudit(audit)); assertEquals(CommitResult.DUPLICATE, store.recordAdminAudit(audit));
            assertThrows(EventCollisionException.class, () -> store.recordEvidence(new EvidenceWrite(round, operation, "COMBAT", new byte[]{1})));
            assertEquals(List.of(audit), store.audits(round, 1, 0)); assertTrue(store.audits(round, 1, 1).isEmpty());
            assertEquals(1, store.audits(null, 10, 0).size());
            var global = new AdminAudit(UUID.randomUUID(), null, "server-admin", "STATUS", "检查服务器状态", "", "unchanged", 1001);
            assertEquals(CommitResult.APPLIED, store.recordAdminAudit(global));
            assertEquals(List.of(global, audit), store.audits(null, 10, 0));
            assertEquals(List.of(audit), store.audits(round, 10, 0));
            assertThrows(IllegalArgumentException.class, () -> store.audits(null, 1001, 0));
            assertThrows(IllegalArgumentException.class, () -> store.audits(round, 1, -1));
            sql.execute("UPDATE admin_audits SET reason='changed'");
            assertThrows(SQLException.class, () -> store.audits(round, 10, 0));
            assertThrows(SQLException.class, () -> store.recordAdminAudit(audit));
        }
    }

    @Test void correctionAndExplicitVerificationAppendNewEditionsPreservingOriginalEvidenceAndBooks() throws Exception {
        Path db = database(); HistoryRevision correction;
        byte[] snapshotBytes, terminalBytes, evidenceBytes;
        try (var store = new SqliteRoundStore(db)) {
            create(store); finish(store, true, false);
            snapshotBytes = store.load(round).orElseThrow().payload(); terminalBytes = store.terminal(round).orElseThrow().snapshot().payload();
            evidenceBytes = store.terminal(round).orElseThrow().evidence().payload();
            assertEquals(1, store.leaderboard(RulesConfig.defaults().fingerprint(), 10).size());
            store.ensureAutomaticBookClaim(player, round, 1, 1); store.markBookDelivered(player, round, 1, 1);
            correction = revision(1, 5_000_000, false, HistoryRevision.Action.CORRECT, player);
            var audit = audit(correction.operationId(), "HISTORY_CORRECT", "10 ms", "5 ms");
            assertEquals(CommitResult.APPLIED, store.reviseHistory(correction, audit));
            assertEquals(CommitResult.DUPLICATE, store.reviseHistory(correction, audit));
            var history = store.roundHistory(round).orElseThrow(); assertEquals(2, history.edition());
            assertEquals(5_000_000, history.challengeNanos()); assertFalse(history.trustedWinner());
            assertTrue(store.leaderboard(history.rulesFingerprint(), 10).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> store.ensureAutomaticBookClaim(player, round, 1, 1));
            assertFalse(store.ensureAutomaticBookClaim(player, round, 2, 1).delivered());
            var book = HistoryBookComposer.compose(history, history.rulesFingerprint(), 1, 1,
                    store.participantRecords(round, 40, 0), store.leaderboard(history.rulesFingerprint(), 10));
            assertEquals(2, book.edition()); assertTrue(String.join("", book.pages()).contains("书版 2"));
            assertTrue(String.join("", book.pages()).contains("归因：待核验"));
            var verify = revision(2, 5_000_000, false, HistoryRevision.Action.VERIFY, player);
            assertEquals(CommitResult.APPLIED, store.reviseHistory(verify, audit(verify.operationId(), "HISTORY_VERIFY", "pending", "verified")));
            assertEquals(3, store.roundHistory(round).orElseThrow().edition());
            assertEquals(5, store.leaderboard(history.rulesFingerprint(), 10).getFirst().round().challengeMillis());
            assertArrayEquals(snapshotBytes, store.load(round).orElseThrow().payload());
            assertArrayEquals(terminalBytes, store.terminal(round).orElseThrow().snapshot().payload());
            assertArrayEquals(evidenceBytes, store.terminal(round).orElseThrow().evidence().payload());
            assertEquals(2, store.historyRevisions(round, 10, 0).size()); assertEquals(2, store.audits(round, 10, 0).size());
            assertEquals(CommitResult.DUPLICATE, store.reviseHistory(correction, audit));
        }
        try (var store = new SqliteRoundStore(db)) {
            assertEquals(3, store.history(10, 0).getFirst().edition());
            assertEquals(2, store.latestAutomaticBookClaim(player).orElseThrow().edition());
            assertArrayEquals(snapshotBytes, store.load(round).orElseThrow().payload());
        }
    }

    @Test void revisionCasRejectsCompetingAdministratorAndChangedRetryWithoutAudit() throws Exception {
        Path db = database();
        try (var first = new SqliteRoundStore(db); var second = new SqliteRoundStore(db)) {
            create(first); finish(first, true, false);
            var a = revision(1, 5_000_000, false, HistoryRevision.Action.CORRECT, player);
            var b = revision(1, 3_000_000, false, HistoryRevision.Action.CORRECT, player);
            var audit = audit(a.operationId(), "HISTORY_CORRECT", "old", "new");
            assertEquals(CommitResult.APPLIED, first.reviseHistory(a, audit));
            assertEquals(CommitResult.STALE, second.reviseHistory(b, audit(b.operationId(), "HISTORY_CORRECT", "old", "other")));
            var changed = new HistoryRevision(a.operationId(), round, 1, a.result(), 6_000_000, false, a.action());
            assertThrows(EventCollisionException.class, () -> second.reviseHistory(changed, audit));
            assertEquals(1, second.audits(round, 10, 0).size()); assertEquals(5_000_000, second.roundHistory(round).orElseThrow().challengeNanos());
        }
    }

    @Test void failedHistoryProjectionTransactionLeavesNeitherAuditNorRevisionAndSameRetryApplies() throws Exception {
        Path db = database();
        try (var store = new SqliteRoundStore(db); var raw = DriverManager.getConnection("jdbc:sqlite:" + db); var sql = raw.createStatement()) {
            create(store); finish(store, true, false);
            var request = revision(1, 5_000_000, false, HistoryRevision.Action.CORRECT, player);
            var audit = audit(request.operationId(), "HISTORY_CORRECT", "old", "new");
            sql.execute("CREATE TRIGGER fail_projection BEFORE UPDATE ON history_rounds BEGIN SELECT RAISE(ABORT,'injected'); END");
            assertThrows(SQLException.class, () -> store.reviseHistory(request, audit));
            assertTrue(store.audits(round, 10, 0).isEmpty()); assertTrue(store.historyRevisions(round, 10, 0).isEmpty());
            assertEquals(1, store.roundHistory(round).orElseThrow().edition());
            sql.execute("DROP TRIGGER fail_projection"); assertEquals(CommitResult.APPLIED, store.reviseHistory(request, audit));
        }
    }

    @Test void legacyAndUnknownOriginalWinnerCannotBeInventedOrVerifiedIntoLeaderboard() throws Exception {
        Path db = database();
        try (var store = new SqliteRoundStore(db)) {
            create(store); finish(store, false, false);
            var correction = revision(1, 5_000_000, false, HistoryRevision.Action.CORRECT, player);
            assertEquals(CommitResult.APPLIED, store.reviseHistory(correction, audit(correction.operationId(), "HISTORY_CORRECT", "unknown", "claimed")));
            var verification = revision(2, 5_000_000, false, HistoryRevision.Action.VERIFY, player);
            assertThrows(IllegalArgumentException.class, () -> store.reviseHistory(verification, audit(verification.operationId(), "HISTORY_VERIFY", "claimed", "verified")));
            assertTrue(store.leaderboard(RulesConfig.defaults().fingerprint(), 10).isEmpty()); assertEquals(1, store.audits(round, 10, 0).size());
        }
        Path legacy = directory.toRealPath().resolve("legacy.sqlite");
        try (var store = new SqliteRoundStore(legacy)) {
            create(store); var before = RoundSnapshotCodec.decode(store.load(round).orElseThrow().payload());
            var completion = snapshot(before, 1, new RoundResult(RoundResult.Kind.COMPLETED, player, "legacy"), 10_000_000, false);
            store.commit(new SnapshotWrite(round, 0, 1, RoundSnapshotCodec.encode(completion), UUID.randomUUID(), "FINISH"));
            var request = revision(1, 5_000_000, false, HistoryRevision.Action.VERIFY, player);
            assertThrows(IllegalArgumentException.class, () -> store.reviseHistory(request, audit(request.operationId(), "HISTORY_VERIFY", "legacy", "verified")));
            assertTrue(store.audits(round, 10, 0).isEmpty()); assertTrue(store.leaderboard(RulesConfig.defaults().fingerprint(), 10).isEmpty());
        }
    }

    @Test void verificationCanResolveUncertainTimingButCannotChangeTrustedFirstDragonWinner() throws Exception {
        Path db = database();
        try (var store = new SqliteRoundStore(db)) {
            create(store); finish(store, true, true);
            assertTrue(store.leaderboard(RulesConfig.defaults().fingerprint(), 10).isEmpty());
            var request = revision(1, 9_000_000, false, HistoryRevision.Action.VERIFY, player);
            assertEquals(CommitResult.APPLIED, store.reviseHistory(request, audit(request.operationId(), "HISTORY_VERIFY", "uncertain", "checked timing")));
            assertEquals(1, store.leaderboard(RulesConfig.defaults().fingerprint(), 10).size());
            var wrong = revision(2, 9_000_000, false, HistoryRevision.Action.VERIFY, UUID.randomUUID());
            assertThrows(IllegalArgumentException.class, () -> store.reviseHistory(wrong, audit(wrong.operationId(), "HISTORY_VERIFY", "winner", "wrong winner")));
            assertTrue(RoundSnapshotCodec.decode(store.terminal(round).orElseThrow().snapshot().payload()).timingUncertain());
        }
    }

    @Test void schemaFourMigrationPreservesHistoriesClaimsAndLegacyTrustWithoutFabrication() throws Exception {
        Path db = database();
        try (var store = new SqliteRoundStore(db)) { create(store); finish(store, true, false); store.ensureAutomaticBookClaim(player, round, 1, 1); }
        downgradeToFour(db);
        try (var store = new SqliteRoundStore(db)) {
            assertEquals(1, store.roundHistory(round).orElseThrow().edition()); assertTrue(store.audits(null, 10, 0).isEmpty());
            assertEquals(1, store.leaderboard(RulesConfig.defaults().fingerprint(), 10).size());
            assertEquals(1, store.latestAutomaticBookClaim(player).orElseThrow().edition());
            var request = revision(1, 5_000_000, false, HistoryRevision.Action.CORRECT, player);
            assertEquals(CommitResult.APPLIED, store.reviseHistory(request, audit(request.operationId(), "HISTORY_CORRECT", "old", "new")));
        }
    }

    @Test void migrationFailureRollsBackSchemaVersionAndNewColumnsThenRepairCanRetry() throws Exception {
        Path db = database();
        try (var store = new SqliteRoundStore(db)) { create(store); finish(store, true, false); }
        downgradeToFour(db);
        try (var raw = DriverManager.getConnection("jdbc:sqlite:" + db); var sql = raw.createStatement()) { sql.execute("CREATE TABLE admin_audits(bad TEXT)"); }
        assertThrows(SQLException.class, () -> new SqliteRoundStore(db));
        try (var raw = DriverManager.getConnection("jdbc:sqlite:" + db); var sql = raw.createStatement()) {
            try (var rows = sql.executeQuery("PRAGMA user_version")) { assertTrue(rows.next()); assertEquals(4, rows.getInt(1)); }
            try (var rows = sql.executeQuery("PRAGMA table_info(history_rounds)")) { while (rows.next()) assertNotEquals("edition", rows.getString("name")); }
            sql.execute("DROP TABLE admin_audits");
        }
        try (var store = new SqliteRoundStore(db)) { assertEquals(1, store.roundHistory(round).orElseThrow().edition()); }
    }

    @Test void revisedHistoryTamperingOrMissingRevisionOrCorruptedEvidenceFailsVerification() throws Exception {
        Path db = database();
        try (var store = new SqliteRoundStore(db); var raw = DriverManager.getConnection("jdbc:sqlite:" + db); var sql = raw.createStatement()) {
            create(store); finish(store, true, false);
            var request = revision(1, 5_000_000, false, HistoryRevision.Action.CORRECT, player);
            store.reviseHistory(request, audit(request.operationId(), "HISTORY_CORRECT", "old", "new"));
            sql.execute("UPDATE history_rounds SET edition=3"); assertThrows(SQLException.class, () -> store.roundHistory(round));
            sql.execute("UPDATE history_rounds SET edition=2");
            sql.execute("UPDATE admin_audits SET after_value='forged'"); assertThrows(SQLException.class, () -> store.roundHistory(round));
            sql.execute("UPDATE admin_audits SET after_value='new'");
            sql.execute("UPDATE round_terminals SET first_dragon_id='00000000-0000-0000-0000-000000000000'");
            assertThrows(SQLException.class, () -> store.roundHistory(round));
            try (var statement = raw.prepareStatement("UPDATE round_terminals SET first_dragon_id=?")) {
                statement.setString(1, dragon.toString()); statement.executeUpdate();
            }
            sql.execute("UPDATE history_revisions SET before_digest=zeroblob(32)"); assertThrows(SQLException.class, () -> store.roundHistory(round));
            sql.execute("UPDATE history_revisions SET before_digest=(SELECT payload_digest FROM history_originals WHERE history_originals.round_id=history_revisions.round_id)");
            assertEquals(2, store.roundHistory(round).orElseThrow().edition());
            sql.execute("DELETE FROM history_revisions"); assertThrows(SQLException.class, () -> store.roundHistory(round));
        }
    }

    private void downgradeToFour(Path db) throws Exception {
        try (var raw = DriverManager.getConnection("jdbc:sqlite:" + db); var sql = raw.createStatement()) {
            sql.execute("DROP TRIGGER capture_history_original"); sql.execute("DROP TABLE history_revisions");
            sql.execute("DROP TABLE history_originals"); sql.execute("DROP TABLE admin_audits");
            sql.execute("ALTER TABLE history_rounds DROP COLUMN edition"); sql.execute("PRAGMA user_version=4");
        }
    }

    private AdminAudit audit(UUID operation, String action, String before, String after) {
        return new AdminAudit(operation, round, "admin-uuid", action, "人工检查原始证据后的原因", before, after, 1000);
    }
    private HistoryRevision revision(long edition, long nanos, boolean uncertain, HistoryRevision.Action action, UUID winner) {
        return new HistoryRevision(UUID.randomUUID(), round, edition, new RoundResult(RoundResult.Kind.COMPLETED, winner, "checked result"), nanos, uncertain, action);
    }
    private void create(SqliteRoundStore store) throws Exception {
        var participant = new Participant(player, "Alice", ParticipantRole.HUMAN_ACTIVE, true, false, 100, 0, 1, false);
        var value = new RoundSnapshot(round, 1, 123, 0, RulesConfig.defaults(), RoundPhase.RUNNING, PauseReason.NONE, 100, 0,
                Map.of(player, participant), false, false, null);
        store.commit(new SnapshotWrite(round, -1, 0, RoundSnapshotCodec.encode(value), UUID.randomUUID(), "ROUND_CREATED"));
    }
    private RoundSnapshot snapshot(RoundSnapshot before, long version, RoundResult result, long nanos, boolean uncertain) {
        return new RoundSnapshot(round, before.sequence(), before.seed(), version, before.rules(), result == null ? RoundPhase.RUNNING : RoundPhase.SETTLING,
                result == null ? PauseReason.NONE : PauseReason.SETTLED, nanos, 0, before.participants(), false, uncertain, result);
    }
    private void finish(SqliteRoundStore store, boolean known, boolean uncertain) throws Exception {
        var before = RoundSnapshotCodec.decode(store.load(round).orElseThrow().payload());
        var origin = known ? Optional.of(new CombatOrigin(round, UUID.randomUUID(), new CombatActor(player, ParticipantRole.HUMAN_ACTIVE, 1, 0),
                AttackKind.MELEE, Optional.of(player), List.of())) : Optional.<CombatOrigin>empty();
        var death = new CombatEvent(round, UUID.randomUUID(), UUID.randomUUID(), 1, dragon, origin, CombatEvent.classify(round, origin),
                "minecraft:player", Optional.of(player), Optional.of(player), 1, true, 100);
        var settled = snapshot(before, 1, DragonDeathArbiter.adjudicate(before, death, dragon), 10_000_000, uncertain);
        store.commitTerminal(new SnapshotWrite(round, 0, 1, RoundSnapshotCodec.encode(settled), UUID.randomUUID(), "FINISH"),
                new EvidenceWrite(round, death.eventId(), "COMBAT", CombatEventCodec.encode(death)), dragon);
    }
}
