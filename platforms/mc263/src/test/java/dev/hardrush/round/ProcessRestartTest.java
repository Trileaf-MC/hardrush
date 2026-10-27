package dev.hardrush.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.round.RoundEngine;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.persistence.SqliteRoundStore;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Each writer and restorer runs in its own JVM, using actual session IO and SQLite. */
final class ProcessRestartTest {
    @TempDir Path directory;

    @BeforeEach void canonicalTemporaryDirectory() throws Exception { directory = directory.toRealPath(); }

    @ParameterizedTest(name = "normal stop and new JVM recovery: {0}")
    @EnumSource(value = RoundPhase.class, names = {
            "PREPARING", "WAITING", "COUNTDOWN", "RUNNING", "SETTLING", "CELEBRATING", "ROTATING"})
    void normalStopRestoresPhaseTimingIdentityAndIsolatedHistoryInNewProcess(RoundPhase phase) throws Exception {
        Path database = directory.resolve(phase.name().toLowerCase() + ".sqlite");
        long writer = runProcess("write", phase, database);
        long reader = runProcess("recover", phase, database);
        assertNotEquals(writer, reader, "Writer must exit before a distinct recovery JVM starts");
        assertTrue(Files.isRegularFile(database));
        System.out.println("Restart matrix " + phase + ": writerPid=" + writer + ", readerPid=" + reader
                + ", both exited with explicit PASS and exit=0");
    }

    private long runProcess(String operation, RoundPhase phase, Path database) throws Exception {
        Path log = directory.resolve(phase.name().toLowerCase() + "-" + operation + ".log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp", fixtureClasspath(),
                RestartProcessFixture.class.getName(), operation, phase.name(), database.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS),
                    () -> "Subprocess exceeded 30 seconds: " + operation + " " + phase + "\n" + readLog(log));
            assertEquals(0, process.exitValue(), () -> readLog(log));
            assertTrue(readLog(log).contains("PASS " + operation + " " + phase), () -> readLog(log));
            return process.pid();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Timed out subprocess must exit after termination");
            }
        }
    }

    private static String fixtureClasspath() throws Exception {
        // Gradle's test worker java.class.path may only contain its worker launcher.
        // Resolve the exact code locations of the standalone fixture and its runtime dependencies.
        var paths = new LinkedHashSet<String>();
        for (var type : new Class<?>[]{RestartProcessFixture.class, RoundSession.class,
                RoundEngine.class, SqliteRoundStore.class, Class.forName("org.sqlite.JDBC"),
                Class.forName("org.slf4j.LoggerFactory")}) {
            paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        return String.join(File.pathSeparator, paths);
    }

    private static String readLog(Path path) {
        try { return Files.readString(path); }
        catch (Exception failure) { return "Cannot read subprocess log: " + failure; }
    }
}
