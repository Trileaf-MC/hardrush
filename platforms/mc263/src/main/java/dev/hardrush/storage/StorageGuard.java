package dev.hardrush.storage;

import dev.hardrush.HardRushProperties;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import dev.hardrush.world.RoundWorldPaths;

/** A preparation admission check, never a promise that later native writes cannot fail. */
public final class StorageGuard {
    public static final long DEFAULT_MINIMUM_FREE_BYTES = 512L * 1024 * 1024;
    public static final String MINIMUM_FREE_PROPERTY = HardRushProperties.STORAGE_MINIMUM_FREE_BYTES;

    private StorageGuard() {}

    public static void requireWorldPreparation(Path root) throws IOException {
        requireCapacity(root);
        Path directory = root.toAbsolutePath().normalize().resolve("hardrush");
        RoundWorldPaths.checkAncestors(directory, true);
        Files.createDirectories(directory);
        // Check actual create/write/fsync permission on the manifest's filesystem.
        // Only this newly created probe may be removed; no existing data is touched.
        Path probe = Files.createTempFile(directory, ".storage-probe-", ".tmp");
        try {
            try (var channel = FileChannel.open(probe, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(new byte[1024]);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
        } catch (IOException failure) {
            try { Files.deleteIfExists(probe); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        Files.delete(probe);
    }

    public static void requireCapacity(Path root) throws IOException {
        requireCapacity(root, minimumFreeBytes(HardRushProperties.minimumFreeBytes()), path -> {
            var store = Files.getFileStore(path);
            return new Capacity(store.getUsableSpace(), store.isReadOnly());
        });
    }

    static long minimumFreeBytes(String configured) throws IOException {
        if (configured == null) return DEFAULT_MINIMUM_FREE_BYTES;
        try {
            long value = Long.parseLong(configured);
            if (value < 0) throw new NumberFormatException("negative");
            return value;
        } catch (NumberFormatException invalid) {
            throw new IOException(MINIMUM_FREE_PROPERTY + " 必须是非负整数字节数；拒绝准备新世界", invalid);
        }
    }

    static void requireCapacity(Path root, long minimum, CapacityReader reader) throws IOException {
        Path path = root.toAbsolutePath().normalize();
        RoundWorldPaths.checkAncestors(path, true);
        Capacity capacity = reader.read(path);
        if (capacity.readOnly()) throw new IOException("世界存储卷只读；保留资料并暂停世界准备");
        if (capacity.usableBytes() < 0 || capacity.usableBytes() < minimum) {
            throw new IOException("世界存储可用空间 " + capacity.usableBytes() + " bytes，最低储备 "
                    + minimum + " bytes；保留资料并暂停世界准备");
        }
    }

    record Capacity(long usableBytes, boolean readOnly) {}
    @FunctionalInterface interface CapacityReader { Capacity read(Path path) throws IOException; }
}
