package dev.hardrush.lobby.pack.imports;

import dev.hardrush.lobby.pack.PackException;
import dev.hardrush.lobby.pack.TileKey;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;

/**
 * Collects blocks by position and writes them out as 48³ structure-template tiles. Memory is one int array per
 * touched tile, not one tag per block, until {@link #build}.
 */
final class TileAssembler {
    private static final int N = TileKey.SIZE, CELLS = N * N * N;

    private static final class Tile {
        final int[] cells = new int[CELLS];
        final List<StateEntry> palette = new ArrayList<>();
        final Map<String, Integer> paletteIndex = new HashMap<>();
        final Map<Integer, CompoundTag> blockEntities = new HashMap<>();
    }

    private final int dataVersion;
    private final long maxBlocks;
    private final Map<TileKey, Tile> tiles = new HashMap<>();
    private long blocks;
    private TileKey lastKey;
    private Tile lastTile;

    TileAssembler(int dataVersion, long maxBlocks) {
        this.dataVersion = dataVersion;
        this.maxBlocks = maxBlocks;
    }

    private Tile tile(int x, int y, int z, boolean create) {
        var key = TileKey.of(x, y, z);
        if (key.equals(lastKey)) return lastTile;
        var tile = create ? tiles.computeIfAbsent(key, k -> new Tile()) : tiles.get(key);
        if (tile != null) { lastKey = key; lastTile = tile; }
        return tile;
    }

    private static int cell(int x, int y, int z) {
        return Math.floorMod(x, N) + Math.floorMod(z, N) * N + Math.floorMod(y, N) * N * N;
    }

    /** Places a block (not air; callers filter) and drops any block entity already there. Returns true when it replaced a block. */
    boolean put(int x, int y, int z, StateEntry state) throws PackException {
        var tile = tile(x, y, z, true);
        int index = cell(x, y, z);
        int local = tile.paletteIndex.computeIfAbsent(state.key, k -> { tile.palette.add(state); return tile.palette.size(); });
        boolean replaced = tile.cells[index] != 0;
        tile.cells[index] = local;
        tile.blockEntities.remove(index);
        if (!replaced && ++blocks > maxBlocks)
            throw new PackException("建筑太大：非空气方块超过 " + maxBlocks + " 个");
        return replaced;
    }

    /** Attaches a block entity to an already placed block; false (and dropped) when that position holds nothing. */
    boolean setBlockEntity(int x, int y, int z, CompoundTag nbt) {
        var tile = tile(x, y, z, false);
        int index = cell(x, y, z);
        if (tile == null || tile.cells[index] == 0) return false;
        tile.blockEntities.put(index, nbt);
        return true;
    }

    Map<TileKey, CompoundTag> build() {
        var sorted = new TreeMap<TileKey, Tile>(TileKey.ORDER);
        sorted.putAll(tiles);
        var out = new LinkedHashMap<TileKey, CompoundTag>();
        sorted.forEach((key, tile) -> out.put(key, write(tile)));
        return out;
    }

    private CompoundTag write(Tile tile) {
        var tag = new CompoundTag();
        tag.put("size", ints(N, N, N));
        var palette = new ListTag();
        for (var state : tile.palette) palette.add(state.tag.copy());
        tag.put("palette", palette);
        var blockList = new ListTag();
        for (int i = 0; i < CELLS; i++) {
            if (tile.cells[i] == 0) continue;
            var block = new CompoundTag();
            block.put("pos", ints(i % N, i / (N * N), i / N % N));
            block.putInt("state", tile.cells[i] - 1);
            var nbt = tile.blockEntities.get(i);
            if (nbt != null) block.put("nbt", nbt);
            blockList.add(block);
        }
        tag.put("blocks", blockList);
        tag.put("entities", new ListTag());
        tag.putInt("DataVersion", dataVersion);
        return tag;
    }

    private static ListTag ints(int... values) {
        var list = new ListTag();
        for (int v : values) list.add(IntTag.valueOf(v));
        return list;
    }
}
