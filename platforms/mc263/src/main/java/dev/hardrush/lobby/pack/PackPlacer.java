package dev.hardrush.lobby.pack;

import dev.hardrush.core.lobby.plan.Box;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;

/**
 * Pack tiles → world, one tile per call so installs can spread over ticks; callers go in {@link TileKey#ORDER}
 * ({@link PackContents} already iterates that way) so lower layers stand before what rests on them. Tiles must be
 * upgraded and sanitized already ({@link PackTiles#upgrade}, {@link TileSanitizer}); chunks must be loaded.
 */
public final class PackPlacer {
    /**
     * Copy exact states: tell clients, but skip neighbour shape updates, which would pop a door half whose partner
     * sits in a tile not placed yet. Entity UUIDs need no handling: {@code StructureTemplate} drops them itself.
     */
    public static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private PackPlacer() {}

    /**
     * Places {@code tile} at its origin. Content outside {@code bounds} is skipped: a read pack only guarantees that
     * tiles intersect the bounds. Returns whether the template placed anything.
     */
    public static boolean place(ServerLevel level, TileKey key, CompoundTag tile, Box bounds) {
        var template = PackTiles.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), tile);
        var origin = new BlockPos(key.originX(), key.originY(), key.originZ());
        var settings = new StructurePlaceSettings().setKnownShape(true).setIgnoreEntities(false)
                .setBoundingBox(new BoundingBox(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                        bounds.max().x(), bounds.max().y(), bounds.max().z()));
        return template.placeInWorld(level, origin, origin, settings, level.getRandom(), FLAGS);
    }
}
