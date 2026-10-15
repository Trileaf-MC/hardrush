package dev.hardrush.p0.monster;

import dev.hardrush.p0.disguise.DisguiseProbe;
import dev.hardrush.p0.monster.mixin.MonsterMobAccessor;
import dev.hardrush.p0.monster.mixin.MonsterEntityAccessor;
import dev.hardrush.p0.monster.mixin.MonsterLivingAccessor;
import dev.hardrush.p0.monster.mixin.NativeCreeperAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Real server-enforced species mechanics for the operator prototype. Appearance
 * remains independent. Race participation, lobby/cooldown and reward persistence
 * will bind this service in the formal controller; this class never invents them.
 */
public final class MonsterRoles {
    private static final Map<UUID, Role> ROLES = new HashMap<>();
    private static final Map<UUID, SkillTimers> SKILL_TIMERS = new HashMap<>();
    private static final String SUMMON_OWNER = "hardrush.summon-owner=";
    private static final String SHOT_OWNER = "hardrush.shot-owner=";
    private static final List<Holder<Attribute>> CONTROLLED = List.of(Attributes.MAX_HEALTH, Attributes.MOVEMENT_SPEED,
        Attributes.ARMOR, Attributes.ARMOR_TOUGHNESS, Attributes.ATTACK_DAMAGE, Attributes.ATTACK_SPEED,
        Attributes.KNOCKBACK_RESISTANCE, Attributes.STEP_HEIGHT);

    private MonsterRoles() {}

