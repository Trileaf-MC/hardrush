package dev.hardrush.persistence;

import dev.hardrush.core.combat.CombatEventCodec;
import dev.hardrush.core.round.DragonDeathArbiter;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.core.round.RoundSnapshotCodec;
import dev.hardrush.core.record.*;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable, Minecraft-independent storage. Calls serialize on one connection and may block for IO. */
public final class SqliteRoundStore implements AutoCloseable {
    public static final int SCHEMA_VERSION = 4;
    public static final int DEFAULT_BUSY_TIMEOUT_MILLIS = 1_000;

    private final Path databasePath;
    private final Connection connection;
    private final SqliteRecordIndex records;

    public SqliteRoundStore(Path databasePath) throws IOException, SQLException {
        this(databasePath, DEFAULT_BUSY_TIMEOUT_MILLIS);
    }

    public SqliteRoundStore(Path databasePath, int busyTimeoutMillis) throws IOException, SQLException {
        if (busyTimeoutMillis < 0) throw new IllegalArgumentException("Negative busy timeout");
        this.databasePath = SafeDatabasePath.prepare(Objects.requireNonNull(databasePath, "databasePath"));
        var config = new SQLiteConfig();
        config.setBusyTimeout(busyTimeoutMillis);
        // Fabric/Knot can initialize the store before JDBC's service loader sees the mod class loader.
        // Connect directly through the bundled driver instead of depending on TCCL/SPI registration order.
        connection = new org.sqlite.JDBC().connect("jdbc:sqlite:" + this.databasePath, config.toProperties());
        records = new SqliteRecordIndex(connection);
        try {
            var version = schemaVersion();
            if (version > SCHEMA_VERSION) throw new SQLException("Database schema is newer than this build: " + version);
            try (var statement = connection.createStatement()) {
                try (var mode = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                    if (!mode.next() || !"wal".equalsIgnoreCase(mode.getString(1))) {
                        throw new SQLException("SQLite WAL mode is unavailable");
                    }
                }
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("PRAGMA foreign_keys=ON");
            }
            if (version < SCHEMA_VERSION) transaction(() -> {
                // Another connection may have migrated while this one waited for the write lock.
                int lockedVersion = schemaVersion();
                if (lockedVersion > SCHEMA_VERSION) throw new SQLException("Database schema is newer than this build: " + lockedVersion);
                if (lockedVersion == 0) migrateInitialSchema();
                if (schemaVersion() == 1) migrateEvidenceSchema();
                if (schemaVersion() == 2) migrateTerminalSchema();
                if (schemaVersion() == 3) migrateRecordSchema();
                return null;
            });
        } catch (SQLException | RuntimeException | Error failure) {
            try { connection.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    public synchronized CommitResult commit(SnapshotWrite write) throws SQLException {
        Objects.requireNonNull(write, "write");
        checkOpenPath();
        var digest = WriteDigest.snapshot(write);
        return transaction(() -> {
            if (duplicate(write.eventId(), digest)) return CommitResult.DUPLICATE;
            var current = loadInternal(write.roundId());
            if (current.map(SnapshotData::version).orElse(-1L) != write.expectedVersion()) return CommitResult.STALE;
            if (current.isPresent()) preserveRoundIdentity(write, current.orElseThrow());
            var terminal = terminalInternal(write.roundId());
            if (terminal.isPresent()) preserveTerminal(write, terminal.orElseThrow());
            else if (current.isPresent()) preserveLegacyTerminal(write, current.orElseThrow());
            insertSnapshot(write, digest);
            records.index(write, false);
            return CommitResult.APPLIED;
        });
    }

    /** First-dragon evidence, original SETTLING snapshot and unique terminal row share one CAS transaction. */
    public synchronized CommitResult commitTerminal(SnapshotWrite write, EvidenceWrite evidence,
                                                    UUID firstDragonId) throws SQLException {
        Objects.requireNonNull(write); Objects.requireNonNull(evidence); Objects.requireNonNull(firstDragonId);
        RoundSnapshot settling = validateTerminalTags(write, evidence, firstDragonId);
        checkOpenPath();
        byte[] digest = WriteDigest.terminal(write, evidence, firstDragonId);
        return transaction(() -> {
            if (duplicate(write.eventId(), digest)) {
                var stored = terminalInternal(write.roundId());
                if (stored.isEmpty() || !stored.orElseThrow().eventId().equals(write.eventId())) {
                    throw new SQLException("Terminal event has no owning terminal record");
                }
                return CommitResult.DUPLICATE;
            }
            boolean existingEvidence = duplicate(evidence.eventId(), WriteDigest.evidence(evidence));
            if (existingEvidence) evidenceInternal(evidence.eventId());
            var current = loadInternal(write.roundId());
            if (current.isEmpty() || current.orElseThrow().version() != write.expectedVersion()) return CommitResult.STALE;
            if (terminalInternal(write.roundId()).isPresent()) return CommitResult.STALE;
            RoundSnapshot before = RoundSnapshotCodec.decode(current.orElseThrow().payload());
            if (before.result() != null) return CommitResult.STALE;
            var death = CombatEventCodec.decode(evidence.payload());
            if (!settling.result().equals(DragonDeathArbiter.adjudicate(before, death, firstDragonId))) {
                throw new IllegalArgumentException("SETTLING result does not match actual dragon-death evidence");
            }
            if (before.sequence() != settling.sequence() || before.seed() != settling.seed()
                    || !before.rules().equals(settling.rules()) || settling.challengeNanos() < before.challengeNanos()) {
                throw new IllegalArgumentException("Terminal snapshot changed the round identity or reversed time");
            }
            if (!existingEvidence) insertEvidence(evidence);
            insertSnapshot(write, digest);
            try (var statement = connection.prepareStatement("""
                    INSERT INTO round_terminals(round_id, event_id, combat_event_id, first_dragon_id, payload, payload_digest)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, write.roundId().toString()); statement.setString(2, write.eventId().toString());
                statement.setString(3, evidence.eventId().toString()); statement.setString(4, firstDragonId.toString());
                statement.setBytes(5, write.payload()); statement.setBytes(6, WriteDigest.payload(write.payload()));
                statement.executeUpdate();
            }
            records.index(write, true);
            return CommitResult.APPLIED;
        });
    }

    public synchronized Optional<TerminalData> terminal(UUID roundId) throws SQLException {
        Objects.requireNonNull(roundId); checkOpenPath();
        return terminalInternal(roundId);
    }

    /** Immutable settled histories only. Active seeds are never returned by these APIs. */
    public synchronized Optional<RoundHistory> roundHistory(UUID roundId) throws SQLException {
        Objects.requireNonNull(roundId); checkOpenPath();
        var value = records.history(roundId); if (value.isPresent()) verifyHistory(value.orElseThrow()); return value;
    }
    public synchronized Optional<RoundHistory> historyBySequence(long sequence) throws SQLException {
        checkOpenPath(); var value = records.bySequence(sequence, false);
        if (value.isPresent()) verifyHistory(value.orElseThrow()); return value;
    }
    public synchronized Optional<RoundHistory> roundBySequence(long sequence) throws SQLException { return historyBySequence(sequence); }
    public synchronized Optional<RoundHistory> latestHistoryBefore(long sequence) throws SQLException {
        checkOpenPath(); var value = records.bySequence(sequence, true);
        if (value.isPresent()) verifyHistory(value.orElseThrow()); return value;
    }
    public synchronized List<RoundHistory> history(int limit, int offset) throws SQLException {
        checkOpenPath(); var values = records.histories(limit, offset); for (var value : values) verifyHistory(value); return values;
    }
    public synchronized List<LeaderboardEntry> leaderboard(String rulesFingerprint, int limit) throws SQLException {
        checkOpenPath(); var values = records.leaderboard(rulesFingerprint, limit);
        for (var value : values) verifyHistory(value.round()); return values;
    }
    public synchronized List<ParticipantRecord> participantRecords(UUID roundId, int limit, int offset) throws SQLException {
        Objects.requireNonNull(roundId); checkOpenPath(); return records.participants(roundId, limit, offset);
    }
    public synchronized Optional<ParticipantRecord> participantRecord(UUID roundId, UUID playerId) throws SQLException {
        Objects.requireNonNull(roundId); Objects.requireNonNull(playerId); checkOpenPath(); return records.participant(roundId, playerId);
    }
    public synchronized long participantCount(UUID roundId) throws SQLException {
        Objects.requireNonNull(roundId); checkOpenPath(); return records.participantCount(roundId);
    }
    public synchronized long endEntrantCount(UUID roundId) throws SQLException {
        Objects.requireNonNull(roundId); checkOpenPath(); return records.endCount(roundId);
    }
    public synchronized CommitResult recordProgress(ProgressWrite write) throws SQLException {
        Objects.requireNonNull(write); checkOpenPath();
        byte[] digest = WriteDigest.payload(RecordEncoding.progress(write));
        return transaction(() -> {
            if (duplicate(write.eventId(), digest)) {
                records.verifyProgress(write.eventId());
                return CommitResult.DUPLICATE;
            }
            var snapshot = loadInternal(write.roundId());
            if (snapshot.isEmpty()) return CommitResult.STALE;
            return records.progress(write, snapshot.orElseThrow());
        });
    }
    public synchronized List<String> advancementIds(UUID roundId, UUID playerId, int limit, int offset) throws SQLException {
        Objects.requireNonNull(roundId); Objects.requireNonNull(playerId); checkOpenPath();
        return records.advancementIds(roundId, playerId, limit, offset);
    }
    public synchronized BookClaim ensureAutomaticBookClaim(UUID playerId, UUID roundId, int edition, int volume) throws SQLException {
        Objects.requireNonNull(playerId); Objects.requireNonNull(roundId); checkOpenPath();
        return transaction(() -> records.ensureClaim(playerId, roundId, edition, volume));
    }
    public synchronized Optional<BookClaim> latestAutomaticBookClaim(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId); checkOpenPath(); return records.latestClaim(playerId);
    }
    public synchronized boolean markBookDelivered(UUID playerId, UUID roundId, int edition, int volume) throws SQLException {
        Objects.requireNonNull(playerId); Objects.requireNonNull(roundId); checkOpenPath();
        return transaction(() -> records.deliver(playerId, roundId, edition, volume));
    }

    private void verifyHistory(RoundHistory history) throws SQLException {
        var terminal = terminalInternal(history.roundId());
        if (terminal.isEmpty()) {
            if (history.trustedWinner()) throw new SQLException("Trusted history has no original terminal evidence");
            return; // Legacy results and explicit failure/abort can legitimately have no dragon-death evidence.
        }
        var original = RoundSnapshotCodec.decode(terminal.orElseThrow().snapshot().payload());
        if (!Objects.equals(history.result(), original.result()) || history.sequence() != original.sequence()
                || history.seed() != original.seed() || !history.rules().equals(original.rules())
                || history.challengeNanos() != original.challengeNanos() || history.timingUncertain() != original.timingUncertain()
                || history.trustedWinner() != (original.result().winner() != null)) {
            throw new SQLException("History does not match its original terminal evidence");
        }
    }

    private void migrateRecordSchema() throws SQLException {
        records.migrate();
        var snapshots = new ArrayList<SnapshotData>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT round_id FROM round_snapshots")) {
            while (rows.next()) snapshots.add(loadInternal(UUID.fromString(rows.getString(1))).orElseThrow());
        }
        for (var snapshot : snapshots) {
            var terminal = terminalInternal(snapshot.roundId());
            if (terminal.isPresent()) records.index(terminal.orElseThrow().snapshot(), true);
            else records.index(new SnapshotWrite(snapshot.roundId(), snapshot.version() - 1, snapshot.version(),
                    snapshot.payload(), UUID.randomUUID(), "LEGACY_INDEX"), false);
        }
        try (var statement = connection.createStatement()) { statement.execute("PRAGMA user_version=4"); }
    }

    public synchronized Optional<SnapshotData> load(UUID roundId) throws SQLException {
        Objects.requireNonNull(roundId, "roundId");
        checkOpenPath();
        var stored = loadInternal(roundId);
        if (stored.isPresent()) verifyTerminalCurrent(stored.orElseThrow());
        return stored;
    }

    public synchronized CommitResult recordEvidence(EvidenceWrite write) throws SQLException {
        Objects.requireNonNull(write); checkOpenPath();
        byte[] digest = WriteDigest.evidence(write);
        return transaction(() -> {
            if (duplicate(write.eventId(), digest)) return CommitResult.DUPLICATE;
            if (loadInternal(write.roundId()).isEmpty()) throw new SQLException("Evidence round is not committed");
            insertEvidence(write);
            return CommitResult.APPLIED;
        });
    }

    /** Bounded, insertion-ordered query; payload integrity is checked before returning evidence. */
    public synchronized List<EvidenceWrite> evidence(UUID roundId, String kind, int limit) throws SQLException {
        Objects.requireNonNull(roundId); StoreValues.text(kind, "kind", 64);
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Evidence limit must be 1..1000");
        checkOpenPath();
        var result = new ArrayList<EvidenceWrite>();
        try (var statement = connection.prepareStatement("""
                SELECT * FROM evidence_events WHERE round_id=? AND kind=? ORDER BY sequence LIMIT ?
                """)) {
            statement.setString(1, roundId.toString()); statement.setString(2, kind); statement.setInt(3, limit);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    byte[] payload = rows.getBytes("payload");
                    validatePayload(payload, rows.getBytes("payload_digest"), "Evidence");
                    var write = new EvidenceWrite(roundId, UUID.fromString(rows.getString("event_id")), kind, payload);
                    if (!java.security.MessageDigest.isEqual(WriteDigest.evidence(write), rows.getBytes("write_digest"))) {
                        throw new SQLException("Evidence labels or digest do not match");
                    }
                    result.add(write);
                }
            }
        }
        return List.copyOf(result);
    }

    public synchronized Optional<SnapshotData> latest() throws SQLException {
        checkOpenPath();
        try (var statement = connection.prepareStatement("""
                SELECT snapshots.round_id, snapshots.version, snapshots.payload,
                    committed.payload_digest, committed.scope_type AS event_scope,
                    committed.round_id AS event_round_id, committed.new_version AS event_version,
                    creation.event_id AS creation_event_id
                FROM round_snapshots AS snapshots
                LEFT JOIN store_events AS committed ON committed.sequence=snapshots.revision
                LEFT JOIN store_events AS creation ON creation.round_id=snapshots.round_id
                    AND creation.scope_type='ROUND' AND creation.new_version=0
                ORDER BY (creation.sequence IS NULL) DESC, creation.sequence DESC LIMIT 1
                """); var rows = statement.executeQuery()) {
            if (!rows.next()) return Optional.empty();
            if (rows.getString("creation_event_id") == null) {
                throw new SQLException("Round snapshot has no owning creation event");
            }
            var stored = snapshot(rows);
            verifyTerminalCurrent(stored);
            return Optional.of(stored);
        }
    }

    public synchronized CommitResult savePendingRules(byte[] canonicalRules, UUID eventId, String actor) throws SQLException {
        byte[] payload = Objects.requireNonNull(canonicalRules, "canonicalRules").clone();
        Objects.requireNonNull(eventId, "eventId");
        String auditActor = StoreValues.text(actor, "actor", 256);
        checkOpenPath();
        byte[] digest = WriteDigest.rules(payload, auditActor);
        return transaction(() -> {
            if (duplicate(eventId, digest)) return CommitResult.DUPLICATE;
            long revision = insertEvent(eventId, "RULES", null, "RULES_CONFIG", auditActor,
                    null, null, digest, WriteDigest.payload(payload));
            try (var statement = connection.prepareStatement("""
                    INSERT INTO config_events(event_id, actor, payload, revision) VALUES (?, ?, ?, ?)
                    """)) {
                statement.setString(1, eventId.toString());
                statement.setString(2, auditActor);
                statement.setBytes(3, payload);
                statement.setLong(4, revision);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO pending_rules(singleton, payload, revision) VALUES (1, ?, ?)
                    ON CONFLICT(singleton) DO UPDATE SET payload=excluded.payload, revision=excluded.revision
                    """)) {
                statement.setBytes(1, payload);
                statement.setLong(2, revision);
                statement.executeUpdate();
            }
            return CommitResult.APPLIED;
        });
    }

    public synchronized Optional<byte[]> loadPendingRules() throws SQLException {
        checkOpenPath();
        try (var statement = connection.prepareStatement("""
                SELECT pending.payload, audit.payload AS audit_payload,
                    audit.actor AS audit_actor, committed.actor AS event_actor,
                    committed.scope_type AS event_scope, committed.payload_digest
                FROM pending_rules AS pending
                LEFT JOIN config_events AS audit ON audit.revision=pending.revision
                LEFT JOIN store_events AS committed ON committed.sequence=audit.revision
                    AND committed.event_id=audit.event_id
                WHERE pending.singleton=1
                """);
             var rows = statement.executeQuery()) {
            if (!rows.next()) return Optional.empty();
            if (!"RULES".equals(rows.getString("event_scope")) || rows.getString("audit_actor") == null
                    || !Objects.equals(rows.getString("audit_actor"), rows.getString("event_actor"))) {
                throw new SQLException("Pending rules have missing or inconsistent owning audit events");
            }
            byte[] payload = rows.getBytes("payload");
            byte[] digest = rows.getBytes("payload_digest");
            validatePayload(payload, digest, "Pending rules");
            validatePayload(rows.getBytes("audit_payload"), digest, "Pending rules audit");
            return Optional.of(payload);
        }
    }

    public synchronized List<OperationData> operations(UUID roundId) throws SQLException {
        return readOperations(roundId, false);
    }

    public synchronized List<OperationData> pendingOperations(UUID roundId) throws SQLException {
        return readOperations(roundId, true);
    }

    public synchronized Optional<OperationData> operation(UUID roundId, UUID operationId) throws SQLException {
        Objects.requireNonNull(roundId, "roundId");
        Objects.requireNonNull(operationId, "operationId");
        checkOpenPath();
        return operationInternal(roundId, operationId);
    }

    /** Stage CAS uses the operation's version, independently of later round snapshot versions. */
    public synchronized OperationAdvanceResult advanceOperation(UUID roundId, UUID operationId,
            long expectedOperationVersion, OperationStage target) throws SQLException {
        Objects.requireNonNull(roundId, "roundId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(target, "target");
        if (expectedOperationVersion < 0) throw new IllegalArgumentException("Negative expected operation version");
        checkOpenPath();
        return transaction(() -> {
            var operation = operationInternal(roundId, operationId);
            if (operation.isEmpty()) return OperationAdvanceResult.STALE;
            var current = operation.orElseThrow();
            if (target.ordinal() < current.stage().ordinal()) {
                // A retried historical CAS is already satisfied even if verification completed later.
                if (target != OperationStage.PREPARED && expectedOperationVersion == target.ordinal() - 1L) {
                    return OperationAdvanceResult.DUPLICATE;
                }
                throw new IllegalArgumentException("Operation stage cannot move backwards");
            }
            if (target == current.stage()) {
                return expectedOperationVersion == current.version()
                        || expectedOperationVersion == current.version() - 1
                        ? OperationAdvanceResult.DUPLICATE : OperationAdvanceResult.STALE;
            }
            if (current.version() != expectedOperationVersion) return OperationAdvanceResult.STALE;
            if (target.ordinal() != current.stage().ordinal() + 1) {
                throw new IllegalArgumentException("Operation stage must advance by exactly one");
            }
            try (var statement = connection.prepareStatement("""
                    UPDATE operations SET stage=?, version=version+1
                    WHERE round_id=? AND operation_id=? AND version=?
                    """)) {
                statement.setString(1, target.name());
                statement.setString(2, roundId.toString());
                statement.setString(3, operationId.toString());
                statement.setLong(4, expectedOperationVersion);
                return statement.executeUpdate() == 1 ? OperationAdvanceResult.APPLIED : OperationAdvanceResult.STALE;
            }
        });
    }

    @Override
    public synchronized void close() throws SQLException { connection.close(); }

    private void migrateInitialSchema() throws SQLException {
        // DDL and user_version are committed together; a failed statement leaves the old schema intact.
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE store_events (
                        sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                        event_id TEXT NOT NULL UNIQUE,
                        scope_type TEXT NOT NULL CHECK(scope_type IN ('ROUND', 'RULES')),
                        round_id TEXT,
                        event_type TEXT NOT NULL,
                        actor TEXT,
                        expected_version INTEGER,
                        new_version INTEGER,
                        write_digest BLOB NOT NULL CHECK(length(write_digest)=32),
                        payload_digest BLOB NOT NULL CHECK(length(payload_digest)=32),
                        UNIQUE(round_id, new_version),
                        CHECK((scope_type='ROUND' AND round_id IS NOT NULL AND actor IS NULL
                            AND expected_version>=-1 AND new_version=expected_version+1)
                            OR (scope_type='RULES' AND round_id IS NULL AND actor IS NOT NULL
                            AND expected_version IS NULL AND new_version IS NULL))
                    )
                    """);
            statement.execute("""
                    CREATE TABLE round_snapshots (
                        round_id TEXT PRIMARY KEY,
                        version INTEGER NOT NULL CHECK(version>=0),
                        payload BLOB NOT NULL,
                        revision INTEGER NOT NULL UNIQUE REFERENCES store_events(sequence)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE operations (
                        operation_id TEXT PRIMARY KEY,
                        round_id TEXT NOT NULL REFERENCES round_snapshots(round_id),
                        event_id TEXT NOT NULL REFERENCES store_events(event_id),
                        operation_type TEXT NOT NULL,
                        expected_version INTEGER NOT NULL CHECK(expected_version>=0),
                        payload BLOB NOT NULL,
                        stage TEXT NOT NULL CHECK(stage IN ('PREPARED', 'APPLIED', 'VERIFIED')),
                        version INTEGER NOT NULL CHECK(version>=0)
                    )
                    """);
            statement.execute("CREATE INDEX operations_by_round ON operations(round_id, stage)");
            statement.execute("""
                    CREATE TABLE config_events (
                        event_id TEXT PRIMARY KEY REFERENCES store_events(event_id),
                        actor TEXT NOT NULL CHECK(length(actor) BETWEEN 1 AND 256),
                        payload BLOB NOT NULL,
                        revision INTEGER NOT NULL UNIQUE REFERENCES store_events(sequence)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE pending_rules (
                        singleton INTEGER PRIMARY KEY CHECK(singleton=1),
                        payload BLOB NOT NULL,
                        revision INTEGER NOT NULL REFERENCES config_events(revision)
                    )
                    """);
            statement.execute("PRAGMA user_version=1");
        }
    }

    private void migrateEvidenceSchema() throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE evidence_events (
                        sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                        event_id TEXT NOT NULL UNIQUE,
                        round_id TEXT NOT NULL REFERENCES round_snapshots(round_id),
                        kind TEXT NOT NULL,
                        payload BLOB NOT NULL,
                        write_digest BLOB NOT NULL CHECK(length(write_digest)=32),
                        payload_digest BLOB NOT NULL CHECK(length(payload_digest)=32)
                    )
                    """);
            statement.execute("CREATE INDEX evidence_by_round ON evidence_events(round_id, kind, sequence)");
            statement.execute("PRAGMA user_version=2");
        }
    }

    private void migrateTerminalSchema() throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE round_terminals (
                        round_id TEXT PRIMARY KEY REFERENCES round_snapshots(round_id),
                        event_id TEXT NOT NULL UNIQUE REFERENCES store_events(event_id),
                        combat_event_id TEXT NOT NULL UNIQUE REFERENCES evidence_events(event_id),
                        first_dragon_id TEXT NOT NULL,
                        payload BLOB NOT NULL,
                        payload_digest BLOB NOT NULL CHECK(length(payload_digest)=32)
                    )
                    """);
            statement.execute("PRAGMA user_version=3");
        }
    }

    private static RoundSnapshot validateTerminalTags(SnapshotWrite write, EvidenceWrite evidence, UUID firstDragonId) {
        if (write.expectedVersion() < 0 || !"FINISH".equals(write.eventType())
                || !"COMBAT".equals(evidence.kind()) || !write.roundId().equals(evidence.roundId())
                || write.eventId().equals(evidence.eventId())) {
            throw new IllegalArgumentException("Invalid first-dragon terminal write tags");
        }
        var snapshot = RoundSnapshotCodec.decode(write.payload());
        var death = CombatEventCodec.decode(evidence.payload());
        if (!snapshot.roundId().equals(write.roundId()) || snapshot.version() != write.newVersion()
                || snapshot.phase() != RoundPhase.SETTLING || snapshot.cleanShutdown()
                || !death.roundId().equals(write.roundId()) || !death.eventId().equals(evidence.eventId())
                || !death.victimId().equals(firstDragonId) || !death.lethal()) {
            throw new IllegalArgumentException("Terminal must contain this round's SETTLING snapshot and actual first-dragon death");
        }
        return snapshot;
    }

    private static void preserveTerminal(SnapshotWrite write, TerminalData terminal) {
        var original = RoundSnapshotCodec.decode(terminal.snapshot().payload());
        var after = RoundSnapshotCodec.decode(write.payload());
        if (!after.roundId().equals(write.roundId()) || after.version() != write.newVersion()) {
            throw new IllegalArgumentException("Snapshot payload does not match its round/version tags");
        }
        preserveTerminal(after, original);
    }

    private static void preserveRoundIdentity(SnapshotWrite write, SnapshotData current) {
        RoundSnapshot before;
        try { before = RoundSnapshotCodec.decode(current.payload()); }
        catch (IllegalArgumentException opaqueLegacyPayload) { return; }
        var after = RoundSnapshotCodec.decode(write.payload());
        if (!after.roundId().equals(write.roundId()) || after.version() != write.newVersion()
                || after.sequence() != before.sequence() || after.seed() != before.seed() || !after.rules().equals(before.rules())) {
            throw new IllegalArgumentException("A round's identity, seed and immutable rules snapshot cannot change");
        }
    }

    private static void preserveTerminal(RoundSnapshot after, RoundSnapshot original) {
        if (!Objects.equals(after.result(), original.result())
                || after.challengeNanos() != original.challengeNanos()
                || after.sequence() != original.sequence() || after.seed() != original.seed()
                || !after.rules().equals(original.rules())) {
            throw new IllegalArgumentException("A committed terminal result and stopped challenge time cannot be replaced");
        }
    }

    private static void preserveLegacyTerminal(SnapshotWrite write, SnapshotData current) {
        // Earlier schemas allowed opaque snapshots, including a completed round without causal evidence.
        // Preserve any recognizable legacy result without inventing a first-dragon terminal record.
        RoundSnapshot before;
        try { before = RoundSnapshotCodec.decode(current.payload()); }
        catch (IllegalArgumentException opaqueLegacyPayload) { return; }
        if (before.result() != null) {
            var after = RoundSnapshotCodec.decode(write.payload());
            if (!after.roundId().equals(write.roundId()) || after.version() != write.newVersion()) {
                throw new IllegalArgumentException("Snapshot payload does not match its round/version tags");
            }
            preserveTerminal(after, before);
        }
    }

    private void verifyTerminalCurrent(SnapshotData current) throws SQLException {
        var terminal = terminalInternal(current.roundId());
        if (terminal.isPresent()) {
            try {
                var snapshot = RoundSnapshotCodec.decode(current.payload());
                if (snapshot.version() != current.version() || !snapshot.roundId().equals(current.roundId())) {
                    throw new IllegalArgumentException("Snapshot payload does not match its round/version tags");
                }
                preserveTerminal(snapshot, RoundSnapshotCodec.decode(terminal.orElseThrow().snapshot().payload()));
            } catch (IllegalArgumentException invalid) {
                throw new SQLException("Current snapshot does not preserve its original terminal result", invalid);
            }
        }
    }

    private EvidenceWrite evidenceInternal(UUID eventId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM evidence_events WHERE event_id=?")) {
            statement.setString(1, eventId.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Existing evidence event is missing its evidence row");
                byte[] payload = rows.getBytes("payload");
                validatePayload(payload, rows.getBytes("payload_digest"), "Existing combat evidence");
                try {
                    var evidence = new EvidenceWrite(UUID.fromString(rows.getString("round_id")), eventId,
                            rows.getString("kind"), payload);
                    if (!java.security.MessageDigest.isEqual(WriteDigest.evidence(evidence), rows.getBytes("write_digest"))) {
                        throw new SQLException("Existing evidence labels or digest do not match");
                    }
                    return evidence;
                } catch (IllegalArgumentException invalid) {
                    throw new SQLException("Existing evidence tags are invalid", invalid);
                }
            }
        }
    }

    private Optional<TerminalData> terminalInternal(UUID roundId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT terminal.*, committed.scope_type AS event_scope, committed.round_id AS event_round_id,
                    committed.expected_version, committed.new_version, committed.event_type, committed.write_digest,
                    committed.payload_digest AS event_payload_digest,
                    evidence.round_id AS evidence_round_id, evidence.kind AS evidence_kind,
                    evidence.payload AS evidence_payload, evidence.write_digest AS evidence_write_digest,
                    evidence.payload_digest AS evidence_payload_digest
                FROM round_terminals AS terminal
                LEFT JOIN store_events AS committed ON committed.event_id=terminal.event_id
                LEFT JOIN evidence_events AS evidence ON evidence.event_id=terminal.combat_event_id
                WHERE terminal.round_id=?
                """)) {
            statement.setString(1, roundId.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                if (!"ROUND".equals(rows.getString("event_scope"))
                        || !roundId.toString().equals(rows.getString("event_round_id"))
                        || !roundId.toString().equals(rows.getString("evidence_round_id"))) {
                    throw new SQLException("Terminal record has missing or inconsistent owning events");
                }
                byte[] payload = rows.getBytes("payload");
                byte[] evidencePayload = rows.getBytes("evidence_payload");
                validatePayload(payload, rows.getBytes("payload_digest"), "Terminal snapshot");
                validatePayload(payload, rows.getBytes("event_payload_digest"), "Terminal event snapshot");
                validatePayload(evidencePayload, rows.getBytes("evidence_payload_digest"), "Terminal combat evidence");
                try {
                    UUID eventId = UUID.fromString(rows.getString("event_id"));
                    var operations = readOperations(roundId, false).stream().filter(value -> value.eventId().equals(eventId))
                            .map(value -> new PreparedOperation(value.operationId(), value.operationType(),
                                    value.expectedVersion(), value.payload())).toList();
                    var snapshot = new SnapshotWrite(roundId, rows.getLong("expected_version"), rows.getLong("new_version"),
                            payload, eventId, rows.getString("event_type"), operations);
                    var evidence = new EvidenceWrite(roundId, UUID.fromString(rows.getString("combat_event_id")),
                            rows.getString("evidence_kind"), evidencePayload);
                    UUID firstDragonId = UUID.fromString(rows.getString("first_dragon_id"));
                    validateTerminalTags(snapshot, evidence, firstDragonId);
                    if (!java.security.MessageDigest.isEqual(WriteDigest.evidence(evidence), rows.getBytes("evidence_write_digest"))
                            || !java.security.MessageDigest.isEqual(WriteDigest.terminal(snapshot, evidence, firstDragonId), rows.getBytes("write_digest"))) {
                        throw new SQLException("Terminal labels or owning write digests do not match");
                    }
                    return Optional.of(new TerminalData(snapshot, evidence, firstDragonId));
                } catch (IllegalArgumentException malformed) {
                    throw new SQLException("Terminal payload or tags are invalid", malformed);
                }
            }
        }
    }

    private int schemaVersion() throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA user_version")) {
            if (!rows.next()) throw new SQLException("SQLite schema version is missing");
            return rows.getInt(1);
        }
    }

    private Optional<SnapshotData> loadInternal(UUID roundId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT snapshots.round_id, snapshots.version, snapshots.payload,
                    committed.payload_digest, committed.scope_type AS event_scope,
                    committed.round_id AS event_round_id, committed.new_version AS event_version
                FROM round_snapshots AS snapshots
                LEFT JOIN store_events AS committed ON committed.sequence=snapshots.revision
                WHERE snapshots.round_id=?
                """)) {
            statement.setString(1, roundId.toString());
            try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(snapshot(rows)) : Optional.empty(); }
        }
    }

    private static SnapshotData snapshot(ResultSet rows) throws SQLException {
        String roundId = rows.getString("round_id");
        long version = rows.getLong("version");
        Object eventVersion = rows.getObject("event_version");
        if (!"ROUND".equals(rows.getString("event_scope"))
                || !Objects.equals(roundId, rows.getString("event_round_id"))
                || !(eventVersion instanceof Number number) || number.longValue() != version) {
            throw new SQLException("Round snapshot has missing or inconsistent owning event tags");
        }
        byte[] payload = rows.getBytes("payload");
        validatePayload(payload, rows.getBytes("payload_digest"), "Round snapshot");
        try { return new SnapshotData(UUID.fromString(roundId), version, payload); }
        catch (IllegalArgumentException invalidTags) { throw new SQLException("Round snapshot tags are invalid", invalidTags); }
    }

    private static void validatePayload(byte[] payload, byte[] digest, String subject) throws SQLException {
        if (payload == null || digest == null || digest.length != 32
                || !java.security.MessageDigest.isEqual(WriteDigest.payload(payload), digest)) {
            throw new SQLException(subject + " payload digest is missing or does not match");
        }
    }

    private boolean duplicate(UUID eventId, byte[] digest) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT write_digest FROM store_events WHERE event_id=?
                UNION ALL SELECT write_digest FROM evidence_events WHERE event_id=?
                UNION ALL SELECT write_digest FROM progress_events WHERE event_id=?
                """)) {
            statement.setString(1, eventId.toString());
            statement.setString(2, eventId.toString());
            statement.setString(3, eventId.toString());
            try (var rows = statement.executeQuery()) {
                boolean found = false;
                while (rows.next()) {
                    found = true;
                    if (!java.security.MessageDigest.isEqual(rows.getBytes(1), digest)) throw new EventCollisionException(eventId);
                }
                return found;
            }
        }
    }

    private long insertEvent(UUID eventId, String scope, UUID roundId, String eventType, String actor,
            Long expectedVersion, Long newVersion, byte[] digest, byte[] payloadDigest) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO store_events(event_id, scope_type, round_id, event_type, actor,
                    expected_version, new_version, write_digest, payload_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, eventId.toString());
            statement.setString(2, scope);
            statement.setString(3, roundId == null ? null : roundId.toString());
            statement.setString(4, eventType);
            statement.setString(5, actor);
            statement.setObject(6, expectedVersion);
            statement.setObject(7, newVersion);
            statement.setBytes(8, digest);
            statement.setBytes(9, payloadDigest);
            statement.executeUpdate();
        }
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT last_insert_rowid()")) {
            if (!rows.next()) throw new SQLException("Inserted event revision is missing");
            return rows.getLong(1);
        }
    }

    private void insertSnapshot(SnapshotWrite write, byte[] digest) throws SQLException {
        long revision = insertEvent(write.eventId(), "ROUND", write.roundId(), write.eventType(), null,
                write.expectedVersion(), write.newVersion(), digest, WriteDigest.payload(write.payload()));
        try (var statement = connection.prepareStatement("""
                INSERT INTO round_snapshots(round_id, version, payload, revision) VALUES (?, ?, ?, ?)
                ON CONFLICT(round_id) DO UPDATE SET version=excluded.version,
                    payload=excluded.payload, revision=excluded.revision
                """)) {
            statement.setString(1, write.roundId().toString()); statement.setLong(2, write.newVersion());
            statement.setBytes(3, write.payload()); statement.setLong(4, revision); statement.executeUpdate();
        }
        for (var operation : write.operations()) insertOperation(write, operation);
    }

    private void insertEvidence(EvidenceWrite write) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO evidence_events(event_id, round_id, kind, payload, write_digest, payload_digest)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, write.eventId().toString()); statement.setString(2, write.roundId().toString());
            statement.setString(3, write.kind()); statement.setBytes(4, write.payload());
            statement.setBytes(5, WriteDigest.evidence(write)); statement.setBytes(6, WriteDigest.payload(write.payload()));
            statement.executeUpdate();
        }
    }

    private void insertOperation(SnapshotWrite write, PreparedOperation operation) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO operations(operation_id, round_id, event_id, operation_type,
                    expected_version, payload, stage, version) VALUES (?, ?, ?, ?, ?, ?, 'PREPARED', 0)
                """)) {
            statement.setString(1, operation.operationId().toString());
            statement.setString(2, write.roundId().toString());
            statement.setString(3, write.eventId().toString());
            statement.setString(4, operation.operationType());
            statement.setLong(5, operation.expectedVersion());
            statement.setBytes(6, operation.payload());
            statement.executeUpdate();
        }
    }

    private List<OperationData> readOperations(UUID roundId, boolean pendingOnly) throws SQLException {
        Objects.requireNonNull(roundId, "roundId");
        checkOpenPath();
        var result = new ArrayList<OperationData>();
        String sql = "SELECT * FROM operations WHERE round_id=?"
                + (pendingOnly ? " AND stage<>'VERIFIED'" : "") + " ORDER BY operation_id";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, roundId.toString());
            try (var rows = statement.executeQuery()) { while (rows.next()) result.add(operation(rows)); }
        }
        return List.copyOf(result);
    }

    private Optional<OperationData> operationInternal(UUID roundId, UUID operationId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM operations WHERE round_id=? AND operation_id=?")) {
            statement.setString(1, roundId.toString());
            statement.setString(2, operationId.toString());
            try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(operation(rows)) : Optional.empty(); }
        }
    }

    private static OperationData operation(ResultSet rows) throws SQLException {
        return new OperationData(UUID.fromString(rows.getString("round_id")), UUID.fromString(rows.getString("operation_id")),
                UUID.fromString(rows.getString("event_id")), rows.getString("operation_type"), rows.getLong("expected_version"),
                rows.getBytes("payload"), OperationStage.valueOf(rows.getString("stage")), rows.getLong("version"));
    }

    private void checkOpenPath() throws SQLException {
        if (connection.isClosed()) throw new SQLException("Round store is closed");
        try { SafeDatabasePath.validate(databasePath); }
        catch (IOException unsafePath) { throw new SQLException("Database path is no longer safe", unsafePath); }
    }

    private <T> T transaction(SqlAction<T> action) throws SQLException {
        // Xerial's Connection.commit/rollback restart a transaction while autoCommit is false.
        // Explicit SQL avoids a second lock attempt after a commit already became durable.
        boolean began = false;
        try (var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            began = true;
            T value = action.run();
            statement.execute("COMMIT");
            return value;
        } catch (SQLException | RuntimeException | Error transactionFailure) {
            if (began) {
                try (var statement = connection.createStatement()) { statement.execute("ROLLBACK"); }
                catch (SQLException rollbackFailure) { transactionFailure.addSuppressed(rollbackFailure); }
            }
            throw transactionFailure;
        }
    }

    @FunctionalInterface
    private interface SqlAction<T> { T run() throws SQLException; }
}
