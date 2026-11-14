package dev.hardrush.lobby.pack.imports;

import dev.hardrush.lobby.pack.PackException;
import dev.hardrush.lobby.pack.TileKey;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * A converted building file: structure-template tiles in coordinates relative to the building's minimum corner,
 * still at the file's data version and not yet sanitized. {@code notes} says what the conversion left out.
 */
public record ImportedStructure(int dataVersion, int sizeX, int sizeY, int sizeZ,
                                Map<TileKey, CompoundTag> tiles, List<String> notes) {
    public ImportedStructure {
        tiles = Map.copyOf(tiles);
        notes = List.copyOf(notes);
    }

    /** Moves the whole structure so its minimum corner sits at the world position, and re-cuts it into world-aligned tiles. */
    public Map<TileKey, CompoundTag> placedAt(int originX, int originY, int originZ) {
        var assembler = new TileAssembler(dataVersion, Long.MAX_VALUE);
        try {
            for (var entry : tiles.entrySet()) {
                var key = entry.getKey();
                var palette = entry.getValue().getListOrEmpty("palette");
                var states = new StateEntry[palette.size()];
                for (int i = 0; i < states.length; i++) states[i] = StateEntry.fromTag(palette.getCompoundOrEmpty(i));
                for (var tag : entry.getValue().getListOrEmpty("blocks")) {
                    if (!(tag instanceof CompoundTag block)) continue;
                    ListTag pos = block.getListOrEmpty("pos");
                    int x = originX + key.originX() + pos.getIntOr(0, 0);
                    int y = originY + key.originY() + pos.getIntOr(1, 0);
                    int z = originZ + key.originZ() + pos.getIntOr(2, 0);
                    assembler.put(x, y, z, states[block.getIntOr("state", 0)]);
                    block.getCompound("nbt").ifPresent(nbt -> assembler.setBlockEntity(x, y, z, nbt.copy()));
                }
            }
        } catch (PackException unreachable) {
            throw new IllegalStateException("unbounded assembler refused a block", unreachable);
        }
        return assembler.build();
    }
}