    public static void initialize() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Zombie child && summonOwner(child) != null) configureSummon(child);
        });
        AttackEntityCallback.EVENT.register((player, level, hand, target, hit) -> {
            if (!(player instanceof ServerPlayer owner) || !isMonster(owner)) return InteractionResult.PASS;
            attack(owner, target);
            return InteractionResult.FAIL;
        });
        AttackBlockCallback.EVENT.register((p, l, h, pos, face) -> isMonster(p) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((p, l, h, hit) -> {
            if (!(p instanceof ServerPlayer owner) || !isMonster(owner)) return InteractionResult.PASS;
            useControl(owner, h); return InteractionResult.CONSUME;
        });
        UseEntityCallback.EVENT.register((p, l, h, target, hit) -> {
            if (!(p instanceof ServerPlayer owner) || !isMonster(owner)) return InteractionResult.PASS;
            useControl(owner, h); return InteractionResult.CONSUME;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!(player instanceof ServerPlayer owner) || !isMonster(owner)) return InteractionResult.PASS;
            useControl(owner, hand);
            return InteractionResult.CONSUME;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(MonsterRoles::allowDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) {
                Role role = ROLES.get(player.getUUID());
                if (role != null) {
                    dropRoleEquipment(player,true);
                    role.burstRemaining = 0; role.chargeTick = -1; role.igniteAt = 0;
                }
            }
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((old, player, alive) -> {
            Role role = ROLES.get(player.getUUID());
            if (role != null) bind(player, role, true);
        });
    }

    public static String select(ServerPlayer player, String requested) {
        requireThread(player);
        Kind kind;
        try { kind = Kind.valueOf(requested.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException error) { return "拒绝：角色只能为 zombie、skeleton、creeper。"; }
        if (!player.isAlive() || player.isSpectator()) return "拒绝：请在存活、非旁观时进入怪物角色。";
        Role role = ROLES.get(player.getUUID());
        if (role == null) {
            role = new Role(player);
            ROLES.put(player.getUUID(), role);
        }
        if (role.igniteAt != 0) return "引爆期间不能切换角色。";
        if (role.kind!=kind) {
            role.gear.returnWorldGear(player,false);
            role.gear.reset(kind==Kind.SKELETON);
        }
        role.kind = kind;
        role.burstRemaining = 0; role.chargeTick = -1;
        bind(player, role, true);
        return "已进入真实怪物角色；僵尸／骷髅自动拾取装备，背包换装与普通物品操作禁用。" + status(player);
    }

    public static void clear(ServerPlayer player) {
        requireThread(player);
        Role role = ROLES.remove(player.getUUID());
        if (role == null) return;
        boolean dead = player.isDeadOrDying();
        role.gear.returnWorldGear(player,false);
        player.stopUsingItem();
        restoreOriginal(player, role);
        if (dead) player.setHealth(0);
        DisguiseProbe.clear(player);
        player.level().getServer().getPlayerList().sendAllPlayerInfo(player);
    }

    private static void restoreOriginal(ServerPlayer player, Role role) {
        for (var entry : role.originalAttributes.entrySet()) player.getAttribute(entry.getKey()).setBaseValue(entry.getValue());
        for (int slot = 0; slot < role.inventory.size(); slot++) player.getInventory().setItem(slot, role.inventory.get(slot).copy());
        player.getInventory().setSelectedSlot(role.selectedSlot);
        for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.slots.get(slot).set(role.crafting.get(slot - 1).copy());
        player.inventoryMenu.setCarried(role.carried.copy());
        ((MonsterLivingAccessor)player).hardrush$detectEquipmentUpdates();
        player.setGameMode(role.gameMode);
        player.getFoodData().setFoodLevel(role.food);
        player.getFoodData().setSaturation(role.saturation);
        player.setHealth(Math.min(role.health, player.getMaxHealth()));
    }

    public static boolean isMonster(Entity entity) {
        return entity instanceof Player && ROLES.containsKey(entity.getUUID());
    }

    public static boolean isUndead(ServerPlayer player) {
        Role role = ROLES.get(player.getUUID());
        return role != null && role.kind != Kind.CREEPER;
    }

    public static Mob nativeModel(ServerPlayer player) {
        Role role = ROLES.get(player.getUUID());
        return role == null ? null : role.model;
    }

    public static ItemStack actualEquipment(ServerPlayer player,EquipmentSlot slot) {
        Role role=ROLES.get(player.getUUID());
        return (role==null?player.getItemBySlot(slot):role.gear.get(slot)).copy();
    }

    public static boolean pickUpEquipment(ServerPlayer player,ItemEntity item) {
        requireThread(player);
        Role role=ROLES.get(player.getUUID());
        if (role==null || role.kind==Kind.CREEPER || role.model==null || !player.isAlive()) return false;
        syncNativeContext(player,role);
        if (!role.gear.pickUp(player,role.model,item)) return false;
        repairLoadout(player,role);
        DisguiseProbe.refreshEquipment(player);
        return true;
    }

    public static void dropRoleEquipment(ServerPlayer player,boolean death) {
        Role role=ROLES.get(player.getUUID());
        if (role==null) return;
        role.gear.returnWorldGear(player,death);
        role.gear.reset(role.kind==Kind.SKELETON);
    }

    public static Entity enchantmentTarget(Entity target) {
        if (target instanceof ServerPlayer player) {
            Role role=ROLES.get(player.getUUID());
            if (role!=null && role.model!=null) { syncNativeContext(player,role); return role.model; }
        }
        return target;
    }

    private static void syncNativeContext(ServerPlayer player,Role role) {
        role.model.snapTo(player.position());
        role.model.setYRot(player.getYRot()); role.model.setXRot(player.getXRot());
        role.model.setHealth(Math.min(player.getHealth(),role.model.getMaxHealth()));
        role.model.setRemainingFireTicks(player.getRemainingFireTicks());
        ((MonsterEntityAccessor)role.model).hardrush$updateFluidInteraction();
        role.model.setIsInPowderSnow(player.isInPowderSnow);
        ((MonsterEntityAccessor)role.model).hardrush$setWasInPowderSnow(((MonsterEntityAccessor)player).hardrush$wasInPowderSnow());
        role.model.removeAllEffects();
        for (var effect:player.getActiveEffects()) role.model.addEffect(new MobEffectInstance(effect));
    }

    public static SoundEvent hurtSound(ServerPlayer player) {
        Role role=ROLES.get(player.getUUID());
        return role==null?null:switch(role.kind) {
            case ZOMBIE -> SoundEvents.ZOMBIE_HURT;
            case SKELETON -> SoundEvents.SKELETON_HURT;
            case CREEPER -> SoundEvents.CREEPER_HURT;
        };
    }

    public static SoundEvent deathSound(ServerPlayer player) {
        Role role=ROLES.get(player.getUUID());
        return role==null?null:switch(role.kind) {
            case ZOMBIE -> SoundEvents.ZOMBIE_DEATH;
            case SKELETON -> SoundEvents.SKELETON_DEATH;
            case CREEPER -> SoundEvents.CREEPER_DEATH;
        };
    }

    public static void updateEnvironment(ServerPlayer player) {
        Role role=ROLES.get(player.getUUID());
        if (role==null || role.model==null || !player.isAlive()) return;
        syncNativeContext(player,role);
        if (role.kind!=Kind.CREEPER) ((MonsterMobAccessor)role.model).hardrush$burnUndead();
        player.setRemainingFireTicks(role.model.getRemainingFireTicks());
        role.gear.capture(role.model);
        repairLoadout(player,role);
    }

    public static String status(ServerPlayer player) {
        Role role = ROLES.get(player.getUUID());
        if (role == null) return "monster=off";
        return "monster=" + role.kind.name().toLowerCase(java.util.Locale.ROOT)
            + "; nativeMaxHealth=" + role.model.getMaxHealth()
            + "; nativeMovementAttribute=" + role.model.getAttributeValue(Attributes.MOVEMENT_SPEED)
            + "; playerMovementEquivalent=" + player.getAttributeValue(Attributes.MOVEMENT_SPEED)
            + "; nativeAttackDamage=" + role.model.getAttributeValue(Attributes.ATTACK_DAMAGE)
            + "; intrinsicArmor=" + player.getAttributeValue(Attributes.ARMOR)
            + "; equipment=automatic; ordinaryItems=false; burstRemaining=" + role.burstRemaining
            + "; arrowsFired=" + role.arrowsFired;
    }

    public static void tick(MinecraftServer server) {
        for (var entry : ROLES.entrySet()) {
            Role role = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || !player.isAlive()) continue;
            if (role.player != player || role.model == null || role.model.level() != player.level()) bind(player, role, false);
            player.setSprinting(false);
            if (player.gameMode.getGameModeForPlayer() != GameType.ADVENTURE) player.setGameMode(GameType.ADVENTURE);
            // No hunger/regen or player sprint. Food remains at a stable value
            // that vanilla LocalPlayer itself recognises as unable to sprint.
            player.getFoodData().setFoodLevel(6);
            player.getFoodData().setSaturation(0);
            repairLoadout(player, role);
            if (role.chargeTick >= 0 && !player.isUsingItem()) role.chargeTick = -1;
            updateEnvironment(player);
            if (server.getTickCount()>=role.nextAmbientTick) {
                role.model.playAmbientSound();
                role.nextAmbientTick=server.getTickCount()+role.model.getAmbientSoundInterval()+player.getRandom().nextInt(80);
            }
            long now = System.nanoTime();
            if (role.burstRemaining > 0 && now >= role.nextBurstAt) {
                shoot(player, role);
                role.burstRemaining--; role.nextBurstAt = now + 600_000_000L;
            }
            if (role.igniteAt != 0 && server.getTickCount() >= role.igniteAt) explode(player, role);
        }
    }

    public static void onDisconnect(ServerPlayer player) {
        Role role = ROLES.get(player.getUUID());
        if (role != null) {
            role.disconnectedHealth = player.getHealth();
            role.disconnectedSource=player;
            restoreOriginal(player, role);
            role.burstRemaining = 0; role.chargeTick = -1; role.igniteAt = 0;
            role.player = null; role.model = null;
        }
    }

    public static void onJoin(ServerPlayer player) {
        Role role=ROLES.get(player.getUUID());
        if (role!=null && player.isAlive()) bind(player,role,false);
    }

    public static void stop(MinecraftServer server) {
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) clear(player);
        // Offline roles are session-only too: return their world-owned gear
        // before the server saves worlds, rather than losing it with the map.
        for (Role role:ROLES.values()) if(role.disconnectedSource!=null)role.gear.returnWorldGear(role.disconnectedSource,false);
        ROLES.clear(); SKILL_TIMERS.clear();
    }

    public static boolean attack(ServerPlayer player, Entity target) {
        dev.hardrush.p2.lobby.P2LobbyRuntime.interruptReturn(player);
        Role role = ROLES.get(player.getUUID());
        if (role == null || !player.isAlive() || (role.kind!=Kind.ZOMBIE && (role.kind!=Kind.SKELETON || role.gear.get(EquipmentSlot.MAINHAND).is(Items.BOW))) || !(target instanceof LivingEntity living)
                || !legalTarget(player.getUUID(), player.level(), target)) return false;
        role.model.snapTo(player.position());
        if (!role.model.isWithinMeleeAttackRange(living) || !player.hasLineOfSight(target)
                || player.level().getServer().getTickCount() < role.nextMeleeTick || role.igniteAt != 0) return false;
        role.nextMeleeTick = player.level().getServer().getTickCount() + 20;
        syncNativeContext(player,role);
        return role.model.doHurtTarget(player.level(), target);
    }

    public static boolean useControl(ServerPlayer player, InteractionHand hand) {
        dev.hardrush.p2.lobby.P2LobbyRuntime.interruptReturn(player);
        Role role = ROLES.get(player.getUUID());
        if (role == null || hand != InteractionHand.MAIN_HAND || !player.isAlive()) return false;
        repairLoadout(player, role);
        int slot = player.getInventory().getSelectedSlot();
        if (role.kind == Kind.SKELETON && slot == 0 && role.gear.get(EquipmentSlot.MAINHAND).is(Items.BOW)) {
            if (role.burstRemaining > 0 || role.chargeTick >= 0 || player.level().getServer().getTickCount() < role.nextBowTick) return false;
            role.chargeTick = player.level().getServer().getTickCount();
            player.startUsingItem(hand);
            return true;
        }
        if (role.kind == Kind.ZOMBIE && slot == 1) return summon(player);
        if (role.kind == Kind.SKELETON && slot == 1) return burst(player);
        if (role.kind == Kind.CREEPER && slot == 0) return ignite(player);
        return false;
    }

    public static void releaseBow(ServerPlayer player) {
        Role role = ROLES.get(player.getUUID());
        if (role == null) return;
        int started = role.chargeTick; role.chargeTick = -1;
        player.stopUsingItem();
        // Skeleton's native bow goal draws for 20 ticks before shooting.
        if (role.kind == Kind.SKELETON && role.gear.get(EquipmentSlot.MAINHAND).is(Items.BOW) && started >= 0 && role.burstRemaining == 0
                && player.level().getServer().getTickCount() - started >= 20) shoot(player, role);
    }

    public static boolean burst(ServerPlayer player) {
        dev.hardrush.p2.lobby.P2LobbyRuntime.interruptReturn(player);
        Role role = ROLES.get(player.getUUID());
        long now = System.nanoTime();
        if (role == null || !player.isAlive() || role.kind != Kind.SKELETON || !role.gear.get(EquipmentSlot.MAINHAND).is(Items.BOW) || now < role.timers.burstReadyAt || role.burstRemaining > 0) return false;
        player.stopUsingItem(); role.chargeTick = -1;
        role.timers.burstReadyAt = now + 20_000_000_000L;
        shoot(player, role); role.burstRemaining = 2; role.nextBurstAt = now + 600_000_000L;
        return true;
    }

    public static boolean summon(ServerPlayer player) {
        dev.hardrush.p2.lobby.P2LobbyRuntime.interruptReturn(player);
        Role role = ROLES.get(player.getUUID());
        long now = System.nanoTime();
        if (role == null || !player.isAlive() || role.kind != Kind.ZOMBIE || now < role.timers.summonReadyAt) return false;
        var positions = safeSummonPositions(player);
        if (positions.size() != 2) return false;
        var children = new ArrayList<Zombie>();
        for (Vec3 position : positions) {
            Zombie child = EntityTypes.ZOMBIE.create(player.level(), EntitySpawnReason.MOB_SUMMONED);
            child.setBaby(true); child.snapTo(position);
            child.entityTags().add(SUMMON_OWNER + player.getUUID());
            configureSummon(child);
            children.add(child);
        }
        for (Zombie child : children) {
            if (!player.level().addFreshEntity(child)) {
                children.forEach(Entity::discard);
                return false;
            }
        }
        role.timers.summonReadyAt = now + 90_000_000_000L;
        return true;
    }

    private static List<Vec3> safeSummonPositions(ServerPlayer player) {
        var found = new ArrayList<Vec3>();
        for (int dx = -4; dx <= 4 && found.size() < 2; dx++) {
            for (int dz = -4; dz <= 4 && found.size() < 2; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance < 2 || distance > 4) continue;
                BlockPos pos = player.blockPosition().offset(dx, 0, dz);
                AABB box = new AABB(pos.getX() + .2, pos.getY(), pos.getZ() + .2, pos.getX() + .8, pos.getY() + 1, pos.getZ() + .8);
                if (player.level().getBlockState(pos.below()).isCollisionShapeFullBlock(player.level(), pos.below())
                        && player.level().getFluidState(pos).isEmpty() && player.level().noCollision(box)) found.add(Vec3.atBottomCenterOf(pos));
            }
        }
        return found;
    }

    public static boolean ignite(ServerPlayer player) {
        dev.hardrush.p2.lobby.P2LobbyRuntime.interruptReturn(player);
        Role role = ROLES.get(player.getUUID());
        if (role == null || !player.isAlive() || role.kind != Kind.CREEPER || role.igniteAt != 0) return false;
        int fuse = ((NativeCreeperAccessor) role.model).hardrush$maxSwell();
        role.igniteAt = player.level().getServer().getTickCount() + fuse;
        player.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        DisguiseProbe.setCreeperSwelling(player, true);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.CREEPER_PRIMED, SoundSource.HOSTILE, 1, .5f);
        return true;
    }

    private static void explode(ServerPlayer player, Role role) {
        Creeper creeper = (Creeper) role.model;
        creeper.snapTo(player.position());
        UUID owner = player.getUUID(); ServerLevel level = player.level();
        var calculator = new ExplosionDamageCalculator() {
            @Override public boolean shouldDamageEntity(Explosion explosion, Entity target) { return legalTarget(owner, level, target); }
            @Override public float getKnockbackMultiplier(Entity target) { return legalTarget(owner, level, target) ? super.getKnockbackMultiplier(target) : 0; }
        };
        level.explode(creeper, null, calculator, player.position(), ((NativeCreeperAccessor) creeper).hardrush$explosionRadius(), false, Level.ExplosionInteraction.NONE);
        role.igniteAt = 0;
        DisguiseProbe.setCreeperSwelling(player, false);
        // Prototype ends this body via ordinary death; the formal controller
        // will consume this event after rewards and return to the monster lobby.
        player.hurtServer(level, player.damageSources().genericKill(), Float.MAX_VALUE);
    }

    private static void shoot(ServerPlayer player, Role role) {
        syncNativeContext(player,role);
        ItemStack weapon=role.gear.get(EquipmentSlot.MAINHAND);
        AbstractArrow arrow = ProjectileUtil.getMobArrow(role.model, new ItemStack(Items.ARROW), 1, weapon);
        // A real owner avoids treating the player's own body as a foreign
        // collision. MonsterArrowMixin supplies the native skeleton damage
        // source independently, preserving difficulty and UUID attribution.
        arrow.setOwner(player);
        arrow.entityTags().add(SHOT_OWNER + player.getUUID());
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        arrow.setCritArrow(false);
        Vec3 aim = player.getLookAngle();
        Projectile.spawnProjectileUsingShoot(arrow,player.level(),weapon,aim.x,aim.y,aim.z,1.6f,14-player.level().getDifficulty().getId()*4);
        role.nextBowTick = player.level().getServer().getTickCount()
            + (player.level().getDifficulty() == net.minecraft.world.Difficulty.HARD ? 20 : 40);
        role.arrowsFired++;
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SKELETON_SHOOT, SoundSource.HOSTILE, 1, 1);
    }

    public static UUID summonOwner(Entity entity) { return taggedOwner(entity, SUMMON_OWNER); }
    public static Entity nativeArrowShooter(AbstractArrow arrow, Entity vanilla) {
        UUID owner = taggedOwner(arrow, SHOT_OWNER);
        if (owner == null || !(arrow.level() instanceof ServerLevel level)) return vanilla;
        Role role = ROLES.get(owner);
        if (role != null && role.kind == Kind.SKELETON && role.model != null && role.model.level() == level) return role.model;
        Mob source = EntityTypes.SKELETON.create(level, EntitySpawnReason.COMMAND);
        source.setUUID(owner);
        if (vanilla != null) source.setId(vanilla.getId());
        return source;
    }
    private static UUID taggedOwner(Entity entity, String prefix) {
        if (entity == null) return null;
        for (String tag : entity.entityTags()) if (tag.startsWith(prefix)) {
            try { return UUID.fromString(tag.substring(prefix.length())); }
            catch (IllegalArgumentException ignored) { return null; }
        }
        return null;
    }

    public static boolean legalTarget(UUID owner, ServerLevel level, Entity target) {
        if (!dev.hardrush.p2.lobby.P2LobbyRuntime.targetAllowed(owner, level, target)) return false;
        return target instanceof ServerPlayer player && !owner.equals(player.getUUID()) && player.level() == level
            && player.isAlive() && !isMonster(player) && !player.isSpectator() && !player.getAbilities().invulnerable;
    }

    public static boolean isIgniting(ServerPlayer player) {
        var role = ROLES.get(player.getUUID());
        return role != null && role.igniteAt != 0;
    }

    private static boolean allowDamage(LivingEntity target, DamageSource source, float amount) {
        Entity direct = source.getDirectEntity(), causing = source.getEntity();
        UUID owner = taggedOwner(direct, SHOT_OWNER);
        if (owner == null) owner = summonOwner(causing);
        if (owner == null && causing != null && !(causing instanceof Player) && ROLES.containsKey(causing.getUUID())) owner = causing.getUUID();
        if (owner != null) return legalTarget(owner, (ServerLevel) target.level(), target);
        // Monster players never fall back to the ordinary player combat path.
        return !(causing instanceof ServerPlayer player && isMonster(player));
    }

    public static void configureSummon(Zombie child) {
        UUID owner = summonOwner(child);
        if (owner == null) return;
        child.setCanPickUpLoot(false); child.setCanBreakDoors(false);
        child.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE).setBaseValue(0);
        for (var slot : EquipmentSlot.values()) child.setItemSlot(slot, ItemStack.EMPTY);
        var targets = ((MonsterMobAccessor) child).hardrush$targetSelector();
        targets.removeAllGoals(goal -> true);
        targets.addGoal(2, new NearestAttackableTargetGoal<>(child, ServerPlayer.class, true,
            (candidate, level) -> legalTarget(owner, level, candidate)));
    }

    private static void bind(ServerPlayer player, Role role, boolean heal) {
        float resumedHealth=role.player==null ? role.disconnectedHealth : Float.NaN;
        role.player = player;
        role.disconnectedSource=null;
        role.model = role.kind.type.create(player.level(), EntitySpawnReason.COMMAND);
        if (role.model == null) throw new IllegalStateException("Native monster template unavailable");
        role.model.setUUID(player.getUUID());
        role.model.setId(player.getId());
        role.gear.bind(role.model);
        role.nextAmbientTick=player.level().getServer().getTickCount()+80;
        player.closeContainer(); player.stopUsingItem(); player.stopRiding(); player.setSprinting(false);
        player.setGameMode(GameType.ADVENTURE);
        for (var attribute : CONTROLLED) {
            var instance = player.getAttribute(attribute);
            if (attribute.equals(Attributes.MOVEMENT_SPEED)) {
                // Mob.setSpeed sets both speed and forward input to its native
                // value. LocalPlayer forward input is one, so preserve actual
                // walking speed rather than making a .23 zombie run 2.3x human.
                double speed = role.model.getAttributeValue(attribute);
                instance.setBaseValue(speed * speed);
            } else if (attribute.equals(Attributes.ATTACK_SPEED)) instance.setBaseValue(1);
            else if (role.model.getAttributes().hasAttribute(attribute)) instance.setBaseValue(role.model.getAttribute(attribute).getBaseValue());
        }
        if (heal) player.setHealth(player.getMaxHealth());
        else if (!Float.isNaN(resumedHealth)) player.setHealth(Math.min(resumedHealth,player.getMaxHealth()));
        role.disconnectedHealth=Float.NaN;
        repairLoadout(player, role);
        player.getFoodData().setFoodLevel(6); player.getFoodData().setSaturation(0);
        DisguiseProbe.apply(player, role.kind.name().toLowerCase(java.util.Locale.ROOT));
        player.level().getServer().getPlayerList().sendAllPlayerInfo(player);
    }

    public static void repairLoadout(ServerPlayer player) {
        Role role = ROLES.get(player.getUUID());
        if (role != null) repairLoadout(player, role);
    }

    private static void repairLoadout(ServerPlayer player, Role role) {
        for (int slot = 1; slot <= 4; slot++) {
            if (!player.inventoryMenu.slots.get(slot).getItem().isEmpty()) player.inventoryMenu.slots.get(slot).set(ItemStack.EMPTY);
        }
        if (!player.inventoryMenu.getCarried().isEmpty()) player.inventoryMenu.setCarried(ItemStack.EMPTY);
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack wanted = ItemStack.EMPTY;
            if (slot == 0) wanted = role.gear.get(EquipmentSlot.MAINHAND).copy();
            if (slot == 0 && role.kind == Kind.CREEPER) wanted = named(Items.GUNPOWDER, "苦力怕：右键引爆");
            if (slot == 1 && role.kind == Kind.SKELETON) wanted = named(Items.BONE, "骷髅：三连射");
            if (slot == 1 && role.kind == Kind.ZOMBIE) wanted = named(Items.ROTTEN_FLESH, "僵尸：召唤 2 只小僵尸");
            if (slot == 8 && role.kind == Kind.SKELETON) wanted = named(Items.ARROW, "角色箭矢（无限，不可转移）");
            EquipmentSlot armorSlot=switch(slot) {
                case 36 -> EquipmentSlot.FEET;
                case 37 -> EquipmentSlot.LEGS;
                case 38 -> EquipmentSlot.CHEST;
                case 39 -> EquipmentSlot.HEAD;
                case 40 -> EquipmentSlot.OFFHAND;
                default -> null;
            };
            if (armorSlot!=null) wanted=role.gear.get(armorSlot).copy();
            if (!ItemStack.matches(player.getInventory().getItem(slot), wanted)) player.getInventory().setItem(slot, wanted);
        }
        for (var slot:EquipmentSlot.values()) {
            if (slot!=EquipmentSlot.MAINHAND && !ItemStack.matches(player.getItemBySlot(slot),role.gear.get(slot)))
                player.setItemSlot(slot,role.gear.get(slot).copy());
        }
        ((MonsterLivingAccessor)player).hardrush$detectEquipmentUpdates();
        // Weapon modifiers must not freeze the owner's melee indicator; mob
        // combat itself uses the independent native 20-tick attack interval.
        var attackSpeed=player.getAttribute(Attributes.ATTACK_SPEED);
        double itemOffset=player.getMainHandItem().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
            net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY).compute(Attributes.ATTACK_SPEED,0,EquipmentSlot.MAINHAND);
        attackSpeed.setBaseValue(1-itemOffset);
        player.inventoryMenu.broadcastChanges();
    }

    private static ItemStack named(net.minecraft.world.item.Item item, String name) {
        var stack = new ItemStack(item); stack.set(DataComponents.CUSTOM_NAME, Component.literal(name)); return stack;
    }

    private static void requireThread(ServerPlayer player) {
        if (!player.level().getServer().isSameThread()) throw new IllegalStateException("Monster roles require server thread");
    }

    private enum Kind {
        ZOMBIE(EntityTypes.ZOMBIE), SKELETON(EntityTypes.SKELETON), CREEPER(EntityTypes.CREEPER);
        final EntityType<? extends Mob> type;
        Kind(EntityType<? extends Mob> type) { this.type = type; }
    }

    private static final class Role {
        final MonsterEquipment gear=new MonsterEquipment();
        Kind kind;
        ServerPlayer player;
        ServerPlayer disconnectedSource;
        Mob model;
        final List<ItemStack> inventory = new ArrayList<>();
        final List<ItemStack> crafting = new ArrayList<>();
        final ItemStack carried;
        final Map<Holder<Attribute>, Double> originalAttributes = new HashMap<>();
        final GameType gameMode;
        final int selectedSlot, food;
        final float health, saturation;
        float disconnectedHealth=Float.NaN;
        final SkillTimers timers;
        long nextBurstAt, igniteAt;
        int nextMeleeTick, nextBowTick, chargeTick = -1, burstRemaining, arrowsFired;
        int nextAmbientTick;
        Role(ServerPlayer player) {
            timers = SKILL_TIMERS.computeIfAbsent(player.getUUID(), uuid -> new SkillTimers());
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) inventory.add(player.getInventory().getItem(slot).copy());
            for (var attribute : CONTROLLED) originalAttributes.put(attribute, player.getAttribute(attribute).getBaseValue());
            for (int slot = 1; slot <= 4; slot++) crafting.add(player.inventoryMenu.slots.get(slot).getItem().copy());
            carried = player.inventoryMenu.getCarried().copy();
            gameMode = player.gameMode.getGameModeForPlayer(); selectedSlot = player.getInventory().getSelectedSlot();
            food = player.getFoodData().getFoodLevel(); health = player.getHealth(); saturation = player.getFoodData().getSaturationLevel();
        }
    }

    private static final class SkillTimers { long summonReadyAt, burstReadyAt; }
}
