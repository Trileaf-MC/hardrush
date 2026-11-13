package dev.hardrush.monster;

import dev.hardrush.protection.ProtectionRuntime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.HitResult;

/** Vanilla mining input drives the native door goal; the authoritative body stays in adventure mode. */
public final class MonsterDoorBreaking {
    private static final Map<UUID, DoorGoal> ACTIVE = new HashMap<>();
    private static final Map<UUID, ServerPlayer> MINING_INPUT = new HashMap<>();
    private static final Identifier NO_PREDICTED_MINING = Identifier.fromNamespaceAndPath("hardrush", "door_input");
    private static final int INPUT_GRACE_TICKS = 4;

    private MonsterDoorBreaking() {}

    /** Called inside ServerPlayerGameMode so the network handler still acknowledges prediction sequences. */
    public static void action(ServerPlayer player, BlockPos pos, Action action) {
        switch (action) {
            case START_DESTROY_BLOCK -> {
                if (!start(player, pos)) { cancel(player); resync(player, pos); }
            }
            case ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> {
                // A native zero-speed client never predicts completion. STOP cannot bypass the timer.
                cancel(player);
                resync(player, pos);
            }
            case CHANGE_DESTROY_DIRECTION -> { }
            default -> throw new IllegalArgumentException("Not a mining action: " + action);
        }
    }

    static boolean start(ServerPlayer player, BlockPos clicked) {
        var target = target(player);
        if (target == null || !target.equals(upperDoor(player.level(), clicked))) return false;
        miningInput(player, true);
        MonsterRoles.syncNativeContext(player);
        var current = ACTIVE.get(player.getUUID());
        if (current != null && current.valid(player, target)) return true;
        cancel(player);
        var goal = new DoorGoal(player, (Zombie) MonsterRoles.nativeModel(player), target);
        dev.hardrush.lobby.LobbyRuntime.interruptReturn(player, "敲门");
        goal.start();
        ACTIVE.put(player.getUUID(), goal);
        dev.hardrush.notice.Notices.hint(player, "按住左键敲门约 " + dev.hardrush.notice.Notices.key(12) + " 秒 · 松开或移开准星会中断");
        return true;
    }

    /** 26.3 sends a punch every client tick while holding attack on a mineable block. */
    public static void punch(ServerPlayer player) {
        if (!MonsterRoles.isMonster(player)) return;
        var target = target(player);
        if (target == null) { clear(player); return; }
        if (start(player, target)) ACTIVE.get(player.getUUID()).lastPunchTick = player.level().getServer().getTickCount();
    }

    public static boolean acceptsAttack(ServerPlayer player, BlockPos pos) {
        var target = target(player);
        return target != null && target.equals(upperDoor(player.level(), pos));
    }

    public static void tick(ServerPlayer player) {
        var target = target(player);
        miningInput(player, target != null);
        var goal = ACTIVE.get(player.getUUID());
        if (goal == null) return; // Looking at a door enables input, never progress.
        MonsterRoles.syncNativeContext(player);
        if (!goal.valid(player, target)
                || player.level().getServer().getTickCount() - goal.lastPunchTick > INPUT_GRACE_TICKS) {
            cancel(player); return;
        }
        goal.tick();
        goal.showOwnerProgress();
        if (goal.finished()) cancel(player);
    }

    public static void clear(ServerPlayer player) {
        cancel(player);
        miningInput(player, false);
    }

    public static void useBlock(ServerPlayer player, BlockPos clicked) {
        clear(player);
        // Rejected right-click still needs both predicted-open halves corrected on a vanilla client.
        if (upperDoor(player.level(), clicked) != null) resync(player, clicked);
    }

    /** A cosmetic owner respawn replaces the local player and its input mode, but not this server body. */
    public static void refreshInput(ServerPlayer player) {
        if (MINING_INPUT.get(player.getUUID()) != player) return;
        if (target(player) == null) clear(player);
        else syncInput(player, GameType.SURVIVAL);
    }

    private static void cancel(ServerPlayer player) {
        var goal = ACTIVE.remove(player.getUUID());
        if (goal != null) goal.stop();
    }

