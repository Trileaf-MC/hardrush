package dev.hardrush.lobby.pack;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class PackTilesTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** One oak stair facing east, upside down, spelt the pre-26.3 way ({@code {Name, Properties}}). */
    private static CompoundTag oldSpelling(int dataVersion) {
        var properties = new CompoundTag();
        properties.putString("facing", "east");
        properties.putString("half", "top");
        var entry = new CompoundTag();
        entry.putString("Name", "minecraft:oak_stairs");
        entry.put("Properties", properties);
        var palette = new ListTag();
        palette.add(entry);
        var pos = new ListTag();
        for (int i = 0; i < 3; i++) pos.add(IntTag.valueOf(0));
        var block = new CompoundTag();
        block.put("pos", pos);
        block.putInt("state", 0);
        var blocks = new ListTag();
        blocks.add(block);
        var size = new ListTag();
        for (int i = 0; i < 3; i++) size.add(IntTag.valueOf(1));
        var tile = new CompoundTag();
        tile.put("size", size);
        tile.put("palette", palette);
        tile.put("blocks", blocks);
        tile.put("entities", new ListTag());
        tile.putInt("DataVersion", dataVersion);
        return tile;
    }

    /**
     * Every version reads back as the same stair in the new spelling: before the rename the DataFixer renames it, from
     * the rename on {@link PackTiles#upgrade} does, up to and including this game's version.
     */
    @Test void oldSpellingUpgradesFromAnyVersion() {
        int current = PackTiles.currentDataVersion();
        for (int version : new int[] {3900, PackTiles.PALETTE_RENAME_VERSION - 1, PackTiles.PALETTE_RENAME_VERSION,
                PackTiles.PALETTE_RENAME_VERSION + 4, current}) {
            var upgraded = PackTiles.upgrade(oldSpelling(version), version);
            var entry = upgraded.getListOrEmpty("palette").getCompoundOrEmpty(0);
            assertEquals("minecraft:oak_stairs", entry.getStringOr("id", ""), "version " + version + ": " + entry);
            assertFalse(entry.contains("Name") || entry.contains("Properties"), "version " + version + ": " + entry);
            var state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, entry);
            assertEquals(Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST).setValue(StairBlock.HALF, Half.TOP),
                    state, "version " + version);
            assertEquals(current, NbtUtils.getDataVersion(upgraded, 0));
        }
    }

    /** From the rename on, the fixes after it read only the new names: the palette is renamed before they run. */
    @Test void renamedBeforeTheDataFixerFromTheRenameOn() {
        var tile = oldSpelling(PackTiles.PALETTE_RENAME_VERSION + 4);
        PackTiles.upgrade(tile, PackTiles.PALETTE_RENAME_VERSION + 4);
        assertEquals("minecraft:oak_stairs", tile.getListOrEmpty("palette").getCompoundOrEmpty(0).getStringOr("id", ""),
                "the DataFixer's input is already in the new spelling");
        var before = oldSpelling(PackTiles.PALETTE_RENAME_VERSION - 1);
        PackTiles.upgrade(before, PackTiles.PALETTE_RENAME_VERSION - 1);
        assertEquals("minecraft:oak_stairs", before.getListOrEmpty("palette").getCompoundOrEmpty(0).getStringOr("Name", ""),
                "before the rename the DataFixer gets the old spelling it expects");
    }
}
