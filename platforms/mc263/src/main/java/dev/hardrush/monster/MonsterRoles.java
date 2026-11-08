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
    // Player forms take about five seconds; ordinary mobs and companions keep vanilla timing.
    private static final int ENVIRONMENT_EXPOSURE_TICKS = 50;
    private static final int ENVIRONMENT_CONVERSION_TICKS = 50;
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
        AttackBlockCallback.EVENT.register((p, l, h, pos, face) -> {
            if (!isMonster(p)) return InteractionResult.PASS;
            if (p instanceof ServerPlayer owner && MonsterDoorBreaking.acceptsAttack(owner, pos)) return InteractionResult.PASS;
            if (p instanceof ServerPlayer owner) MonsterDoorBreaking.clear(owner);
            return InteractionResult.FAIL;
        });
        UseBlockCallback.EVENT.register((p, l, hand, hit) -> {
            if (!(p instanceof ServerPlayer owner) || !isMonster(owner)) return InteractionResult.PASS;
            MonsterDoorBreaking.useBlock(owner, hit.getBlockPos());
            if (MonsterSoulcatcher.holds(owner, hand)) {
                markBagUse(owner); MonsterSoulcatcher.releaseOn(owner, hand, hit); return InteractionResult.CONSUME;
            }
            if (isEgg(owner, owner.getItemInHand(hand))) {
                MonsterCompanions.useOn(owner, hand, hit); return InteractionResult.CONSUME;
            }
            if (useControl(owner, hand)) return InteractionResult.CONSUME;
            // Beyond its own controls, a native monster opens no container and operates no block.
            return InteractionResult.FAIL;
        });
        UseEntityCallback.EVENT.register((p, l, hand, target, hit) -> {
            if (!(p instanceof ServerPlayer owner) || !isMonster(owner)) return InteractionResult.PASS;
            MonsterDoorBreaking.clear(owner);
            if (MonsterSoulcatcher.holds(owner, hand)) {
                // The native client sends a positioned and a plain interaction for one click; only the plain one acts.
                markBagUse(owner);
                if (hit == null) MonsterSoulcatcher.capture(owner, hand, target);
                return InteractionResult.CONSUME;
            }
            if (useControl(owner, hand)) return InteractionResult.CONSUME;
            return InteractionResult.FAIL;
        });
        UseItemCallback.EVENT.register((p, l, hand) -> {
            if (!(p instanceof ServerPlayer owner) || !isMonster(owner)) return InteractionResult.PASS;
            MonsterDoorBreaking.clear(owner);
            if (MonsterSoulcatcher.holds(owner, hand)) {
                // A click already spent on a creature or block also reaches here as an air use; it must not release.
                if (!recentBagUse(owner)) MonsterSoulcatcher.releaseInLiquid(owner, hand);
                return InteractionResult.CONSUME;
            }
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
        var parked = MonsterParking.decode(player, MonsterRoundRuntime.parkedBody(player), form.role).orElse(null);
        if (parked != null) role.form = parked.form();
        bind(player, role, true);
        var kit = MonsterRoundRuntime.sortieKit(player);
        if (parked != null) {
            resume(player, role, parked, kit);
            return "已继续上次暂存的出场。" + status(player);
        }
        // Vault gear takes its slot; the newest piece is worn and the rest are spares in the backpack.
        var bought = new java.util.EnumMap<EquipmentSlot, ItemStack>(EquipmentSlot.class);
        var spares = new ArrayList<ItemStack>();
        var pieces = new ArrayList<>(kit.activeGear());
        pieces.sort(java.util.Comparator.comparingInt(dev.hardrush.core.round.MonsterWallet.GearPiece::serial).reversed());
        for (var piece : pieces) {
            var stack = MonsterGacha.decode(player.level().registryAccess(), piece.item());
            if (stack.isEmpty()) continue;
            var slot = MonsterGacha.equipmentSlot(piece.slot());
            if (bought.containsKey(slot)) spares.add(stack); else bought.put(slot, stack);
        }
        if (form != MonsterForm.CREEPER) {
            for (var entry : MonsterSpawnEquipment.generate(player, role.model, form.skeleton()).entrySet())
                if (!entry.getValue().isEmpty() && !bought.containsKey(entry.getKey()))
                    player.setItemSlot(entry.getKey(), MonsterItems.bind(player, entry.getValue(), "gear"));
            bought.forEach((slot, stack) -> player.setItemSlot(slot, MonsterItems.bind(player, stack, "gear")));
        }
        if (form.zombie()) {
            player.getInventory().setItem(1, MonsterCompanions.egg(player, true, false)
                    .copyWithCount(1 + kit.carried(dev.hardrush.core.round.ShopOffer.BABY_EGG)));
            player.getInventory().setItem(2, MonsterCompanions.egg(player, false, false)
                    .copyWithCount(1 + kit.carried(dev.hardrush.core.round.ShopOffer.ADULT_EGG)));
            if (MonsterSoulcatcher.enabled(player)) player.getInventory().setItem(3, MonsterSoulcatcher.bag(player));
        } else if (form == MonsterForm.CREEPER) player.getInventory().setItem(0, MonsterCreeper.createIgniter(player));
        // A skeleton's volley lives on its own sortie bow; MonsterBowDisplay explains it on that bow itself.
        player.getInventory().setItem(7, MonsterItems.bind(player,
            named(Items.ECHO_SHARD, "归返营地 · 右键引导 " + channelSeconds(role) + " 秒",
                "按方向键、跳跃、攻击、使用其他物品或受伤会中断", "被水流推动不会中断；出生物资、买来的增益与身价留到下次出场，转化形态不保留"), "return"));
        player.getInventory().setItem(6, MonsterItems.bind(player, named(Items.RECOVERY_COMPASS, "追迹罗盘 · 右键引导 " + channelSeconds(role) + " 秒",
            "目标离你 40 格以上持续 10 秒，或去了其他维度时发光可用", "循着目标足迹出现在它附近，可跨维度；冷却 15 秒"),
            dev.hardrush.hunt.HuntRetracks.PURPOSE));
        player.getInventory().setSelectedSlot(0);
        applyPurchases(player, kit, form, spares);
        repairLoadout(player);
        return "已进入怪物角色；可整理背包与装备，出生物资仅限本次出场本人使用。" + status(player);
    }

    /**
     * A paused sortie resumes in the parked body: the same bound items in the same slots, rebound to
     * this deployment, its form, charge and the camp body's health. Goods bought during the pause
     * join it; new vault gear waits in the backpack instead of replacing what is worn.
     */
    private static void resume(ServerPlayer player, Role role, MonsterParking.Body body, dev.hardrush.core.round.MonsterWallet kit) {
        var inventory = player.getInventory();
        var leftovers = new ArrayList<ItemStack>();
        for (var entry : body.items().entrySet()) {
            var purpose = MonsterItems.purpose(entry.getValue());
            boolean egg = purpose.equals("baby_egg") || purpose.equals("adult_egg");
            // A body always resumes in its base form, so a drowned's eggs come back as zombie eggs.
            var stack = egg ? MonsterCompanions.egg(player, purpose.equals("baby_egg"), role.form == MonsterForm.DROWNED)
                    .copyWithCount(entry.getValue().getCount()) : MonsterItems.bind(player, entry.getValue(), purpose);
            int slot = entry.getKey();
            if (slot >= 0 && slot < inventory.getContainerSize() && inventory.getItem(slot).isEmpty()) inventory.setItem(slot, stack);
            else leftovers.add(stack);
        }
        for (var stack : leftovers) inventory.add(stack);
        if (role.form.zombie()) refreshSoulcatcher(player);
        if (body.selected() >= 0 && body.selected() < 9) inventory.setSelectedSlot(body.selected());
        if (body.charged()) MonsterCreeper.charge(player);
        for (var effect : body.effects()) player.addEffect(effect);
        player.setHealth(Math.max(1, Math.min(role.health, player.getMaxHealth())));
        if (role.form.zombie()) {
            addEggs(player, role, true, kit.carried(dev.hardrush.core.round.ShopOffer.BABY_EGG));
            addEggs(player, role, false, kit.carried(dev.hardrush.core.round.ShopOffer.ADULT_EGG));
        }
        var spares = new ArrayList<ItemStack>();
        for (var piece : kit.activeGear()) {
            var stack = MonsterGacha.decode(player.level().registryAccess(), piece.item());
            if (!stack.isEmpty()) spares.add(stack);
        }
        applyPurchases(player, kit, role.form, spares);
        repairLoadout(player);
    }

    /** The parked bag takes this deployment's tier and capacity; a body parked before the soulcatcher receives one. */
    private static void refreshSoulcatcher(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (MonsterItems.matches(player, inventory.getItem(i), MonsterSoulcatcher.PURPOSE)) {
                inventory.setItem(i, MonsterSoulcatcher.refresh(player, inventory.getItem(i))); return;
            }
        }
        if (MonsterSoulcatcher.enabled(player)) inventory.add(MonsterSoulcatcher.bag(player));
    }

    /** A zombie hatched from the role's own egg folds back into that egg; it never takes a soulcatcher place. */
    static boolean returnEgg(ServerPlayer player, boolean baby) {
        var role = ROLES.get(player.getUUID());
        if (role == null) return false;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (MonsterItems.matches(player, stack, baby ? "baby_egg" : "adult_egg") && stack.getCount() < stack.getMaxStackSize()) {
                stack.grow(1); return true;
            }
        }
        return inventory.add(MonsterCompanions.egg(player, baby, role.form == MonsterForm.DROWNED));
    }

    private static final java.util.WeakHashMap<ServerPlayer, Integer> BAG_USE = new java.util.WeakHashMap<>();
    private static void markBagUse(ServerPlayer player) { BAG_USE.put(player, player.level().getServer().getTickCount()); }
    private static boolean recentBagUse(ServerPlayer player) {
        Integer tick = BAG_USE.get(player);
        return tick != null && player.level().getServer().getTickCount() - tick <= 1;
    }

    private static long channelSeconds(Role role) { return Math.max(1, (role.millis("monster.returnChannelMillis") + 999) / 1000); }

    private static void addEggs(ServerPlayer player, Role role, boolean baby, int count) {
        if (count <= 0) return;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (MonsterItems.matches(player, stack, baby ? "baby_egg" : "adult_egg")) { stack.grow(count); return; }
        }
        inventory.add(MonsterCompanions.egg(player, baby, role.form == MonsterForm.DROWNED).copyWithCount(count));
    }

    /** Witch goods paid in the camp; everything left over is cleared with the rest of the sortie kit. */
    private static void applyPurchases(ServerPlayer player, dev.hardrush.core.round.MonsterWallet kit, MonsterForm form,
                                       List<ItemStack> spares) {
        var rules = MonsterRoundRuntime.rules(player).orElse(DEFAULT_RULES);
        if (!dev.hardrush.core.round.MonsterCoins.enabled(rules)) return;
        if (form != MonsterForm.CREEPER) for (var spare : spares) player.getInventory().add(MonsterItems.bind(player, spare, "gear"));
        int arrows = dev.hardrush.core.round.MonsterCoins.amount(rules, dev.hardrush.core.round.MonsterCoins.ARROWS_PER_BUNDLE);
        for (var entry : java.util.Map.of(dev.hardrush.core.round.ShopOffer.SLOWNESS_ARROWS, net.minecraft.world.item.alchemy.Potions.SLOWNESS,
                dev.hardrush.core.round.ShopOffer.POISON_ARROWS, net.minecraft.world.item.alchemy.Potions.POISON).entrySet()) {
            int count = kit.carried(entry.getKey()) * arrows;
            if (count > 0) player.getInventory().add(MonsterItems.bind(player, net.minecraft.world.item.alchemy.PotionContents
                    .createItemStack(Items.TIPPED_ARROW, entry.getValue()).copyWithCount(count), "shop_arrow"));
        }
        effect(player, kit, dev.hardrush.core.round.ShopOffer.SWIFTNESS, net.minecraft.world.effect.MobEffects.SPEED,
                rules, dev.hardrush.core.round.MonsterCoins.SWIFTNESS_SECONDS);
        effect(player, kit, dev.hardrush.core.round.ShopOffer.FIRE_RESISTANCE, net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE,
                rules, dev.hardrush.core.round.MonsterCoins.FIRE_RESISTANCE_SECONDS);
        effect(player, kit, dev.hardrush.core.round.ShopOffer.REGENERATION, net.minecraft.world.effect.MobEffects.REGENERATION,
                rules, dev.hardrush.core.round.MonsterCoins.REGENERATION_SECONDS);
        if (form == MonsterForm.CREEPER && kit.carried(dev.hardrush.core.round.ShopOffer.CHARGED) > 0) MonsterCreeper.charge(player);
    }

    private static void effect(ServerPlayer player, dev.hardrush.core.round.MonsterWallet kit, dev.hardrush.core.round.ShopOffer offer,
                               net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect, RulesConfig rules, String seconds) {
        if (kit.carried(offer) <= 0) return;
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(effect,
                dev.hardrush.core.round.MonsterCoins.amount(rules, seconds) * 20));
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
        MonsterDisguise.clear(player); MonsterBowDisplay.clear(player);
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
        MonsterDisguise.clear(player); MonsterBowDisplay.clear(player);
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
    /** A slain monster first rolls its worn sortie equipment like a native mob, then settles world items. */
    public static void dropRoleEquipment(ServerPlayer player, net.minecraft.world.damagesource.DamageSource source) {
        var role = ROLES.get(player.getUUID());
        if (role == null) return;
        if (!role.settled) dropWornEquipment(player, source, player.getRandom());
        settleInventory(player, role, true);
    }
    /** Native getLastHurtByPlayer() memory, except a monster that last hurt itself was not slain by a player. */
    public static boolean recentlyHurtByOtherPlayer(ServerPlayer player) {
        var attacker = player.getLastHurtByPlayer();
        return attacker != null && !attacker.getUUID().equals(player.getUUID());
    }
    /** Mob.dropCustomDeathLoot for spawned equipment: 8.5% per worn slot, looting adds, only for a player kill. */
    static void dropWornEquipment(ServerPlayer player, net.minecraft.world.damagesource.DamageSource source,
                                  net.minecraft.util.RandomSource random) {
        if (!recentlyHurtByOtherPlayer(player)) return;
        for (var slot : EquipmentSlot.VALUES) {
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR && slot.getType() != EquipmentSlot.Type.HAND) continue;
            var stack = player.getItemBySlot(slot);
            if (stack.isEmpty() || !MonsterItems.matches(player, stack, "gear") && !MonsterItems.matches(player, stack, SEA_TRIDENT)
                    || EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) continue;
            float chance = SPAWNED_EQUIPMENT_DROP_CHANCE;
            if (source.getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker)
                chance = EnchantmentHelper.processEquipmentDropChance(player.level(), attacker, source, chance);
            if (random.nextFloat() >= chance) continue;
            var dropped = MonsterItems.unbind(stack.copy());
            if (dropped.isDamageableItem()) dropped.setDamageValue(dropped.getMaxDamage()
                    - random.nextInt(1 + random.nextInt(Math.max(dropped.getMaxDamage() - 3, 1))));
            player.spawnAtLocation(player.level(), dropped);
            player.setItemSlot(slot, ItemStack.EMPTY);
        }
    }
    /** Mob's default drop chance for equipment it spawned with. */
    static final float SPAWNED_EQUIPMENT_DROP_CHANCE = 0.085F;
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
        // Keep 26.3 ConversionTracker boundaries, with shorter player-only phase durations.
        if (!exposed) { role.exposure = -1; role.conversion = -1; return; }
        if (role.conversion >= 0) {
            if (--role.conversion < 0) convert(player, role.form == MonsterForm.ZOMBIE ? MonsterForm.DROWNED : MonsterForm.STRAY);
        } else if (++role.exposure >= ENVIRONMENT_EXPOSURE_TICKS) role.conversion = ENVIRONMENT_CONVERSION_TICKS;
    }
    /** Purpose of the trident a zombie receives when it rises from the sea as a drowned; never parked. */
    public static final String SEA_TRIDENT = "sea_trident";

    /**
     * A zombie deployed under water rises as a drowned at once, without the conversion: it never was a land
     * zombie here. Like a native drowned, it holds a trident (thrown copies, endless; see MonsterNativeWeapons).
     */
    public static void riseFromSea(ServerPlayer player) {
        var role = ROLES.get(player.getUUID());
        if (role == null || role.form != MonsterForm.ZOMBIE) return;
        convert(player, MonsterForm.DROWNED, false);
        var inventory = player.getInventory();
        int slot = inventory.getSelectedSlot();
        var trident = MonsterItems.bind(player, new ItemStack(Items.TRIDENT), SEA_TRIDENT);
        var held = inventory.getItem(slot);
        // Into the hand when the held stack can move aside; otherwise the backpack (a full kit is never dropped).
        if (held.isEmpty()) inventory.setItem(slot, trident);
        else if (inventory.getFreeSlot() >= 0) { inventory.setItem(slot, trident); inventory.add(held); }
        else inventory.add(trident);
        repairLoadout(player);
    }
    static void convert(ServerPlayer player, MonsterForm form) { convert(player, form, true); }
    private static void convert(ServerPlayer player, MonsterForm form, boolean announce) {
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
        if (announce) {
            player.level().playSound(null, player.blockPosition(), form == MonsterForm.DROWNED
                    ? SoundEvents.ZOMBIE_CONVERTED_TO_DROWNED : SoundEvents.SKELETON_CONVERTED_TO_STRAY, SoundSource.HOSTILE, 1, 1);
            // The native conversion sound and the changed eggs and model say it; no chat line.
        }
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
            MonsterWorthRewards.showWorth(player);
            if (role.form.skeleton()) MonsterBowDisplay.update(player, role.form, burstCooldownRemaining(player),
                    role.millis("skeleton.burstCooldownMillis"));
            else MonsterBowDisplay.clear(player);
            boolean aiming = volleying(player);
            if (aiming != role.volleyPose) { role.volleyPose = aiming; MonsterDisguise.refreshMetadata(player); }
            if (!MonsterRoundRuntime.canAct(player)) {
                role.burstRemaining = 0; role.chargeTick = -1; MonsterNativeWeapons.clear(player);
                MonsterDoorBreaking.clear(player);
                MonsterCreeper.cancelInput(player); player.stopUsingItem(); continue;
            }
            if (role.player != player || role.model == null || role.model.level() != player.level()) bind(player, role, false);
            player.setSprinting(false);
            if (player.gameMode.getGameModeForPlayer() != GameType.ADVENTURE) {
                MonsterDoorBreaking.clear(player);
                player.setGameMode(GameType.ADVENTURE);
            }
            player.getFoodData().setFoodLevel(6); player.getFoodData().setSaturation(0);
            repairLoadout(player);
            // Rebinding can be requested again during one server tick; timers advance only once.
            if (role.lastBodyTick != server.getTickCount()) {
                role.lastBodyTick = server.getTickCount();
                updateEnvironment(player); MonsterNativeWeapons.tick(player);
                MonsterDoorBreaking.tick(player);
                if (role.form == MonsterForm.CREEPER) MonsterCreeper.tick(player);
            }
            if (role.chargeTick >= 0 && (!player.isUsingItem() || !player.getItemInHand(role.bowHand).is(Items.BOW))) role.chargeTick = -1;
            if (role.chargeTick >= 0 && !role.volleyCued && player.isShiftKeyDown()
                    && volleyBow(player, player.getItemInHand(role.bowHand)) && burstCooldownRemaining(player) <= 0
                    && server.getTickCount() - role.chargeTick >= role.value("skeleton.bowChargeTicks")) {
                role.volleyCued = true; MonsterBowDisplay.fullDrawCue(player);
            }
            if (server.getTickCount() >= role.nextAmbientTick) {
                role.model.playAmbientSound(); role.nextAmbientTick = server.getTickCount() + role.model.getAmbientSoundInterval() + player.getRandom().nextInt(80);
            }
            if (role.burstRemaining > 0 && System.nanoTime() >= role.nextBurstAt) {
                // The volley is loosed from the bow in hand; switching it away cancels the unfired arrows.
                var bow = player.getItemInHand(role.bowHand);
                if (volleyBow(player, bow)) { shoot(player, role, bow); role.burstRemaining--; }
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
        ROLES.clear(); BURST_READY.clear(); MonsterBowDisplay.clearAll();
    }
    private static void stopActions(ServerPlayer player) {
        var role = ROLES.get(player.getUUID()); if (role != null) { role.burstRemaining = 0; role.chargeTick = -1; }
        MonsterDoorBreaking.clear(player);
        MonsterNativeWeapons.clear(player); MonsterCreeper.clear(player); player.stopUsingItem();
    }

    public static boolean attack(ServerPlayer player, Entity target) {
        MonsterDoorBreaking.clear(player);
        dev.hardrush.lobby.LobbyRuntime.interruptReturn(player, "攻击");
        var role = ROLES.get(player.getUUID());
        if (role == null || !MonsterRoundRuntime.canAct(player) || !player.isAlive() || role.form == MonsterForm.CREEPER
                || role.form.skeleton() && player.getMainHandItem().is(Items.BOW)
                || player.getMainHandItem().is(ItemTags.SPEARS) || !(target instanceof LivingEntity living)
                || !legalTarget(player.getUUID(), player.level(), target)
                || dev.hardrush.pact.DuskPactRuntime.reject(player, target)) return false;
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
        MonsterDoorBreaking.clear(player);
        var item = player.getItemInHand(hand);
        if (MonsterItems.matches(player, item, "return")) return dev.hardrush.lobby.LobbyRuntime.useReturn(player);
        if (MonsterItems.matches(player, item, dev.hardrush.hunt.HuntRetracks.PURPOSE)) return dev.hardrush.lobby.LobbyRuntime.useRetrack(player, item);
        if (!MonsterRoundRuntime.canAct(player)) return false;
        dev.hardrush.lobby.LobbyRuntime.interruptReturn(player, "使用物品");
        if (isEgg(player, item)) return MonsterCompanions.use(player, hand);
        if (role.form == MonsterForm.CREEPER) return MonsterCreeper.use(player, hand);
        if (role.form.skeleton() && item.is(Items.BOW)) {
            if (MonsterRoundRuntime.casting(player) || role.burstRemaining > 0 || role.chargeTick >= 0
                    || player.level().getServer().getTickCount() < role.nextBowTick) return false;
            role.chargeTick = player.level().getServer().getTickCount(); role.bowHand = hand; role.volleyCued = false;
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
        var item = player.getItemInHand(hand);
        return isMonster(player) && (MonsterItems.matches(player, item, "return") || MonsterItems.matches(player, item, dev.hardrush.hunt.HuntRetracks.PURPOSE));
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
        if (!MonsterRoundRuntime.canAct(player) || !role.form.skeleton() || !holding || started < 0 || role.burstRemaining > 0
                || player.level().getServer().getTickCount() - started < role.value("skeleton.bowChargeTicks")) return;
        // Sneak is read at release: crouching mid-draw still looses the volley; a cooling volley or a
        // picked-up world bow falls back to one arrow.
        if (player.isShiftKeyDown()) {
            if (!volleyBow(player, player.getItemInHand(role.bowHand))) {
                volleyHint(player, "只有" + MonsterBowDisplay.bowName(role.form) + "能三连射");
            } else {
                if (burst(player, role.bowHand)) return;
                long remaining = burstCooldownRemaining(player);
                if (remaining > 0) volleyHint(player, "三连射冷却中 · 剩余 " + (remaining + 999) / 1000 + " 秒");
            }
        }
        shoot(player, role, player.getItemInHand(role.bowHand));
    }
    /** Remaining volley cooldown from the round record, the celebration memory or standalone tooling. */
    static long burstCooldownRemaining(ServerPlayer player) {
        var formal = MonsterRoundRuntime.skillRemaining(player, MonsterSkill.SKELETON_BURST);
        if (formal.isPresent()) return formal.getAsLong();
        Long ready = BURST_READY.get(player.getUUID());
        return ready == null ? 0 : Math.max(0, (ready - System.nanoTime() + 999_999) / 1_000_000);
    }
    /** The native body keeps its bow raised from release until the volley's last arrow. */
    public static boolean volleying(ServerPlayer player) {
        var role = ROLES.get(player.getUUID());
        return role != null && role.form.skeleton() && (role.burstRemaining > 0 || MonsterRoundRuntime.casting(player));
    }
    private static void volleyHint(ServerPlayer player, String text) {
        dev.hardrush.lobby.LobbyInteractions.get(player.level().getServer()).ifPresent(router -> router.hints(player)
                .offer("volley", dev.hardrush.core.lobby.LobbyHints.Priority.PROGRESS, text, System.nanoTime() / 1_000_000 + 1500));
    }
    /**
     * The volley belongs to the sortie's own bow (born or bought). A bow picked up in the world shoots
     * single arrows, as it would for a native skeleton, and keeps its own name.
     */
    public static boolean volleyBow(ServerPlayer player, ItemStack stack) {
        return stack.is(Items.BOW) && MonsterItems.owned(player, stack);
    }
    /** Looses the volley from whichever hand holds the sortie bow, main hand first. */
    public static boolean burst(ServerPlayer player) {
        if (volleyBow(player, player.getMainHandItem())) return burst(player, InteractionHand.MAIN_HAND);
        return volleyBow(player, player.getOffhandItem()) && burst(player, InteractionHand.OFF_HAND);
    }
    private static boolean burst(ServerPlayer player, InteractionHand hand) {
        var role = ROLES.get(player.getUUID());
        if (role == null || !MonsterRoundRuntime.canAct(player) || !player.isAlive() || !role.form.skeleton()
                || !volleyBow(player, player.getItemInHand(hand)) || role.burstRemaining > 0) return false;
        dev.hardrush.lobby.LobbyRuntime.interruptReturn(player, "施放技能");
        var formal = MonsterRoundRuntime.cast(player, MonsterSkill.SKELETON_BURST, () -> {
            if (ROLES.get(player.getUUID()) != role || !volleyBow(player, player.getItemInHand(hand))) return false;
            startBurst(player, role, hand); return true;
        });
        if (formal.isPresent()) return formal.orElseThrow();
        long now = System.nanoTime();
        if (now < BURST_READY.getOrDefault(player.getUUID(), 0L)) return false;
        BURST_READY.put(player.getUUID(), now + role.millis("skeleton.burstCooldownMillis") * 1_000_000L);
        startBurst(player, role, hand); return true;
    }
    private static void startBurst(ServerPlayer player, Role role, InteractionHand hand) {
        player.stopUsingItem(); role.chargeTick = -1; role.bowHand = hand;
        shoot(player, role, player.getItemInHand(hand));
        role.burstRemaining = (int) role.value("skeleton.burstCount") - 1;
        role.nextBurstAt = System.nanoTime() + role.millis("skeleton.burstIntervalMillis") * 1_000_000L;
    }
    public static ItemStack selectedArrow(ServerPlayer player) {
        if (player.getOffhandItem().is(ItemTags.ARROWS)) return player.getOffhandItem();
        for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).is(ItemTags.ARROWS)) return player.getInventory().getItem(i);
        return new ItemStack(Items.ARROW);
    }
    private static void shoot(ServerPlayer player, Role role, ItemStack weapon) {
        if (weapon.isEmpty()) return;
        syncNativeContext(player, role);
        var ammunition = selectedArrow(player);
        AbstractArrow arrow = ((MonsterSkeletonAccessor) role.model).hardrush$getArrow(ammunition, 1, weapon);
        // Bought tipped arrows are real ammunition; any other arrow stays an unlimited native template.
        if (MonsterItems.matches(player, ammunition, "shop_arrow")) ammunition.shrink(1);
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
        Mob source = EntityTypes.SKELETON.create(level, new EntitySpawnRequest(EntitySpawnReason.COMMAND, true));
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
        MonsterDoorBreaking.clear(player);
        float resumedHealth = role.player == null ? role.disconnectedHealth : Float.NaN;
        role.player = player; role.disconnectedSource = null;
        // Unregistered template: peaceful difficulty must not veto a body that never spawns.
        role.model = role.form.type.create(player.level(), new EntitySpawnRequest(EntitySpawnReason.COMMAND, true));
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
    public static ItemStack named(Item item, String name, String... description) {
        var stack = new ItemStack(item); stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        stack.set(DataComponents.LORE, new ItemLore(java.util.Arrays.stream(description).map(line -> (Component) Component.literal(line)).toList()));
        return stack;
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
        boolean settled, volleyCued, volleyPose;
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
