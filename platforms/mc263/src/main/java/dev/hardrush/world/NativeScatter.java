package dev.hardrush.world;

import dev.hardrush.core.spawn.SpawnScatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Vanilla's first-join/respawn scatter (PlayerSpawnFinder): the {@code respawn_radius} square around a spawn,
 * walked in vanilla's order. Unlike vanilla it never loads chunks, and every landing must also pass
 * {@link RoundWorldBundle#safeStanding}; with no such cell it keeps the exact spawn.
 */
public final class NativeScatter {
    /** Where a player lands and faces; yaw is the destination's native spawn angle. */
    public record Arrival(ServerLevel level, Vec3 position, float yaw) {}

    /** PlayerSpawnFinder.findSpawn's radius: the level's gamerule, shrunk to the world border. */
    public static int radius(ServerLevel level, BlockPos center) {
        int radius = Math.max(0, level.getGameRules().get(GameRules.RESPAWN_RADIUS));
        int toBorder = Mth.floor(level.getWorldBorder().getDistanceToBorder(center.getX(), center.getZ()));
        if (toBorder < radius) radius = toBorder;
        if (toBorder <= 1) radius = 1;
        return radius;
    }

    /** First accepted feet position in vanilla's walk; {@code probe} maps a column to feet or null. */
    public static @Nullable BlockPos first(BlockPos center, int radius, Function<BlockPos, @Nullable BlockPos> probe) {
        for (var offset : SpawnScatter.candidates(radius, ThreadLocalRandom.current())) {
            var feet = probe.apply(center.offset(offset[0], 0, offset[1]));
            if (feet != null) return feet;
        }
        return null;
    }

    /** An opening life: vanilla's scatter around this round's natural spawn. */
    public static Arrival opening(ServerLevel level, BlockPos spawn) {
        var feet = first(spawn, radius(level, spawn), column -> {
            var surface = surface(level, column.getX(), column.getZ());
            return surface != null && RoundWorldBundle.safeStanding(level, surface) ? surface : null;
        });
        return new Arrival(level, Vec3.atBottomCenterOf(feet == null ? spawn : feet), level.getRespawnData().yaw());
    }

    /** PlayerSpawnFinder.getLevelRespawnPos on an already loaded chunk: the top full face, never under fluid. */
    static @Nullable BlockPos surface(ServerLevel level, int x, int z) {
        var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return null;
        int top = level.dimensionType().hasCeiling() ? level.getChunkSource().getGenerator().getSpawnHeight(level)
                : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
        if (top < level.getMinY()) return null;
        int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        if (surface <= top && surface > chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x & 15, z & 15)) return null;
        var pos = new BlockPos.MutableBlockPos();
        for (int y = top + 1; y >= level.getMinY(); y--) {
            pos.set(x, y, z);
            var state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) break;
            if (Block.isFaceFull(state.getCollisionShape(level, pos), Direction.UP)) return pos.above().immutable();
        }
        return null;
    }

    private NativeScatter() {}
}
