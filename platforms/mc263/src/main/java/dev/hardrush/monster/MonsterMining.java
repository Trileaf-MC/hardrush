package dev.hardrush.monster;

import dev.hardrush.protection.ProtectionRuntime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

/**
 * A monster's hands on the world, driven by vanilla mining input while the authoritative body stays in adventure mode:
 * a zombie breaks a wooden door with the native door goal, and a zombie or skeleton digs through the plain ground of
 * {@link MonsterDiggable}, two blocks at a time so the opening fits its own body.
 */
public final class MonsterMining {
    private static final Map<UUID, Progress> ACTIVE = new HashMap<>();
    private static final Map<UUID, ServerPlayer> MINING_INPUT = new HashMap<>();
    /** The tick each body last heard "empty hands only", so a held swing does not repeat it every tick. */
    private static final Map<UUID, Integer> HAND_HINTS = new HashMap<>();
    private static final int HAND_HINT_TICKS = 60;
    private static final Identifier NO_PREDICTED_MINING = Identifier.fromNamespaceAndPath("hardrush", "mining_input");
    private static final int INPUT_GRACE_TICKS = 4;
    /** Vanilla only sends block-breaking progress this close ({@code ServerLevel.destroyBlockProgress}). */
    private static final double CRACK_RANGE_SQR = 1024;

