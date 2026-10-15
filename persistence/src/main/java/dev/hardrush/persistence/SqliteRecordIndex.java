package dev.hardrush.persistence;

import dev.hardrush.core.record.*;
import dev.hardrush.core.round.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Derived indexes and durable observations; every mutation shares the owning store transaction. */
final class SqliteRecordIndex {
    private final Connection connection;
    SqliteRecordIndex(Connection connection) { this.connection = connection; }

    void migrate() throws SQLException {
        try (var sql = connection.createStatement()) {
            sql.execute("""
                CREATE TABLE IF NOT EXISTS history_rounds (
                  round_id TEXT PRIMARY KEY REFERENCES round_snapshots(round_id),
                  round_sequence INTEGER NOT NULL UNIQUE, rules_fingerprint TEXT NOT NULL,
                  challenge_millis INTEGER NOT NULL, eligible INTEGER NOT NULL CHECK(eligible IN (0,1)),
                  trusted_winner INTEGER NOT NULL CHECK(trusted_winner IN (0,1)),
                  edition INTEGER NOT NULL DEFAULT 1 CHECK(edition BETWEEN 1 AND 1001),
                  payload BLOB NOT NULL, payload_digest BLOB NOT NULL CHECK(length(payload_digest)=32))
                """);
            sql.execute("CREATE INDEX IF NOT EXISTS history_leaderboard ON history_rounds(rules_fingerprint,eligible,challenge_millis,round_sequence)");
            sql.execute("""
                CREATE TABLE IF NOT EXISTS participant_records (
                  round_id TEXT NOT NULL REFERENCES round_snapshots(round_id), player_id TEXT NOT NULL,
                  life_sequence INTEGER NOT NULL, first_end_nanos INTEGER, payload BLOB NOT NULL,
                  payload_digest BLOB NOT NULL CHECK(length(payload_digest)=32), PRIMARY KEY(round_id,player_id))
                """);
            sql.execute("""
                CREATE TABLE IF NOT EXISTS progress_events (
                  sequence INTEGER PRIMARY KEY AUTOINCREMENT,event_id TEXT NOT NULL UNIQUE,
                  round_id TEXT NOT NULL REFERENCES round_snapshots(round_id),player_id TEXT NOT NULL,
                  payload BLOB NOT NULL,write_digest BLOB NOT NULL CHECK(length(write_digest)=32))
                """);
            sql.execute("CREATE INDEX IF NOT EXISTS participant_end_entries ON participant_records(round_id,first_end_nanos)");
            sql.execute("""
                CREATE TABLE IF NOT EXISTS round_advancements (
                  round_id TEXT NOT NULL,player_id TEXT NOT NULL,advancement_id TEXT NOT NULL,
                  event_id TEXT NOT NULL REFERENCES progress_events(event_id),
                  PRIMARY KEY(round_id,player_id,advancement_id),
                  FOREIGN KEY(round_id,player_id) REFERENCES participant_records(round_id,player_id))
                """);
            sql.execute("""
                CREATE TABLE IF NOT EXISTS advancement_sets (
                  round_id TEXT NOT NULL,player_id TEXT NOT NULL,fingerprint TEXT NOT NULL,
                  event_id TEXT NOT NULL REFERENCES progress_events(event_id),
                  PRIMARY KEY(round_id,player_id,fingerprint),
                  FOREIGN KEY(round_id,player_id) REFERENCES participant_records(round_id,player_id))
                """);
            sql.execute("""
                CREATE TABLE IF NOT EXISTS book_deliveries (
                  player_id TEXT NOT NULL,round_id TEXT NOT NULL REFERENCES history_rounds(round_id),
                  edition INTEGER NOT NULL,volume INTEGER NOT NULL,delivered INTEGER NOT NULL CHECK(delivered IN (0,1)),
                  claim_digest BLOB NOT NULL CHECK(length(claim_digest)=32), PRIMARY KEY(player_id,round_id,edition,volume))
                """);
            sql.execute("CREATE INDEX IF NOT EXISTS advancement_fingerprint_lookup ON advancement_sets(round_id,fingerprint)");
        }
    }

