package dev.hardrush.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Real writer death and a different recovery JVM for each SQLite atomicity boundary. */
final class StoreCrashRecoveryTest {
    @TempDir Path directory;

    static Stream<Arguments> boundaries() {
        return Stream.of(StorageFaultFixture.Scenario.values()).flatMap(scenario ->
                Stream.of(StoreCrashProcessFixture.Boundary.values()).map(boundary -> Arguments.of(scenario, boundary)));
    }

    @ParameterizedTest(name = "{0}: {1}, then different JVM recovers and replays")
    @MethodSource("boundaries")
    void abruptProcessDeathKeepsGrantConsumptionAndTerminalAtomic(StorageFaultFixture.Scenario scenario,
            StoreCrashProcessFixture.Boundary boundary) throws Exception {
        Path database = directory.toRealPath().resolve("crash.sqlite");
        StorageFaultFixture.prepare(database, scenario);
        Path writerLog = StorageFaultFixture.sidecar(database, "writer.log");
        var writer = launch("write", scenario, boundary, database, writerLog);
        try {
            if (boundary == StoreCrashProcessFixture.Boundary.KILL_AFTER_INDEX) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                Path marker = StorageFaultFixture.sidecar(database, "kill-ready");
                while (!Files.exists(marker) && writer.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(Files.exists(marker), () -> "Writer did not reach uncommitted boundary\n" + readLog(writerLog));
                assertEquals(writer.pid(), Long.parseLong(Files.readString(marker)));
                writer.destroyForcibly();
            }
            assertTrue(writer.waitFor(25, TimeUnit.SECONDS), () -> "Writer did not terminate\n" + readLog(writerLog));
            if (boundary == StoreCrashProcessFixture.Boundary.KILL_AFTER_INDEX) assertNotEquals(0, writer.exitValue(), "force-killed writer must not exit successfully");
            else assertEquals(StoreCrashProcessFixture.HALT_CODE, writer.exitValue(), () -> readLog(writerLog));
            assertTrue(readLog(writerLog).contains("REACHED " + scenario + " " + boundary), () -> readLog(writerLog));
        } finally {
            if (writer.isAlive()) { writer.destroyForcibly(); assertTrue(writer.waitFor(5, TimeUnit.SECONDS)); }
        }
        Path readerLog = StorageFaultFixture.sidecar(database, "reader.log");
        var reader = launch("read", scenario, boundary, database, readerLog);
        try {
            assertNotEquals(writer.pid(), reader.pid(), "Recovery must use a new JVM after writer death");
            assertTrue(reader.waitFor(25, TimeUnit.SECONDS), () -> "Reader did not finish\n" + readLog(readerLog));
            assertEquals(0, reader.exitValue(), () -> readLog(readerLog));
            assertTrue(readLog(readerLog).contains("PASS read " + scenario + " " + boundary), () -> readLog(readerLog));
            System.out.println("P3 SQLite crash " + scenario + " " + boundary + ": writerPid=" + writer.pid()
                    + " writerExit=" + writer.exitValue() + " readerPid=" + reader.pid() + " readerExit=0; "
                    + readLog(writerLog).strip());
        } finally {
            if (reader.isAlive()) { reader.destroyForcibly(); assertTrue(reader.waitFor(5, TimeUnit.SECONDS)); }
        }
    }

    private static Process launch(String operation, StorageFaultFixture.Scenario scenario,
            StoreCrashProcessFixture.Boundary boundary, Path database, Path log) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp", fixtureClasspath(), StoreCrashProcessFixture.class.getName(),
                operation, scenario.name(), boundary.name(), database.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static String fixtureClasspath() throws Exception {
        // The Gradle worker's java.class.path may contain only its launcher; use actual code locations.
        var paths = new LinkedHashSet<String>();
        for (var type : new Class<?>[]{StoreCrashProcessFixture.class, SqliteRoundStore.class,
                dev.hardrush.core.round.RoundEngine.class, Class.forName("org.sqlite.JDBC")}) {
            paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        return String.join(File.pathSeparator, paths);
    }

    private static String readLog(Path path) {
        try { return Files.readString(path); }
        catch (Exception failure) { return "Cannot read subprocess log: " + failure; }
    }
}
