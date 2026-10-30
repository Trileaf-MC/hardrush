package dev.hardrush.monster;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.disguise.MonsterDisguise;
import dev.hardrush.monster.mixin.CreeperPoweredAccessor;
import dev.hardrush.combat.CombatOriginHolder;
import dev.hardrush.combat.CombatRuntime;
import dev.hardrush.combat.MonsterSourceHolder;
import dev.hardrush.combat.MonsterCombatRuntime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.world.level.gameevent.GameEvent;

/** Player input drives the native 26.3 swell curve; the body owns charge and explosion provenance. */
public final class MonsterCreeper {
    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final RulesConfig DEFAULTS = RulesConfig.defaults();

    private MonsterCreeper() {}

    public static ItemStack createIgniter(ServerPlayer player) {
        var stack = new ItemStack(Items.GUNPOWDER);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("引爆器"));
        stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(
                Component.literal("按住右键膨胀，松开后逐渐消退"))));
        // Native clients must enter item-use mode and send RELEASE_USE_ITEM. A consumable
        // without food, effects, animation or slowdown is only the input transport.
        stack.set(DataComponents.CONSUMABLE,
                new Consumable(3600f, ItemUseAnimation.NONE, Holder.direct(SoundEvents.EMPTY), false, List.of()));
        stack.set(DataComponents.USE_EFFECTS, new UseEffects(false, false, 1f));
        MonsterItems.bind(player, stack, "igniter");
        return stack;
    }

    public static boolean use(ServerPlayer player, InteractionHand hand) {
        if (!available(player) || !MonsterItems.matches(player, player.getItemInHand(hand), "igniter")) return false;
        State state = state(player);
        if (state.exploding) return false;
        player.startUsingItem(hand);
        setDirection(player, state, 1);
        return true;
    }

    public static void release(ServerPlayer player) {
        player.stopUsingItem();
        State state = STATES.get(player.getUUID());
        if (state != null) setDirection(player, state, -1);
    }

    /** A paused body loses active input but retains its lightning charge. */
    public static void cancelInput(ServerPlayer player) {
        release(player);
        State state = STATES.get(player.getUUID());
        if (state != null) state.swell = 0;
    }

    /** Rebuilding a model in another dimension preserves the same deployed body's charge and swell. */
    public static void bindModel(ServerPlayer player, Creeper model) {
        State state = STATES.computeIfAbsent(player.getUUID(), ignored -> new State());
        state.model = model;
        model.getEntityData().set(CreeperPoweredAccessor.hardrush$poweredData(), state.charged);
        model.setSwellDir(state.direction);
        MonsterDisguise.setCreeperState(player, state.direction, state.charged);
    }

    public static void clear(ServerPlayer player) {
        State removed = STATES.remove(player.getUUID());
        if (removed == null) return;
        player.stopUsingItem();
        if (removed.model != null) removed.model.setSwellDir(-1);
        MonsterDisguise.setCreeperState(player, -1, false);
    }

    public static boolean isSwelling(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        return state != null && (state.swell > 0 || state.direction > 0);
    }

    public static void tick(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        if (state == null) return;
        if (!available(player)) {
            cancelInput(player);
            return;
        }
        if (state.exploding) return;
        boolean held = player.isUsingItem()
                && MonsterItems.matches(player, player.getUseItem(), "igniter")
                && MonsterItems.matches(player, player.getItemInHand(player.getUsedItemHand()), "igniter");
        int direction = held ? 1 : -1;
        setDirection(player, state, direction);
        if (direction > 0 && state.swell == 0) {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.CREEPER_PRIMED, SoundSource.HOSTILE, 1f, .5f);
            player.gameEvent(GameEvent.PRIME_FUSE);
        }
        state.swell = Math.max(0, state.swell + direction);
        if (state.swell >= fuse(player)) explode(player, state);
    }

    /** Called after ordinary fall damage, matching Creeper.causeFallDamage's order and cap. */
    public static void onFall(ServerPlayer player, double distance) {
        if (!available(player)) return;
        State state = state(player);
        state.swell += (int)(distance * 1.5);
        state.swell = Math.min(state.swell, Math.max(0, fuse(player) - 5));
    }

    /** Entity.thunderHit has already applied native lightning damage and fire exactly once. */
    public static void onLightning(ServerPlayer player) {
        if (!available(player)) return;
        State state = state(player);
        state.charged = true;
        state.model.getEntityData().set(CreeperPoweredAccessor.hardrush$poweredData(), true);
        MonsterDisguise.setCreeperState(player, state.direction, true);
    }

    /** A purchased charged sortie starts in the same powered state a real lightning strike leaves. */
    public static void charge(ServerPlayer player) {
        if (!(MonsterRoles.nativeModel(player) instanceof Creeper)) return;
        State state = state(player);
        state.charged = true;
        state.model.getEntityData().set(CreeperPoweredAccessor.hardrush$poweredData(), true);
        MonsterDisguise.setCreeperState(player, state.direction, true);
    }

    public static boolean isUsingIgniter(ServerPlayer player) {
        return MonsterRoles.isMonster(player) && MonsterItems.matches(player, player.getUseItem(), "igniter");
    }

    private static boolean available(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && MonsterRoundRuntime.canAct(player)
                && MonsterRoles.nativeModel(player) instanceof Creeper;
    }

    private static State state(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        Creeper model = (Creeper)MonsterRoles.nativeModel(player);
        if (state == null || state.model != model) bindModel(player, model);
        return STATES.get(player.getUUID());
    }

    private static void setDirection(ServerPlayer player, State state, int direction) {
        if (state.direction == direction) return;
        state.direction = direction;
        if (state.model != null) state.model.setSwellDir(direction);
        MonsterDisguise.setCreeperState(player, direction, state.charged);
    }

    private static int fuse(ServerPlayer player) {
        return (int)value(player, "creeper.fuseTicks");
    }

    private static double value(ServerPlayer player, String key) {
        return MonsterRoundRuntime.rules(player).orElse(DEFAULTS).combatValues()
                .getOrDefault(key, DEFAULTS.combatValues().get(key));
    }

    private static void explode(ServerPlayer player, State state) {
        state.exploding = true;
        Creeper model = state.model;
        model.snapTo(player.position());
        float strength = (float)value(player, "creeper.explosionStrength") * (state.charged ? 2f : 1f);
        // The real powered Creeper remains the native damage source, so 26.3's
        // killedEntity/charged_creeper loot table enforces its one-head budget.
        try (var batch = CombatRuntime.explosion(player.level(), model)) {
            MonsterRoles.nativeCreeperBlast(model, player.getUUID(), player.position(), strength);
            spawnCloud(player, model);
            state.swell = 0;
            release(player);
            player.hurtServer(player.level(), player.damageSources().genericKill(), Float.MAX_VALUE);
        }
    }

    private static void spawnCloud(ServerPlayer player, Creeper model) {
        if (player.getActiveEffects().isEmpty()) return;
        var cloud = new AreaEffectCloud(player.level(), player.getX(), player.getY(), player.getZ());
        cloud.setRadius(2.5f);
        cloud.setRadiusOnUse(-.5f);
        cloud.setWaitTime(10);
        cloud.setDuration(300);
        cloud.setPotionDurationScale(.25f);
        cloud.setRadiusPerTick(-cloud.getRadius() / cloud.getDuration());
        cloud.setOwner(player);
        MonsterClouds.bind(cloud, player.getUUID());
        for (var effect : player.getActiveEffects()) cloud.addEffect(new MobEffectInstance(effect));
        // Capture before killing the owner. Later life, role and connection changes must
        // not make a lingering cloud adopt a new actor or lose its original permission.
        MonsterCombatRuntime.source(model).ifPresent(source -> ((MonsterSourceHolder)cloud).hardrush$stampMonsterSource(source));
        var origin = CombatRuntime.originOf(model)
                .map(parent -> parent.child(UUID.randomUUID(), AttackKind.CREEPER, Optional.of(cloud.getUUID())));
        ((CombatOriginHolder)cloud).hardrush$sealCombatOrigin(origin);
        player.level().addFreshEntity(cloud);
    }

    static int swellTicks(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        return state == null ? 0 : state.swell;
    }

    private static final class State {
        Creeper model;
        int swell;
        int direction = -1;
        boolean charged;
        boolean exploding;
    }
}
