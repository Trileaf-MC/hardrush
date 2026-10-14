package dev.hardrush.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SqliteRoundStoreTest {
    @TempDir Path temporaryDirectory;
    private Path database;
    private final UUID round = UUID.randomUUID();

    @BeforeEach
    void realDatabasePath() throws IOException {
        // macOS's system temporary path can itself be a symlink; the store accepts only real parents.
        database = temporaryDirectory.toRealPath().resolve("rounds.sqlite");
    }

    @Test
    void snapshotCommitUsesVersionsAndLatestTracksCreationRatherThanLateOldCheckpoints() throws Exception {
        UUID otherRound = UUID.randomUUID();
        try (var store = new SqliteRoundStore(database)) {
            assertTrue(store.latest().isEmpty());
            assertEquals(CommitResult.STALE, store.commit(write(round, 0, "future")));
            assertTrue(store.load(round).isEmpty());
            assertEquals(CommitResult.APPLIED, store.commit(write(round, -1, "initial")));
            assertEquals(CommitResult.STALE, store.commit(write(round, -1, "stale")));
            assertEquals("initial", text(store.load(round).orElseThrow().payload()));
            assertEquals(CommitResult.APPLIED, store.commit(write(otherRound, -1, "other")));
            assertEquals(otherRound, store.latest().orElseThrow().roundId());
            assertEquals(CommitResult.APPLIED, store.commit(write(round, 0, "updated")));
            var latest = store.latest().orElseThrow();
            assertEquals(otherRound, latest.roundId(), "A late old-round checkpoint must not become the active round on reopen");
            assertEquals(0, latest.version());
            assertEquals("other", text(latest.payload()));
            assertEquals("updated", text(store.load(round).orElseThrow().payload()));
        }
        try (var raw = raw(); var statement = raw.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM store_events")) {
            assertTrue(rows.next());
            assertEquals(3, rows.getInt(1), "Stale writes must not leave events");
        }
    }

    @Test
    void identicalEventsReplayAfterLaterCommitsButEventCollisionsRejectEveryChangedField() throws Exception {
        UUID event = UUID.randomUUID();
        var initial = new SnapshotWrite(round, -1, 0, bytes("initial"), event, "ROUND_CREATED");
        try (var store = new SqliteRoundStore(database)) {
            assertEquals(CommitResult.APPLIED, store.commit(initial));
            store.commit(write(round, 0, "later"));
            assertEquals(CommitResult.DUPLICATE, store.commit(initial));
            assertThrows(EventCollisionException.class, () -> store.commit(
                    new SnapshotWrite(round, -1, 0, bytes("different"), event, "ROUND_CREATED")));
            assertThrows(EventCollisionException.class, () -> store.commit(
                    new SnapshotWrite(round, -1, 0, bytes("initial"), event, "DIFFERENT_TYPE")));
            assertThrows(EventCollisionException.class, () -> store.commit(
                    new SnapshotWrite(UUID.randomUUID(), -1, 0, bytes("initial"), event, "ROUND_CREATED")));
            assertThrows(EventCollisionException.class, () -> store.commit(
                    new SnapshotWrite(round, 0, 1, bytes("initial"), event, "ROUND_CREATED")));
            assertEquals("later", text(store.load(round).orElseThrow().payload()));
        }
    }

    @Test
    void operationCollisionRollsBackSnapshotEventAndOtherPreparedOperations() throws Exception {
        UUID existingOperation = UUID.randomUUID();
        UUID newOperation = UUID.randomUUID();
        UUID failedEvent = UUID.randomUUID();
        try (var store = new SqliteRoundStore(database)) {
            store.commit(new SnapshotWrite(round, -1, 0, bytes("before"), UUID.randomUUID(), "INITIAL",
                    List.of(new PreparedOperation(existingOperation, "RESET", 0, bytes("old")))));
            var failure = new SnapshotWrite(round, 0, 1, bytes("after"), failedEvent, "RESET",
                    List.of(new PreparedOperation(newOperation, "RESET", 1, bytes("new")),
                            new PreparedOperation(existingOperation, "RESET", 1, bytes("collision"))));
            assertThrows(SQLException.class, () -> store.commit(failure));
            assertEquals(0, store.load(round).orElseThrow().version());
            assertEquals("before", text(store.load(round).orElseThrow().payload()));
            assertEquals(1, store.operations(round).size());
            assertTrue(store.operation(round, newOperation).isEmpty());
            assertEquals(CommitResult.APPLIED, store.commit(
                    new SnapshotWrite(round, 0, 1, bytes("retried"), failedEvent, "RESET")),
                    "The rolled-back event ID must be available for the retry");
        }
    }

    @Test
    void duplicateDigestIncludesPreparedOperationPayloadAndIgnoresListOrdering() throws Exception {
        UUID event = UUID.randomUUID();
        var a = new PreparedOperation(UUID.randomUUID(), "RESET", 0, bytes("a"));
        var b = new PreparedOperation(UUID.randomUUID(), "BOOK", 0, bytes("b"));
        try (var store = new SqliteRoundStore(database)) {
            store.commit(new SnapshotWrite(round, -1, 0, bytes("state"), event, "INITIAL", List.of(a, b)));
            assertEquals(CommitResult.DUPLICATE, store.commit(
                    new SnapshotWrite(round, -1, 0, bytes("state"), event, "INITIAL", List.of(b, a))));
            assertThrows(EventCollisionException.class, () -> store.commit(new SnapshotWrite(round, -1, 0,
                    bytes("state"), event, "INITIAL", List.of(
                            new PreparedOperation(a.operationId(), "RESET", 0, bytes("changed")), b))));
        }
    }

    @Test
    void operationsRecoverAcrossReopenWithMonotonicStageAndIndependentVersionCas() throws Exception {
        UUID operationId = UUID.randomUUID();
        try (var store = new SqliteRoundStore(database)) {
            store.commit(new SnapshotWrite(round, -1, 0, bytes("state"), UUID.randomUUID(), "INITIAL",
                    List.of(new PreparedOperation(operationId, "PLAYER_RESET", 0, bytes("target")))));
        }
        try (var store = new SqliteRoundStore(database)) {
            var prepared = store.pendingOperations(round).getFirst();
            assertEquals(OperationStage.PREPARED, prepared.stage());
            assertEquals(0, prepared.expectedVersion());
            assertEquals(0, prepared.version());
            assertThrows(IllegalArgumentException.class,
                    () -> store.advanceOperation(round, operationId, 0, OperationStage.VERIFIED));
            assertEquals(OperationAdvanceResult.STALE,
                    store.advanceOperation(UUID.randomUUID(), operationId, 0, OperationStage.APPLIED));
            assertEquals(OperationAdvanceResult.APPLIED,
                    store.advanceOperation(round, operationId, 0, OperationStage.APPLIED));
            assertEquals(OperationAdvanceResult.DUPLICATE,
                    store.advanceOperation(round, operationId, 0, OperationStage.APPLIED));
            store.commit(write(round, 0, "newer round state"));
            assertEquals(OperationAdvanceResult.STALE,
                    store.advanceOperation(round, operationId, 0, OperationStage.VERIFIED));
            assertEquals(OperationAdvanceResult.APPLIED,
                    store.advanceOperation(round, operationId, 1, OperationStage.VERIFIED));
            assertEquals(OperationAdvanceResult.DUPLICATE,
                    store.advanceOperation(round, operationId, 1, OperationStage.VERIFIED));
            assertEquals(OperationAdvanceResult.DUPLICATE,
                    store.advanceOperation(round, operationId, 0, OperationStage.APPLIED),
                    "A historical retry must not undo or fail a completed verification");
            assertTrue(store.pendingOperations(round).isEmpty());
            assertEquals(2, store.operation(round, operationId).orElseThrow().version());
            assertThrows(IllegalArgumentException.class,
                    () -> store.advanceOperation(round, operationId, 2, OperationStage.PREPARED));
        }
    }

    @Test
    void pendingRulesPersistWithAuditedIdempotencyAndGlobalEventCollisionChecks() throws Exception {
        UUID configEvent = UUID.randomUUID();
        UUID secondEvent = UUID.randomUUID();
        byte[] supplied = bytes("normal");
        try (var store = new SqliteRoundStore(database)) {
            assertTrue(store.loadPendingRules().isEmpty());
            assertEquals(CommitResult.APPLIED, store.savePendingRules(supplied, configEvent, "admin"));
            supplied[0] = 0;
            assertEquals(CommitResult.DUPLICATE, store.savePendingRules(bytes("normal"), configEvent, "admin"));
            assertThrows(EventCollisionException.class,
                    () -> store.savePendingRules(bytes("normal"), configEvent, "other admin"));
            assertThrows(EventCollisionException.class,
                    () -> store.savePendingRules(bytes("extreme"), configEvent, "admin"));
            assertThrows(EventCollisionException.class, () -> store.commit(
                    new SnapshotWrite(round, -1, 0, bytes("normal"), configEvent, "ROUND_CREATED")));
            assertEquals(CommitResult.APPLIED, store.savePendingRules(bytes("extreme"), secondEvent, "admin"));
            assertEquals(CommitResult.DUPLICATE, store.savePendingRules(bytes("normal"), configEvent, "admin"));
            assertEquals("extreme", text(store.loadPendingRules().orElseThrow()), "Old retries must not undo newer config");
            byte[] loaded = store.loadPendingRules().orElseThrow();
            loaded[0] = 0;
            assertEquals("extreme", text(store.loadPendingRules().orElseThrow()));
            assertThrows(IllegalArgumentException.class,
                    () -> store.savePendingRules(bytes("bad"), UUID.randomUUID(), " "));
            assertThrows(IllegalArgumentException.class,
                    () -> store.savePendingRules(bytes("bad"), UUID.randomUUID(), "x".repeat(257)));
        }
        try (var store = new SqliteRoundStore(database); var raw = raw(); var statement = raw.createStatement()) {
            assertEquals("extreme", text(store.loadPendingRules().orElseThrow()));
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM config_events WHERE actor='admin'")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
        }
    }

    @Test
    void failedConfigAuditRollsBackConfigAndEventRegistration() throws Exception {
        UUID retryEvent = UUID.randomUUID();
        try (var store = new SqliteRoundStore(database); var raw = raw(); var sql = raw.createStatement()) {
            store.savePendingRules(bytes("before"), UUID.randomUUID(), "admin");
            sql.execute("CREATE TRIGGER fail_config BEFORE INSERT ON config_events BEGIN SELECT RAISE(ABORT, 'injected'); END");
            assertThrows(SQLException.class, () -> store.savePendingRules(bytes("after"), retryEvent, "admin"));
            assertEquals("before", text(store.loadPendingRules().orElseThrow()));
            sql.execute("DROP TRIGGER fail_config");
            assertEquals(CommitResult.APPLIED, store.savePendingRules(bytes("after"), retryEvent, "admin"));
        }
    }

    @Test
    void nativeWriteLockFailureLeavesNoPartialCommitAndStoreCanRetry() throws Exception {
        try (var store = new SqliteRoundStore(database, 30); var lock = raw(); var sql = lock.createStatement()) {
            store.commit(write(round, -1, "before"));
            lock.setAutoCommit(false);
            sql.executeUpdate("UPDATE round_snapshots SET payload=payload");
            var next = write(round, 0, "after");
            var failure = assertThrows(SQLException.class, () -> store.commit(next));
            assertTrue(failure.getMessage().contains("SQLITE_BUSY"));
            assertEquals("before", text(store.load(round).orElseThrow().payload()));
            lock.rollback();
            assertEquals(CommitResult.APPLIED, store.commit(next));
        }
        try (var store = new SqliteRoundStore(database); var raw = raw(); var sql = raw.createStatement();
             var check = sql.executeQuery("PRAGMA integrity_check")) {
            assertEquals("after", text(store.load(round).orElseThrow().payload()));
            assertTrue(check.next());
            assertEquals("ok", check.getString(1));
        }
    }

    @Test
    void twoStoreConnectionsSerializeCasSoOnlyOneCompetingWriteCommits() throws Exception {
        try (var first = new SqliteRoundStore(database); var second = new SqliteRoundStore(database)) {
            first.commit(write(round, -1, "before"));
            assertEquals(CommitResult.APPLIED, second.commit(write(round, 0, "second")));
            assertEquals(CommitResult.STALE, first.commit(write(round, 0, "first")));
            assertEquals("second", text(first.load(round).orElseThrow().payload()));
        }
    }

    @Test
    void validByteSnapshotTamperingFailsLoadsAndCasInsteadOfSilentlyChangingState() throws Exception {
        try (var store = new SqliteRoundStore(database); var raw = raw();
             var tamper = raw.prepareStatement("UPDATE round_snapshots SET payload=? WHERE round_id=?")) {
            store.commit(write(round, -1, "{\"cleanShutdown\":true,\"challengeNanos\":100}"));
            tamper.setBytes(1, bytes("{\"cleanShutdown\":false,\"challengeNanos\":999}"));
            tamper.setString(2, round.toString());
            assertEquals(1, tamper.executeUpdate());
            assertThrows(SQLException.class, () -> store.load(round));
            assertThrows(SQLException.class, store::latest);
            assertThrows(SQLException.class, () -> store.commit(write(round, 0, "repair without verification")));
            try (var sql = raw.createStatement(); var rows = sql.executeQuery("SELECT COUNT(*) FROM store_events")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1), "Rejected reads must not allow an unverified CAS update");
            }
        }
    }

    @Test
    void validBytePendingRuleTamperingFailsEvenWhenTheChangedConfigWouldDecode() throws Exception {
        try (var store = new SqliteRoundStore(database); var raw = raw();
             var tamper = raw.prepareStatement("UPDATE pending_rules SET payload=? WHERE singleton=1")) {
            store.savePendingRules(bytes("{\"mode\":\"NORMAL\"}"), UUID.randomUUID(), "admin");
            tamper.setBytes(1, bytes("{\"mode\":\"HARDCORE\"}"));
            assertEquals(1, tamper.executeUpdate());
            assertThrows(SQLException.class, store::loadPendingRules);
        }
    }

    @Test
    void missingOwningRoundEventIsAnErrorRatherThanAnEmptyOrOlderRound() throws Exception {
        UUID later = UUID.randomUUID();
        try (var store = new SqliteRoundStore(database); var raw = raw();
             var remove = raw.prepareStatement("DELETE FROM store_events WHERE round_id=?")) {
            store.commit(write(round, -1, "older valid round"));
            store.commit(write(later, -1, "latest round"));
            remove.setString(1, later.toString());
            assertEquals(1, remove.executeUpdate());
            assertThrows(SQLException.class, () -> store.load(later));
            assertThrows(SQLException.class, store::latest, "Missing event must not silently reveal an older round");
            assertEquals("older valid round", text(store.load(round).orElseThrow().payload()));
        }
    }

    @Test
    void missingConfigAuditOrOwningEventIsAnErrorRatherThanDefaultRules() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (var store = new SqliteRoundStore(database); var raw = raw(); var sql = raw.createStatement()) {
            store.savePendingRules(bytes("normal"), first, "admin");
            sql.executeUpdate("DELETE FROM config_events");
            assertThrows(SQLException.class, store::loadPendingRules);
            store.savePendingRules(bytes("hardcore"), second, "admin");
            try (var remove = raw.prepareStatement("DELETE FROM store_events WHERE event_id=?")) {
                remove.setString(1, second.toString());
                assertEquals(1, remove.executeUpdate());
            }
            assertThrows(SQLException.class, store::loadPendingRules);
        }
    }

    @Test
    void currentConfigAuditPayloadMustMatchItsDigestAsWellAsPendingRules() throws Exception {
        try (var store = new SqliteRoundStore(database); var raw = raw();
             var tamper = raw.prepareStatement("UPDATE config_events SET payload=?")) {
            store.savePendingRules(bytes("normal"), UUID.randomUUID(), "admin");
            tamper.setBytes(1, bytes("hardcore"));
            assertEquals(1, tamper.executeUpdate());
            assertThrows(SQLException.class, store::loadPendingRules);
        }
    }

    @Test
    void schemaFailureRollsBackEarlierDdlAndUserVersion() throws Exception {
        try (var raw = raw(); var sql = raw.createStatement()) {
            sql.execute("CREATE TABLE round_snapshots(unrelated TEXT)");
        }
        assertThrows(SQLException.class, () -> new SqliteRoundStore(database));
        try (var raw = raw(); var sql = raw.createStatement()) {
            try (var rows = sql.executeQuery("PRAGMA user_version")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='store_events'")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1), "DDL before migration failure must roll back");
            }
        }
    }

    @Test
    void rejectsNewerSchemasWithoutChangingTheirVersionOrJournalMode() throws Exception {
        try (var raw = raw(); var sql = raw.createStatement()) {
            sql.execute("PRAGMA user_version=3");
            sql.execute("CREATE TABLE future_data(value TEXT)");
            sql.execute("INSERT INTO future_data VALUES ('preserved')");
        }
        assertThrows(SQLException.class, () -> new SqliteRoundStore(database));
        try (var raw = raw(); var sql = raw.createStatement()) {
            try (var rows = sql.executeQuery("PRAGMA user_version")) { assertTrue(rows.next()); assertEquals(3, rows.getInt(1)); }
            try (var rows = sql.executeQuery("PRAGMA journal_mode")) { assertTrue(rows.next()); assertEquals("delete", rows.getString(1)); }
            try (var rows = sql.executeQuery("SELECT value FROM future_data")) { assertTrue(rows.next()); assertEquals("preserved", rows.getString(1)); }
        }
    }

    @Test
    void configuresWalAndEnforcesForeignKeys() throws Exception {
        try (var store = new SqliteRoundStore(database); var raw = raw(); var sql = raw.createStatement()) {
            store.commit(write(round, -1, "state"));
            try (var rows = sql.executeQuery("PRAGMA journal_mode")) { assertTrue(rows.next()); assertEquals("wal", rows.getString(1)); }
            // A store-triggered invalid reference proves foreign_keys applies on its actual connection.
            sql.execute("""
                    CREATE TRIGGER fail_fk AFTER UPDATE ON round_snapshots
                    BEGIN INSERT INTO operations VALUES ('invalid', 'missing-round', 'missing-event', 'TEST', 1, x'', 'PREPARED', 0); END
                    """);
            assertThrows(SQLException.class, () -> store.commit(write(round, 0, "after")));
            assertEquals(0, store.load(round).orElseThrow().version());
        }
    }

    @Test
    void rejectsSymbolicLinkDatabaseParentsAndSQLiteSidecars() throws Exception {
        var real = database.getParent();
        var outside = Files.createDirectory(real.resolve("outside"));
        var linkedParent = real.resolve("linked");
        Files.createSymbolicLink(linkedParent, outside);
        assertThrows(IOException.class, () -> new SqliteRoundStore(linkedParent.resolve("escaped.sqlite")));
        assertFalse(Files.exists(outside.resolve("escaped.sqlite")));
        var target = Files.write(real.resolve("target.sqlite"), bytes("preserve"));
        Files.createSymbolicLink(database, target);
        assertThrows(IOException.class, () -> new SqliteRoundStore(database));
        Files.delete(database);
        Files.createSymbolicLink(database.resolveSibling("rounds.sqlite-wal"), target);
        assertThrows(IOException.class, () -> new SqliteRoundStore(database));
        assertEquals("preserve", Files.readString(target));
    }

    @Test
    void refusesSidecarSymlinkIntroducedAfterOpening() throws Exception {
        var target = Files.write(database.getParent().resolve("target"), bytes("preserve"));
        try (var store = new SqliteRoundStore(database)) {
            Files.createSymbolicLink(database.resolveSibling("rounds.sqlite-journal"), target);
            assertThrows(SQLException.class, () -> store.commit(write(round, -1, "unsafe")));
        }
        assertEquals("preserve", Files.readString(target));
    }

    @Test
    void createsMissingDirectoriesButRejectsDirectoryAsDatabaseAndJdbcQueryPaths() throws Exception {
        try (var store = new SqliteRoundStore(database.getParent().resolve("nested/data/rounds.sqlite"))) {
            assertEquals(CommitResult.APPLIED, store.commit(write(round, -1, "state")));
        }
        assertThrows(IOException.class, () -> new SqliteRoundStore(database.getParent()));
        assertThrows(IOException.class, () -> new SqliteRoundStore(database.resolveSibling("rounds.sqlite?mode=memory")));
    }

    @Test
    void valueObjectsOwnTheirByteArraysAndValidateVersions() {
        byte[] source = bytes("safe");
        var op = new PreparedOperation(UUID.randomUUID(), "RESET", 0, source);
        var write = new SnapshotWrite(round, -1, 0, source, UUID.randomUUID(), "CREATE", List.of(op));
        var snapshot = new SnapshotData(round, 0, source);
        source[0] = 0;
        write.payload()[0] = 1;
        snapshot.payload()[0] = 1;
        op.payload()[0] = 1;
        assertEquals("safe", text(write.payload()));
        assertEquals("safe", text(snapshot.payload()));
        assertEquals("safe", text(op.payload()));
        assertThrows(IllegalArgumentException.class,
                () -> new SnapshotWrite(round, -1, 1, source, UUID.randomUUID(), "CREATE"));
        assertThrows(IllegalArgumentException.class,
                () -> new SnapshotWrite(round, Long.MAX_VALUE, Long.MIN_VALUE, source, UUID.randomUUID(), "CREATE"));
        assertThrows(IllegalArgumentException.class,
                () -> new SnapshotWrite(round, 0, 1, source, UUID.randomUUID(), "CREATE", List.of(op)));
        assertThrows(IllegalArgumentException.class,
                () -> new SnapshotWrite(round, -1, 0, source, UUID.randomUUID(), "CREATE", List.of(op, op)));
    }

    private Connection raw() throws SQLException { return DriverManager.getConnection("jdbc:sqlite:" + database); }

    private static SnapshotWrite write(UUID round, long previousVersion, String payload) {
        return new SnapshotWrite(round, previousVersion, previousVersion + 1, bytes(payload), UUID.randomUUID(), "UPDATE");
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
}
