package dev.hardrush.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Native SQLite error codes, including automatic SQLITE_FULL rollback; never fills the host disk. */
final class StorageFaultTest {
    @TempDir Path directory;
    enum Fault { READ_ONLY, FULL, INDEX_ABORT }

    static Stream<Arguments> boundaries() {
        return Stream.of(StorageFaultFixture.Scenario.values()).flatMap(scenario ->
                Stream.of(Fault.values()).map(fault -> Arguments.of(scenario, fault)));
    }

    @ParameterizedTest(name = "{0}: {1} fails atomically, then identical event retries")
    @MethodSource("boundaries")
    void failedGrantConsumptionAndTerminalNeverExposePartialState(StorageFaultFixture.Scenario scenario, Fault fault) throws Exception {
        Path database = directory.toRealPath().resolve("fault.sqlite");
        StorageFaultFixture.prepare(database, scenario);
        var request = StorageFaultFixture.request(database, scenario);
        try (var store = new SqliteRoundStore(database); var sql = StorageFaultFixture.connection(store).createStatement()) {
            switch (fault) {
                case READ_ONLY -> sql.execute("PRAGMA query_only=ON");
                case FULL -> {
                    int pages;
                    try (var row = sql.executeQuery("PRAGMA page_count")) { assertTrue(row.next()); pages = row.getInt(1); }
                    sql.execute("PRAGMA max_page_count=" + pages);
                    sql.execute("CREATE TEMP TRIGGER fail_full AFTER UPDATE ON round_snapshots BEGIN INSERT INTO fault_payload VALUES (zeroblob(262144)); END");
                }
                case INDEX_ABORT -> sql.execute("CREATE TEMP TRIGGER fail_index " + StorageFaultFixture.lateTrigger(scenario)
                        + " BEGIN SELECT RAISE(ABORT, 'p3 injected index failure'); END");
            }
            SQLException failure = assertThrows(SQLException.class, () -> request.commit(store));
            assertEquals(switch (fault) { case READ_ONLY -> 8; case FULL -> 13; case INDEX_ABORT -> 19; }, failure.getErrorCode(), failure::toString);
            StorageFaultFixture.verify(store, database, scenario, false);
            switch (fault) {
                case READ_ONLY -> sql.execute("PRAGMA query_only=OFF");
                case FULL -> { sql.execute("PRAGMA max_page_count=1073741823"); sql.execute("DROP TRIGGER fail_full"); }
                case INDEX_ABORT -> sql.execute("DROP TRIGGER fail_index");
            }
            try (var row = sql.executeQuery("SELECT COUNT(*) FROM fault_payload")) { assertTrue(row.next()); assertEquals(0, row.getInt(1)); }
            assertEquals(CommitResult.APPLIED, request.commit(store));
            assertEquals(CommitResult.DUPLICATE, request.commit(store));
            StorageFaultFixture.verify(store, database, scenario, true);
        }
        try (var reopened = new SqliteRoundStore(database)) {
            assertEquals(CommitResult.DUPLICATE, request.commit(reopened));
            StorageFaultFixture.verify(reopened, database, scenario, true);
        }
    }
}
