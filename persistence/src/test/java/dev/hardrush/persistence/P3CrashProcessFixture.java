package dev.hardrush.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.locks.LockSupport;
import org.sqlite.Function;

/** Standalone subprocess; halt branches intentionally never invoke close or shutdown hooks. */
public final class P3CrashProcessFixture {
    enum Boundary { HALT_AFTER_SNAPSHOT, HALT_AFTER_INDEX, HALT_AFTER_COMMIT, KILL_AFTER_INDEX }
    static final int HALT_CODE = 73;

    public static void main(String[] args) throws Exception {
        String operation = args[0];
        var scenario = P3FaultFixture.Scenario.valueOf(args[1]);
        var boundary = Boundary.valueOf(args[2]);
        Path database = Path.of(args[3]);
        var request = P3FaultFixture.request(database, scenario);
        if (operation.equals("read")) {
            try (var store = new SqliteRoundStore(database)) {
                boolean committed = boundary == Boundary.HALT_AFTER_COMMIT;
                P3FaultFixture.verify(store, database, scenario, committed);
                try (var sql = P3FaultFixture.connection(store).createStatement();
                     var row = sql.executeQuery("SELECT COUNT(*) FROM p3_fault_payload")) {
                    P3FaultFixture.require(row.next() && row.getInt(1) == 0, "uncommitted spill rows must roll back");
                }
                P3FaultFixture.require(request.commit(store) == (committed ? CommitResult.DUPLICATE : CommitResult.APPLIED), "same event recovery");
                P3FaultFixture.require(request.commit(store) == CommitResult.DUPLICATE, "second recovery is idempotent");
                P3FaultFixture.verify(store, database, scenario, true);
            }
            try (var reopened = new SqliteRoundStore(database)) {
                P3FaultFixture.require(request.commit(reopened) == CommitResult.DUPLICATE, "reopen replay");
                P3FaultFixture.verify(reopened, database, scenario, true);
            }
            System.out.println("PASS read " + scenario + " " + boundary + " pid=" + ProcessHandle.current().pid());
            return;
        }
        if (!operation.equals("write")) throw new IllegalArgumentException("Unknown operation " + operation);
        // Deliberately not a try-with-resources: every successful writer must die with SQLite open.
        var store = new SqliteRoundStore(database);
        var connection = P3FaultFixture.connection(store);
        try (var sql = connection.createStatement()) {
            sql.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            sql.execute("PRAGMA cache_size=1");
            sql.execute("PRAGMA cache_spill=ON");
        }
        if (boundary != Boundary.HALT_AFTER_COMMIT) {
            Function.create(connection, "p3_crash_boundary", new Function() {
                @Override protected void xFunc() throws SQLException {
                    try {
                        reached(database, scenario, boundary);
                        if (boundary == Boundary.KILL_AFTER_INDEX) {
                            Path marker = P3FaultFixture.sidecar(database, "kill-ready-writing");
                            Files.writeString(marker, Long.toString(ProcessHandle.current().pid()));
                            Files.move(marker, P3FaultFixture.sidecar(database, "kill-ready"), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                            for (;;) LockSupport.parkNanos(1_000_000_000L);
                        }
                        Runtime.getRuntime().halt(HALT_CODE);
                    } catch (Exception failure) {
                        throw new SQLException("Cannot reach real crash boundary", failure);
                    }
                }
            });
            String trigger = boundary == Boundary.HALT_AFTER_SNAPSHOT ? "AFTER UPDATE ON round_snapshots"
                    : P3FaultFixture.lateTrigger(scenario);
            try (var sql = connection.createStatement()) {
                sql.execute("CREATE TEMP TRIGGER p3_crash " + trigger + " BEGIN "
                        + "INSERT INTO p3_fault_payload VALUES (zeroblob(262144)); SELECT p3_crash_boundary(); END");
            }
        }
        P3FaultFixture.require(request.commit(store) == CommitResult.APPLIED, "writer must commit exactly once");
        if (boundary != Boundary.HALT_AFTER_COMMIT) throw new AssertionError("Crash trigger did not execute");
        reached(database, scenario, boundary);
        Runtime.getRuntime().halt(HALT_CODE);
    }

    private static void reached(Path database, P3FaultFixture.Scenario scenario, Boundary boundary) throws Exception {
        Path wal = database.resolveSibling(database.getFileName() + "-wal");
        long bytes = Files.size(wal);
        P3FaultFixture.require(bytes > 32, "crash must happen with real WAL frames, not an empty/in-memory transaction");
        System.out.println("REACHED " + scenario + " " + boundary + " pid=" + ProcessHandle.current().pid() + " walBytes=" + bytes);
        System.out.flush();
    }
}
