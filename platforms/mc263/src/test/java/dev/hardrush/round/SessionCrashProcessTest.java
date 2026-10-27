package dev.hardrush.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.round.RoundEngine;
import dev.hardrush.core.round.RoundSnapshotCodec;
import dev.hardrush.persistence.SqliteRoundStore;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SIGKILL-style process death after a real WAL commit, before its Session callback. */
final class SessionCrashProcessTest {
    @TempDir Path temporary;

    @ParameterizedTest(name = "forced process termination after durable {0}, before callback")
    @ValueSource(strings = {"AWARD", "CONSUME", "TERMINAL"})
    void committedIdentityAndTerminalSurviveForcedDeathWithoutReplayingEffects(String boundary) throws Exception {
        Path directory = temporary.toRealPath();
        Path database = directory.resolve(boundary.toLowerCase() + ".sqlite");
        Path writerLog = directory.resolve(boundary.toLowerCase() + "-writer.log");
        Process writer = start("write", boundary, database, writerLog);
        try {
            Path marker = SessionCrashProcessFixture.companion(database, "ready");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(marker) && writer.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(marker), () -> "Writer did not reach the committed boundary:\n" + read(writerLog));
            assertTrue(writer.isAlive(), "Writer must remain alive until this parent forcibly terminates it");
            assertEquals(boundary + ":" + writer.pid(), Files.readString(marker));
            var memory = RoundSnapshotCodec.decode(Files.readAllBytes(SessionCrashProcessFixture.companion(database, "memory")));
            var durable = RoundSnapshotCodec.decode(Files.readAllBytes(SessionCrashProcessFixture.companion(database, "durable")));
            assertEquals(memory.version() + 1, durable.version(), "SQLite committed while the engine still has its prior version");
            assertFalse(durable.cleanShutdown());
            assertTrue(Files.size(Path.of(database + "-wal")) > 32, "The live writer must have an actual nonempty SQLite WAL");
            writer.destroyForcibly();
            assertTrue(writer.waitFor(5, TimeUnit.SECONDS), "Forced writer must exit before recovery starts");
            assertNotEquals(0, writer.exitValue(), "Writer must not complete a normal stop");
            assertFalse(read(writerLog).contains("PASS recover"));

            Path readerLog = directory.resolve(boundary.toLowerCase() + "-reader.log");
            Process reader = start("recover", boundary, database, readerLog);
            try {
                assertNotEquals(writer.pid(), reader.pid(), "Recovery must use a distinct JVM");
                assertTrue(reader.waitFor(20, TimeUnit.SECONDS), () -> "Recovery exceeded timeout:\n" + read(readerLog));
                assertEquals(0, reader.exitValue(), () -> read(readerLog));
                assertTrue(read(readerLog).contains("PASS recover " + boundary), () -> read(readerLog));
                System.out.println("Session forced recovery " + boundary + ": writerPid=" + writer.pid()
                        + ", forcedExit=" + writer.exitValue() + ", readerPid=" + reader.pid() + ", recovery=PASS");
            } finally {
                if (reader.isAlive()) {
                    reader.destroyForcibly();
                    assertTrue(reader.waitFor(5, TimeUnit.SECONDS));
                }
            }
        } finally {
            if (writer.isAlive()) {
                writer.destroyForcibly();
                assertTrue(writer.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    private static Process start(String operation, String boundary, Path database, Path log) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp", fixtureClasspath(),
                SessionCrashProcessFixture.class.getName(), operation, boundary, database.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static String fixtureClasspath() throws Exception {
        var paths = new LinkedHashSet<String>();
        for (var type : new Class<?>[]{SessionCrashProcessFixture.class, RoundSession.class, RoundEngine.class,
                SqliteRoundStore.class, Class.forName("org.sqlite.JDBC"), Class.forName("org.slf4j.LoggerFactory")}) {
            paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        return String.join(File.pathSeparator, paths);
    }

    private static String read(Path path) {
        try { return Files.readString(path); }
        catch (Exception error) { return "Cannot read fixture log: " + error; }
    }
}
