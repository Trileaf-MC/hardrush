package dev.hardrush.p1.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class P1StorageGuardTest {
    @TempDir Path root;

    @BeforeEach void canonicalRoot() throws Exception { root = root.toRealPath(); }

    @Test void reserveAndReadOnlyVolumeRejectPreparationWithoutTouchingExistingData() throws Exception {
        var sentinel = root.resolve("existing-world");
        Files.writeString(sentinel, "preserve-world");
        var shortage = assertThrows(IOException.class, () -> P1StorageGuard.requireCapacity(root, 1024,
                path -> new P1StorageGuard.Capacity(1023, false)));
        assertTrue(shortage.getMessage().contains("1023 bytes"));
        assertTrue(shortage.getMessage().contains("1024 bytes"));
        assertThrows(IOException.class, () -> P1StorageGuard.requireCapacity(root, 1024,
                path -> new P1StorageGuard.Capacity(4096, true)));
        assertThrows(IOException.class, () -> P1StorageGuard.requireCapacity(root, 0,
                path -> new P1StorageGuard.Capacity(-1, false)));
        P1StorageGuard.requireCapacity(root, 1024, path -> new P1StorageGuard.Capacity(1024, false));
        assertEquals("preserve-world", Files.readString(sentinel));
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }

    @Test void invalidReserveDoesNotSilentlyDisableProtection() throws Exception {
        assertEquals(512L * 1024 * 1024, P1StorageGuard.minimumFreeBytes(null));
        assertEquals(0, P1StorageGuard.minimumFreeBytes("0"));
        assertEquals(Long.MAX_VALUE, P1StorageGuard.minimumFreeBytes(Long.toString(Long.MAX_VALUE)));
        for (String invalid : new String[]{"", "-1", "512MiB", "9223372036854775808"}) {
            assertThrows(IOException.class, () -> P1StorageGuard.minimumFreeBytes(invalid));
        }
    }

    @Test void actualWriteProbePreservesFilesAndCannotFollowOwnedDirectorySymlink() throws Exception {
        Files.createDirectories(root.resolve("hardrush"));
        Files.writeString(root.resolve("hardrush/round.json"), "original-manifest");
        P1StorageGuard.requireWorldPreparation(root);
        assertEquals("original-manifest", Files.readString(root.resolve("hardrush/round.json")));
        try (var files = Files.list(root.resolve("hardrush"))) { assertEquals(1, files.count()); }

        var other = Files.createDirectory(root.resolve("another-root"));
        var outside = Files.createDirectory(root.resolve("outside"));
        Files.createSymbolicLink(other.resolve("hardrush"), outside);
        assertThrows(IOException.class, () -> P1StorageGuard.requireWorldPreparation(other));
        try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
    }

    @Test void ioFailureRemainsFailureAndDiagnosticsExplainRealSqliteCodes() {
        assertThrows(IOException.class, () -> P1StorageGuard.requireCapacity(root, 0,
                path -> { throw new IOException("volume unavailable"); }));
        assertTrue(P1StorageFailure.advice(new CompletionException(new SQLException("locked", "", 5))).contains("写锁"));
        assertTrue(P1StorageFailure.advice(new SQLException("readonly", "", 8 | (1 << 8))).contains("写权限"));
        assertTrue(P1StorageFailure.advice(new SQLException("full", "", 13)).contains("不要删除"));
        assertTrue(P1StorageFailure.advice(new SQLException("corrupt", "", 11)).contains("副本"));
        assertTrue(P1StorageFailure.advice(new IOException("unknown")).contains("retry-world"));
    }
}
