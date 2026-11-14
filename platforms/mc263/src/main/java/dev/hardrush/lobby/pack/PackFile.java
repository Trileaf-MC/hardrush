package dev.hardrush.lobby.pack;

import dev.hardrush.core.lobby.plan.LobbyPlan;
import dev.hardrush.core.lobby.plan.PackManifest;
import dev.hardrush.world.RoundWorldPaths;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtAccounterException;
import net.minecraft.nbt.NbtIo;

/**
 * A {@code .hrlobby} zip: {@code pack.json} plus gzip NBT structure templates under {@code tiles/}. Packs come from
 * strangers, so reading trusts nothing in the zip headers: every limit is counted on the bytes actually inflated,
 * entries are processed in memory one at a time, and entry names never become paths. Reading neither upgrades nor
 * sanitizes; callers go read, {@link PackTiles#upgrade}, {@link TileSanitizer}, {@link PackTiles#load}.
 */
public final class PackFile {
    public static final String MANIFEST = "pack.json";
    static final int MAX_ENTRIES = 4096;
    static final int MAX_MANIFEST_BYTES = 1 << 20;
    static final int MAX_TILE_BYTES = 16 << 20;
    static final long MAX_TOTAL_BYTES = 1L << 30;
    /** Zip timestamps are pinned so equal content gives equal bytes; this is the DOS epoch. */
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);

    private PackFile() {}

    public static PackContents read(Path file, PackLimits limits) throws PackException {
        try {
            RoundWorldPaths.checkAncestors(file, false);
            try (var in = new BufferedInputStream(Files.newInputStream(file))) {
                return read(in, limits);
            }
        } catch (IOException e) {
            throw new PackException("无法读取大厅包 " + file.getFileName() + "：" + e.getMessage(), e);
        }
    }

    public static PackContents read(InputStream zip, PackLimits limits) throws PackException {
        var names = new HashSet<String>();
        var tiles = new HashMap<TileKey, CompoundTag>();
        byte[] manifestBytes = null;
        var total = new long[1];
        int entries = 0;
        try (var in = new ZipInputStream(new NonClosing(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (++entries > MAX_ENTRIES) throw new PackException("大厅包里的文件太多，最多 " + MAX_ENTRIES + " 个");
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                if (!names.add(name)) throw new PackException("大厅包里有重复的文件：" + shown(name));
                if (name.equals(MANIFEST)) {
                    manifestBytes = readLimited(in, MAX_MANIFEST_BYTES, total, MANIFEST + " 超过 1 MB");
                    continue;
                }
                var key = TileKey.parseEntryName(name)
                        .orElseThrow(() -> new PackException("大厅包里有不认识的文件：" + shown(name)));
                tiles.put(key, readTile(in, name, total));
            }
        } catch (IOException | RuntimeException e) {
            throw new PackException("大厅包损坏，无法解压：" + e.getMessage(), e);
        }
        if (manifestBytes == null) throw new PackException("大厅包缺少 " + MANIFEST);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(manifestBytes)).toString();
        } catch (CharacterCodingException e) {
            throw new PackException(MANIFEST + " 不是 UTF-8 文本", e);
        }
        var json = LobbyPlanJson.parse(text);
        var manifest = LobbyPlanJson.manifestFromJson(json);
        var problems = manifest.problems(limits.maxHorizontalSize(), limits.serverDataVersion());
        if (!problems.isEmpty()) throw new PackException(String.join("；", problems));
        var plan = LobbyPlanJson.planFromPack(json, manifest.bounds());
        for (var key : tiles.keySet()) {
            if (!key.box().intersects(manifest.bounds()))
                throw new PackException("瓦片 " + key.entryName() + " 不在大厅范围内");
        }
        return new PackContents(manifest, plan, tiles);
    }

    /**
     * Writes atomically: a temporary file in the target's directory, forced to disk, then moved over the target.
     * Entry order, timestamps and compression are fixed, so equal content gives byte-identical files.
     */
    public static void write(Path target, PackManifest manifest, LobbyPlan plan, Map<TileKey, CompoundTag> tiles) throws IOException {
        target = target.toAbsolutePath().normalize();
        RoundWorldPaths.checkAncestors(target, false);
        var directory = target.getParent();
        Files.createDirectories(directory);
        byte[] manifestBytes = LobbyPlanJson.write(LobbyPlanJson.packToJson(manifest, plan)).getBytes(StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                var zip = new ZipOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel)), StandardCharsets.UTF_8);
                var json = new ZipEntry(MANIFEST);
                json.setTimeLocal(ENTRY_TIME);
                zip.putNextEntry(json);
                zip.write(manifestBytes);
                zip.closeEntry();
                for (var key : tiles.keySet().stream().sorted(TileKey.ORDER).toList()) {
                    var bytes = new ByteArrayOutputStream();
                    NbtIo.writeCompressed(tiles.get(key), bytes);
                    // Tiles are gzip already; storing avoids inflating twice on every read.
                    var entry = new ZipEntry(key.entryName());
                    var data = bytes.toByteArray();
                    var crc = new CRC32(); crc.update(data);
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(data.length); entry.setCompressedSize(data.length); entry.setCrc(crc.getValue());
                    entry.setTimeLocal(ENTRY_TIME);
                    zip.putNextEntry(entry);
                    zip.write(data);
                    zip.closeEntry();
                }
                zip.finish();
                zip.flush();
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        try (var in = Files.newInputStream(file)) {
            var buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Counts the zip-level bytes (bounding the inflater) and the gzip-level bytes (what the NBT occupies), and
     * lets {@link NbtAccounter} bound the heap the tags take.
     */
    private static CompoundTag readTile(InputStream entry, String name, long[] total) throws IOException, PackException {
        var tooBig = name + " 解压后超过 16 MB";
        var zipLevel = new Limited(entry, MAX_TILE_BYTES, null, tooBig);
        var nbtLevel = new Limited(new GZIPInputStream(zipLevel), MAX_TILE_BYTES, total, tooBig);
        CompoundTag tag;
        try {
            tag = NbtIo.read(new DataInputStream(new BufferedInputStream(nbtLevel)), NbtAccounter.create(MAX_TILE_BYTES));
        } catch (Limited.Exceeded e) {
            throw new PackException(e.getMessage());
        } catch (RuntimeException e) {
            // NbtIo wraps IO failures inside tags (including ours) in ReportedNbtException; the accounter throws
            // NbtAccounterException. Both are runtime exceptions.
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof Limited.Exceeded || cause instanceof NbtAccounterException)
                    throw new PackException(tooBig, e);
            }
            throw new PackException(name + " 不是有效的 NBT：" + e.getMessage(), e);
        }
        // Drain what follows the tag so trailing data is held to the same limits as the tag itself.
        try {
            zipLevel.transferTo(OutputStream.nullOutputStream());
        } catch (Limited.Exceeded e) {
            throw new PackException(e.getMessage());
        }
        return tag;
    }

    private static byte[] readLimited(InputStream in, int max, long[] total, String tooBig) throws IOException, PackException {
        try {
            return new Limited(in, max, total, tooBig).readAllBytes();
        } catch (Limited.Exceeded e) {
            throw new PackException(e.getMessage());
        }
    }

    /** Entry names come from the file; keep them short and printable in messages. */
    private static String shown(String name) {
        var printable = name.codePoints().map(c -> Character.isISOControl(c) ? '?' : c)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return printable.length() > 80 ? printable.substring(0, 80) + "…" : printable;
    }

    /** Fails once more than {@code max} bytes pass through, and adds every byte to {@code total} if given. */
    private static final class Limited extends FilterInputStream {
        static final class Exceeded extends IOException {
            Exceeded(String message) { super(message); }
        }
        private final long max;
        private final long[] total;
        private final String tooBig;
        private long count;

        Limited(InputStream in, long max, long[] total, String tooBig) {
            super(in); this.max = max; this.total = total; this.tooBig = tooBig;
        }
        @Override public int read() throws IOException {
            int b = super.read();
            if (b >= 0) add(1);
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) add(n);
            return n;
        }
        @Override public long skip(long n) throws IOException {
            // Route skips through read so they are counted too.
            var buffer = new byte[(int) Math.min(n, 8192)];
            long skipped = 0;
            while (skipped < n) {
                int r = read(buffer, 0, (int) Math.min(buffer.length, n - skipped));
                if (r < 0) break;
                skipped += r;
            }
            return skipped;
        }
        @Override public void close() {}
        private void add(long n) throws Exceeded {
            count += n;
            if (count > max) throw new Exceeded(tooBig);
            if (total != null) {
                total[0] += n;
                if (total[0] > MAX_TOTAL_BYTES) throw new Exceeded("大厅包解压后超过 1 GB");
            }
        }
    }

    /** The caller owns the stream it hands us. */
    private static final class NonClosing extends FilterInputStream {
        NonClosing(InputStream in) { super(in); }
        @Override public void close() {}
    }
}
