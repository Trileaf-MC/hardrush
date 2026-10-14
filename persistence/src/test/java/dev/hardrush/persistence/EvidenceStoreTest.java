package dev.hardrush.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EvidenceStoreTest {
    @TempDir Path root;
    private SnapshotWrite initial(UUID round) {
        return new SnapshotWrite(round, -1, 0, new byte[]{1}, UUID.randomUUID(), "PREPARING");
    }

    @Test void evidenceIsDurableIdempotentBoundedAndDoesNotChangeStateVersion() throws Exception {
        Path database = root.toRealPath().resolve("evidence.sqlite");
        UUID round = UUID.randomUUID(), event = UUID.randomUUID();
        var write = new EvidenceWrite(round, event, "COMBAT", new byte[]{4, 5});
        try (var store = new SqliteRoundStore(database)) {
            assertThrows(java.sql.SQLException.class, () -> store.recordEvidence(write));
            store.commit(initial(round));
            assertEquals(CommitResult.APPLIED, store.recordEvidence(write));
            assertEquals(CommitResult.DUPLICATE, store.recordEvidence(write));
            assertEquals(0, store.load(round).orElseThrow().version());
            assertThrows(EventCollisionException.class, () -> store.recordEvidence(
                    new EvidenceWrite(round, event, "COMBAT", new byte[]{6})));
            assertThrows(IllegalArgumentException.class, () -> store.evidence(round, "COMBAT", 1001));
            assertEquals(1, store.evidence(round, "COMBAT", 1).size());
            assertThrows(EventCollisionException.class, () -> store.commit(
                    new SnapshotWrite(round, 0, 1, new byte[]{2}, event, "CHECKPOINT")));
        }
        try (var store = new SqliteRoundStore(database)) {
            assertArrayEquals(write.payload(), store.evidence(round, "COMBAT", 1).getFirst().payload());
            assertEquals(CommitResult.DUPLICATE, store.recordEvidence(write));
        }
    }

    @Test void versionOneMigrationPreservesRoundsAndRollsBackOnConflictingEvidenceSchema() throws Exception {
        Path database = root.toRealPath().resolve("migration.sqlite");
        UUID round = UUID.randomUUID();
        try (var store = new SqliteRoundStore(database)) { store.commit(initial(round)); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = connection.createStatement()) {
            sql.execute("DROP TABLE evidence_events");
            sql.execute("PRAGMA user_version=1");
            sql.execute("CREATE TABLE evidence_events(unrelated TEXT)");
        }
        assertThrows(java.sql.SQLException.class, () -> new SqliteRoundStore(database));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("PRAGMA user_version")) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
            sql.execute("DROP TABLE evidence_events");
        }
        try (var store = new SqliteRoundStore(database)) {
            assertEquals(0, store.load(round).orElseThrow().version());
            assertEquals(CommitResult.APPLIED, store.recordEvidence(
                    new EvidenceWrite(round, UUID.randomUUID(), "COMBAT", new byte[]{3})));
        }
    }

    @Test void corruptedEvidenceStopsQueryInsteadOfReturningGuessedAttribution() throws Exception {
        Path database = root.toRealPath().resolve("corrupt.sqlite");
        UUID round = UUID.randomUUID();
        try (var store = new SqliteRoundStore(database)) {
            store.commit(initial(round));
            store.recordEvidence(new EvidenceWrite(round, UUID.randomUUID(), "COMBAT", new byte[]{3}));
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = connection.createStatement()) {
                sql.execute("UPDATE evidence_events SET payload=X'04'");
            }
            assertThrows(java.sql.SQLException.class, () -> store.evidence(round, "COMBAT", 10));
        }
    }
}
