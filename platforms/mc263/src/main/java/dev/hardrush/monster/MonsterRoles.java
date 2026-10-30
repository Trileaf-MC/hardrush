package dev.hardrush.monster;

import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.MonsterSkill;
import dev.hardrush.disguise.MonsterDisguise;
import dev.hardrush.monster.mixin.MonsterEntityAccessor;
import dev.hardrush.monster.mixin.MonsterLivingAccessor;
import dev.hardrush.monster.mixin.MonsterSkeletonAccessor;
import dev.hardrush.combat.MonsterCombatRuntime;
import dev.hardrush.protection.ProtectionRuntime;
import java.util.*;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.*;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.Vec3;

/** Server-owned role identity; the vanilla player inventory remains the only item store. */
public final class MonsterRoles {
    private static final RulesConfig DEFAULT_RULES = RulesConfig.defaults();
    private static final Map<UUID, Role> ROLES = new HashMap<>();
    private static final Map<UUID, Long> BURST_READY = new HashMap<>();
    private static final String SHOT_OWNER = "hardrush.shot-owner=";
    private static final String SHOT_BINDING = "hardrush.shot-binding=";
    private static final String NATIVE_BODY = "hardrush.native-body";
    private static final List<Holder<Attribute>> CONTROLLED = List.of(Attributes.MAX_HEALTH, Attributes.MOVEMENT_SPEED,
        Attributes.ARMOR, Attributes.ARMOR_TOUGHNESS, Attributes.ATTACK_DAMAGE, Attributes.ATTACK_SPEED,
        Attributes.KNOCKBACK_RESISTANCE, Attributes.STEP_HEIGHT);
    private MonsterRoles() {}

