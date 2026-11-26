package dev.hardrush.monster;

import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.RoundEngine;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * What a monster's hands may do to a block, and which ground a burrow may be hollowed from.
 * Digging rejects doors, containers, ores and blocks a bare hand cannot finish within {@code monster.digMaxMillis};
 * everything else uses bare-handed time. Burrows still require {@link #NATURAL} itself, not an infested disguise.
 */
public final class MonsterDiggable {
    /** Plain ground a burrow may be cut from. Digging is wider; only this set is capped by {@code monster.digMillis}. */
    public static final Set<Block> NATURAL = Set.of(
            Blocks.STONE, Blocks.COBBLESTONE, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE, Blocks.GRANITE, Blocks.DIORITE,
            Blocks.ANDESITE, Blocks.TUFF, Blocks.GRAVEL, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.COARSE_DIRT,
            Blocks.ROOTED_DIRT, Blocks.MYCELIUM,
            Blocks.NETHERRACK, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM, Blocks.BASALT, Blocks.BLACKSTONE,
            Blocks.SOUL_SAND, Blocks.SOUL_SOIL,
            Blocks.END_STONE);
    private static final RulesConfig DEFAULT_RULES = RulesConfig.defaults();

    /** Why a swing cannot open this block. The first match is the one a monster is told. */
    public enum Verdict { DIGGABLE, DOOR, CONTAINER, ORE, TOO_HARD }

    private MonsterDiggable() {}

    public static boolean natural(BlockState state) { return NATURAL.contains(state.getBlock()); }

    /**
     * The cell a standing body mostly fills. Not {@code blockPosition()}: that floors the feet, so on soul sand
     * (14/16 high), a bottom slab, a path or farmland it names the floor block itself, one cell too low.
     */
    public static BlockPos bodyCell(net.minecraft.world.entity.Entity body) {
        return BlockPos.containing(body.getX(), body.getY() + .5, body.getZ());
    }

    public static boolean natural(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && natural(level.getBlockState(pos));
    }

    /** This round's verdict for the player's hands. */
    public static Verdict verdict(ServerPlayer player, BlockPos pos) {
        return verdict(player.level(), pos, capTicks(player), maxTicks(player));
    }

    /**
     * Doors (and the block a door stands on, which vanilla {@code DoorBlock.canSurvive} needs), then containers, then
     * ores (the {@code ores} tag already covers the eight ore tags and nether quartz), then anything unbreakable or
     * whose actual dig time, after the natural cap (itself no longer than {@code maxTicks}), exceeds {@code maxTicks}. Infested blocks are judged as their
     * host, so the disguise is not given away by a different answer. Air is nothing to dig and has no verdict.
     */
    public static Verdict verdict(ServerLevel level, BlockPos pos, int capTicks, int maxTicks) {
        if (!level.isLoaded(pos)) return null;
        var state = level.getBlockState(pos);
        if (state.isAir()) return null;
        if (state.getBlock() instanceof DoorBlock) return Verdict.DOOR;
        var above = level.getBlockState(pos.above());
        if (above.getBlock() instanceof DoorBlock && above.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) return Verdict.DOOR;
        if (state.hasBlockEntity()) return Verdict.CONTAINER;
        var counted = counted(state);
        if (counted.is(BlockTags.ORES) || counted.is(Blocks.ANCIENT_DEBRIS)) return Verdict.ORE;
        // Natural ground is never held longer than the limit either, so burrow ground always stays diggable.
        if (ticks(level, pos, state, Math.min(capTicks, maxTicks)) > maxTicks) return Verdict.TOO_HARD;
        return Verdict.DIGGABLE;
    }

    /**
     * Vanilla bare-handed time ({@code BlockBehaviour.getDestroyProgress}: hardness x 30 ticks, or x 100 when the block
     * needs a tool), without held items, effects, water or airborne penalties. Only natural ground is then capped.
     */
    public static int ticks(ServerLevel level, BlockPos pos, BlockState state, int capTicks) {
        var counted = counted(state);
        int bare = bareTicks(level, pos, counted);
        return natural(counted) ? Math.min(capTicks, bare) : bare;
    }

    /** This round's cap for natural ground, in ticks; a shorter {@code monster.digMaxMillis} shortens it too. */
    public static int capTicks(ServerPlayer player) {
        long millis = RoundEngine.digMillis(MonsterRoundRuntime.rules(player).orElse(DEFAULT_RULES));
        return Math.min(millisToTicks(millis), maxTicks(player));
    }

    /** Blocks slower than this bare-handed time cannot be dug. */
    public static int maxTicks(ServerPlayer player) {
        long millis = RoundEngine.digMaxMillis(MonsterRoundRuntime.rules(player).orElse(DEFAULT_RULES));
        return millisToTicks(millis);
    }

    /** "7.5", "2", "0.8": a duration a player reads at a glance. */
    public static String seconds(int ticks) {
        double seconds = ticks / 20.0;
        return seconds == Math.rint(seconds) ? Long.toString((long) seconds) : String.format(java.util.Locale.ROOT, "%.1f", seconds);
    }

    /** Infested blocks take their host's time and tags; the block in the world is still the one that breaks. */
    private static BlockState counted(BlockState state) {
        return state.getBlock() instanceof InfestedBlock infested ? infested.getHostBlock().defaultBlockState() : state;
    }

    private static int bareTicks(ServerLevel level, BlockPos pos, BlockState state) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) return Integer.MAX_VALUE;
        return Math.max(1, Mth.ceil(hardness * (state.requiresCorrectToolForDrops() ? 100 : 30)));
    }

    private static int millisToTicks(long millis) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, (millis + 49) / 50));
    }
}
