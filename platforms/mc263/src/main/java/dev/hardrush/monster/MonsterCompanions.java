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
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Native zombies with a persistent sortie owner; ordinary equipment, damage and death stay vanilla. */
public final class MonsterCompanions {
    private static final String OWNER = "hardrush.summon-owner=";
    private static final String EVENT = "hardrush.summon-event=";
    private static final String BINDING = "hardrush.companion-binding=";
    private static final String COMMAND = "hardrush.companion-target=";
    private static final String DISMISSING = "hardrush.companion-dismissing";
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
                else if (mob instanceof Zombie zombie) configure(zombie);
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
        var level = player.level();
        BlockPos clicked = hit.getBlockPos();
        if (!withinReach(player, clicked) || !loaded(level, clicked)
                || level.getBlockEntity(clicked) instanceof Spawner
                || ProtectionRuntime.rejectInteraction(player, level, clicked)) return false;
        BlockPos spawn = level.getBlockState(clicked).getCollisionShape(level, clicked).isEmpty()
                ? clicked : clicked.relative(hit.getDirection());
        return spawn(player, stack, spawn, true, !clicked.equals(spawn) && hit.getDirection() == Direction.UP);
    }

    /** Vanilla egg air-use raycasts source liquids; ordinary air does not create a companion. */
    public static boolean use(ServerPlayer player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!usableEgg(player, stack)) return false;
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getViewVector(1).scale(player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE)));
        BlockHitResult hit = player.level().clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player));
        if (hit.getType() != HitResult.Type.BLOCK || !loaded(player.level(), hit.getBlockPos())
                || !(player.level().getBlockState(hit.getBlockPos()).getBlock() instanceof LiquidBlock)
                || ProtectionRuntime.rejectInteraction(player, player.level(), hit.getBlockPos())) return false;
        return spawn(player, stack, hit.getBlockPos(), false, false);
    }

    private static boolean usableEgg(ServerPlayer player, ItemStack stack) {
        return player.isAlive() && MonsterRoundRuntime.canAct(player) && !MonsterRoles.binding(player).isEmpty()
                && (stack.is(Items.ZOMBIE_SPAWN_EGG) || stack.is(Items.DROWNED_SPAWN_EGG))
                && (MonsterItems.matches(player, stack, "baby_egg") || MonsterItems.matches(player, stack, "adult_egg"));
    }

    private static boolean spawn(ServerPlayer player, ItemStack stack, BlockPos pos, boolean moveDown, boolean movedUp) {
        var level = player.level();
        if (!loaded(level, pos) || !level.getWorldBorder().isWithinBounds(pos)
                || ProtectionRuntime.protectedBlock(level, pos)) return false;
        boolean baby = MonsterItems.matches(player, stack, "baby_egg");
        EntityType<? extends Zombie> type = stack.is(Items.DROWNED_SPAWN_EGG) ? EntityTypes.DROWNED : EntityTypes.ZOMBIE;
        Zombie zombie = type.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
        if (zombie == null) return false;
        zombie.setBaby(baby);
        double offset = 0;
        if (moveDown) {
            zombie.setPos(pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5);
            offset = CompanionSpawnAccessor.hardrush$spawnYOffset(level, pos, movedUp, zombie.getBoundingBox());
        }
        zombie.snapTo(pos.getX() + .5, pos.getY() + offset, pos.getZ() + .5,
                Mth.wrapDegrees(level.getRandom().nextFloat() * 360), 0);
        zombie.yHeadRot = zombie.getYRot(); zombie.yBodyRot = zombie.getYRot();
        if (!safeBox(level, zombie, zombie.getBoundingBox())) { zombie.discard(); return false; }
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
        MonsterCombatRuntime.stampSource(zombie, player);
        configure(zombie);
        if (!level.addFreshEntity(zombie)) { zombie.discard(); return false; }
        stack.shrink(1);
        player.inventoryMenu.broadcastChanges();
        level.gameEvent(player, GameEvent.ENTITY_PLACE, pos);
        return true;
    }

    public static UUID owner(Entity entity) { return uuidTag(entity, OWNER); }
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
            if (!(mob instanceof Zombie zombie) || activeOwner(zombie) != player
                    || zombie.level() != target.level() || !zombie.isAlive() || zombie == target) continue;
            replaceTag(zombie, COMMAND, target.getUUID().toString());
            if (allowedTarget(zombie, target) && !MonsterCombatRuntime.reject(zombie, target)) zombie.setTarget(target);
            else replaceTag(zombie, COMMAND, null);
        }
    }

    /** Does not reset vanilla equipment, attributes, loot or existing target goals. */
    public static void configure(Zombie zombie) {
        if (owner(zombie) == null || isDismissing(zombie)) return;
        var access = (MonsterMobAccessor) zombie;
        access.hardrush$goalSelector().removeAllGoals(goal -> goal instanceof CompanionFollowGoal);
        access.hardrush$targetSelector().removeAllGoals(goal -> goal instanceof CompanionCommandGoal);
        access.hardrush$goalSelector().addGoal(4, new CompanionFollowGoal(zombie));
        access.hardrush$targetSelector().addGoal(0, new CompanionCommandGoal(zombie));
    }

    public static void clearOwner(ServerPlayer player) {
        for (Mob mob : companions(player.level().getServer())) if (player.getUUID().equals(owner(mob))) dismiss(mob);
    }
    private static List<Mob> companions(MinecraftServer server) {
        var loaded = LOADED.get(server);
        return loaded == null ? List.of() : List.copyOf(loaded.values());
    }
    private static void tick(MinecraftServer server) {
        for (Mob mob : companions(server)) if (!mob.isRemoved() && activeOwner(mob) == null) dismiss(mob);
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
            return;
        }
        UUID owner = owner(source);
        if (owner == null) return;
        replaceTag(child, OWNER, owner.toString());
        replaceTag(child, EVENT, tag(source, EVENT));
        replaceTag(child, BINDING, tag(source, BINDING));
        replaceTag(child, COMMAND, tag(source, COMMAND));
        if (child instanceof MonsterSourceHolder holder) MonsterCombatRuntime.source(source).ifPresent(holder::hardrush$stampMonsterSource);
        if (child instanceof CombatOriginHolder holder && !holder.hardrush$combatOriginSealed()) {
            Optional<CombatOrigin> inherited = CombatRuntime.originOf(source);
            try { inherited = inherited.map(origin -> origin.child(UUID.randomUUID(), AttackKind.SUMMON, Optional.of(child.getUUID()))); }
            catch (IllegalArgumentException exhaustedChain) { inherited = Optional.empty(); }
            holder.hardrush$sealCombatOrigin(inherited);
        }
        if (child instanceof Zombie zombie) configure(zombie);
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
        private final Zombie zombie;
        private LivingEntity ordered;
        CompanionCommandGoal(Zombie zombie) { super(zombie, false); this.zombie = zombie; }
        @Override public boolean canUse() {
            UUID targetId = uuidTag(zombie, COMMAND);
            Entity candidate = targetId == null ? null : ((ServerLevel) zombie.level()).getEntity(targetId);
            if (!(candidate instanceof LivingEntity target) || !allowedTarget(zombie, target) || MonsterCombatRuntime.reject(zombie, target)) {
                replaceTag(zombie, COMMAND, null); ordered = null; return false;
            }
            ordered = target;
            return true;
        }
        @Override public boolean canContinueToUse() {
            return ordered != null && ordered.getUUID().equals(uuidTag(zombie, COMMAND))
                    && allowedTarget(zombie, ordered) && !MonsterCombatRuntime.reject(zombie, ordered) && super.canContinueToUse();
        }
        @Override public void start() { zombie.setTarget(ordered); super.start(); }
        @Override public void stop() {
            if (ordered != null && ordered.getUUID().equals(uuidTag(zombie, COMMAND))) replaceTag(zombie, COMMAND, null);
            ordered = null; super.stop();
        }
    }

    /** Wolf-like same-dimension follow, with native pathing and a loaded, protected-area-safe teleport. */
    private static final class CompanionFollowGoal extends Goal {
        private final Zombie zombie;
        private ServerPlayer player;
        private int recalc;
        private float waterCost;
        CompanionFollowGoal(Zombie zombie) { this.zombie = zombie; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() {
            player = activeOwner(zombie);
            return player != null && player.level() == zombie.level() && !zombie.isPassenger() && !zombie.isLeashed()
                    && zombie.getTarget() == null && zombie.distanceToSqr(player) >= 100;
        }
        @Override public boolean canContinueToUse() {
            return player != null && activeOwner(zombie) == player && player.level() == zombie.level()
                    && !zombie.isPassenger() && !zombie.isLeashed() && zombie.getTarget() == null
                    && zombie.distanceToSqr(player) > 4 && !zombie.getNavigation().isDone();
        }
        @Override public void start() {
            recalc = 0; waterCost = zombie.getPathfindingMalus(PathType.WATER);
            zombie.setPathfindingMalus(PathType.WATER, 0);
        }
        @Override public void stop() {
            zombie.getNavigation().stop(); zombie.setPathfindingMalus(PathType.WATER, waterCost); player = null;
        }
        @Override public void tick() {
            if (player == null) return;
            zombie.getLookControl().setLookAt(player, 10, zombie.getMaxHeadXRot());
            if (--recalc > 0) return;
            recalc = adjustedTickDelay(10);
            if (zombie.distanceToSqr(player) >= 144 && teleportNearOwner()) return;
            zombie.getNavigation().moveTo(player, 1);
        }
        private boolean teleportNearOwner() {
            var level = (ServerLevel) zombie.level();
            for (int attempt = 0; attempt < 10; attempt++) {
                int x = zombie.getRandom().nextIntBetweenInclusive(-3, 3);
                int z = zombie.getRandom().nextIntBetweenInclusive(-3, 3);
                if (Math.abs(x) < 2 && Math.abs(z) < 2) continue;
                var pos = player.blockPosition().offset(x, zombie.getRandom().nextIntBetweenInclusive(-1, 1), z);
                if (!loaded(level, pos) || ProtectionRuntime.protectedBlock(level, pos)
                        || level.getBlockState(pos.below()).getBlock() instanceof LeavesBlock
                        || WalkNodeEvaluator.getPathTypeStatic(zombie, pos) != PathType.WALKABLE) continue;
                var destination = Vec3.atBottomCenterOf(pos);
                var box = zombie.getBoundingBox().move(destination.subtract(zombie.position()));
                if (!safeBox(level, zombie, box)) continue;
                zombie.snapTo(destination.x, destination.y, destination.z, zombie.getYRot(), zombie.getXRot());
                zombie.getNavigation().stop(); return true;
            }
            return false;
        }
    }
}