    void index(SnapshotWrite write, boolean trustedWinner) throws SQLException {
        RoundSnapshot snapshot;
        try { snapshot = RoundSnapshotCodec.decode(write.payload()); }
        catch (IllegalArgumentException opaqueLegacySnapshot) { return; }
        if (!snapshot.roundId().equals(write.roundId()) || snapshot.version() != write.newVersion()) {
            throw new IllegalArgumentException("Record snapshot tags do not match");
        }
        // The first terminal commit owns immutable participant facts and result data.
        if (history(snapshot.roundId()).isPresent()) return;
        for (Participant participant : snapshot.participants().values()) {
            if (participant.joinedAtChallengeNanos() < 0 || participant.lifeSequence() == 0) continue;
            var before = participant(snapshot.roundId(), participant.id());
            int previousLife = participantLife(snapshot.roundId(), participant.id());
            int deaths = before.map(ParticipantRecord::deathCount).orElse(Math.max(0, participant.lifeSequence() - 1));
            int revivals = before.map(ParticipantRecord::revivalCount).orElse(Math.max(0, participant.lifeSequence() - 1));
            if (before.isPresent()) {
                ParticipantRecord previous = before.orElseThrow();
                if (previous.finalRole() == ParticipantRole.HUMAN_ACTIVE
                        && participant.role() == ParticipantRole.HUMAN_RESPAWNING) deaths = Math.addExact(deaths, 1);
                if (participant.lifeSequence() > previousLife) revivals = Math.addExact(revivals, participant.lifeSequence() - previousLife);
            } else if (participant.role() == ParticipantRole.HUMAN_RESPAWNING) deaths = Math.addExact(deaths, 1);
            var record = new ParticipantRecord(participant.id(), before.map(ParticipantRecord::playerName).orElse(participant.name()),
                    before.map(ParticipantRecord::joinedAtChallengeNanos).orElse(participant.joinedAtChallengeNanos()),
                    participant.humanNanos(), deaths, revivals, before.map(ParticipantRecord::advancementCount).orElse(0),
                    before.map(ParticipantRecord::firstEndNanos).orElse(null), participant.role(),
                    before.map(ParticipantRecord::advancementSetFingerprints).orElse(Set.of()));
            saveParticipant(snapshot.roundId(), record, participant.lifeSequence());
        }
        if (snapshot.result() != null) {
            boolean trusted = trustedWinner && snapshot.result().winner() != null;
            boolean eligible = trusted && !snapshot.timingUncertain() && snapshot.result().kind() == RoundResult.Kind.COMPLETED;
            try (var statement = connection.prepareStatement("""
                INSERT INTO history_rounds(round_id,round_sequence,rules_fingerprint,challenge_millis,eligible,trusted_winner,payload,payload_digest)
                VALUES (?,?,?,?,?,?,?,?)
                """)) {
                statement.setString(1, snapshot.roundId().toString()); statement.setLong(2, snapshot.sequence());
                statement.setString(3, snapshot.rules().fingerprint()); statement.setLong(4, snapshot.challengeMillis());
                statement.setBoolean(5, eligible); statement.setBoolean(6, trusted); statement.setBytes(7, write.payload());
                statement.setBytes(8, RecordEncoding.historyDigest(write.payload(), trusted)); statement.executeUpdate();
            }
        }
    }

