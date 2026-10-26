package dev.hardrush.p0.monster;

import dev.hardrush.p0.monster.mixin.MonsterWeaponEntityAccessor;
import dev.hardrush.p1.combat.CombatOriginHolder;
import dev.hardrush.p1.combat.P1CombatRuntime;
import dev.hardrush.p2.combat.MonsterSourceHolder;
import dev.hardrush.p2.combat.P2CombatRuntime;
import dev.hardrush.p2.monster.P2MonsterRuntime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/** Vanilla inputs with the native mob's range, motion thresholds, damage source and enchantments. */
public final class MonsterNativeWeapons {
    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final String TRIDENT_OWNER = "hardrush.trident-owner=";

    private MonsterNativeWeapons() {}

    public static boolean startUse(ServerPlayer player, InteractionHand hand) {
        Mob model = usableModel(player);
        if (model == null) return false;
        sync(player, model);
        ItemStack weapon = model.getItemInHand(hand);
        boolean spear = spear(model, weapon);
        boolean trident = model instanceof Drowned && weapon.is(Items.TRIDENT);
        if (!spear && !trident) return false;
        State state = state(player);
        if (state.model != null) {
            if (state.model == model && state.hand == hand && holding(state) && player.isUsingItem()) return true;
            stop(state);
        }
        if (trident && now(player) < state.nextTridentTick) return false;
        state.model = model;
        state.weapon = weapon;
        state.hand = hand;
        state.slot = player.getInventory().getSelectedSlot();
        state.held = player.getItemInHand(hand).copy();
        state.started = now(player);
        state.lastTick = Long.MIN_VALUE;
        player.startUsingItem(hand);
        model.startUsingItem(hand);
        if (spear) weapon.get(DataComponents.KINETIC_WEAPON).makeSound(player);
        return true;
    }

