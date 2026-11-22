package dev.hardrush.monster;

import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.RoundEngine;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The ground a monster may dig through and hollow a burrow in: the plain stone, dirt and gravel a miner meets
 * underground in every dimension, never ores, building blocks or anything holding data. One list serves digging,
 * the burrow search and the rule text.
 */
public final class MonsterDiggable {
    public static final Set<Block> BLOCKS = Set.of(
            Blocks.STONE, Blocks.COBBLESTONE, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE, Blocks.GRANITE, Blocks.DIORITE,
            Blocks.ANDESITE, Blocks.TUFF, Blocks.GRAVEL, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.COARSE_DIRT,
            Blocks.ROOTED_DIRT, Blocks.MYCELIUM,
            Blocks.NETHERRACK, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM, Blocks.BASALT, Blocks.BLACKSTONE,
            Blocks.SOUL_SAND, Blocks.SOUL_SOIL,
            Blocks.END_STONE);
    private static final RulesConfig DEFAULT_RULES = RulesConfig.defaults();

    private MonsterDiggable() {}

    public static boolean is(BlockState state) { return BLOCKS.contains(state.getBlock()); }

    /**
     * The cell a standing body mostly fills. Not {@code blockPosition()}: that floors the feet, so on soul sand
     * (14/16 high), a bottom slab, a path or farmland it names the floor block itself, one cell too low.
     */
    public static BlockPos bodyCell(net.minecraft.world.entity.Entity body) {
        return BlockPos.containing(body.getX(), body.getY() + .5, body.getZ());
    }

    public static boolean is(ServerLevel level, BlockPos pos) { return level.isLoaded(pos) && is(level.getBlockState(pos)); }

    /**
     * Vanilla bare-handed time ({@code BlockBehaviour.getDestroyProgress}: hardness x 30 ticks, or x 100 when the block
     * needs a tool), without held items, effects, water or airborne penalties; never longer than {@code capTicks}.
     */
    public static int ticks(BlockState state, int capTicks) {
        float hardness = state.getBlock().defaultDestroyTime();
        int bare = Math.max(1, Mth.ceil(hardness * (state.requiresCorrectToolForDrops() ? 100 : 30)));
        return Math.min(capTicks, bare);
    }

    /** This round's cap, in ticks. */
    public static int capTicks(ServerPlayer player) {
        long millis = RoundEngine.digMillis(MonsterRoundRuntime.rules(player).orElse(DEFAULT_RULES));
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, (millis + 49) / 50));
    }

    /** "7.5", "2", "0.8": a duration a player reads at a glance. */
    public static String seconds(int ticks) {
        double seconds = ticks / 20.0;
        return seconds == Math.rint(seconds) ? Long.toString((long) seconds) : String.format(java.util.Locale.ROOT, "%.1f", seconds);
    }
}
