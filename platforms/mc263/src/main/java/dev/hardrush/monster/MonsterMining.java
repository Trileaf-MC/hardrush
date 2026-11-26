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
 * a zombie breaks a wooden door with the native door goal, and a zombie or skeleton digs whatever
 * {@link MonsterDiggable} allows, two blocks at a time so the opening fits its own body. Held items dig at the same
 * bare-handed speed; the client's survival input turns on only while that dig is actually held.
 */
public final class MonsterMining {
    private static final Map<UUID, Progress> ACTIVE = new HashMap<>();
    private static final Map<UUID, ServerPlayer> MINING_INPUT = new HashMap<>();
    /** Main hand that armed survival input. Empty hands arm from aim alone and never land here. */
    private static final Map<UUID, net.minecraft.world.item.ItemStack> HELD_LATCH = new HashMap<>();
    /** The tick each body last heard why a swing failed, so a held swing does not repeat it every tick. */
    private static final Map<UUID, Integer> HAND_HINTS = new HashMap<>();
    private static final int HAND_HINT_TICKS = 60;
    private static final Identifier NO_PREDICTED_MINING = Identifier.fromNamespaceAndPath("hardrush", "mining_input");
    private static final int INPUT_GRACE_TICKS = 4;
    /**
     * After a held item's first swing arms survival input, the client streams punches only once that mode change has
     * reached it, a round trip later: wait this long for the stream before giving up.
     */
    private static final int ARMING_GRACE_TICKS = 20;
    /** Vanilla only sends block-breaking progress this close ({@code ServerLevel.destroyBlockProgress}). */
    private static final double CRACK_RANGE_SQR = 1024;

    private MonsterMining() {}

    /**
     * Called inside ServerPlayerGameMode so the network handler still acknowledges prediction sequences.
     * A release is only noted here and settled in {@link #tick}: the server handles every queued packet before it
     * ticks ({@code MinecraftServer.processPacketsAndTick}), and a client whose held item changed components (a bow's
     * refreshed bar, a glowing compass) restarts the same break as ABORT then START within one batch.
     */
    public static void action(ServerPlayer player, BlockPos pos, Action action) {
        switch (action) {
            case START_DESTROY_BLOCK -> {
                if (!start(player, pos)) { cancel(player); releaseHeldInput(player); resync(player, pos); return; }
                var work = ACTIVE.get(player.getUUID());
                work.released = null;
                work.confirmed = true;
            }
            case ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> {
                // A native zero-speed client never predicts completion. STOP cannot bypass the timer.
                var work = ACTIVE.get(player.getUUID());
                if (work != null) { work.released = pos.immutable(); return; }
                releaseHeldInput(player);
                resync(player, pos);
            }
            case CHANGE_DESTROY_DIRECTION -> { }
            default -> throw new IllegalArgumentException("Not a mining action: " + action);
        }
    }

