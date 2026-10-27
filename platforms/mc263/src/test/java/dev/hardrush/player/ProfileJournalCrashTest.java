package dev.hardrush.player;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ProfileJournalCrashTest {
    @TempDir Path directory;

    @ParameterizedTest(name = "force-killed profile writer at {0}, then new JVM recovery")
    @ValueSource(strings = {"PREPARED", "SUSPENDED", "APPLIED", "VERIFIED"})
    void forceTerminationRetainsOriginalAndReusesOnlyACompleteTargetSnapshot(String boundary) throws Exception {
        Path root = directory.resolve(boundary.toLowerCase());
        Path writerLog = directory.resolve(boundary + "-writer.log");
        var writer = process("write", boundary, root, writerLog);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (writer.isAlive() && !read(writerLog).contains("READY " + boundary) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(writer.isAlive() && read(writerLog).contains("READY " + boundary), () -> read(writerLog));
            writer.destroyForcibly();
            assertTrue(writer.waitFor(5, TimeUnit.SECONDS), "Writer exits after forced termination");
            assertNotEquals(0, writer.exitValue(), "Writer must not take a normal shutdown path");
            Path readerLog = directory.resolve(boundary + "-reader.log");
            var reader = process("recover", boundary, root, readerLog);
            try {
                assertTrue(reader.waitFor(10, TimeUnit.SECONDS), () -> read(readerLog));
                assertEquals(0, reader.exitValue(), () -> read(readerLog));
                assertTrue(read(readerLog).contains("PASS recover " + boundary), () -> read(readerLog));
                assertNotEquals(writer.pid(), reader.pid(), "Recovery must use an independent JVM");
                System.out.println("Profile crash " + boundary + ": killedPid=" + writer.pid() + ", recoveryPid=" + reader.pid());
            } finally { if (reader.isAlive()) reader.destroyForcibly().waitFor(5, TimeUnit.SECONDS); }
        } finally { if (writer.isAlive()) writer.destroyForcibly().waitFor(5, TimeUnit.SECONDS); }
    }

    private static Process process(String operation, String boundary, Path root, Path log) throws Exception {
        var paths = new LinkedHashSet<String>();
        for (var type : new Class<?>[]{ProfileCrashProcessFixture.class, ProfileJournal.class}) {
            paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", String.join(File.pathSeparator, paths), ProfileCrashProcessFixture.class.getName(),
                operation, boundary, root.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static String read(Path path) {
        try { return Files.readString(path); }
        catch (Exception error) { return "Cannot read subprocess log: " + error; }
    }
}
