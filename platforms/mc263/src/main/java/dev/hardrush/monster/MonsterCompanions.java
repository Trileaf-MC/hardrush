package dev.hardrush.monster;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.monster.mixin.CompanionMobAccessor;
import dev.hardrush.monster.mixin.CompanionSpawnAccessor;
import dev.hardrush.monster.mixin.MonsterMobAccessor;
import dev.hardrush.combat.CombatOriginHolder;
import dev.hardrush.combat.CombatRuntime;
import dev.hardrush.combat.MonsterSourceHolder;
import dev.hardrush.combat.MonsterCombatRuntime;
import dev.hardrush.protection.ProtectionRuntime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Spawner;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Native mobs with a persistent sortie owner: zombies hatched from the role's eggs, and creatures a
 * soulcatcher releases as companions. Ordinary equipment, damage and death stay vanilla.
 */
public final class MonsterCompanions {
    private static final String OWNER = "hardrush.summon-owner=";
    private static final String EVENT = "hardrush.summon-event=";
    private static final String BINDING = "hardrush.companion-binding=";
    private static final String COMMAND = "hardrush.companion-target=";
    private static final String DISMISSING = "hardrush.companion-dismissing";
    /** The role egg a companion hatched from; descendants never inherit it, so only that body folds back into the egg. */
    private static final String HATCHED = "hardrush.companion-egg=";
    /** Twelve blocks, the distance at which a tamed wolf gives up walking and teleports to its owner. */
    private static final double CATCH_UP_DISTANCE_SQR = 144;
    /** A tamed wolf's landing ring: within three blocks of the owner but not right beside them, one block up or down. */
    private static final List<BlockPos> CATCH_UP_RING = catchUpRing();
    private static List<BlockPos> catchUpRing() {
        var ring = new ArrayList<BlockPos>();
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = -1; y <= 1; y++)
            if (Math.abs(x) >= 2 || Math.abs(z) >= 2) ring.add(new BlockPos(x, y, z));
        return List.copyOf(ring);
    }
    private static final Map<MinecraftServer, Map<UUID, Mob>> LOADED = new IdentityHashMap<>();
    private static final ThreadLocal<Deque<Entity>> INFECTION = ThreadLocal.withInitial(ArrayDeque::new);
    private static boolean initialized;

    private MonsterCompanions() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Mob mob && owner(mob) != null) {
                LOADED.computeIfAbsent(level.getServer(), ignored -> new HashMap<>()).put(mob.getUUID(), mob);
                if (activeOwner(mob) == null || isDismissing(mob)) dismiss(mob);
                else configure(mob);
            } else if (entity instanceof Vex vex && owner(vex) == null && vex.getOwner() != null && owner(vex.getOwner()) != null) {
                // An evoker companion's spell joins its vexes to the same sortie; the vex copies its evoker's target natively.
                inherit(vex.getOwner(), vex);
                LOADED.computeIfAbsent(level.getServer(), ignored -> new HashMap<>()).put(vex.getUUID(), vex);
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            var loaded = LOADED.get(level.getServer());
            if (loaded != null && entity instanceof Mob mob) loaded.remove(mob.getUUID(), mob);
        });
        ServerTickEvents.END_SERVER_TICK.register(MonsterCompanions::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LOADED.remove(server));
        ServerLivingEntityEvents.AFTER_DAMAGE.register((target, source, base, taken, blocked) -> {
            if (taken <= 0 || !(target.level() instanceof ServerLevel level)) return;
            Entity attacker = source.getEntity();
            ServerPlayer player = attacker instanceof ServerPlayer direct ? direct
                    : MonsterRoles.isNativeBody(attacker) ? level.getServer().getPlayerList().getPlayer(attacker.getUUID()) : null;
            if (player == null) return;
            // A projectile from an earlier outing must not order this outing's companions.
            var frozen = MonsterCombatRuntime.source(source.getDirectEntity());
            var current = MonsterCombatRuntime.source(MonsterRoles.nativeModel(player));
            if (frozen.isPresent() && !frozen.equals(current)) return;
            onOwnerAttack(player, target);
        });
    }

    public static ItemStack egg(ServerPlayer player, boolean baby, boolean drowned) {
        var stack = new ItemStack(drowned ? Items.DROWNED_SPAWN_EGG : Items.ZOMBIE_SPAWN_EGG);
        var entityData = new CompoundTag();
        entityData.putBoolean("IsBaby", baby);
        stack.set(DataComponents.ENTITY_DATA, TypedEntityData.of(drowned ? EntityTypes.DROWNED : EntityTypes.ZOMBIE, entityData));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal((baby ? "幼年" : "成年") + (drowned ? "溺尸" : "僵尸") + "刷怪蛋"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("右键方块或水面生成一名随从；成功后消耗"),
                Component.literal("跟随并协助主人攻击；离开本次出场后死亡"))));
        return MonsterItems.bind(player, stack, baby ? "baby_egg" : "adult_egg");
    }

    public static boolean useOn(ServerPlayer player, InteractionHand hand, BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);
        if (!usableEgg(player, stack)) return false;
        var landing = landingOn(player, hit);
        return landing != null && spawn(player, stack, landing);
    }

    /** Vanilla egg air-use raycasts source liquids; ordinary air does not create a companion. */
    public static boolean use(ServerPlayer player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!usableEgg(player, stack)) return false;
        var landing = landingInLiquid(player);
        return landing != null && spawn(player, stack, landing);
    }

    /** Where a native spawn egg would put its mob; {@code moveDown} applies the native floor offset. */
    public record Landing(BlockPos pos, boolean moveDown, boolean movedUp) {}

    /** The clicked face, or the clicked block itself when it has no collision, as {@code SpawnEggItem.useOn} picks it. */
    public static Landing landingOn(ServerPlayer player, BlockHitResult hit) {
        var level = player.level();
        BlockPos clicked = hit.getBlockPos();
        if (!withinReach(player, clicked) || !loaded(level, clicked)
                || level.getBlockEntity(clicked) instanceof Spawner
                || ProtectionRuntime.rejectInteraction(player, level)) return null;
        BlockPos spawn = level.getBlockState(clicked).getCollisionShape(level, clicked).isEmpty()
                ? clicked : clicked.relative(hit.getDirection());
        return new Landing(spawn, true, !clicked.equals(spawn) && hit.getDirection() == Direction.UP);
    }

    /** The first liquid source along the view, as {@code SpawnEggItem.use} finds it; under water that is the eye's own block. */
    public static Landing landingInLiquid(ServerPlayer player) {
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getViewVector(1).scale(player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE)));
        BlockHitResult hit = player.level().clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player));
        if (hit.getType() != HitResult.Type.BLOCK || !loaded(player.level(), hit.getBlockPos())
                || !(player.level().getBlockState(hit.getBlockPos()).getBlock() instanceof LiquidBlock)
                || ProtectionRuntime.rejectInteraction(player, player.level())) return null;
        return new Landing(hit.getBlockPos(), false, false);
    }

    /** Positions {@code mob} at the landing like a native egg; false when the spot is unloaded, protected or obstructed. */
    public static boolean place(ServerPlayer player, Mob mob, Landing landing) {
        var level = player.level();
        var pos = landing.pos();
        if (!loaded(level, pos) || !level.getWorldBorder().isWithinBounds(pos) || ProtectionRuntime.protectedLevel(level)) return false;
        double offset = 0;
        if (landing.moveDown()) {
            mob.setPos(pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5);
            offset = CompanionSpawnAccessor.hardrush$spawnYOffset(level, pos, landing.movedUp(), mob.getBoundingBox());
        }
        mob.snapTo(pos.getX() + .5, pos.getY() + offset, pos.getZ() + .5,
                Mth.wrapDegrees(level.getRandom().nextFloat() * 360), 0);
        mob.yHeadRot = mob.getYRot(); mob.yBodyRot = mob.getYRot();
        return safeBox(level, mob, mob.getBoundingBox());
    }

    private static boolean usableEgg(ServerPlayer player, ItemStack stack) {
        return player.isAlive() && MonsterRoundRuntime.canAct(player) && !MonsterRoles.binding(player).isEmpty()
                && (stack.is(Items.ZOMBIE_SPAWN_EGG) || stack.is(Items.DROWNED_SPAWN_EGG))
                && (MonsterItems.matches(player, stack, "baby_egg") || MonsterItems.matches(player, stack, "adult_egg"));
    }

    private static boolean spawn(ServerPlayer player, ItemStack stack, Landing landing) {
        var level = player.level();
        var pos = landing.pos();
        boolean baby = MonsterItems.matches(player, stack, "baby_egg");
        EntityType<? extends Zombie> type = stack.is(Items.DROWNED_SPAWN_EGG) ? EntityTypes.DROWNED : EntityTypes.ZOMBIE;
        Zombie zombie = type.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
        if (zombie == null) return false;
        zombie.setBaby(baby);
        if (!place(player, zombie, landing)) { zombie.discard(); return false; }
        var difficulty = level.getCurrentDifficultyAt(zombie.blockPosition());
        // Explicit group data prevents vanilla creating an extra chicken before applying egg NBT.
        zombie.finalizeSpawn(level, difficulty, EntitySpawnReason.SPAWN_ITEM_USE, new Zombie.ZombieGroupData(baby, false));
        zombie.setBaby(baby);
        if (zombie instanceof Drowned) {
            zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
            ((CompanionMobAccessor) zombie).hardrush$enchantSpawnedWeapon(level, zombie.getRandom(), difficulty);
        }
        zombie.entityTags().add(OWNER + player.getUUID());
        zombie.entityTags().add(EVENT + UUID.randomUUID());
        zombie.entityTags().add(BINDING + MonsterRoles.binding(player));
        zombie.entityTags().add(HATCHED + (baby ? "baby" : "adult"));
        MonsterCombatRuntime.stampSource(zombie, player);
        configure(zombie);
        if (!level.addFreshEntity(zombie)) { zombie.discard(); return false; }
        stack.shrink(1);
        player.inventoryMenu.broadcastChanges();
        level.gameEvent(player, GameEvent.ENTITY_PLACE, pos);
        return true;
    }

    public static UUID owner(Entity entity) { return uuidTag(entity, OWNER); }
    /** Whether this companion hatched from the role's baby egg, adult egg, or neither. */
    public static Optional<Boolean> hatchedBaby(Entity entity) {
        String value = tag(entity, HATCHED);
        return value == null ? Optional.empty() : Optional.of(value.equals("baby"));
    }
    public static UUID event(Entity entity) { return uuidTag(entity, EVENT); }
    public static boolean isDismissing(Entity entity) { return entity != null && entity.entityTags().contains(DISMISSING); }

    private static UUID uuidTag(Entity entity, String prefix) {
        String value = tag(entity, prefix);
        try { return value == null ? null : UUID.fromString(value); }
        catch (IllegalArgumentException malformed) { return null; }
    }
    private static String tag(Entity entity, String prefix) {
        if (entity == null) return null;
        for (String value : entity.entityTags()) if (value.startsWith(prefix)) return value.substring(prefix.length());
        return null;
    }
    private static void replaceTag(Entity entity, String prefix, String value) {
        entity.entityTags().removeIf(tag -> tag.startsWith(prefix));
        if (value != null) entity.entityTags().add(prefix + value);
    }

    private static ServerPlayer activeOwner(Entity summon) {
        UUID id = owner(summon);
        if (id == null || !(summon.level() instanceof ServerLevel level)) return null;
        var player = level.getServer().getPlayerList().getPlayer(id);
        if (player == null || !player.isAlive() || !MonsterRoles.isMonster(player)) return null;
        String binding = MonsterRoles.binding(player);
        return !binding.isEmpty() && binding.equals(tag(summon, BINDING)) ? player : null;
    }

    /** Policy only: callers additionally apply shared combat admission and protection gates. */
    public static boolean allowedTarget(Entity summon, Entity target) {
        UUID owner = owner(summon);
        if (owner == null) return true;
        if (!(target instanceof LivingEntity) || owner.equals(target.getUUID()) || summon == target
                || isDismissing(summon) || !summon.isAlive() || !target.isAlive() || target.isRemoved()
                || summon.level() != target.level() || !(summon.level() instanceof ServerLevel level)
                || activeOwner(summon) == null || ProtectionRuntime.protectedEntity(summon)
                || ProtectionRuntime.protectedEntity(target) || !MonsterRoles.legalTarget(owner, level, target)) return false;
        boolean ally = MonsterRoles.isMonster(target) || owner(target) != null;
        return !ally || target.getUUID().equals(uuidTag(summon, COMMAND));
    }

    /** Called only after an attack really deals damage; failed swings cannot issue orders. */
    public static void onOwnerAttack(ServerPlayer player, LivingEntity target) {
        if (!MonsterRoundRuntime.canAct(player) || !target.isAlive() || target == player
                || !MonsterRoles.legalTarget(player.getUUID(), player.level(), target)) return;
        for (Mob mob : companions(player.level().getServer())) {
            if (activeOwner(mob) != player || mob.level() != target.level() || !mob.isAlive() || mob == target) continue;
            replaceTag(mob, COMMAND, target.getUUID().toString());
            if (allowedTarget(mob, target) && !MonsterCombatRuntime.reject(mob, target)) mob.setTarget(target);
            else replaceTag(mob, COMMAND, null);
        }
    }

    /**
     * Does not reset vanilla equipment, attributes, loot or existing target goals. Only goal-driven
     * companions take orders; one that walks or swims also follows. A mount a jockey brought along keeps its own AI.
     */
    public static void configure(Mob mob) {
        if (owner(mob) == null || isDismissing(mob)) return;
        if (!(mob instanceof Zombie) && !(mob instanceof Vex) && !MonsterSoulcatcher.companionType(mob.getType())) return;
        var access = (MonsterMobAccessor) mob;
        access.hardrush$goalSelector().removeAllGoals(goal -> goal instanceof CompanionFollowGoal);
        access.hardrush$targetSelector().removeAllGoals(goal -> goal instanceof CompanionCommandGoal);
        if (follower(mob)) access.hardrush$goalSelector().addGoal(4, new CompanionFollowGoal(mob));
        access.hardrush$targetSelector().addGoal(0, new CompanionCommandGoal(mob));
    }

    /**
     * Companions that travel after their owner on foot. A drowned's native navigation is amphibious rather
     * than ground, yet it walks and swims after its owner all the same; fliers and vexes keep their own AI.
     */
    private static boolean follower(Mob mob) {
        return !(mob instanceof Vex) && (mob.getNavigation() instanceof GroundPathNavigation
                || mob.getNavigation() instanceof AmphibiousPathNavigation);
    }

    /**
     * The owner just made a long jump of their own (a retrack). Followers land beside them now, while their old
     * chunks are still loaded; once those unload, nothing would bring them along. A retrack may cross
     * dimensions, so followers left in another dimension travel too: the sortie's companions go where it goes.
     */
    public static void regroup(ServerPlayer owner) {
        for (Mob mob : companions(owner.level().getServer()))
            if (activeOwner(mob) == owner && canTravel(mob) && (mob.level() != owner.level() || farBehind(mob, owner))) catchUp(mob, owner);
    }

    /** Alive, walks or swims after its owner, and not tied to a mount or a lead. */
    private static boolean canTravel(Mob mob) {
        return !mob.isRemoved() && mob.isAlive() && !isDismissing(mob) && follower(mob) && !mob.isPassenger() && !mob.isLeashed();
    }

    /** In the owner's dimension and as far behind as a tamed wolf lets itself fall before it teleports. */
    private static boolean farBehind(Mob mob, ServerPlayer owner) {
        return owner.level() == mob.level() && mob.distanceToSqr(owner) >= CATCH_UP_DISTANCE_SQR;
    }

    /**
     * Wolf-like teleport to {@link #besideOwner}. Whatever it was fighting is now far behind, so it drops that
     * target. Into another dimension it travels the vanilla way: {@code Entity.teleport} recreates it there from
     * its saved data, so its tags, frozen combat source and sealed combat origin carry over, and the copy is
     * configured again when it loads.
     */
    private static boolean catchUp(Mob mob, ServerPlayer owner) {
        var destination = besideOwner(mob, owner);
        if (destination == null) return false;
        mob.getNavigation().stop();
        mob.setTarget(null);
        replaceTag(mob, COMMAND, null);
        if (mob.level() == owner.level()) {
            mob.snapTo(destination.x, destination.y, destination.z, mob.getYRot(), mob.getXRot());
            return true;
        }
        return mob.teleport(new TeleportTransition(owner.level(), destination, Vec3.ZERO,
                mob.getYRot(), mob.getXRot(), TeleportTransition.DO_NOTHING)) != null;
    }

    /**
     * A loaded, walkable spot in the owner's level, outside protected levels, where the mob fits; an amphibious
     * follower may also land in water. Every spot of the wolf's ring is tried in random order, since a regroup
     * gets one chance only. Checks the owner's level, not the mob's, so it also serves a mob in another dimension.
     */
    private static Vec3 besideOwner(Mob mob, ServerPlayer owner) {
        var level = owner.level();
        if (ProtectionRuntime.protectedLevel(level)) return null;
        boolean swims = mob.getNavigation() instanceof AmphibiousPathNavigation;
        var pathing = new PathfindingContext(level, mob);
        var spots = new ArrayList<BlockPos>(CATCH_UP_RING.size());
        for (BlockPos offset : CATCH_UP_RING) spots.add(owner.blockPosition().offset(offset));
        Collections.shuffle(spots, new Random(mob.getRandom().nextLong()));
        for (BlockPos pos : spots) {
            if (!loaded(level, pos) || level.getBlockState(pos.below()).getBlock() instanceof LeavesBlock) continue;
            var type = WalkNodeEvaluator.getPathTypeStatic(pathing, pos.mutable());
            if (type != PathType.WALKABLE && !(swims && type == PathType.WATER)) continue;
            var destination = Vec3.atBottomCenterOf(pos);
            if (safeBox(level, mob, mob.getBoundingBox().move(destination.subtract(mob.position())))) return destination;
        }
        return null;
    }

    /** A creature the soulcatcher releases as this sortie's companion, with a fresh creation event. */
    public static void adopt(ServerPlayer player, Mob mob) {
        release(mob);
        mob.entityTags().add(OWNER + player.getUUID());
        mob.entityTags().add(EVENT + UUID.randomUUID());
        mob.entityTags().add(BINDING + MonsterRoles.binding(player));
        MonsterCombatRuntime.stampSource(mob, player);
        configure(mob);
    }

    /** Strips every trace of an earlier sortie; the creature returns to its native allegiances. */
    public static void release(Mob mob) {
        for (String prefix : List.of(OWNER, EVENT, BINDING, COMMAND, HATCHED)) replaceTag(mob, prefix, null);
        mob.entityTags().remove(DISMISSING);
    }

    public static void clearOwner(ServerPlayer player) {
        for (Mob mob : companions(player.level().getServer())) if (player.getUUID().equals(owner(mob))) dismiss(mob);
    }
    private static List<Mob> companions(MinecraftServer server) {
        var loaded = LOADED.get(server);
        return loaded == null ? List.of() : List.copyOf(loaded.values());
    }
    /**
     * Dismisses companions whose sortie is over, and brings along followers left outside entity simulation:
     * their goals no longer run, and if the owner keeps going their chunks unload with them inside.
     */
    private static void tick(MinecraftServer server) {
        for (Mob mob : companions(server)) {
            if (mob.isRemoved()) continue;
            ServerPlayer owner = activeOwner(mob);
            if (owner == null) dismiss(mob);
            else if (canTravel(mob) && farBehind(mob, owner) && !((ServerLevel) mob.level()).isPositionEntityTicking(mob.blockPosition())) catchUp(mob, owner);
        }
    }
    private static void dismiss(Mob mob) {
        mob.entityTags().add(DISMISSING);
        mob.setTarget(null); mob.getNavigation().stop(); mob.setNoAi(true);
        if (!mob.isDeadOrDying()) {
            // No attacker: the loot hook suppresses equipment, experience and loot.
            mob.setHealth(0);
            mob.die(mob.damageSources().genericKill());
        }
    }

    /** Descendants retain the immutable source, never the owner's identity at conversion time. */
    public static void inherit(Entity source, Mob child) {
        // Curing produces an ordinary villager, outside the monster's sortie and combat lineage.
        if (child instanceof Villager) {
            replaceTag(child, OWNER, null); replaceTag(child, EVENT, null);
            replaceTag(child, BINDING, null); replaceTag(child, COMMAND, null);
            child.entityTags().remove(DISMISSING);
            MonsterSoulcatcher.clearRelease(child);
            return;
        }
        UUID owner = owner(source);
        if (owner == null) return;
        replaceTag(child, OWNER, owner.toString());
        replaceTag(child, EVENT, tag(source, EVENT));
        replaceTag(child, BINDING, tag(source, BINDING));
        replaceTag(child, COMMAND, tag(source, COMMAND));
        MonsterSoulcatcher.inheritRelease(source, child);
        if (child instanceof MonsterSourceHolder holder) MonsterCombatRuntime.source(source).ifPresent(holder::hardrush$stampMonsterSource);
        if (child instanceof CombatOriginHolder holder && !holder.hardrush$combatOriginSealed()) {
            Optional<CombatOrigin> inherited = CombatRuntime.originOf(source);
            try { inherited = inherited.map(origin -> origin.child(UUID.randomUUID(), AttackKind.SUMMON, Optional.of(child.getUUID()))); }
            catch (IllegalArgumentException exhaustedChain) { inherited = Optional.empty(); }
            holder.hardrush$sealCombatOrigin(inherited);
        }
        configure(child);
    }

    public interface InheritanceScope extends AutoCloseable { @Override void close(); }
    public static InheritanceScope infection(Entity source) {
        INFECTION.get().push(source);
        return () -> {
            var stack = INFECTION.get();
            if (stack.pop() != source) throw new IllegalStateException("Companion inheritance scopes closed out of order");
            if (stack.isEmpty()) INFECTION.remove();
        };
    }
    public static Entity conversionSource(Mob original) {
        if (owner(original) != null) return original;
        return INFECTION.get().peek();
    }

    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }
    private static boolean withinReach(ServerPlayer player, BlockPos pos) {
        double reach = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) + 1;
        return player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= reach * reach;
    }
    private static boolean safeBox(ServerLevel level, Entity entity, AABB box) {
        if (!loaded(level, BlockPos.containing(box.minX, box.minY, box.minZ))
                || !loaded(level, BlockPos.containing(box.maxX, box.maxY, box.maxZ))) return false;
        return level.getWorldBorder().isWithinBounds(box) && level.noCollision(entity, box);
    }

    private static final class CompanionCommandGoal extends TargetGoal {
        private final Mob companion;
        private LivingEntity ordered;
        CompanionCommandGoal(Mob companion) { super(companion, false); this.companion = companion; }
        @Override public boolean canUse() {
            UUID targetId = uuidTag(companion, COMMAND);
            Entity candidate = targetId == null ? null : ((ServerLevel) companion.level()).getEntity(targetId);
            if (!(candidate instanceof LivingEntity target) || !allowedTarget(companion, target) || MonsterCombatRuntime.reject(companion, target)) {
                replaceTag(companion, COMMAND, null); ordered = null; return false;
            }
            ordered = target;
            return true;
        }
        @Override public boolean canContinueToUse() {
            return ordered != null && ordered.getUUID().equals(uuidTag(companion, COMMAND))
                    && allowedTarget(companion, ordered) && !MonsterCombatRuntime.reject(companion, ordered) && super.canContinueToUse();
        }
        @Override public void start() { companion.setTarget(ordered); super.start(); }
        @Override public void stop() {
            if (ordered != null && ordered.getUUID().equals(uuidTag(companion, COMMAND))) replaceTag(companion, COMMAND, null);
            ordered = null; super.stop();
        }
    }

    /** Wolf-like same-dimension follow: native pathing (through water too), then {@link #catchUp} once twelve blocks behind. */
    private static final class CompanionFollowGoal extends Goal {
        private final Mob companion;
        private ServerPlayer player;
        private int recalc;
        private float waterCost;
        CompanionFollowGoal(Mob companion) { this.companion = companion; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() {
            player = activeOwner(companion);
            return player != null && player.level() == companion.level() && !companion.isPassenger() && !companion.isLeashed()
                    && companion.getTarget() == null && companion.distanceToSqr(player) >= 100;
        }
        @Override public boolean canContinueToUse() {
            return player != null && activeOwner(companion) == player && player.level() == companion.level()
                    && !companion.isPassenger() && !companion.isLeashed() && companion.getTarget() == null
                    && companion.distanceToSqr(player) > 4 && !companion.getNavigation().isDone();
        }
        @Override public void start() {
            recalc = 0; waterCost = companion.getPathfindingMalus(PathType.WATER);
            companion.setPathfindingMalus(PathType.WATER, 0);
        }
        @Override public void stop() {
            companion.getNavigation().stop(); companion.setPathfindingMalus(PathType.WATER, waterCost); player = null;
        }
        @Override public void tick() {
            if (player == null) return;
            companion.getLookControl().setLookAt(player, 10, companion.getMaxHeadXRot());
            if (--recalc > 0) return;
            recalc = adjustedTickDelay(10);
            if (companion.distanceToSqr(player) >= CATCH_UP_DISTANCE_SQR && catchUp(companion, player)) return;
            companion.getNavigation().moveTo(player, 1);
        }
    }
}