    private static void miningInput(ServerPlayer player, boolean enabled) {
        var previous = MINING_INPUT.get(player.getUUID());
        if (enabled && previous == player) return;
        if (previous != null) {
            MINING_INPUT.remove(player.getUUID());
            previous.getAttribute(Attributes.BLOCK_BREAK_SPEED).removeModifier(NO_PREDICTED_MINING);
            syncInput(previous, previous.gameMode.getGameModeForPlayer());
        }
        if (enabled) {
            // Empty hands cannot mine in a vanilla adventure client. Only its input mode changes;
            // all mining packets are still rejected or handled by our server-side door whitelist.
            var speed = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
            speed.addTransientModifier(new AttributeModifier(NO_PREDICTED_MINING, -1,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            MINING_INPUT.put(player.getUUID(), player);
            syncInput(player, GameType.SURVIVAL);
        }
    }

    private static void syncInput(ServerPlayer player, GameType clientMode) {
        // Zero speed prevents predicted early destruction for wood/copper, haste, water and airborne mining.
        player.connection.send(new ClientboundBundlePacket(List.of(
                new ClientboundUpdateAttributesPacket(player.getId(), List.of(player.getAttribute(Attributes.BLOCK_BREAK_SPEED))),
                new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, clientMode.getId()))));
    }

    private static void resync(ServerPlayer player, BlockPos clicked) {
        if (!player.level().hasChunkAt(clicked) || !player.isWithinBlockInteractionRange(clicked, 1)) return;
        var upper = upperDoor(player.level(), clicked);
        player.connection.send(new ClientboundBlockUpdatePacket(player.level(), clicked));
        if (upper != null) {
            player.connection.send(new ClientboundBlockUpdatePacket(player.level(), upper));
            player.connection.send(new ClientboundBlockUpdatePacket(player.level(), upper.below()));
        }
    }

    private static boolean available(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE
                && MonsterRoles.nativeModel(player) instanceof Zombie model && model.level() == player.level()
                && MonsterRoundRuntime.canAct(player) && player.getMainHandItem().isEmpty() && !player.isUsingItem()
                && player.containerMenu == player.inventoryMenu
                && player.level().getDifficulty() == Difficulty.HARD
                && player.level().getGameRules().get(GameRules.MOB_GRIEFING);
    }

    private static BlockPos target(ServerPlayer player) {
        if (!available(player)) return null;
        var level = player.level();
        var eye = player.getEyePosition();
        var hit = level.clip(new ClipContext(eye, eye.add(player.getLookAngle().scale(3)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        var upper = upperDoor(level, hit.getBlockPos());
        if (upper == null || !upper.closerToCenterThan(player.position(), 2)) return null;
        var top = level.getBlockState(upper);
        var bottom = level.getBlockState(upper.below());
        if (!DoorBlock.isWoodenDoor(top) || !bottom.is(top.getBlock())
                || top.getValue(DoorBlock.HALF) != DoubleBlockHalf.UPPER
                || bottom.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER
                || top.getValue(DoorBlock.OPEN) || bottom.getValue(DoorBlock.OPEN)) return null;
        for (var pos : new BlockPos[]{upper, upper.below()}) {
            if (ProtectionRuntime.rejectInteraction(player, level) || !level.mayInteract(player, pos)
                    || level.getServer().isUnderSpawnProtection(level, pos, player)) return null;
        }
        return upper;
    }

    private static BlockPos upperDoor(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return null;
        var state = level.getBlockState(pos);
        if (!DoorBlock.isWoodenDoor(state)) return null; // Also includes hand-openable copper doors in 26.3.
        return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.immutable() : pos.above();
    }

    private static final class DoorGoal extends BreakDoorGoal {
        private final ServerPlayer owner;
        private final ServerLevel level;
        private final String binding;
        private final BlockState upperState;
        private final BlockState lowerState;
        private int ownerProgress = -1;
        private int lastPunchTick;

        DoorGoal(ServerPlayer player, Zombie model, BlockPos upper) {
            super(model, difficulty -> difficulty == Difficulty.HARD);
            owner = player; level = player.level(); binding = MonsterRoles.binding(player);
            doorPos = upper; hasDoor = true;
            upperState = level.getBlockState(upper); lowerState = level.getBlockState(upper.below());
            lastPunchTick = level.getServer().getTickCount();
        }

        boolean finished() { return breakTime >= getDoorBreakTime(); }

        boolean valid(ServerPlayer player, BlockPos target) {
            return owner == player && player.level() == level && MonsterRoles.nativeModel(player) == mob
                    && binding.equals(MonsterRoles.binding(player)) && doorPos.equals(target)
                    && level.getBlockState(doorPos) == upperState && level.getBlockState(doorPos.below()) == lowerState
                    && super.canContinueToUse();
        }

        void showOwnerProgress() {
            int progress = breakTime * 10 / getDoorBreakTime();
            if (progress == ownerProgress) return;
            ownerProgress = progress;
            // ServerLevel excludes the breaker; a distinct ID also survives the client's zero-speed overlay.
            owner.connection.send(new ClientboundBlockDestructionPacket(~owner.getId(), doorPos, progress));
        }

        @Override public void stop() {
            super.stop();
            owner.connection.send(new ClientboundBlockDestructionPacket(~owner.getId(), doorPos, -1));
        }
    }
}