    static boolean start(ServerPlayer player, BlockPos clicked) {
        var aim = aim(player);
        if (aim == null || !aim.accepts(player.level(), clicked)) return false;
        if (player.getMainHandItem().isEmpty()) miningInput(player, true);
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
            var reason = refusal(player);
            if (reason != null) handHint(player, reason);
            return;
        }
        // Adventure still sends the swing. Survival input waits for a break that actually started, so a held egg
        // or bow keeps its right-click until the monster is holding the break. Doors arm the same way: a knock
        // needs the stream of punches an adventure client only sends once the block looks mineable.
        boolean holding = !player.getMainHandItem().isEmpty();
        var before = ACTIVE.get(player.getUUID());
        if (start(player, aim.primary())) {
            boolean arming = holding && !held(player);
            if (holding) holdInput(player);
            var work = ACTIVE.get(player.getUUID());
            work.lastPunchTick = player.level().getServer().getTickCount();
            work.grace = arming ? ARMING_GRACE_TICKS : INPUT_GRACE_TICKS;
            // A lone click with a held item arms input but is not yet a held break: an adventure client sends
            // nothing when it lets go. Time starts with the next punch, or the START a held button sends.
            work.confirmed = !(arming && work != before);
        }
    }

    public static boolean acceptsAttack(ServerPlayer player, BlockPos pos) {
        var aim = aim(player);
        return aim != null && aim.accepts(player.level(), pos);
    }

    public static void tick(ServerPlayer player) {
        var aim = aim(player);
        if (player.getMainHandItem().isEmpty()) {
            HELD_LATCH.remove(player.getUUID());
            miningInput(player, aim != null);
        } else if (aim == null || !held(player)) {
            HELD_LATCH.remove(player.getUUID());
            miningInput(player, false);
        } else miningInput(player, true);
        var work = ACTIVE.get(player.getUUID());
        if (work == null) return; // Aiming enables input, never progress.
        MonsterRoles.syncNativeContext(player);
        var released = work.released;
        if (released != null || !work.valid(player, aim)
                || player.level().getServer().getTickCount() - work.lastPunchTick > work.grace) {
            cancel(player);
            releaseHeldInput(player);
            if (released != null) resync(player, released);
            return;
        }
        if (!work.confirmed) return;
        work.tick();
        if (work.finished()) { cancel(player); releaseHeldInput(player); }
    }

    public static void clear(ServerPlayer player) {
        cancel(player);
        HELD_LATCH.remove(player.getUUID());
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
        var aim = aim(player);
        boolean armed = player.getMainHandItem().isEmpty() || held(player);
        if (aim == null || !armed) clear(player);
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
            // An adventure client will not stream mining packets. Only its input mode changes;
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

    /** Standing in the world as an acting monster, under mob griefing. The main hand may hold anything. */
    private static boolean available(ServerPlayer player) { return able(player); }

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

    /** Why this swing cannot open the block it hit, or null when the monster should hear nothing. */
    private static String refusal(ServerPlayer player) {
        if (!able(player)) return null;
        var pos = hit(player);
        if (pos == null || !clearOfCreatures(player, pos) || !mayChange(player, pos)) return null;
        if (!digger(player)) return "苦力怕挖不动 · 引爆能炸开方块";
        var verdict = MonsterDiggable.verdict(player, pos);
        if (verdict == null || verdict == MonsterDiggable.Verdict.DIGGABLE) return null;
        return switch (verdict) {
            case DOOR -> doorRefusal(player, pos);
            case CONTAINER -> "里面装着东西，挖不动";
            case ORE -> "矿石挖不动";
            case TOO_HARD -> "太硬了，挖不动";
            case DIGGABLE -> null;
        };
    }

    /** Wooden doors name who can knock; an iron door is shut to everyone. A knock that merely missed its range stays quiet. */
    private static String doorRefusal(ServerPlayer player, BlockPos pos) {
        // The floor under a door answers for the door it carries.
        var state = player.level().getBlockState(pos);
        if (!(state.getBlock() instanceof DoorBlock)) state = player.level().getBlockState(pos.above());
        if (!DoorBlock.isWoodenDoor(state)) return "铁门敲不开";
        if (!(MonsterRoles.nativeModel(player) instanceof Zombie)) return "只有僵尸能敲开木门";
        if (player.level().getDifficulty() != Difficulty.HARD) return "困难难度下僵尸才能敲开木门";
        return null;
    }

    /** A creature between the eyes and the block means the swing was an attack, not a failed dig. */
    private static boolean clearOfCreatures(ServerPlayer player, BlockPos block) {
        var eye = player.getEyePosition();
        var end = eye.add(player.getLookAngle().scale(Math.sqrt(player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(block))) + 1));
        var box = player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1);
        return net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(player, eye, end, box,
                entity -> !entity.isSpectator() && entity.isPickable(), eye.distanceToSqr(end)) == null;
    }

    private static void handHint(ServerPlayer player, String text) {
        int now = player.level().getServer().getTickCount();
        Integer last = HAND_HINTS.get(player.getUUID());
        if (last != null && now - last < HAND_HINT_TICKS && now >= last) return;
        HAND_HINTS.put(player.getUUID(), now);
        dev.hardrush.notice.Notices.hint(player, text);
    }

    private static void holdInput(ServerPlayer player) {
        HELD_LATCH.put(player.getUUID(), player.getMainHandItem().copy());
        miningInput(player, true);
    }

    private static boolean held(ServerPlayer player) {
        var stack = HELD_LATCH.get(player.getUUID());
        return stack != null && net.minecraft.world.item.ItemStack.matches(stack, player.getMainHandItem());
    }

    /** Releasing the break, or changing hands, returns a held item to adventure input. Empty hands stay aimed. */
    private static void releaseHeldInput(ServerPlayer player) {
        if (player.getMainHandItem().isEmpty()) return;
        HELD_LATCH.remove(player.getUUID());
        miningInput(player, false);
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
        return MonsterDiggable.verdict(player, pos) == MonsterDiggable.Verdict.DIGGABLE
                && player.isWithinBlockInteractionRange(pos, slack) && mayChange(player, pos);
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
        /** Ticks without a punch before the break is abandoned. */
        int grace = INPUT_GRACE_TICKS;
        /** Where the client let go this batch; a START for the same break clears it before the tick settles it. */
        BlockPos released;
        /** Whether the break is actually held. Only a held item's lone first swing starts unconfirmed, without time. */
        boolean confirmed = true;

        final net.minecraft.world.item.ItemStack hand;

        Progress(ServerPlayer player) {
            owner = player; level = player.level(); binding = MonsterRoles.binding(player);
            hand = player.getMainHandItem().copy();
            lastPunchTick = level.getServer().getTickCount();
        }

        boolean sameBody(ServerPlayer player) {
            return owner == player && player.level() == level && binding.equals(MonsterRoles.binding(player))
                    && net.minecraft.world.item.ItemStack.matches(hand, player.getMainHandItem());
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
                longest = Math.max(longest, MonsterDiggable.ticks(level, cell, state, cap));
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