    CommitResult progress(ProgressWrite write, SnapshotData stored) throws SQLException {
        var snapshot = RoundSnapshotCodec.decode(stored.payload());
        Participant player = snapshot.participants().get(write.playerId());
        if (snapshot.result() != null || (snapshot.phase() != RoundPhase.RUNNING && snapshot.phase() != RoundPhase.RESCUE)
                || player == null || !player.online() || player.role() != ParticipantRole.HUMAN_ACTIVE
                || player.lifeSequence() < 1 || player.joinedAtChallengeNanos() < 0
                || write.challengeNanos() < player.joinedAtChallengeNanos()) return CommitResult.STALE;
        var before = participant(write.roundId(), write.playerId()).orElseThrow(() -> new SQLException("Admitted participant index is missing"));
        try (var statement = connection.prepareStatement("SELECT COUNT(DISTINCT fingerprint) AS total, MAX(fingerprint=?) AS present FROM advancement_sets WHERE round_id=?")) {
            statement.setString(1, write.advancementSetFingerprint()); statement.setString(2, write.roundId().toString());
            try (var rows = statement.executeQuery()) {
                rows.next();
                if (!rows.getBoolean("present") && rows.getInt("total") >= 64) throw new SQLException("Advancement set changes exceed per-round query budget");
            }
        }
        byte[] payload = RecordEncoding.progress(write);
        try (var statement = connection.prepareStatement("INSERT INTO progress_events(event_id,round_id,player_id,payload,write_digest) VALUES (?,?,?,?,?)")) {
            statement.setString(1, write.eventId().toString()); statement.setString(2, write.roundId().toString());
            statement.setString(3, write.playerId().toString()); statement.setBytes(4, payload); statement.setBytes(5, WriteDigest.payload(payload));
            statement.executeUpdate();
        }
        for (String id : write.completedAdvancementIds()) {
            try (var statement = connection.prepareStatement("INSERT OR IGNORE INTO round_advancements VALUES (?,?,?,?)")) {
                statement.setString(1, write.roundId().toString()); statement.setString(2, write.playerId().toString());
                statement.setString(3, id); statement.setString(4, write.eventId().toString()); statement.executeUpdate();
            }
        }
        try (var statement = connection.prepareStatement("INSERT OR IGNORE INTO advancement_sets VALUES (?,?,?,?)")) {
            statement.setString(1, write.roundId().toString()); statement.setString(2, write.playerId().toString());
            statement.setString(3, write.advancementSetFingerprint()); statement.setString(4, write.eventId().toString()); statement.executeUpdate();
        }
        var fingerprints = new HashSet<>(before.advancementSetFingerprints()); fingerprints.add(write.advancementSetFingerprint());
        Long firstEnd = before.firstEndNanos() != null ? before.firstEndNanos() : write.enteredEnd() ? write.challengeNanos() : null;
        int count;
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM round_advancements WHERE round_id=? AND player_id=?")) {
            statement.setString(1, write.roundId().toString()); statement.setString(2, write.playerId().toString());
            try (var rows = statement.executeQuery()) { rows.next(); count = rows.getInt(1); }
        }
        saveParticipant(write.roundId(), new ParticipantRecord(before.playerId(), before.playerName(), before.joinedAtChallengeNanos(),
                before.humanNanos(), before.deathCount(), before.revivalCount(), count, firstEnd, before.finalRole(), fingerprints), player.lifeSequence());
        return CommitResult.APPLIED;
    }

    Optional<RoundHistory> history(UUID roundId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM history_rounds WHERE round_id=?")) {
            statement.setString(1, roundId.toString()); try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(history(rows)) : Optional.empty(); }
        }
    }
    List<RoundHistory> histories(int limit, int offset) throws SQLException {
        bounds(limit, offset); var result = new ArrayList<RoundHistory>();
        try (var statement = connection.prepareStatement("SELECT * FROM history_rounds ORDER BY round_sequence DESC LIMIT ? OFFSET ?")) {
            statement.setInt(1, limit); statement.setInt(2, offset);
            try (var rows = statement.executeQuery()) { while (rows.next()) result.add(history(rows)); }
        }
        return List.copyOf(result);
    }
    Optional<RoundHistory> bySequence(long sequence, boolean before) throws SQLException {
        if (sequence < 1) throw new IllegalArgumentException("Invalid sequence");
        try (var statement = connection.prepareStatement("SELECT * FROM history_rounds WHERE round_sequence" + (before ? "<? ORDER BY round_sequence DESC LIMIT 1" : "=?"))) {
            statement.setLong(1, sequence); try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(history(rows)) : Optional.empty(); }
        }
    }
    List<LeaderboardEntry> leaderboard(String fingerprint, int limit) throws SQLException {
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid rules fingerprint");
        bounds(limit, 0); var result = new ArrayList<LeaderboardEntry>();
        try (var statement = connection.prepareStatement("""
            SELECT *,RANK() OVER(ORDER BY challenge_millis) AS placement FROM history_rounds
            WHERE rules_fingerprint=? AND eligible=1 ORDER BY challenge_millis,round_sequence LIMIT ?
            """)) {
            statement.setString(1, fingerprint); statement.setInt(2, limit);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    RoundHistory value = history(rows);
                    var winner = participant(value.roundId(), value.result().winner()).orElseThrow(() -> new SQLException("Winner record is missing"));
                    result.add(new LeaderboardEntry(rows.getInt("placement"), value, winner.playerName()));
                }
            }
        }
        return List.copyOf(result);
    }
    List<ParticipantRecord> participants(UUID roundId, int limit, int offset) throws SQLException {
        bounds(limit, offset); var result = new ArrayList<ParticipantRecord>();
        try (var statement = connection.prepareStatement("SELECT * FROM participant_records WHERE round_id=? ORDER BY player_id LIMIT ? OFFSET ?")) {
            statement.setString(1, roundId.toString()); statement.setInt(2, limit); statement.setInt(3, offset);
            try (var rows = statement.executeQuery()) { while (rows.next()) result.add(participant(rows)); }
        }
        return List.copyOf(result);
    }
    long participantCount(UUID roundId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM participant_records WHERE round_id=?")) {
            statement.setString(1, roundId.toString()); try (var rows = statement.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }
    long endCount(UUID roundId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM participant_records WHERE round_id=? AND first_end_nanos IS NOT NULL")) {
            statement.setString(1, roundId.toString()); try (var rows = statement.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }
    BookClaim ensureClaim(UUID player, UUID round, int edition, int volume) throws SQLException {
        new BookClaim(player, round, edition, volume, false);
        var source = history(round).orElseThrow(() -> new IllegalArgumentException("Books require a completed history round"));
        if (source.edition() != edition) throw new IllegalArgumentException("Book edition does not match history");
        try (var statement = connection.prepareStatement("INSERT OR IGNORE INTO book_deliveries VALUES (?,?,?,?,0,?)")) {
            statement.setString(1, player.toString()); statement.setString(2, round.toString()); statement.setInt(3, edition); statement.setInt(4, volume);
            statement.setBytes(5, RecordEncoding.claimDigest(player, round, edition, volume, false)); statement.executeUpdate();
        }
        return claim(player, round, edition, volume).orElseThrow();
    }
    Optional<BookClaim> latestClaim(UUID player) throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT delivery.* FROM book_deliveries delivery JOIN history_rounds history USING(round_id)
            WHERE delivery.player_id=? AND volume=1 ORDER BY history.round_sequence DESC,edition DESC LIMIT 1
            """)) {
            statement.setString(1, player.toString()); try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(claim(rows)) : Optional.empty(); }
        }
    }
    boolean deliver(UUID player, UUID round, int edition, int volume) throws SQLException {
        var current = claim(player, round, edition, volume);
        if (current.isEmpty()) return false; if (current.orElseThrow().delivered()) return true;
        try (var statement = connection.prepareStatement("UPDATE book_deliveries SET delivered=1,claim_digest=? WHERE player_id=? AND round_id=? AND edition=? AND volume=? AND delivered=0")) {
            statement.setBytes(1, RecordEncoding.claimDigest(player, round, edition, volume, true));
            statement.setString(2, player.toString()); statement.setString(3, round.toString()); statement.setInt(4, edition); statement.setInt(5, volume);
            return statement.executeUpdate() == 1;
        }
    }
    private Optional<BookClaim> claim(UUID player, UUID round, int edition, int volume) throws SQLException {
        new BookClaim(player, round, edition, volume, false);
        try (var statement = connection.prepareStatement("SELECT * FROM book_deliveries WHERE player_id=? AND round_id=? AND edition=? AND volume=?")) {
            statement.setString(1, player.toString()); statement.setString(2, round.toString()); statement.setInt(3, edition); statement.setInt(4, volume);
            try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(claim(rows)) : Optional.empty(); }
        }
    }
    private static BookClaim claim(ResultSet rows) throws SQLException {
        var value = new BookClaim(UUID.fromString(rows.getString("player_id")), UUID.fromString(rows.getString("round_id")),
                rows.getInt("edition"), rows.getInt("volume"), rows.getBoolean("delivered"));
        digest(RecordEncoding.claimDigest(value.playerId(), value.roundId(), value.edition(), value.volume(), value.delivered()), rows.getBytes("claim_digest"), "Book claim");
        return value;
    }
    private RoundHistory history(ResultSet rows) throws SQLException {
        byte[] payload = rows.getBytes("payload"); boolean trusted = rows.getBoolean("trusted_winner");
        long edition = rows.getLong("edition");
        digest(RecordEncoding.historyDigest(payload, trusted, edition), rows.getBytes("payload_digest"), "History");
        RoundSnapshot snapshot;
        try { snapshot = RoundSnapshotCodec.decode(payload); } catch (IllegalArgumentException invalid) { throw new SQLException("Invalid history snapshot", invalid); }
        if (snapshot.result() == null || !snapshot.roundId().toString().equals(rows.getString("round_id"))
                || snapshot.sequence() != rows.getLong("round_sequence") || !snapshot.rules().fingerprint().equals(rows.getString("rules_fingerprint"))
                || snapshot.challengeMillis() != rows.getLong("challenge_millis")) throw new SQLException("History index tags do not match");
        var value = new RoundHistory(snapshot.roundId(), snapshot.sequence(), snapshot.seed(), snapshot.rules(), snapshot.result(),
                snapshot.challengeNanos(), snapshot.timingUncertain(), trusted, edition, fingerprints(snapshot.roundId()));
        if (value.leaderboardEligible() != rows.getBoolean("eligible")) throw new SQLException("History eligibility does not match");
        return value;
    }
    private Set<String> fingerprints(UUID round) throws SQLException {
        var result = new HashSet<String>();
        try (var statement = connection.prepareStatement("""
            SELECT sets.*,event.payload,event.write_digest,event.round_id AS owning_round,event.player_id AS owning_player
            FROM advancement_sets sets LEFT JOIN progress_events event USING(event_id) WHERE sets.round_id=?
            GROUP BY sets.fingerprint LIMIT 65
            """)) {
            statement.setString(1, round.toString());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    byte[] payload = rows.getBytes("payload"); digest(WriteDigest.payload(payload), rows.getBytes("write_digest"), "Advancement set event");
                    ProgressWrite write;
                    try { write = RecordEncoding.progress(payload); } catch (IllegalArgumentException invalid) { throw new SQLException("Invalid progress event", invalid); }
                    if (!write.roundId().equals(round) || !write.playerId().toString().equals(rows.getString("player_id"))
                            || !write.eventId().toString().equals(rows.getString("event_id"))
                            || !round.toString().equals(rows.getString("owning_round"))
                            || !write.playerId().toString().equals(rows.getString("owning_player"))
                            || !write.advancementSetFingerprint().equals(rows.getString("fingerprint"))) throw new SQLException("Advancement set owner mismatch");
                    result.add(write.advancementSetFingerprint());
                    if (result.size() > 64) throw new SQLException("Advancement set count exceeds per-round query budget");
                }
            }
        }
        return Set.copyOf(result);
    }
    void verifyProgress(UUID eventId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM progress_events WHERE event_id=?")) {
            statement.setString(1, eventId.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Progress event is missing");
                byte[] payload = rows.getBytes("payload");
                digest(WriteDigest.payload(payload), rows.getBytes("write_digest"), "Progress event");
                ProgressWrite write;
                try { write = RecordEncoding.progress(payload); } catch (IllegalArgumentException invalid) { throw new SQLException("Invalid progress event", invalid); }
                if (!write.eventId().equals(eventId) || !write.roundId().toString().equals(rows.getString("round_id"))
                        || !write.playerId().toString().equals(rows.getString("player_id"))) throw new SQLException("Progress event owner mismatch");
            }
        }
    }
    List<String> advancementIds(UUID round, UUID player, int limit, int offset) throws SQLException {
        bounds(limit, offset); var result = new ArrayList<String>();
        try (var statement = connection.prepareStatement("""
            SELECT fact.*,event.payload,event.write_digest,event.round_id AS owning_round,event.player_id AS owning_player FROM round_advancements fact
            LEFT JOIN progress_events event USING(event_id)
            WHERE fact.round_id=? AND fact.player_id=? ORDER BY advancement_id LIMIT ? OFFSET ?
            """)) {
            statement.setString(1, round.toString()); statement.setString(2, player.toString());
            statement.setInt(3, limit); statement.setInt(4, offset);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    byte[] payload = rows.getBytes("payload"); digest(WriteDigest.payload(payload), rows.getBytes("write_digest"), "Advancement event");
                    ProgressWrite write;
                    try { write = RecordEncoding.progress(payload); } catch (IllegalArgumentException invalid) { throw new SQLException("Invalid progress event", invalid); }
                    String id = rows.getString("advancement_id");
                    if (!write.roundId().equals(round) || !write.playerId().equals(player)
                            || !write.eventId().toString().equals(rows.getString("event_id")) || !write.completedAdvancementIds().contains(id)
                            || !round.toString().equals(rows.getString("owning_round")) || !player.toString().equals(rows.getString("owning_player"))) {
                        throw new SQLException("Advancement owner mismatch");
                    }
                    result.add(id);
                }
            }
        }
        return List.copyOf(result);
    }
    Optional<ParticipantRecord> participant(UUID round, UUID player) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM participant_records WHERE round_id=? AND player_id=?")) {
            statement.setString(1, round.toString()); statement.setString(2, player.toString());
            try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(participant(rows)) : Optional.empty(); }
        }
    }
    private static ParticipantRecord participant(ResultSet rows) throws SQLException {
        byte[] payload = rows.getBytes("payload");
        digest(RecordEncoding.participantDigest(UUID.fromString(rows.getString("round_id")), rows.getInt("life_sequence"), payload), rows.getBytes("payload_digest"), "Participant");
        try {
            var value = RecordEncoding.participant(payload);
            if (!value.playerId().toString().equals(rows.getString("player_id"))) throw new SQLException("Participant owner mismatch");
            Object firstEnd = rows.getObject("first_end_nanos");
            if (value.firstEndNanos() == null ? firstEnd != null : !(firstEnd instanceof Number number) || number.longValue() != value.firstEndNanos()) {
                throw new SQLException("Participant END index mismatch");
            }
            return value;
        } catch (IllegalArgumentException invalid) { throw new SQLException("Invalid participant payload", invalid); }
    }
    private int participantLife(UUID round, UUID player) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT life_sequence FROM participant_records WHERE round_id=? AND player_id=?")) {
            statement.setString(1, round.toString()); statement.setString(2, player.toString());
            try (var rows = statement.executeQuery()) { return rows.next() ? rows.getInt(1) : 0; }
        }
    }
    private void saveParticipant(UUID round, ParticipantRecord value, int lifeSequence) throws SQLException {
        byte[] payload = RecordEncoding.participant(value);
        try (var statement = connection.prepareStatement("""
            INSERT INTO participant_records VALUES (?,?,?,?,?,?) ON CONFLICT(round_id,player_id) DO UPDATE SET
            life_sequence=excluded.life_sequence,first_end_nanos=excluded.first_end_nanos,payload=excluded.payload,payload_digest=excluded.payload_digest
            """)) {
            statement.setString(1, round.toString()); statement.setString(2, value.playerId().toString()); statement.setInt(3, lifeSequence);
            statement.setObject(4, value.firstEndNanos()); statement.setBytes(5, payload);
            statement.setBytes(6, RecordEncoding.participantDigest(round, lifeSequence, payload)); statement.executeUpdate();
        }
    }
    static void bounds(int limit, int offset) {
        if (limit < 1 || limit > 1000 || offset < 0 || offset > 1_000_000) throw new IllegalArgumentException("Query requires limit 1..1000 and offset 0..1000000");
    }
    private static void digest(byte[] actual, byte[] expected, String subject) throws SQLException {
        if (expected == null || expected.length != 32 || !MessageDigest.isEqual(actual, expected)) throw new SQLException(subject + " digest mismatch");
    }
}
