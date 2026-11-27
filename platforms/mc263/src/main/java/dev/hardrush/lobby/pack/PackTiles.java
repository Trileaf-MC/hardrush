package dev.hardrush.lobby.pack;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Steps between reading a tile and placing it: read, {@link #upgrade}, sanitize, {@link #load}. */
public final class PackTiles {
    private PackTiles() {}

    public static int currentDataVersion() { return SharedConstants.getCurrentVersion().dataVersion().version(); }

    /**
     * The data version whose DataFixer ({@code BlockStateFieldNamesFix}) renamed palette entries' {@code Name} and
     * {@code Properties} to {@code id} and {@code properties}. Vanilla's own constant,
     * {@code NbtUtils.BLOCK_STATE_ID_PROPERTIES_RENAME_VERSION} (5006 in 26.3), is private.
     */
    static final int PALETTE_RENAME_VERSION = 5006;

    /** Runs the structure DataFixer from {@code fromDataVersion} to this game's version and stamps the result; may edit {@code tile}. */
    public static CompoundTag upgrade(CompoundTag tile, int fromDataVersion) {
        int current = currentDataVersion();
        // Fixes after the rename read the new names; a tile written in the old spelling at such a version (a building
        // file converted by imports) is renamed first, or those fixes would not see its palette.
        if (fromDataVersion >= PALETTE_RENAME_VERSION) canonicalPalettes(tile);
        var upgraded = fromDataVersion >= current ? tile
                : DataFixTypes.STRUCTURE.update(DataFixers.getDataFixer(), tile, fromDataVersion, current);
        canonicalPalettes(upgraded);
        return NbtUtils.addDataVersion(upgraded, current);
    }

    /**
     * Palette entries were {@code {Name, Properties}} until 26.3 renamed them to {@code {id, properties}}; the game now
     * reads only the new names and treats anything else as air. The DataFixer renames them on the way up from before
     * {@link #PALETTE_RENAME_VERSION}, but a tile that claims a later version while still spelling them the old way (a
     * building file converted by {@code imports}, a hand-made pack) gets no rename from it. Renaming here means the
     * fixes and everything after {@link #upgrade} (the sanitizer's block list above all) see one spelling.
     */
    static void canonicalPalettes(CompoundTag tile) {
        tile.getList("palette").ifPresent(PackTiles::canonicalPalette);
        tile.getList("palettes").ifPresent(all -> { for (var p : all) if (p instanceof ListTag list) canonicalPalette(list); });
    }

    private static void canonicalPalette(ListTag palette) {
        for (var tag : palette) {
            if (!(tag instanceof CompoundTag entry)) continue;
            rename(entry, NbtUtils.LEGACY_BLOCK_STATE_ID_TAG, "id");
            rename(entry, NbtUtils.LEGACY_BLOCKSTATE_PROPERTY_TAG, "properties");
        }
    }

    private static void rename(CompoundTag entry, String old, String current) {
        var value = entry.get(old);
        if (value == null) return;
        entry.remove(old);
        if (!entry.contains(current)) entry.put(current, value);
    }

    /** A palette entry's block id in either spelling; air when it has neither. */
    public static String blockName(CompoundTag entry) {
        return entry.getStringOr("id", entry.getStringOr("Name", "minecraft:air"));
    }

    /** A palette entry's properties in either spelling. */
    public static CompoundTag blockProperties(CompoundTag entry) {
        return entry.getCompound("properties").orElseGet(() -> entry.getCompoundOrEmpty("Properties"));
    }

    public static StructureTemplate load(HolderGetter<Block> blocks, CompoundTag tile) {
        var template = new StructureTemplate();
        template.load(blocks, tile);
        return template;
    }
}
