package dev.hardrush.lobby.pack;

import static dev.hardrush.lobby.pack.PackFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.lobby.plan.Box;
import dev.hardrush.core.lobby.plan.Cell;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackFileTest {
    @TempDir Path directory;

    static Map<TileKey, CompoundTag> tiles() {
        var tiles = new HashMap<TileKey, CompoundTag>();
        tiles.put(new TileKey(0, 1, 0), tile());
        var other = tile();
        other.putString("author", "粉丝");
        tiles.put(new TileKey(-1, 1, -1), other);
        return tiles;
    }

    @Test void writeThenReadRoundTrips() throws Exception {
        Path file = directory.toRealPath().resolve("packs").resolve(manifest().fileName());
        PackFile.write(file, manifest(), plan(), tiles());
        var read = PackFile.read(file, LIMITS);
        assertEquals(manifest(), read.manifest());
        assertEquals(plan(), read.plan());
        assertEquals(tiles(), read.tiles());
        assertEquals(List.of(new TileKey(-1, 1, -1), new TileKey(0, 1, 0)), List.copyOf(read.tiles().keySet()));
        try (var listing = Files.list(file.getParent())) { assertEquals(1, listing.count(), "no temporary file left"); }
        assertEquals(64, PackFile.sha256(file).length());
    }

    @Test void equalContentGivesIdenticalBytes() throws Exception {
        Path a = directory.toRealPath().resolve("a.hrlobby"), b = directory.toRealPath().resolve("b.hrlobby");
        PackFile.write(a, manifest(), plan(), tiles());
        PackFile.write(b, manifest(), plan(), new TreeMap<>(TileKey.ORDER.reversed()) {{ putAll(tiles()); }});
        assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(b));
        assertEquals(PackFile.sha256(a), PackFile.sha256(b));
        // Overwriting in place is atomic and keeps the same bytes.
        PackFile.write(a, manifest(), plan(), tiles());
        assertArrayEquals(Files.readAllBytes(b), Files.readAllBytes(a));
    }

    @Test void writeRejectsSymlinkedTarget() throws Exception {
        Path root = directory.toRealPath();
        Path real = Files.createDirectory(root.resolve("real"));
        Path link = Files.createSymbolicLink(root.resolve("link"), real);
        assertThrows(IOException.class, () -> PackFile.write(link.resolve("x.hrlobby"), manifest(), plan(), tiles()));
    }

    @Test void entryNamesOutsideTheFormatAreRejected() throws Exception {
        for (var name : List.of("../x", "tiles/a_b_c.nbt", "tiles/1_2_3.nbt.bak", "PACK.json", "tiles/-0_1_0.nbt")) {
            var message = reject(entries(Map.entry(name, new byte[] {1})));
            assertTrue(message.contains("不认识"), name + ": " + message);
        }
        // Directory entries are ignored.
        read(entries(Map.entry("tiles/", new byte[0])));
    }

    @Test void duplicateEntriesAreRejected() throws Exception {
        var tile = gzip(tile());
        byte[] zip = zip(List.of(Map.entry("pack.json", json(manifest(), plan())),
                Map.entry("tiles/0_1_0.nbt", tile), Map.entry("tiles/0_1_1.nbt", tile)));
        // ZipOutputStream refuses duplicate names, so rename the second entry in place (same length).
        replace(zip, "tiles/0_1_1.nbt", "tiles/0_1_0.nbt");
        assertTrue(reject(zip).contains("重复"));

        zip = zip(List.of(Map.entry("pack.json", json(manifest(), plan())), Map.entry("pack.jsoN", json(manifest(), plan()))));
        replace(zip, "pack.jsoN", "pack.json");
        assertTrue(reject(zip).contains("重复"));
    }

    @Test void missingManifestIsRejected() throws Exception {
        assertTrue(reject(zip(List.of(Map.entry("tiles/0_1_0.nbt", gzip(tile()))))).contains("缺少 pack.json"));
    }

    @Test void tooManyEntriesAreRejected() throws Exception {
        var list = new ArrayList<Map.Entry<String, byte[]>>();
        list.add(Map.entry("pack.json", json(manifest(), plan())));
        for (int i = 0; i < PackFile.MAX_ENTRIES; i++) list.add(Map.entry("d" + i + "/", new byte[0]));
        assertTrue(reject(zip(list)).contains("太多"));
    }

    @Test void oversizedManifestIsRejected() throws Exception {
        var big = new byte[PackFile.MAX_MANIFEST_BYTES + 1];
        Arrays.fill(big, (byte) ' ');
        assertTrue(reject(zip(List.of(Map.entry("pack.json", big)))).contains("1 MB"));
    }

    @Test void oversizedTileIsRejected() throws Exception {
        var tile = tile();
        tile.putByteArray("padding", new byte[PackFile.MAX_TILE_BYTES + 1]);
        var message = reject(entries(Map.entry("tiles/0_1_0.nbt", gzip(tile))));
        assertTrue(message.contains("16 MB"), message);
    }

    @Test void corruptTileIsRejected() throws Exception {
        var message = reject(entries(Map.entry("tiles/0_1_0.nbt", "not gzip".getBytes(StandardCharsets.UTF_8))));
        assertTrue(message.contains("损坏"), message);
    }

    @Test void manifestProblemsAreRejected() throws Exception {
        assertTrue(reject(zip(List.of(Map.entry("pack.json", json(manifest(2, DATA_VERSION, BOUNDS), plan()))))).contains("格式"));
        assertTrue(reject(zip(List.of(Map.entry("pack.json", json(manifest(1, DATA_VERSION + 1, BOUNDS), plan()))))).contains("更新的游戏版本"));
        var wide = new Box(new Cell(0, 0, 0), new Cell(256, 0, 0));
        assertTrue(reject(zip(List.of(Map.entry("pack.json", json(manifest(1, DATA_VERSION, wide), plan(wide)))))).contains("水平上限"));
        assertTrue(reject(zip(List.of(Map.entry("pack.json", "{\"format\": 1".getBytes(StandardCharsets.UTF_8))))).contains("JSON"));
        assertTrue(reject(zip(List.of(Map.entry("pack.json", new byte[] {(byte) 0xff, '{', '}'})))).contains("UTF-8"));
    }

    @Test void boundsSpanningTheIntRangeAreRejected() throws Exception {
        // max - min + 1 wraps to 0 in int arithmetic, which used to pass the horizontal limit.
        var huge = new Box(new Cell(Integer.MIN_VALUE, 0, 0), new Cell(Integer.MAX_VALUE, 0, 0));
        var message = reject(zip(List.of(Map.entry("pack.json", json(manifest(1, DATA_VERSION, huge), plan(huge))))));
        assertTrue(message.contains("世界范围"), message);
        var beyond = new Box(new Cell(30_000_000, 64, 0), new Cell(30_000_010, 64, 10));
        message = reject(zip(List.of(Map.entry("pack.json", json(manifest(1, DATA_VERSION, beyond), plan(beyond))))));
        assertTrue(message.contains("世界范围"), message);
    }

    @Test void absurdTileKeysAreRejectedAsOutsideBounds() throws Exception {
        // 44739242 × 48 + 47 overflows int, so that tile's box() would throw instead of reporting the pack.
        var message = reject(entries(Map.entry("tiles/44739242_1_0.nbt", gzip(tile()))));
        assertTrue(message.contains("不在大厅范围内"), message);
    }

    @Test void tilesOutsideBoundsAreRejected() throws Exception {
        // BOUNDS spans x -24..39, so tile x = 1 (48..95) misses it.
        var message = reject(entries(Map.entry("tiles/1_1_0.nbt", gzip(tile()))));
        assertTrue(message.contains("tiles/1_1_0.nbt"), message);
        read(entries(Map.entry("tiles/-1_1_-1.nbt", gzip(tile()))));
    }

    @Test void readingDoesNotCloseTheCallersStream() throws Exception {
        var closed = new boolean[1];
        var in = new ByteArrayInputStream(entries()) { @Override public void close() { closed[0] = true; } };
        PackFile.read(in, LIMITS);
        assertFalse(closed[0]);
    }

    @SafeVarargs
    private static byte[] entries(Map.Entry<String, byte[]>... extra) throws IOException {
        var list = new ArrayList<Map.Entry<String, byte[]>>();
        list.add(Map.entry("pack.json", json(manifest(), plan())));
        list.addAll(List.of(extra));
        return zip(list);
    }
    private static PackContents read(byte[] zip) throws PackException { return PackFile.read(new ByteArrayInputStream(zip), LIMITS); }
    private static String reject(byte[] zip) {
        return assertThrows(PackException.class, () -> read(zip)).getMessage();
    }
    private static void replace(byte[] data, String from, String to) {
        byte[] a = from.getBytes(StandardCharsets.UTF_8), b = to.getBytes(StandardCharsets.UTF_8);
        int hits = 0;
        outer:
        for (int i = 0; i + a.length <= data.length; i++) {
            for (int j = 0; j < a.length; j++) if (data[i + j] != a[j]) continue outer;
            System.arraycopy(b, 0, data, i, b.length);
            hits++;
        }
        assertTrue(hits >= 1);
    }
}