    public static void release(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        if (state == null || state.player != player || state.model == null) return;
        Mob model = state.model;
        boolean throwTrident = usableModel(player) == model && model instanceof Drowned
                && holding(state) && state.weapon.is(Items.TRIDENT) && now(player) - state.started >= 10
                && now(player) >= state.nextTridentTick;
        ItemStack weapon = state.held.copy();
        stop(state);
        if (!throwTrident) return;
        state.nextTridentTick = now(player) + 40;
        sync(player, model);
        var projectileWeapon = copyForProjectile(weapon);
        var trident = new ThrownTrident(player.level(), model, projectileWeapon);
        // Capture the native source first, then use the actual player for owner collision exclusion.
        trident.setOwner(player);
        trident.entityTags().add(TRIDENT_OWNER + player.getUUID());
        trident.entityTags().add("hardrush.shot-owner=" + player.getUUID());
        trident.entityTags().add("hardrush.shot-binding=" + MonsterRoles.binding(player));
        // Mob projectiles are copies: loyalty and pickup must never turn them into transferable gear.
        trident.pickup = AbstractArrow.Pickup.DISALLOWED;
        copySource(model, trident);
        var aim = player.getLookAngle();
        try (var ignored = P2CombatRuntime.action(model)) {
            Projectile.spawnProjectileUsingShoot(trident, player.level(), projectileWeapon,
                    aim.x, aim.y, aim.z, 1.6F, ((Drowned) model).rangedAttackUncertainty(player.level()));
        }
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.DROWNED_SHOOT, SoundSource.HOSTILE, 1F,
                1F / (player.getRandom().nextFloat() * 0.4F + 0.8F));
        MonsterRoles.afterNativeAttack(player);
    }

    /** Called once per server tick; never runs AI or movement on the native template. */
    public static void tick(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        if (state == null || state.player != player || state.model == null) return;
        Mob model = state.model;
        if (usableModel(player) != model || !holding(state) || !player.isUsingItem()) {
            stop(state);
            return;
        }
        long tick = now(player);
        if (state.lastTick == tick || !spear(model, state.weapon)) return;
        state.lastTick = tick;
        sync(player, model);
        state.weapon = model.getItemInHand(state.hand);
        int remaining = state.weapon.getUseDuration(model) - (int) Math.min(Integer.MAX_VALUE, tick - state.started);
        try (var batch = P1CombatRuntime.damage(player.level(), state.weapon.getDamageSource(model));
             var ignored = P2CombatRuntime.action(model)) {
            state.weapon.get(DataComponents.KINETIC_WEAPON).damageEntities(state.weapon, remaining, model,
                    state.hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
        }
        MonsterRoles.afterNativeAttack(player);
        state.held = player.getItemInHand(state.hand).copy();
    }

    /** Receives the vanilla STAB action, which does not pass through Player.attack. */
    public static boolean stabbing(ServerPlayer player) {
        return stabbing(player, EquipmentSlot.MAINHAND);
    }

    public static boolean stabbing(ServerPlayer player, EquipmentSlot slot) {
        Mob model = usableModel(player);
        if (model == null || slot != EquipmentSlot.MAINHAND && slot != EquipmentSlot.OFFHAND || player.isUsingItem()) return false;
        sync(player, model);
        var weapon = model.getItemBySlot(slot);
        if (!spear(model, weapon)) return false;
        var piercing = weapon.get(DataComponents.PIERCING_WEAPON);
        if (piercing == null) return false;
        State state = state(player);
        if (now(player) < state.nextJabTick) return false;
        state.nextJabTick = now(player) + Math.max(1, weapon.getAttackAnimation().duration());
        try (var batch = P1CombatRuntime.damage(player.level(), weapon.getDamageSource(model));
             var ignored = P2CombatRuntime.action(model)) {
            piercing.attack(model, slot);
        }
        player.resetAttackStrengthTicker();
        MonsterRoles.afterNativeAttack(player);
        return true;
    }

    /** Desired total attribute value; callers still subtract the displayed item's modifier. */
    public static double attackSpeed(ServerPlayer player) {
        Mob model = MonsterRoles.nativeModel(player);
        return model != null && spear(model, player.getMainHandItem())
                ? 20D / Math.max(1, player.getMainHandItem().getAttackAnimation().duration()) : 1D;
    }

    public static boolean using(ServerPlayer player) {
        var state = STATES.get(player.getUUID());
        return state != null && state.player == player && state.model != null && player.isUsingItem() && holding(state);
    }

    public static Entity tridentShooter(ThrownTrident trident, Entity vanilla) {
        var owner = tridentOwner(trident);
        if (owner == null || !(trident.level() instanceof ServerLevel level)) return vanilla;
        var player = level.getServer().getPlayerList().getPlayer(owner);
        var current = player == null ? null : MonsterRoles.nativeModel(player);
        if (current instanceof Drowned && current.level() == level
                && trident.entityTags().contains("hardrush.shot-binding=" + MonsterRoles.binding(player))
                && P2CombatRuntime.source(trident).equals(P2CombatRuntime.source(current))) return current;
        var model = EntityTypes.DROWNED.create(level, EntitySpawnReason.COMMAND);
        model.setUUID(owner);
        model.entityTags().add("hardrush.native-body");
        if (vanilla != null) model.setId(vanilla.getId());
        model.snapTo(vanilla == null ? trident.position() : vanilla.position());
        return model;
    }

    /** Thorns targets the native attacker; execute damage on the authoritative live player instead. */
    public static boolean hurtNativeBody(LivingEntity model, ServerLevel level, DamageSource source, float damage) {
        var player = MonsterRoles.playerForNativeBody(model);
        if (player == null || !player.isAlive() || player.level() != level) return false;
        var current = P2CombatRuntime.source(model);
        var action = P1CombatRuntime.currentOrigin(level);
        if (current.isPresent() && action.isPresent()) {
            var body = current.orElseThrow(); var origin = action.orElseThrow(); var actor = origin.actor();
            if (body.ownerId().equals(actor.playerId()) && (!body.roundId().equals(origin.roundId())
                    || body.life() != actor.lifeSequence() || body.deployment() != actor.deploymentSequence())) return false;
        }
        boolean hit = player.hurtServer(level, source, damage);
        // Do not mirror equipment here: native attack-time mutations have not been written back yet.
        model.setHealth(Math.min(player.getHealth(), model.getMaxHealth()));
        return hit;
    }

    /** A monster's real armor also reflects through its frozen native source, rather than Player damage. */
    public static DamageSource nativeRetaliationSource(DamageSource source) {
        if (!source.is(DamageTypes.THORNS) || !(source.getEntity() instanceof ServerPlayer wearer)) return source;
        var model = MonsterRoles.nativeModel(wearer);
        if (model == null || MonsterRoles.playerForNativeBody(model) != wearer) return source;
        return new DamageSource(source.typeHolder(), model, model);
    }

    public static UUID tridentOwner(ThrownTrident trident) {
        for (String tag : trident.entityTags()) if (tag.startsWith(TRIDENT_OWNER)) {
            try { return UUID.fromString(tag.substring(TRIDENT_OWNER.length())); }
            catch (IllegalArgumentException ignored) { return null; }
        }
        return null;
    }

    public static void clear(ServerPlayer player) {
        State state = STATES.remove(player.getUUID());
        if (state != null) stop(state);
    }

    static ItemStack copyForProjectile(ItemStack weapon) {
        var copy = weapon.copy();
        EnchantmentHelper.updateEnchantments(copy, enchantments -> enchantments.removeIf(enchantment ->
                enchantment.value().effects().has(EnchantmentEffectComponents.TRIDENT_RETURN_ACCELERATION)));
        return copy;
    }

    private static void copySource(Mob model, ThrownTrident projectile) {
        P2CombatRuntime.source(model).ifPresent(source -> ((MonsterSourceHolder) projectile).hardrush$stampMonsterSource(source));
        var holder = (CombatOriginHolder) projectile;
        if (!holder.hardrush$combatOriginSealed()) P1CombatRuntime.projectileCreated(projectile, model);
    }

    private static boolean spear(Mob model, ItemStack weapon) {
        return model instanceof Zombie && weapon.is(ItemTags.SPEARS) && weapon.has(DataComponents.KINETIC_WEAPON);
    }

    private static Mob usableModel(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && P2MonsterRuntime.canAct(player)
                && !P1CombatRuntime.rejectDamage(player.level())
                ? MonsterRoles.nativeModel(player) : null;
    }

    private static void sync(ServerPlayer player, Mob model) {
        MonsterRoles.syncNativeContext(player);
        ((MonsterWeaponEntityAccessor) model).hardrush$setKnownWeaponSpeed(player.getKnownSpeed());
    }

    private static long now(ServerPlayer player) { return player.level().getServer().getTickCount(); }

    private static State state(ServerPlayer player) {
        var current = STATES.get(player.getUUID());
        if (current != null && current.player != player) clear(current.player);
        return STATES.computeIfAbsent(player.getUUID(), ignored -> new State(player));
    }

    private static void stop(State state) {
        state.player.stopUsingItem();
        if (state.model != null) state.model.stopUsingItem();
        state.model = null;
        state.weapon = ItemStack.EMPTY;
    }

    private static boolean holding(State state) {
        return (state.hand != InteractionHand.MAIN_HAND || state.slot == state.player.getInventory().getSelectedSlot())
                && ItemStack.matches(state.held, state.player.getItemInHand(state.hand));
    }

    private static final class State {
        final ServerPlayer player;
        Mob model;
        ItemStack weapon = ItemStack.EMPTY;
        ItemStack held = ItemStack.EMPTY;
        InteractionHand hand = InteractionHand.MAIN_HAND;
        int slot;
        long started, nextJabTick, nextTridentTick, lastTick = Long.MIN_VALUE;
        State(ServerPlayer player) { this.player = player; }
    }
}
