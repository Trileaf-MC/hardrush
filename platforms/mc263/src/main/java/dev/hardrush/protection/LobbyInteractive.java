package dev.hardrush.protection;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fixed allowlist of interactive lobby decoration that stays usable inside a sealed level: doors, buttons,
 * levers, redstone lamps and the like. Only state changes of the same block are let through; replacing,
 * placing or breaking any block remains refused.
 */
public final class LobbyInteractive {
    private LobbyInteractive() {}

    /** Blocks whose own state may change in a protected level (player input, redstone, entities stepping on them). */
    public static boolean propagates(BlockState state) {
        if (manual(state)) return true;
        Block block = state.getBlock();
        return state.is(BlockTags.PRESSURE_PLATES) || block instanceof CopperBulbBlock
                || block == Blocks.REDSTONE_WIRE || block == Blocks.REPEATER || block == Blocks.COMPARATOR
                || block == Blocks.REDSTONE_LAMP || block == Blocks.REDSTONE_TORCH || block == Blocks.REDSTONE_WALL_TORCH
                || block == Blocks.TARGET || block == Blocks.DAYLIGHT_DETECTOR
                || block == Blocks.TRIPWIRE || block == Blocks.TRIPWIRE_HOOK;
    }

    /**
     * Blocks a player may operate with a plain right click. Iron doors and trapdoors are included on purpose:
     * vanilla ignores hand clicks on them, so letting the click through has no effect.
     */
    public static boolean manual(BlockState state) {
        Block block = state.getBlock();
        return state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES)
                || state.is(BlockTags.BUTTONS) || block == Blocks.LEVER || block == Blocks.NOTE_BLOCK || block == Blocks.BELL;
    }
}
