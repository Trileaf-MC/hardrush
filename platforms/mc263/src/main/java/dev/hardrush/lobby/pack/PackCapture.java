package dev.hardrush.lobby.pack;

import dev.hardrush.core.lobby.plan.Box;
import dev.hardrush.core.lobby.plan.Cell;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * World → pack tiles. Each tile is a vanilla structure template whose positions are relative to the tile origin and
 * whose size runs from that origin to the end of the captured content, so a tile placed at its origin restores the
 * world, and a structure block loads it unchanged. One call captures one tile, so a publish job can spread a pack over
 * several ticks. Chunks (and entity sections) must already be loaded.
 */
public final class PackCapture {
    private static final List<net.minecraft.world.level.block.Block> IGNORED = List.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR);

    private PackCapture() {}

    /** The tiles {@code bounds} touches at or above {@code bottomY}, in {@link TileKey#ORDER}. */
    public static List<TileKey> tiles(Box bounds, OptionalInt bottomY) {
        var keys = new ArrayList<TileKey>();
        var from = TileKey.of(bounds.min().x(), bounds.min().y(), bounds.min().z());
        var to = TileKey.of(bounds.max().x(), bounds.max().y(), bounds.max().z());
        for (int y = from.y(); y <= to.y(); y++)
            for (int z = from.z(); z <= to.z(); z++)
                for (int x = from.x(); x <= to.x(); x++) {
                    var key = new TileKey(x, y, z);
                    if (region(key, bounds, bottomY) != null) keys.add(key);
                }
        keys.sort(TileKey.ORDER);
        return List.copyOf(keys);
    }

    /** Empty when the tile holds nothing; such tiles are left out of a pack. */
    public static Optional<CompoundTag> capture(ServerLevel level, TileKey key, Box bounds, OptionalInt bottomY) {
        var region = region(key, bounds, bottomY);
        if (region == null) return Optional.empty();
        var start = new BlockPos(region.min().x(), region.min().y(), region.min().z());
        var template = new StructureTemplate();
        template.fillFromWorld(level, start, new Vec3i(region.sizeX(), region.sizeY(), region.sizeZ()), true, IGNORED);
        var tile = template.save(new CompoundTag());
        return normalize(tile, key, region) ? Optional.of(tile) : Optional.empty();
    }

    /** Every non-empty tile, in {@link TileKey#ORDER}; for callers that need no spreading over ticks. */
    public static Map<TileKey, CompoundTag> captureAll(ServerLevel level, Box bounds, OptionalInt bottomY) {
        var tiles = new LinkedHashMap<TileKey, CompoundTag>();
        for (var key : tiles(bounds, bottomY)) capture(level, key, bounds, bottomY).ifPresent(tile -> tiles.put(key, tile));
        return tiles;
    }

    /** Tile ∩ bounds ∩ (y ≥ bottomY), or null when empty. */
    static Box region(TileKey key, Box bounds, OptionalInt bottomY) {
        var tile = key.box();
        int minY = Math.max(Math.max(tile.min().y(), bounds.min().y()), bottomY.orElse(Integer.MIN_VALUE));
        var min = new Cell(Math.max(tile.min().x(), bounds.min().x()), minY, Math.max(tile.min().z(), bounds.min().z()));
        var max = new Cell(Math.min(tile.max().x(), bounds.max().x()), Math.min(tile.max().y(), bounds.max().y()),
                Math.min(tile.max().z(), bounds.max().z()));
        return min.x() > max.x() || min.y() > max.y() || min.z() > max.z() ? null : new Box(min, max);
    }

    /**
     * Moves template positions from the capture start to the tile origin. {@code fillFromWorld} gathers entities by
     * bounding box, so one straddling a tile edge is picked up by both neighbours; each tile keeps only those whose
     * {@code blockPos} it captured, which puts every entity in exactly one tile, as {@link TileSanitizer} requires.
     * Returns false when nothing is left.
     */
    static boolean normalize(CompoundTag tile, TileKey key, Box region) {
        int dx = region.min().x() - key.originX(), dy = region.min().y() - key.originY(), dz = region.min().z() - key.originZ();
        var blocks = tile.getListOrEmpty("blocks");
        for (var block : blocks) {
            if (block instanceof CompoundTag b) b.put("pos", shift(b.getListOrEmpty("pos"), dx, dy, dz));
        }
        var entities = tile.getListOrEmpty("entities");
        for (var it = entities.iterator(); it.hasNext(); ) {
            var entity = (CompoundTag) it.next();
            var blockPos = entity.getListOrEmpty("blockPos");
            var world = new Cell(region.min().x() + blockPos.getIntOr(0, 0), region.min().y() + blockPos.getIntOr(1, 0),
                    region.min().z() + blockPos.getIntOr(2, 0));
            if (!region.contains(world)) { it.remove(); continue; }
            entity.put("blockPos", shift(blockPos, dx, dy, dz));
            // Placing drops UUIDs anyway; leaving them out keeps the same build publishing the same bytes.
            entity.getCompound("nbt").ifPresent(nbt -> nbt.remove("UUID"));
            var pos = entity.getListOrEmpty("pos");
            var shifted = new ListTag();
            shifted.add(DoubleTag.valueOf(pos.getDoubleOr(0, 0) + dx));
            shifted.add(DoubleTag.valueOf(pos.getDoubleOr(1, 0) + dy));
            shifted.add(DoubleTag.valueOf(pos.getDoubleOr(2, 0) + dz));
            entity.put("pos", shifted);
        }
        var size = new ListTag();
        size.add(IntTag.valueOf(dx + region.sizeX()));
        size.add(IntTag.valueOf(dy + region.sizeY()));
        size.add(IntTag.valueOf(dz + region.sizeZ()));
        tile.put("size", size);
        return !blocks.isEmpty() || !entities.isEmpty();
    }

    private static ListTag shift(ListTag pos, int dx, int dy, int dz) {
        var out = new ListTag();
        out.add(IntTag.valueOf(pos.getIntOr(0, 0) + dx));
        out.add(IntTag.valueOf(pos.getIntOr(1, 0) + dy));
        out.add(IntTag.valueOf(pos.getIntOr(2, 0) + dz));
        return out;
    }
}
