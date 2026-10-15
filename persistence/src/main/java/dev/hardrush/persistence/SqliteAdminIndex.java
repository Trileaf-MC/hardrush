package dev.hardrush.persistence;

import dev.hardrush.core.record.AdminAudit;
import dev.hardrush.core.record.HistoryRevision;
import dev.hardrush.core.record.RoundHistory;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.core.round.RoundSnapshotCodec;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Administrator ledger and history projections. The caller owns the surrounding transaction. */
final class SqliteAdminIndex {
    private final Connection connection;
    SqliteAdminIndex(Connection connection) { this.connection = connection; }

    void migrate() throws SQLException {
        boolean editionPresent = false;
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA table_info(history_rounds)")) {
            while (rows.next()) if ("edition".equals(rows.getString("name"))) editionPresent = true;
        }
        try (var sql = connection.createStatement()) {
            if (!editionPresent) sql.execute("ALTER TABLE history_rounds ADD COLUMN edition INTEGER NOT NULL DEFAULT 1 CHECK(edition BETWEEN 1 AND 1001)");
            sql.execute("""
                CREATE TABLE IF NOT EXISTS admin_audits (
                  sequence INTEGER PRIMARY KEY AUTOINCREMENT, operation_id TEXT NOT NULL UNIQUE,
                  round_id TEXT REFERENCES round_snapshots(round_id), actor TEXT NOT NULL, action TEXT NOT NULL,
                  reason TEXT NOT NULL, before_value TEXT NOT NULL, after_value TEXT NOT NULL,
                  occurred_at_epoch_millis INTEGER NOT NULL CHECK(occurred_at_epoch_millis>=0),
                  audit_digest BLOB NOT NULL CHECK(length(audit_digest)=32),
                  write_digest BLOB NOT NULL CHECK(length(write_digest)=32))
                """);
            sql.execute("CREATE INDEX IF NOT EXISTS admin_audits_by_round ON admin_audits(round_id,sequence)");
            sql.execute("""
                CREATE TABLE IF NOT EXISTS history_originals (
                  round_id TEXT PRIMARY KEY REFERENCES history_rounds(round_id), payload BLOB NOT NULL,
                  trusted_winner INTEGER NOT NULL CHECK(trusted_winner IN (0,1)),
                  payload_digest BLOB NOT NULL CHECK(length(payload_digest)=32))
                """);
            sql.execute("""
                INSERT OR IGNORE INTO history_originals SELECT round_id,payload,trusted_winner,payload_digest FROM history_rounds
                """);
            sql.execute("""
                CREATE TRIGGER IF NOT EXISTS capture_history_original AFTER INSERT ON history_rounds
                BEGIN INSERT INTO history_originals VALUES (NEW.round_id,NEW.payload,NEW.trusted_winner,NEW.payload_digest); END
                """);
            sql.execute("""
                CREATE TABLE IF NOT EXISTS history_revisions (
                  operation_id TEXT PRIMARY KEY REFERENCES admin_audits(operation_id),
                  round_id TEXT NOT NULL REFERENCES history_originals(round_id), edition INTEGER NOT NULL CHECK(edition BETWEEN 2 AND 1001),
                  payload BLOB NOT NULL, write_digest BLOB NOT NULL CHECK(length(write_digest)=32),
                  before_digest BLOB NOT NULL CHECK(length(before_digest)=32),
                  after_digest BLOB NOT NULL CHECK(length(after_digest)=32),
                  UNIQUE(round_id,edition))
                """);
            sql.execute("PRAGMA user_version=5");
        }
    }

    boolean duplicate(AdminAudit audit, byte[] ownerDigest) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM admin_audits WHERE operation_id=?")) {
            statement.setString(1, audit.operationId().toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return false;
                AdminAudit stored = audit(rows);
                if (!stored.equals(audit) || !MessageDigest.isEqual(ownerDigest, rows.getBytes("write_digest"))) {
                    throw new EventCollisionException(audit.operationId());
                }
                return true;
            }
        }
    }

    void insert(AdminAudit audit, byte[] ownerDigest) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO admin_audits(operation_id,round_id,actor,action,reason,before_value,after_value,
            occurred_at_epoch_millis,audit_digest,write_digest) VALUES (?,?,?,?,?,?,?,?,?,?)
            """)) {
            statement.setString(1, audit.operationId().toString());
            statement.setString(2, audit.roundId() == null ? null : audit.roundId().toString());
            statement.setString(3, audit.actor()); statement.setString(4, audit.action()); statement.setString(5, audit.reason());
            statement.setString(6, audit.beforeValue()); statement.setString(7, audit.afterValue());
            statement.setLong(8, audit.occurredAtEpochMillis());
            statement.setBytes(9, WriteDigest.payload(RecordEncoding.audit(audit))); statement.setBytes(10, ownerDigest);
            statement.executeUpdate();
        }
    }

    List<AdminAudit> audits(UUID roundId, int limit, int offset) throws SQLException {
        SqliteRecordIndex.bounds(limit, offset); var result = new ArrayList<AdminAudit>();
        String sql = "SELECT * FROM admin_audits" + (roundId == null ? "" : " WHERE round_id=?") + " ORDER BY sequence DESC LIMIT ? OFFSET ?";
        try (var statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (roundId != null) statement.setString(index++, roundId.toString());
            statement.setInt(index++, limit); statement.setInt(index, offset);
            try (var rows = statement.executeQuery()) { while (rows.next()) result.add(audit(rows)); }
        }
        return List.copyOf(result);
    }

    private static AdminAudit audit(ResultSet rows) throws SQLException {
        try {
            String round = rows.getString("round_id");
            var value = new AdminAudit(UUID.fromString(rows.getString("operation_id")), round == null ? null : UUID.fromString(round),
                    rows.getString("actor"), rows.getString("action"), rows.getString("reason"),
                    rows.getString("before_value"), rows.getString("after_value"), rows.getLong("occurred_at_epoch_millis"));
            digest(WriteDigest.payload(RecordEncoding.audit(value)), rows.getBytes("audit_digest"), "Admin audit");
            return value;
        } catch (IllegalArgumentException invalid) { throw new SQLException("Invalid administrator audit", invalid); }
    }

    Original original(UUID roundId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM history_originals WHERE round_id=?")) {
            statement.setString(1, roundId.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("History original is missing");
                byte[] payload = rows.getBytes("payload"); boolean trusted = rows.getBoolean("trusted_winner");
                digest(RecordEncoding.historyDigest(payload, trusted), rows.getBytes("payload_digest"), "Original history");
                try {
                    var snapshot = RoundSnapshotCodec.decode(payload);
                    if (!snapshot.roundId().equals(roundId) || snapshot.result() == null) throw new SQLException("Original history tags mismatch");
                    return new Original(snapshot, trusted, payload);
                } catch (IllegalArgumentException invalid) { throw new SQLException("Invalid original history", invalid); }
            }
        }
    }

    /** Rebuild the projection from its immutable original and each audited correction. */
    Original verifyProjection(RoundHistory history) throws SQLException {
        Original original = original(history.roundId());
        byte[] payload = original.payload(); boolean trusted = original.trusted(); long edition = 1;
        byte[] beforeDigest = RecordEncoding.historyDigest(payload, trusted);
        try (var statement = connection.prepareStatement("""
            SELECT revisions.*,audits.actor,audits.action,audits.reason,audits.before_value,audits.after_value,
            audits.occurred_at_epoch_millis,audits.audit_digest,audits.write_digest AS owner_digest,
            audits.round_id AS audit_round_id FROM history_revisions revisions
            LEFT JOIN admin_audits audits USING(operation_id) WHERE revisions.round_id=? ORDER BY edition LIMIT 1001
            """)) {
            statement.setString(1, history.roundId().toString());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    byte[] requestPayload = rows.getBytes("payload");
                    digest(WriteDigest.payload(requestPayload), rows.getBytes("write_digest"), "History revision");
                    digest(WriteDigest.payload(requestPayload), rows.getBytes("owner_digest"), "Revision audit owner");
                    digest(beforeDigest, rows.getBytes("before_digest"), "Revision predecessor");
                    HistoryRevision request;
                    try { request = RecordEncoding.revision(requestPayload); }
                    catch (IllegalArgumentException invalid) { throw new SQLException("Invalid history revision", invalid); }
                    if (request.expectedEdition() != edition || rows.getLong("edition") != edition + 1
                            || !request.roundId().equals(history.roundId())
                            || !request.operationId().toString().equals(rows.getString("operation_id"))
                            || !history.roundId().toString().equals(rows.getString("audit_round_id"))) {
                        throw new SQLException("History revision owner or edition mismatch");
                    }
                    // audit() uses revision's round_id, which was checked against the joined audit above.
                    audit(rows);
                    try {
                        trusted = verifiedTrust(request, original);
                        payload = projectionPayload(RoundSnapshotCodec.decode(payload), request);
                    } catch (IllegalArgumentException invalid) { throw new SQLException("Invalid revised projection", invalid); }
                    beforeDigest = RecordEncoding.historyDigest(payload, trusted, ++edition);
                    digest(beforeDigest, rows.getBytes("after_digest"), "Revision projection");
                }
            }
        }
        var snapshot = RoundSnapshotCodec.decode(payload);
        if (history.edition() != edition || history.sequence() != snapshot.sequence() || history.seed() != snapshot.seed()
                || !history.rules().equals(snapshot.rules()) || !history.result().equals(snapshot.result())
                || history.challengeNanos() != snapshot.challengeNanos() || history.timingUncertain() != snapshot.timingUncertain()
                || history.trustedWinner() != trusted) throw new SQLException("History projection does not match its audited revisions");
        try (var statement = connection.prepareStatement("SELECT payload,payload_digest FROM history_rounds WHERE round_id=?")) {
            statement.setString(1, history.roundId().toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !Arrays.equals(payload, rows.getBytes("payload"))) throw new SQLException("Revised history payload mismatch");
                digest(beforeDigest, rows.getBytes("payload_digest"), "Revised history digest");
            }
        }
        return original;
    }

    void revise(HistoryRevision request, AdminAudit audit, RoundHistory current, Original original) throws SQLException {
        byte[] previous;
        try (var statement = connection.prepareStatement("SELECT payload FROM history_rounds WHERE round_id=?")) {
            statement.setString(1, request.roundId().toString());
            try (var rows = statement.executeQuery()) { if (!rows.next()) throw new SQLException("History missing"); previous = rows.getBytes(1); }
        }
        boolean trusted = verifiedTrust(request, original);
        byte[] payload = projectionPayload(RoundSnapshotCodec.decode(previous), request);
        long edition = current.edition() + 1;
        byte[] requestPayload = RecordEncoding.revision(request), ownerDigest = WriteDigest.payload(requestPayload);
        byte[] afterDigest = RecordEncoding.historyDigest(payload, trusted, edition);
        insert(audit, ownerDigest);
        try (var statement = connection.prepareStatement("INSERT INTO history_revisions VALUES (?,?,?,?,?,?,?)")) {
            statement.setString(1, request.operationId().toString()); statement.setString(2, request.roundId().toString());
            statement.setLong(3, edition); statement.setBytes(4, requestPayload); statement.setBytes(5, ownerDigest);
            statement.setBytes(6, RecordEncoding.historyDigest(previous, current.trustedWinner(), current.edition()));
            statement.setBytes(7, afterDigest); statement.executeUpdate();
        }
        var snapshot = RoundSnapshotCodec.decode(payload);
        boolean eligible = trusted && !snapshot.timingUncertain();
        try (var statement = connection.prepareStatement("""
            UPDATE history_rounds SET challenge_millis=?,eligible=?,trusted_winner=?,payload=?,payload_digest=?,edition=?
            WHERE round_id=? AND edition=?
            """)) {
            statement.setLong(1, snapshot.challengeMillis()); statement.setBoolean(2, eligible); statement.setBoolean(3, trusted);
            statement.setBytes(4, payload); statement.setBytes(5, afterDigest); statement.setLong(6, edition);
            statement.setString(7, request.roundId().toString()); statement.setLong(8, request.expectedEdition());
            if (statement.executeUpdate() != 1) throw new SQLException("History edition changed inside its transaction");
        }
    }

    List<HistoryRevision> revisions(UUID roundId, int limit, int offset) throws SQLException {
        SqliteRecordIndex.bounds(limit, offset); var result = new ArrayList<HistoryRevision>();
        try (var statement = connection.prepareStatement("SELECT * FROM history_revisions WHERE round_id=? ORDER BY edition DESC LIMIT ? OFFSET ?")) {
            statement.setString(1, roundId.toString()); statement.setInt(2, limit); statement.setInt(3, offset);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    byte[] payload = rows.getBytes("payload"); digest(WriteDigest.payload(payload), rows.getBytes("write_digest"), "Revision");
                    var request = RecordEncoding.revision(payload);
                    if (!request.roundId().equals(roundId) || request.expectedEdition() + 1 != rows.getLong("edition")
                            || !request.operationId().toString().equals(rows.getString("operation_id"))) throw new SQLException("Revision tags mismatch");
                    result.add(request);
                }
            }
        }
        return List.copyOf(result);
    }

    private static boolean verifiedTrust(HistoryRevision request, Original original) {
        if (request.action() == HistoryRevision.Action.CORRECT) return false;
        if (!original.trusted() || original.snapshot().result().winner() == null
                || !Objects.equals(request.result().winner(), original.snapshot().result().winner())) {
            throw new IllegalArgumentException("Verification requires the original trusted first-dragon winner");
        }
        return true;
    }

    private static byte[] projectionPayload(RoundSnapshot before, HistoryRevision request) {
        return RoundSnapshotCodec.encode(new RoundSnapshot(before.roundId(), before.sequence(), before.seed(), before.version(), before.rules(),
                before.phase(), before.pauseReason(), request.challengeNanos(), before.remainingPhaseNanos(), before.participants(),
                before.cleanShutdown(), request.timingUncertain(), request.result()));
    }

    private static void digest(byte[] actual, byte[] expected, String subject) throws SQLException {
        if (expected == null || expected.length != 32 || !MessageDigest.isEqual(actual, expected)) throw new SQLException(subject + " digest mismatch");
    }
    record Original(RoundSnapshot snapshot, boolean trusted, byte[] payload) {}
}