    public static void initialize() {
        MonsterCompanions.initialize();
        AttackEntityCallback.EVENT.register((p, level, hand, target, hit) -> {
            if (!(p instanceof ServerPlayer owner) || !isMonster(owner)) return InteractionResult.PASS;
            attack(owner, target); return InteractionResult.FAIL;
        });
        AttackBlockCallback.EVENT.register((p, l, h, pos, face) -> isMonster(p) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((p, l, hand, hit) -> {
            if (!(p instanceof ServerPlayer owner) || !isMonster(owner)) return InteractionResult.PASS;
            if (isEgg(owner, owner.getItemInHand(hand))) {
                MonsterCompanions.useOn(owner, hand, hit); return InteractionResult.CONSUME;
            }
            if (useControl(owner, hand)) return InteractionResult.CONSUME;
            return MonsterRoundRuntime.canAct(owner) && !owner.isSecondaryUseActive() && MonsterInventory.storageBlock(owner, hit.getBlockPos())
                    ? InteractionResult.PASS : InteractionResult.FAIL;
        });
        UseEntityCallback.EVENT.register((p, l, hand, target, hit) -> {
            if (!(p instanceof ServerPlayer owner) || !isMonster(owner)) return InteractionResult.PASS;
            if (useControl(owner, hand)) return InteractionResult.CONSUME;
            return MonsterRoundRuntime.canAct(owner) && MonsterInventory.storageEntity(target)
                    ? InteractionResult.PASS : InteractionResult.FAIL;
        });
        UseItemCallback.EVENT.register((p, l, hand) -> {
            if (!(p instanceof ServerPlayer owner) || !isMonster(owner)) return InteractionResult.PASS;
            useControl(owner, hand); return InteractionResult.CONSUME;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(MonsterRoles::allowDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player && isMonster(player)) {
                dropRoleEquipment(player, true);
                stopActions(player);
                MonsterCompanions.clearOwner(player);
            }
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((old, player, alive) -> {
            Role role = ROLES.get(player.getUUID());
            if (role != null) bind(player, role, false);
        });
    }

    public static String select(ServerPlayer player, String requested) {
        requireThread(player);
        MonsterForm form;
        try { form = MonsterForm.valueOf(requested.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { return "拒绝：角色只能为 zombie、skeleton、creeper。"; }
        if (form == MonsterForm.DROWNED || form == MonsterForm.STRAY) return "该形态需要通过环境转化。";
        if (!MonsterRoundRuntime.canSelect(player, requested)) return "拒绝：角色必须与正式出场身份一致。";
        if (!player.isAlive() || player.isSpectator()) return "拒绝：请在存活、非旁观时进入怪物角色。";
        Role role = ROLES.get(player.getUUID());
        if (role != null && role.form.role == form.role && MonsterRoundRuntime.rules(player).isPresent()) return "已是正式出场角色。";
        if (isIgniting(player)) return "膨胀期间不能切换角色。";
        if (role == null) {
            // Native close may spill cursor/crafting stacks when inventory is full; escrow only what remains.
            player.closeContainer();
            role = new Role(player);
            ROLES.put(player.getUUID(), role);
        } else {
            stopActions(player); MonsterCompanions.clearOwner(player); settleInventory(player, role, false);
            player.closeContainer();
        }
        clearInventory(player);
        player.getInventory().setSelectedSlot(0);
        role.form = form; role.binding = MonsterRoundRuntime.identity(player) + ":" + UUID.randomUUID();
        role.settled = false; role.exposure = 0; role.conversion = -1;
        bind(player, role, true);
        if (form != MonsterForm.CREEPER) {
            for (var entry : MonsterSpawnEquipment.generate(player, role.model, form.skeleton()).entrySet())
                if (!entry.getValue().isEmpty()) player.setItemSlot(entry.getKey(), MonsterItems.bind(player, entry.getValue(), "gear"));
        }
        if (form.zombie()) {
            player.getInventory().setItem(1, MonsterCompanions.egg(player, true, false));
            player.getInventory().setItem(2, MonsterCompanions.egg(player, false, false));
        } else if (form.skeleton()) {
            player.getInventory().setItem(1, MonsterItems.bind(player,
                named(Items.BONE, "骷髅：三连射", "持弓时右键使用；每 0.3 秒一箭，冷却 20 秒"), "burst"));
        } else player.getInventory().setItem(0, MonsterCreeper.createIgniter(player));
        player.getInventory().setItem(7, MonsterItems.bind(player,
            named(Items.ECHO_SHARD, "归返营地 · 右键引导 5 秒", "移动、攻击、使用物品或受伤会中断"), "return"));
        player.getInventory().setSelectedSlot(0);
        repairLoadout(player);
        return "已进入怪物角色；可整理背包与装备，出生物资仅限本次出场本人使用。" + status(player);
    }

    public static void clear(ServerPlayer player) {
        requireThread(player);
        Role role = ROLES.get(player.getUUID());
        if (role == null) return;
        boolean dead = player.isDeadOrDying();
        stopActions(player); MonsterCompanions.clearOwner(player);
        player.closeContainer();
        if (role.disconnectedInventory != null) restoreBodyInventory(player, role);
        settleInventory(player, role, dead);
        ROLES.remove(player.getUUID());
        restoreOriginal(player, role);
        if (dead) player.setHealth(0);
        MonsterDisguise.clear(player);
        player.level().getServer().getPlayerList().sendAllPlayerInfo(player);
        MonsterAiIdentity.changed(player);
    }

    /** Leave the monster form without settling items: the body is carried into the lobby or replaced natively. */
    public static void suspend(ServerPlayer player) {
        Role role = ROLES.get(player.getUUID());
        if (role == null) return;
        boolean dead = player.isDeadOrDying();
        stopActions(player); MonsterCompanions.clearOwner(player);
        ROLES.remove(player.getUUID());
        restoreOriginal(player, role);
        if (dead) player.setHealth(0);
        MonsterDisguise.clear(player);
        player.level().getServer().getPlayerList().sendAllPlayerInfo(player);
        MonsterAiIdentity.changed(player);
    }

    private static void restoreOriginal(ServerPlayer player, Role role) {
        player.stopUsingItem();
        for (var entry : role.originalAttributes.entrySet()) player.getAttribute(entry.getKey()).setBaseValue(entry.getValue());
        for (int slot = 0; slot < role.inventory.size(); slot++) player.getInventory().setItem(slot, role.inventory.get(slot).copy());
        player.getInventory().setSelectedSlot(role.selectedSlot);
        for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.slots.get(slot).set(role.crafting.get(slot - 1).copy());
        player.inventoryMenu.setCarried(role.carried.copy());
        ((MonsterLivingAccessor) player).hardrush$detectEquipmentUpdates();
        player.setGameMode(role.gameMode); player.getFoodData().setFoodLevel(role.food);
        player.getFoodData().setSaturation(role.saturation); player.setHealth(Math.min(role.health, player.getMaxHealth()));
    }

    public static boolean isMonster(Entity entity) { return entity instanceof Player && ROLES.containsKey(entity.getUUID()); }
    public static MonsterForm currentForm(ServerPlayer player) { var role = ROLES.get(player.getUUID()); return role == null ? null : role.form; }
    public static String binding(ServerPlayer player) { var role = ROLES.get(player.getUUID()); return role == null ? "" : role.binding; }
    public static boolean isUndead(ServerPlayer player) { var form = currentForm(player); return form != null && form != MonsterForm.CREEPER; }
    public static Mob nativeModel(ServerPlayer player) { var role = ROLES.get(player.getUUID()); return role == null ? null : role.model; }
    public static boolean isNativeBody(Entity entity) { return entity != null && entity.entityTags().contains(NATIVE_BODY); }
    /** Only the exact current template may redirect an enchantment's damage to its player body. */
    public static ServerPlayer playerForNativeBody(Entity model) {
        if (!isNativeBody(model)) return null;
        var role = ROLES.get(model.getUUID());
        var player = role == null ? null : role.player;
        return player != null && role.model == model && model.level() == player.level()
                && player.level().getServer().getPlayerList().getPlayer(player.getUUID()) == player ? player : null;
    }
    public static ItemStack actualEquipment(ServerPlayer player, EquipmentSlot slot) {
        return currentForm(player) == MonsterForm.CREEPER ? ItemStack.EMPTY : player.getItemBySlot(slot).copy();
    }
    public static boolean pickUpEquipment(ServerPlayer player, ItemEntity item) {
        if (!player.isAlive() || !MonsterRoundRuntime.canAct(player) || item.isRemoved() || item.hasPickUpDelay() || MonsterItems.bound(item.getItem())) return false;
        int before = item.getItem().getCount();
        player.getInventory().add(item.getItem());
        int accepted = before - item.getItem().getCount();
        if (accepted > 0) { player.take(item, accepted); if (item.getItem().isEmpty()) item.discard(); repairLoadout(player); }
        return accepted > 0;
    }
    public static void dropRoleEquipment(ServerPlayer player, boolean death) {
        var role = ROLES.get(player.getUUID());
        if (role != null) settleInventory(player, role, death);
    }
    private static void settleInventory(ServerPlayer player, Role role, boolean death) {
        if (role.settled) return;
        role.settled = true;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            dropWorldItem(player, player.getInventory().getItem(i), death); player.getInventory().setItem(i, ItemStack.EMPTY);
        }
        dropWorldItem(player, player.containerMenu.getCarried(), death); player.containerMenu.setCarried(ItemStack.EMPTY);
        if (player.containerMenu != player.inventoryMenu) {
            dropWorldItem(player, player.inventoryMenu.getCarried(), death); player.inventoryMenu.setCarried(ItemStack.EMPTY);
        }
        for (int i = 1; i <= 4; i++) {
            dropWorldItem(player, player.inventoryMenu.slots.get(i).getItem(), death); player.inventoryMenu.slots.get(i).set(ItemStack.EMPTY);
        }
    }
    private static void dropWorldItem(ServerPlayer player, ItemStack item, boolean death) {
        if (!item.isEmpty() && !MonsterItems.bound(item)
                && (!death || !EnchantmentHelper.has(item, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)))
            player.spawnAtLocation(player.level(), item.copy());
    }
    private static void clearInventory(ServerPlayer player) {
        player.getInventory().clearContent(); player.inventoryMenu.setCarried(ItemStack.EMPTY);
        for (int i = 1; i <= 4; i++) player.inventoryMenu.slots.get(i).set(ItemStack.EMPTY);
    }

    public static Entity enchantmentTarget(Entity target) {
        if (target instanceof ServerPlayer player && nativeModel(player) != null) { syncNativeContext(player); return nativeModel(player); }
        return target;
    }
    public static void syncNativeContext(ServerPlayer player) {
        var role = ROLES.get(player.getUUID()); if (role != null && role.model != null) syncNativeContext(player, role);
    }
    private static void syncNativeContext(ServerPlayer player, Role role) {
        role.model.snapTo(player.position()); role.model.setYRot(player.getYRot()); role.model.setXRot(player.getXRot());
        role.model.setHealth(Math.min(player.getHealth(), role.model.getMaxHealth()));
        role.model.setRemainingFireTicks(player.getRemainingFireTicks());
        ((MonsterEntityAccessor) role.model).hardrush$updateFluidInteraction();
        role.model.setIsInPowderSnow(player.isInPowderSnow);
        ((MonsterEntityAccessor) role.model).hardrush$setWasInPowderSnow(((MonsterEntityAccessor) player).hardrush$wasInPowderSnow());
        role.model.removeAllEffects();
        for (var effect : player.getActiveEffects()) role.model.addEffect(new MobEffectInstance(effect));
        role.gear.mirror(player, role.model, role.form == MonsterForm.CREEPER);
    }
    public static void afterNativeAttack(ServerPlayer player) {
        var role = ROLES.get(player.getUUID()); if (role == null || role.model == null) return;
        role.gear.finish(player, role.model, role.form == MonsterForm.CREEPER);
        repairLoadout(player);
        MonsterDisguise.refreshEquipment(player);
    }

    public static SoundEvent hurtSound(ServerPlayer player) {
        var form = currentForm(player); if (form == null) return null;
        return switch (form) {
            case ZOMBIE -> SoundEvents.ZOMBIE_HURT; case DROWNED -> SoundEvents.DROWNED_HURT;
            case SKELETON -> SoundEvents.SKELETON_HURT; case STRAY -> SoundEvents.STRAY_HURT; case CREEPER -> SoundEvents.CREEPER_HURT;
        };
    }
    public static SoundEvent deathSound(ServerPlayer player) {
        var form = currentForm(player); if (form == null) return null;
        return switch (form) {
            case ZOMBIE -> SoundEvents.ZOMBIE_DEATH; case DROWNED -> SoundEvents.DROWNED_DEATH;
            case SKELETON -> SoundEvents.SKELETON_DEATH; case STRAY -> SoundEvents.STRAY_DEATH; case CREEPER -> SoundEvents.CREEPER_DEATH;
        };
    }
    public static void updateEnvironment(ServerPlayer player) {
        var role = ROLES.get(player.getUUID());
        if (role == null || role.model == null || !player.isAlive()) return;
        syncNativeContext(player, role);
        // Daylight immunity is an intentional role rule; real fire and lava remain harmful.
        boolean exposed = role.form == MonsterForm.ZOMBIE ? player.isEyeInFluid(FluidTags.WATER)
                : role.form == MonsterForm.SKELETON && player.isInPowderSnow;
        // Match 26.3 ConversionTracker: exit resets affliction to -1; countdown converts only below zero.
        if (!exposed) { role.exposure = -1; role.conversion = -1; return; }
        if (role.conversion >= 0) {
            if (--role.conversion < 0) convert(player, role.form == MonsterForm.ZOMBIE ? MonsterForm.DROWNED : MonsterForm.STRAY);
        } else if (++role.exposure >= (role.form == MonsterForm.ZOMBIE ? 600 : 140)) role.conversion = 300;
    }
    static void convert(ServerPlayer player, MonsterForm form) {
        var role = ROLES.get(player.getUUID());
        if (role == null || role.form.role != form.role || role.form == form) return;
        stopActions(player); role.form = form; role.exposure = 0; role.conversion = -1;
        float health = player.getHealth();
        bind(player, role, false); player.setHealth(Math.min(health, player.getMaxHealth()));
        if (form == MonsterForm.DROWNED) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                var old = player.getInventory().getItem(i);
                boolean baby = MonsterItems.matches(player, old, "baby_egg");
                if (baby || MonsterItems.matches(player, old, "adult_egg")) {
                    var egg = MonsterCompanions.egg(player, baby, true); egg.setCount(old.getCount()); player.getInventory().setItem(i, egg);
                }
            }
            var held = player.containerMenu.getCarried();
            boolean baby = MonsterItems.matches(player, held, "baby_egg");
            if (baby || MonsterItems.matches(player, held, "adult_egg")) {
                var egg = MonsterCompanions.egg(player, baby, true); egg.setCount(held.getCount()); player.containerMenu.setCarried(egg);
            }
        }
        player.level().playSound(null, player.blockPosition(), form == MonsterForm.DROWNED
                ? SoundEvents.ZOMBIE_CONVERTED_TO_DROWNED : SoundEvents.SKELETON_CONVERTED_TO_STRAY, SoundSource.HOSTILE, 1, 1);
        player.sendSystemMessage(Component.literal(form == MonsterForm.DROWNED ? "已转化为溺尸，剩余刷怪蛋同步转化。" : "已转化为流浪者。"));
        repairLoadout(player);
    }
    public static String status(ServerPlayer player) {
        var role = ROLES.get(player.getUUID());
        if (role == null) return "monster=off";
        return "monster=" + role.form.id() + "; nativeMaxHealth=" + role.model.getMaxHealth()
            + "; nativeMovementAttribute=" + role.model.getAttributeValue(Attributes.MOVEMENT_SPEED)
            + "; playerMovementEquivalent=" + player.getAttributeValue(Attributes.MOVEMENT_SPEED)
            + "; nativeAttackDamage=" + role.model.getAttributeValue(Attributes.ATTACK_DAMAGE)
            + "; intrinsicArmor=" + player.getAttribute(Attributes.ARMOR).getBaseValue()
            + "; equipment=inventory; ordinaryItems=false; burstRemaining=" + role.burstRemaining + "; arrowsFired=" + role.arrowsFired;
    }
    public static void tick(MinecraftServer server) {
        for (var entry : List.copyOf(ROLES.entrySet())) {
            Role role = entry.getValue(); ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || !player.isAlive()) continue;
            if (!MonsterRoundRuntime.canAct(player)) {
                role.burstRemaining = 0; role.chargeTick = -1; MonsterNativeWeapons.clear(player);
                MonsterCreeper.cancelInput(player); player.stopUsingItem(); continue;
            }
            if (role.player != player || role.model == null || role.model.level() != player.level()) bind(player, role, false);
            player.setSprinting(false);
            if (player.gameMode.getGameModeForPlayer() != GameType.ADVENTURE) player.setGameMode(GameType.ADVENTURE);
            player.getFoodData().setFoodLevel(6); player.getFoodData().setSaturation(0);
            repairLoadout(player);
            // Rebinding can be requested again during one server tick; timers advance only once.
            if (role.lastBodyTick != server.getTickCount()) {
                role.lastBodyTick = server.getTickCount();
                updateEnvironment(player); MonsterNativeWeapons.tick(player);
                if (role.form == MonsterForm.CREEPER) MonsterCreeper.tick(player);
            }
            if (role.chargeTick >= 0 && (!player.isUsingItem() || !player.getItemInHand(role.bowHand).is(Items.BOW))) role.chargeTick = -1;
            if (server.getTickCount() >= role.nextAmbientTick) {
                role.model.playAmbientSound(); role.nextAmbientTick = server.getTickCount() + role.model.getAmbientSoundInterval() + player.getRandom().nextInt(80);
            }
            if (role.burstRemaining > 0 && System.nanoTime() >= role.nextBurstAt) {
                if (!heldBow(player).isEmpty()) { shoot(player, role); role.burstRemaining--; }
                else role.burstRemaining = 0;
                role.nextBurstAt = System.nanoTime() + role.millis("skeleton.burstIntervalMillis") * 1_000_000L;
            }
        }
    }
    public static void onDisconnect(ServerPlayer player) {
        var role = ROLES.get(player.getUUID()); if (role == null) return;
        stopActions(player); MonsterCompanions.clearOwner(player);
        role.disconnectedHealth = player.getHealth(); role.disconnectedSource = player;
        role.disconnectedSlot = player.getInventory().getSelectedSlot();
        player.closeContainer(); role.disconnectedInventory = copyInventory(player);
        restoreOriginal(player, role); role.player = null; role.model = null;
        MonsterAiIdentity.changed(player);
    }
    public static void onJoin(ServerPlayer player) {
        var role = ROLES.get(player.getUUID());
        if (role != null && player.isAlive()) { restoreBodyInventory(player, role); bind(player, role, false); }
    }
    private static void restoreBodyInventory(ServerPlayer player, Role role) {
        if (role.disconnectedInventory == null) return;
        for (int i = 0; i < role.disconnectedInventory.size(); i++) player.getInventory().setItem(i, role.disconnectedInventory.get(i).copy());
        player.getInventory().setSelectedSlot(role.disconnectedSlot);
        role.disconnectedInventory = null;
    }
    public static void stop(MinecraftServer server) {
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) clear(player);
        for (var role : List.copyOf(ROLES.values())) if (role.disconnectedSource != null) clear(role.disconnectedSource);
        ROLES.clear(); BURST_READY.clear();
    }
    private static void stopActions(ServerPlayer player) {
        var role = ROLES.get(player.getUUID()); if (role != null) { role.burstRemaining = 0; role.chargeTick = -1; }
        MonsterNativeWeapons.clear(player); MonsterCreeper.clear(player); player.stopUsingItem();
    }