    private MonsterMining() {}

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
        var aim = aim(player);
        if (aim == null || !aim.accepts(player.level(), clicked)) return false;
        miningInput(player, true);
        MonsterRoles.syncNativeContext(player);
        var current = ACTIVE.get(player.getUUID());
        if (current != null && current.valid(player, aim)) return true;
        cancel(player);
        var work = aim.begin(player);
        if (work == null) return false;
        dev.hardrush.lobby.LobbyRuntime.interruptChannel(player, work.label());
        work.start();
        ACTIVE.put(player.getUUID(), work);
        dev.hardrush.notice.Notices.hint(player, work.hint());
        return true;
    }

    /** 26.3 sends a punch every client tick while holding attack on a mineable block, and one per swing otherwise. */
    public static void punch(ServerPlayer player) {
        if (!MonsterRoles.isMonster(player)) return;
        var aim = aim(player);
        if (aim == null) {
            clear(player);
            if (diggableButHolding(player)) handHint(player);
            return;
        }
        if (start(player, aim.primary())) ACTIVE.get(player.getUUID()).lastPunchTick = player.level().getServer().getTickCount();
    }

    public static boolean acceptsAttack(ServerPlayer player, BlockPos pos) {
        var aim = aim(player);
        return aim != null && aim.accepts(player.level(), pos);
    }

    public static void tick(ServerPlayer player) {
        var aim = aim(player);
        miningInput(player, aim != null);
        var work = ACTIVE.get(player.getUUID());
        if (work == null) return; // Aiming enables input, never progress.
        MonsterRoles.syncNativeContext(player);
        if (!work.valid(player, aim) || player.level().getServer().getTickCount() - work.lastPunchTick > INPUT_GRACE_TICKS) {
            cancel(player); return;
        }
        work.tick();
        if (work.finished()) cancel(player);
    }

    public static void clear(ServerPlayer player) {
        cancel(player);
        miningInput(player, false);
    }

    /** Leaving the round or the server: nothing of this body stays behind. */
    public static void forget(ServerPlayer player) {
        clear(player);
        HAND_HINTS.remove(player.getUUID());
    }

    /**
     * A rejected right-click: the survival input mode lets the client predict opening a door or placing a block
     * against the clicked face, so both the clicked block (and a door's other half) and that face are corrected.
     */
    public static void useBlock(ServerPlayer player, BlockPos clicked, Direction face) {
        clear(player);
        resync(player, clicked);
        resync(player, clicked.relative(face));
    }

    /** A cosmetic owner respawn replaces the local player and its input mode, but not this server body. */
    public static void refreshInput(ServerPlayer player) {
        if (MINING_INPUT.get(player.getUUID()) != player) return;
        if (aim(player) == null) clear(player);
        else syncInput(player, GameType.SURVIVAL);
    }

    /** Whether monsters may change the ground here at all: digging, and hollowing burrows. */
    public static boolean griefing(ServerLevel level) { return level.getGameRules().get(GameRules.MOB_GRIEFING); }

    private static void cancel(ServerPlayer player) {
        var work = ACTIVE.remove(player.getUUID());
        if (work != null) work.stop();
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
            // all mining packets are still rejected or handled here on the server.
            var speed = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
            speed.addTransientModifier(new AttributeModifier(NO_PREDICTED_MINING, -1,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            MINING_INPUT.put(player.getUUID(), player);
            syncInput(player, GameType.SURVIVAL);
        }
    }

    private static void syncInput(ServerPlayer player, GameType clientMode) {
        // Zero speed prevents predicted early destruction for soft blocks, haste, water and airborne mining.
        player.connection.send(new ClientboundBundlePacket(List.of(
                new ClientboundUpdateAttributesPacket(player.getId(), List.of(player.getAttribute(Attributes.BLOCK_BREAK_SPEED))),
                new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, clientMode.getId()))));
    }

    private static void resync(ServerPlayer player, BlockPos clicked) {
        if (!player.level().isLoaded(clicked) || !player.isWithinBlockInteractionRange(clicked, 1)) return;
        var upper = upperDoor(player.level(), clicked);
        player.connection.send(new ClientboundBlockUpdatePacket(player.level(), clicked));
        if (upper != null) {
            player.connection.send(new ClientboundBlockUpdatePacket(player.level(), upper));
            player.connection.send(new ClientboundBlockUpdatePacket(player.level(), upper.below()));
        }
    }

    /** Hands free, standing in the world as an acting monster, under mob griefing. */
    private static boolean available(ServerPlayer player) {
        return player.getMainHandItem().isEmpty() && able(player);
    }

    private static boolean able(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE
                && MonsterRoles.nativeModel(player) != null && MonsterRoles.nativeModel(player).level() == player.level()
                && MonsterRoundRuntime.canAct(player) && !player.isUsingItem()
                && player.containerMenu == player.inventoryMenu && griefing(player.level());
    }

    /** A creeper has no hands; every other form digs. */
    private static boolean digger(ServerPlayer player) {
        var form = MonsterRoles.currentForm(player);
        return form != null && form != MonsterForm.CREEPER;
    }

    /** Swinging something at plain ground, not at a creature standing in front of it. */
    private static boolean diggableButHolding(ServerPlayer player) {
        if (player.getMainHandItem().isEmpty() || !able(player) || !digger(player)) return false;
        var hit = hit(player);
        if (hit == null || !diggableAt(player, hit, 0)) return false;
        var eye = player.getEyePosition();
        var end = eye.add(player.getLookAngle().scale(Math.sqrt(player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(hit))) + 1));
        var box = player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1);
        return net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(player, eye, end, box,
                entity -> !entity.isSpectator() && entity.isPickable(), eye.distanceToSqr(end)) == null;
    }

    private static void handHint(ServerPlayer player) {
        int now = player.level().getServer().getTickCount();
        Integer last = HAND_HINTS.get(player.getUUID());
        if (last != null && now - last < HAND_HINT_TICKS && now >= last) return;
        HAND_HINTS.put(player.getUUID(), now);
        dev.hardrush.notice.Notices.hint(player, "空手才能挖");
    }

    private static BlockPos hit(ServerPlayer player) {
        var eye = player.getEyePosition();
        double reach = Math.max(3, player.blockInteractionRange());
        var hit = player.level().clip(new ClipContext(eye, eye.add(player.getLookAngle().scale(reach)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    private static Aim aim(ServerPlayer player) {
        if (!available(player)) return null;
        var pos = hit(player);
        if (pos == null) return null;
        var level = player.level();
        var upper = upperDoor(level, pos);
        if (upper != null) return doorAim(player, upper);
        if (digger(player) && diggableAt(player, pos, 0)) return new DigAim(pos.immutable());
        return null;
    }

    private static Aim doorAim(ServerPlayer player, BlockPos upper) {
        var level = player.level();
        if (!(MonsterRoles.nativeModel(player) instanceof Zombie) || level.getDifficulty() != Difficulty.HARD
                || !upper.closerToCenterThan(player.position(), 2)) return null;
        var top = level.getBlockState(upper);
        var bottom = level.getBlockState(upper.below());
        if (!DoorBlock.isWoodenDoor(top) || !bottom.is(top.getBlock())
                || top.getValue(DoorBlock.HALF) != DoubleBlockHalf.UPPER
                || bottom.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER
                || top.getValue(DoorBlock.OPEN) || bottom.getValue(DoorBlock.OPEN)) return null;
        for (var pos : new BlockPos[]{upper, upper.below()}) if (!mayChange(player, pos)) return null;
        return new DoorAim(upper);
    }

    private static boolean diggableAt(ServerPlayer player, BlockPos pos, double slack) {
        return MonsterDiggable.is(player.level(), pos) && player.isWithinBlockInteractionRange(pos, slack) && mayChange(player, pos);
    }

    private static boolean mayChange(ServerPlayer player, BlockPos pos) {
        var level = player.level();
        return !ProtectionRuntime.rejectInteraction(player, level) && level.mayInteract(player, pos)
                && !level.getServer().isUnderSpawnProtection(level, pos, player);
    }

    private static BlockPos upperDoor(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return null;
        var state = level.getBlockState(pos);
        if (!DoorBlock.isWoodenDoor(state)) return null; // Also includes hand-openable copper doors in 26.3.
        return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.immutable() : pos.above();
    }

    /**
     * The second block of a dig, so the opening fits the digger: beside its own column, a cut at foot or head height
     * opens both; above head height it opens the block below too and keeps the floor as a step up; below the feet,
     * and straight up or down its own column, only the aimed block goes, so no dig leaves a two-deep pit.
     */
    static BlockPos partner(ServerPlayer player, BlockPos primary) {
        var feet = MonsterDiggable.bodyCell(player);
        if (primary.getX() == feet.getX() && primary.getZ() == feet.getZ()) return null;
        int rise = primary.getY() - feet.getY();
        if (rise < 0) return null;
        var partner = rise == 0 ? primary.above() : primary.below();
        return diggableAt(player, partner, 1) ? partner.immutable() : null;
    }

    private sealed interface Aim permits DoorAim, DigAim {
        BlockPos primary();
        boolean accepts(ServerLevel level, BlockPos clicked);
        Progress begin(ServerPlayer player);
    }

    private record DoorAim(BlockPos primary) implements Aim {
        @Override public boolean accepts(ServerLevel level, BlockPos clicked) { return primary.equals(upperDoor(level, clicked)); }
        @Override public Progress begin(ServerPlayer player) { return new DoorWork(player, (Zombie) MonsterRoles.nativeModel(player), primary); }
    }

    private record DigAim(BlockPos primary) implements Aim {
        @Override public boolean accepts(ServerLevel level, BlockPos clicked) { return primary.equals(clicked); }
        /** The pair is fixed here, on the ground; jumping afterwards cannot move it. */
        @Override public Progress begin(ServerPlayer player) { return player.onGround() ? new DigWork(player, primary, partner(player, primary)) : null; }
    }

    private abstract static class Progress {
        final ServerPlayer owner;
        final ServerLevel level;
        final String binding;
        int lastPunchTick;

        Progress(ServerPlayer player) {
            owner = player; level = player.level(); binding = MonsterRoles.binding(player);
            lastPunchTick = level.getServer().getTickCount();
        }

        boolean sameBody(ServerPlayer player) {
            return owner == player && player.level() == level && binding.equals(MonsterRoles.binding(player));
        }

        abstract String label();
        abstract String hint();
        abstract void start();
        abstract boolean valid(ServerPlayer player, Aim aim);
        abstract void tick();
        abstract boolean finished();
        abstract void stop();
    }

    private static final class DoorWork extends Progress {
        private final DoorGoal goal;
        private final BlockState upperState;
        private final BlockState lowerState;
        private int ownerProgress = -1;

        DoorWork(ServerPlayer player, Zombie model, BlockPos upper) {
            super(player);
            goal = new DoorGoal(model, upper);
            upperState = level.getBlockState(upper); lowerState = level.getBlockState(upper.below());
        }

        @Override String label() { return "敲门"; }
        @Override String hint() {
            return "按住左键敲门约 " + dev.hardrush.notice.Notices.key(12) + " 秒 · 松开或移开准星会中断";
        }
        @Override void start() { goal.start(); }

        @Override boolean valid(ServerPlayer player, Aim aim) {
            return sameBody(player) && aim instanceof DoorAim door && MonsterRoles.nativeModel(player) == goal.model()
                    && goal.door().equals(door.primary())
                    && level.getBlockState(goal.door()) == upperState && level.getBlockState(goal.door().below()) == lowerState
                    && goal.canContinueToUse();
        }

        @Override void tick() {
            goal.tick();
            int progress = goal.progress();
            if (progress == ownerProgress) return;
            ownerProgress = progress;
            // ServerLevel excludes the breaker; a distinct ID also survives the client's zero-speed overlay.
            owner.connection.send(new ClientboundBlockDestructionPacket(~owner.getId(), goal.door(), progress));
        }

        @Override boolean finished() { return goal.finished(); }

        @Override void stop() {
            goal.stop();
            owner.connection.send(new ClientboundBlockDestructionPacket(~owner.getId(), goal.door(), -1));
        }
    }

    private static final class DoorGoal extends BreakDoorGoal {
        DoorGoal(Zombie model, BlockPos upper) {
            super(model, difficulty -> difficulty == Difficulty.HARD);
            doorPos = upper; hasDoor = true;
        }
        BlockPos door() { return doorPos; }
        net.minecraft.world.entity.Mob model() { return mob; }
        int progress() { return breakTime * 10 / getDoorBreakTime(); }
        boolean finished() { return breakTime >= getDoorBreakTime(); }
    }

    /**
     * The monster's own digging: a fixed pair of blocks, timed by the slower of the two and broken together without
     * drops. Cracks show on both blocks to everyone nearby, the digger included; a client keeps one crack per breaker
     * ID, so the two blocks use two IDs no entity has.
     */
    private static final class DigWork extends Progress {
        private final List<BlockPos> cells = new ArrayList<>();
        private final List<BlockState> states = new ArrayList<>();
        private final int duration;
        private int elapsed, shown = -1;

        DigWork(ServerPlayer player, BlockPos primary, BlockPos partner) {
            super(player);
            cells.add(primary);
            if (partner != null) cells.add(partner);
            int cap = MonsterDiggable.capTicks(player), longest = 1;
            for (var cell : cells) {
                var state = level.getBlockState(cell);
                states.add(state);
                longest = Math.max(longest, MonsterDiggable.ticks(state, cap));
            }
            duration = longest;
        }

        @Override String label() { return "挖掘"; }
        @Override String hint() {
            return "按住左键挖开，约 " + dev.hardrush.notice.Notices.key(MonsterDiggable.seconds(duration)) + " 秒 · 松开或移开准星会中断";
        }
        @Override void start() { }

        @Override boolean valid(ServerPlayer player, Aim aim) {
            if (!sameBody(player) || !(aim instanceof DigAim dig) || !dig.primary().equals(cells.getFirst())) return false;
            for (int i = 0; i < cells.size(); i++) {
                if (level.getBlockState(cells.get(i)) != states.get(i) || !diggableAt(player, cells.get(i), 1)) return false;
            }
            return true;
        }

        @Override void tick() {
            elapsed++;
            if (elapsed >= duration) {
                for (var cell : cells) level.destroyBlock(cell, false, owner);
                return;
            }
            int progress = elapsed * 10 / duration;
            if (progress != shown) { shown = progress; crack(progress, true); }
        }

        @Override boolean finished() { return elapsed >= duration; }

        @Override void stop() { crack(-1, false); }

        private void crack(int progress, boolean nearby) {
            for (var viewer : level.players()) {
                for (int i = 0; i < cells.size(); i++) {
                    var cell = cells.get(i);
                    if (nearby && viewer.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(cell)) >= CRACK_RANGE_SQR) continue;
                    viewer.connection.send(new ClientboundBlockDestructionPacket(crackId(owner, i), cell, progress));
                }
            }
        }
    }

    /** Entity IDs count up from zero; both values are negative and far apart from each other's range. */
    static int crackId(ServerPlayer owner, int cell) {
        return cell == 0 ? ~owner.getId() : Integer.MIN_VALUE + owner.getId();
    }
}
