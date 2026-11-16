package dev.hardrush.lobby.pack;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Steps between reading a tile and placing it: read, {@link #upgrade}, sanitize, {@link #load}. */
public final class PackTiles {
    private PackTiles() {}

    public static int currentDataVersion() { return SharedConstants.getCurrentVersion().dataVersion().version(); }

    /** Runs the structure DataFixer from {@code fromDataVersion} to this game's version and stamps the result. */
    public static CompoundTag upgrade(CompoundTag tile, int fromDataVersion) {
        int current = currentDataVersion();
        var upgraded = fromDataVersion >= current ? tile
                : DataFixTypes.STRUCTURE.update(DataFixers.getDataFixer(), tile, fromDataVersion, current);
        return NbtUtils.addDataVersion(upgraded, current);
    }

    public static StructureTemplate load(HolderGetter<Block> blocks, CompoundTag tile) {
        var template = new StructureTemplate();
        template.load(blocks, tile);
        return template;
    }
}