    public static boolean attack(ServerPlayer player, Entity target) {
        dev.hardrush.lobby.LobbyRuntime.interruptReturn(player);
        var role = ROLES.get(player.getUUID());
        if (role == null || !MonsterRoundRuntime.canAct(player) || !player.isAlive() || role.form == MonsterForm.CREEPER
                || role.form.skeleton() && player.getMainHandItem().is(Items.BOW)
                || player.getMainHandItem().is(ItemTags.SPEARS) || !(target instanceof LivingEntity living)
                || !legalTarget(player.getUUID(), player.level(), target)
                || dev.hardrush.pact.DawnPactRuntime.reject(player, target)) return false;
        syncNativeContext(player, role);
        if (!role.model.isWithinMeleeAttackRange(living) || !player.hasLineOfSight(target)
                || player.level().getServer().getTickCount() < role.nextMeleeTick) return false;
        role.nextMeleeTick = player.level().getServer().getTickCount() + (int) role.value("zombie.meleeIntervalTicks");
        boolean hit = role.model.doHurtTarget(player.level(), target);
        afterNativeAttack(player);
        return hit;
    }
    public static boolean useControl(ServerPlayer player, InteractionHand hand) {
        var role = ROLES.get(player.getUUID());
        if (role == null || !player.isAlive()) return false;
        var item = player.getItemInHand(hand);
        if (MonsterItems.matches(player, item, "return")) return dev.hardrush.lobby.LobbyRuntime.useReturn(player);
        if (!MonsterRoundRuntime.canAct(player)) return false;
        dev.hardrush.lobby.LobbyRuntime.interruptReturn(player, "使用物品／技能");
        if (isEgg(player, item)) return MonsterCompanions.use(player, hand);
        if (role.form == MonsterForm.CREEPER) return MonsterCreeper.use(player, hand);
        if (MonsterItems.matches(player, item, "burst")) return burst(player);
        if (role.form.skeleton() && item.is(Items.BOW)) {
            if (MonsterRoundRuntime.casting(player) || role.burstRemaining > 0 || role.chargeTick >= 0
                    || player.level().getServer().getTickCount() < role.nextBowTick) return false;
            role.chargeTick = player.level().getServer().getTickCount(); role.bowHand = hand;
            player.startUsingItem(hand); return true;
        }
        return MonsterNativeWeapons.startUse(player, hand);
    }
    private static boolean isEgg(ServerPlayer player, ItemStack item) {
        return MonsterItems.matches(player, item, "baby_egg") || MonsterItems.matches(player, item, "adult_egg");
    }
    public static boolean performingSkill(ServerPlayer player) {
        var role = ROLES.get(player.getUUID());
        return role != null && (role.burstRemaining > 0 || role.chargeTick >= 0 || MonsterRoundRuntime.casting(player)
                || MonsterNativeWeapons.using(player));
    }
    public static boolean isReturnControl(ServerPlayer player, InteractionHand hand) {
        return isMonster(player) && MonsterItems.matches(player, player.getItemInHand(hand), "return");
    }
    public static void releaseUsingItem(ServerPlayer player) {
        var form = currentForm(player); if (form == null) return;
        if (form == MonsterForm.CREEPER) MonsterCreeper.release(player);
        else if (form.skeleton()) releaseBow(player);
        else MonsterNativeWeapons.release(player);
    }
    public static void releaseBow(ServerPlayer player) {
        var role = ROLES.get(player.getUUID()); if (role == null) return;
        int started = role.chargeTick; role.chargeTick = -1;
        boolean holding = player.getItemInHand(role.bowHand).is(Items.BOW);
        player.stopUsingItem();
        if (MonsterRoundRuntime.canAct(player) && role.form.skeleton() && holding && started >= 0 && role.burstRemaining == 0
                && player.level().getServer().getTickCount() - started >= role.value("skeleton.bowChargeTicks")) shoot(player, role, player.getItemInHand(role.bowHand));
    }
    public static boolean burst(ServerPlayer player) {
        var role = ROLES.get(player.getUUID());
        if (role == null || !MonsterRoundRuntime.canAct(player) || !player.isAlive() || !role.form.skeleton()
                || heldBow(player).isEmpty() || role.burstRemaining > 0) return false;
        dev.hardrush.lobby.LobbyRuntime.interruptReturn(player);
        var formal = MonsterRoundRuntime.cast(player, MonsterSkill.SKELETON_BURST, () -> {
            if (ROLES.get(player.getUUID()) != role || heldBow(player).isEmpty()) return false;
            startBurst(player, role); return true;
        });
        if (formal.isPresent()) return formal.orElseThrow();
        long now = System.nanoTime();
        if (now < BURST_READY.getOrDefault(player.getUUID(), 0L)) return false;
        BURST_READY.put(player.getUUID(), now + role.millis("skeleton.burstCooldownMillis") * 1_000_000L);
        startBurst(player, role); return true;
    }
    private static void startBurst(ServerPlayer player, Role role) {
        player.stopUsingItem(); role.chargeTick = -1; shoot(player, role);
        role.burstRemaining = (int) role.value("skeleton.burstCount") - 1;
        role.nextBurstAt = System.nanoTime() + role.millis("skeleton.burstIntervalMillis") * 1_000_000L;
    }
    private static ItemStack heldBow(ServerPlayer player) {
        if (player.getMainHandItem().is(Items.BOW)) return player.getMainHandItem();
        if (player.getOffhandItem().is(Items.BOW)) return player.getOffhandItem();
        // The movable burst control may occupy a hand; its bow remains in the same inventory.
        for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).is(Items.BOW)) return player.getInventory().getItem(i);
        return ItemStack.EMPTY;
    }
    public static ItemStack selectedArrow(ServerPlayer player) {
        if (player.getOffhandItem().is(ItemTags.ARROWS)) return player.getOffhandItem();
        for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).is(ItemTags.ARROWS)) return player.getInventory().getItem(i);
        return new ItemStack(Items.ARROW);
    }
    private static void shoot(ServerPlayer player, Role role) {
        shoot(player, role, heldBow(player));
    }
    private static void shoot(ServerPlayer player, Role role, ItemStack weapon) {
        if (weapon.isEmpty()) return;
        syncNativeContext(player, role);
        AbstractArrow arrow = ((MonsterSkeletonAccessor) role.model).hardrush$getArrow(selectedArrow(player), 1, weapon);
        arrow.setOwner(player); MonsterCombatRuntime.stampSource(arrow, player);
        arrow.entityTags().add(SHOT_OWNER + player.getUUID()); arrow.entityTags().add(SHOT_BINDING + role.binding);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED; arrow.setCritArrow(false);
        Vec3 aim = player.getLookAngle();
        Projectile.spawnProjectileUsingShoot(arrow, player.level(), weapon, aim.x, aim.y, aim.z, 1.6f, 14 - player.level().getDifficulty().getId() * 4);
        role.nextBowTick = player.level().getServer().getTickCount() + (player.level().getDifficulty() == net.minecraft.world.Difficulty.HARD ? 20 : 40);
        role.arrowsFired++;
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SKELETON_SHOOT, SoundSource.HOSTILE, 1, 1);
    }
    /** Retained as a rejected historical prototype entry point, never a second egg supply. */
    @Deprecated public static boolean summon(ServerPlayer player) { return false; }
    public static boolean ignite(ServerPlayer player) { return MonsterCreeper.use(player, InteractionHand.MAIN_HAND); }
    public static boolean isIgniting(ServerPlayer player) { return MonsterCreeper.isSwelling(player); }
    static void nativeCreeperBlast(Creeper creeper, UUID owner, Vec3 position, float strength) {
        var level = (ServerLevel) creeper.level();
        var calculator = new ExplosionDamageCalculator() {
            @Override public boolean shouldDamageEntity(Explosion explosion, Entity target) { return legalTarget(owner, level, target); }
            @Override public float getKnockbackMultiplier(Entity target) { return legalTarget(owner, level, target) ? super.getKnockbackMultiplier(target) : 0; }
        };
        level.explode(creeper, null, calculator, position, strength, false, Level.ExplosionInteraction.NONE);
    }
    public static UUID summonOwner(Entity entity) { return MonsterCompanions.owner(entity); }
    public static UUID summonEvent(Entity entity) { return MonsterCompanions.event(entity); }
    public static void configureSummon(Zombie child) { MonsterCompanions.configure(child); }
    public static Entity nativeArrowShooter(AbstractArrow arrow, Entity vanilla) {
        UUID owner = taggedOwner(arrow, SHOT_OWNER);
        if (owner == null || !(arrow.level() instanceof ServerLevel level)) return vanilla;
        var role = ROLES.get(owner);
        if (role != null && role.form.skeleton() && role.model != null && role.model.level() == level
                && arrow.entityTags().contains(SHOT_BINDING + role.binding)
                && MonsterCombatRuntime.source(arrow).equals(MonsterCombatRuntime.source(role.model))) return role.model;
        Mob source = EntityTypes.SKELETON.create(level, EntitySpawnReason.COMMAND);
        source.entityTags().add(NATIVE_BODY); source.setUUID(owner);
        if (vanilla != null) source.setId(vanilla.getId());
        return source;
    }
    private static UUID taggedOwner(Entity entity, String prefix) {
        if (entity == null) return null;
        for (String tag : entity.entityTags()) if (tag.startsWith(prefix)) {
            try { return UUID.fromString(tag.substring(prefix.length())); } catch (IllegalArgumentException ignored) { return null; }
        }
        return null;
    }
    public static boolean legalHumanTarget(UUID owner, ServerLevel level, Entity target) {
        return target instanceof ServerPlayer player && !isMonster(player) && legalTarget(owner, level, target);
    }
    public static boolean legalTarget(UUID owner, ServerLevel level, Entity target) {
        if (target == null || owner.equals(target.getUUID()) || target.level() != level || !target.isAlive() || target.isRemoved()
                || target.isInvulnerable() || ProtectionRuntime.protectedEntity(target) || isNativeBody(target)) return false;
        if (target instanceof ServerPlayer player)
            return !player.isSpectator() && !player.getAbilities().invulnerable
                && dev.hardrush.lobby.LobbyRuntime.targetAllowed(owner, level, target);
        return target instanceof Mob && !(target instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon);
    }
    private static boolean allowDamage(LivingEntity target, DamageSource source, float amount) {
        if (target instanceof ServerPlayer player && currentForm(player) == MonsterForm.CREEPER) {
            MonsterInventory.repairArmor(player);
            ((MonsterLivingAccessor) player).hardrush$detectEquipmentUpdates();
        }
        Entity direct = source.getDirectEntity(), causing = source.getEntity();
        if (MonsterCombatRuntime.source(direct).isPresent()) return !MonsterCombatRuntime.reject(direct, target);
        if (direct instanceof net.minecraft.world.entity.AreaEffectCloud && causing != null && isMonster(causing))
            return legalTarget(causing.getUUID(), (ServerLevel) target.level(), target);
        if (direct instanceof Projectile && MonsterCombatRuntime.source(direct).isEmpty()
                && dev.hardrush.combat.CombatRuntime.originOf(direct).filter(o -> o.actor().identity().isActiveHuman()).isPresent()) return true;
        if (summonOwner(causing) != null) return MonsterCompanions.allowedTarget(causing, target);
        UUID owner = taggedOwner(direct, SHOT_OWNER);
        if (owner == null && causing != null && !(causing instanceof Player) && ROLES.containsKey(causing.getUUID())) owner = causing.getUUID();
        if (owner != null) return legalTarget(owner, (ServerLevel) target.level(), target);
        return !(causing instanceof ServerPlayer player && isMonster(player));
    }

    private static void bind(ServerPlayer player, Role role, boolean heal) {
        float resumedHealth = role.player == null ? role.disconnectedHealth : Float.NaN;
        role.player = player; role.disconnectedSource = null;
        role.model = role.form.type.create(player.level(), EntitySpawnReason.COMMAND);
        if (role.model == null) throw new IllegalStateException("Native monster template unavailable");
        role.model.setUUID(player.getUUID()); role.model.setId(player.getId()); role.model.entityTags().add(NATIVE_BODY);
        String species = role.form.role.name().toLowerCase(Locale.ROOT);
        role.model.getAttribute(Attributes.MAX_HEALTH).setBaseValue(role.value(species + ".maxHealth"));
        role.model.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(role.value(species + ".movementAttribute"));
        role.model.getAttribute(Attributes.ARMOR).setBaseValue(role.value(species + ".armor"));
        if (role.form.zombie()) role.model.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(role.value("zombie.baseMeleeDamage"));
        MonsterCombatRuntime.stampSource(role.model, player);
        role.gear.bind(player, role.model, role.form == MonsterForm.CREEPER);
        role.nextAmbientTick = player.level().getServer().getTickCount() + 80;
        player.stopUsingItem(); player.stopRiding(); player.setSprinting(false); player.setGameMode(GameType.ADVENTURE);
        for (var attribute : CONTROLLED) {
            var instance = player.getAttribute(attribute);
            if (attribute.equals(Attributes.MOVEMENT_SPEED)) {
                double speed = role.model.getAttribute(attribute).getBaseValue(); instance.setBaseValue(speed * speed);
            } else if (attribute.equals(Attributes.ATTACK_SPEED)) instance.setBaseValue(1);
            else if (role.model.getAttributes().hasAttribute(attribute)) instance.setBaseValue(role.model.getAttribute(attribute).getBaseValue());
        }
        if (heal) player.setHealth(player.getMaxHealth());
        else if (!Float.isNaN(resumedHealth)) player.setHealth(Math.min(resumedHealth, player.getMaxHealth()));
        role.disconnectedHealth = Float.NaN;
        player.getFoodData().setFoodLevel(6); player.getFoodData().setSaturation(0);
        MonsterDisguise.apply(player, role.form.id());
        if (role.model instanceof Creeper creeper) MonsterCreeper.bindModel(player, creeper);
        player.level().getServer().getPlayerList().sendAllPlayerInfo(player); MonsterAiIdentity.changed(player);
    }
    public static void repairLoadout(ServerPlayer player) {
        var role = ROLES.get(player.getUUID()); if (role == null || role.model == null) return;
        MonsterInventory.repairArmor(player);
        boolean changed = role.gear.mirror(player, role.model, role.form == MonsterForm.CREEPER);
        ((MonsterLivingAccessor) player).hardrush$detectEquipmentUpdates();
        var speed = player.getAttribute(Attributes.ATTACK_SPEED);
        double offset = player.getMainHandItem().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
            net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY).compute(Attributes.ATTACK_SPEED, 0, EquipmentSlot.MAINHAND);
        speed.setBaseValue(MonsterNativeWeapons.attackSpeed(player) - offset);
        if (changed) MonsterDisguise.refreshEquipment(player);
        player.inventoryMenu.broadcastChanges();
    }
    public static ItemStack named(Item item, String name, String description) {
        var stack = new ItemStack(item); stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(description)))); return stack;
    }
    private static List<ItemStack> copyInventory(ServerPlayer player) {
        var result = new ArrayList<ItemStack>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) result.add(player.getInventory().getItem(i).copy());
        return result;
    }
    private static void requireThread(ServerPlayer player) {
        if (!player.level().getServer().isSameThread()) throw new IllegalStateException("Monster roles require server thread");
    }
    private static final class Role {
        final MonsterEquipment gear = new MonsterEquipment();
        MonsterForm form;
        String binding = "";
        ServerPlayer player, disconnectedSource;
        Mob model;
        final List<ItemStack> inventory, crafting = new ArrayList<>();
        List<ItemStack> disconnectedInventory;
        final ItemStack carried;
        final Map<Holder<Attribute>, Double> originalAttributes = new HashMap<>();
        final GameType gameMode;
        final int selectedSlot, food;
        final float health, saturation;
        float disconnectedHealth = Float.NaN;
        final RulesConfig rules;
        long nextBurstAt, lastBodyTick = Long.MIN_VALUE;
        int nextMeleeTick, nextBowTick, chargeTick = -1, burstRemaining, arrowsFired, nextAmbientTick, exposure, conversion = -1, disconnectedSlot;
        InteractionHand bowHand = InteractionHand.MAIN_HAND;
        boolean settled;
        Role(ServerPlayer player) {
            rules = MonsterRoundRuntime.rules(player).orElse(DEFAULT_RULES);
            inventory = copyInventory(player);
            for (var attribute : CONTROLLED) originalAttributes.put(attribute, player.getAttribute(attribute).getBaseValue());
            for (int i = 1; i <= 4; i++) crafting.add(player.inventoryMenu.slots.get(i).getItem().copy());
            carried = player.containerMenu.getCarried().copy(); gameMode = player.gameMode.getGameModeForPlayer();
            selectedSlot = player.getInventory().getSelectedSlot(); food = player.getFoodData().getFoodLevel();
            health = player.getHealth(); saturation = player.getFoodData().getSaturationLevel();
        }
        double value(String key) { return rules.combatValues().getOrDefault(key, DEFAULT_RULES.combatValues().get(key)); }
        long millis(String key) { return (long) value(key); }
    }
}
