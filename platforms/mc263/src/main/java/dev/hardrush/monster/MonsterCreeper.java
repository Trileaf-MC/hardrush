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

/**
 * Player input drives the native 26.3 swell curve; the body owns charge and explosion provenance. The igniter is a
 * flint and steel, the native way to light a creeper: a human's flint and steel or fire charge lights a creeper player
 * the same way, and the creeper player's own lights a native creeper (docs/plan/击败锁定与怪物平衡.md §5).
 */
public final class MonsterCreeper {
    private static final Map<UUID, State> STATES = new HashMap<>();
    /** Bodies being killed by their own blast right now whose fuse a human lit, and who lit it. */
    private static final Map<UUID, UUID> LIT_BY = new HashMap<>();
    private static final RulesConfig DEFAULTS = RulesConfig.defaults();
    static final String IGNITER = "igniter";
    private static final String IGNITER_USAGE = "按住右键引爆 · 中途松开取消";

    private MonsterCreeper() {}

    public static ItemStack createIgniter(ServerPlayer player) {
        var stack = dev.hardrush.notice.ItemText.lore(dev.hardrush.notice.ItemText.name(new ItemStack(Items.FLINT_AND_STEEL), "引爆器"),
                IGNITER_USAGE, "也能点燃原版苦力怕");
        // Native clients must enter item-use mode and send RELEASE_USE_ITEM. A consumable
        // without food, effects, animation or slowdown is only the input transport. In adventure mode a native
        // client never uses a flint and steel on the block it aims at, so it goes on to use the item.
        stack.set(DataComponents.CONSUMABLE,
                new Consumable(3600f, ItemUseAnimation.NONE, Holder.direct(SoundEvents.EMPTY), false, List.of()));
        stack.set(DataComponents.USE_EFFECTS, new UseEffects(false, false, 1f));
        MonsterItems.bind(player, stack, IGNITER);
        return stack;
    }

    /** The igniter's usage, also the first line of its description. */
    public static java.util.Optional<String> heldHint(ServerPlayer player, ItemStack stack) {
        return stack.is(Items.FLINT_AND_STEEL) && MonsterItems.matches(player, stack, IGNITER)
                ? java.util.Optional.of(IGNITER_USAGE) : java.util.Optional.empty();
    }

    public static boolean use(ServerPlayer player, InteractionHand hand) {
        if (!available(player) || !MonsterItems.matches(player, player.getItemInHand(hand), IGNITER)) return false;
        State state = state(player);
        if (state.exploding) return false;
        player.startUsingItem(hand);
        setDirection(player, state, 1);
        return true;
    }

    public static void release(ServerPlayer player) {
        player.stopUsingItem();
        State state = STATES.get(player.getUUID());
        // A fuse a human lit cannot be let go of.
        if (state != null && state.igniter == null) setDirection(player, state, -1);
    }

    /** A paused body loses active input, and any fuse, but retains its lightning charge. */
    public static void cancelInput(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        if (state != null) state.igniter = null;
        release(player);
        if (state != null) state.swell = 0;
    }

    /**
     * A human's native creeper igniter (flint and steel, fire charge) lit this creeper player, as {@code Creeper.mobInteract}
     * lights a native creeper: the fuse cannot be cancelled and runs the creeper's own length. The igniter is
     * remembered as the last player to hurt the body, so the blast that kills it is that human's kill.
     *
     * @return whether the fuse was lit; an unavailable, exploding or already lit body is left alone
     */
    public static boolean forceIgnite(ServerPlayer creeper, ServerPlayer igniter) {
        if (!available(creeper)) return false;
        State state = state(creeper);
        if (state.exploding || state.igniter != null) return false;
        state.igniter = igniter.getUUID();
        creeper.setLastHurtByPlayer(igniter, (int) dev.hardrush.core.combat.KillCreditMemory.WINDOW_TICKS);
        CombatRuntime.creditHit((net.minecraft.server.level.ServerLevel) creeper.level(), creeper, igniter);
        dev.hardrush.lobby.LobbyRuntime.interruptChannel(creeper, "被点燃");
        prime(creeper, state);
        setDirection(creeper, state, 1);
        return true;
    }

    /**
     * The creeper player's own igniter on a native creeper: lit as {@code Creeper.mobInteract} would, without wearing the
     * igniter. The creeper's blast is this monster's, like a companion's (an already attributed creeper keeps its own).
     */
    public static boolean igniteNative(ServerPlayer player, InteractionHand hand, Creeper target) {
        if (!available(player) || !MonsterItems.matches(player, player.getItemInHand(hand), IGNITER)
                || target.isIgnited() || !target.isAlive()) return false;
        var model = MonsterRoles.nativeModel(player);
        if (target instanceof CombatOriginHolder holder && !holder.hardrush$combatOriginSealed()) {
            Optional<dev.hardrush.core.combat.CombatOrigin> origin = CombatRuntime.originOf(model);
            try { origin = origin.map(parent -> parent.child(UUID.randomUUID(), AttackKind.SUMMON, Optional.of(target.getUUID()))); }
            catch (IllegalArgumentException exhaustedChain) { origin = Optional.empty(); }
            holder.hardrush$sealCombatOrigin(origin);
            if (target instanceof MonsterSourceHolder source) MonsterCombatRuntime.source(model).ifPresent(source::hardrush$stampMonsterSource);
        }
        target.level().playSound(player, target.getX(), target.getY(), target.getZ(), SoundEvents.FLINTANDSTEEL_USE,
                target.getSoundSource(), 1f, target.getRandom().nextFloat() * .4f + .8f);
        target.ignite();
        return true;
    }

    /** Whether the creeper is igniting on purpose or burning a lit fuse; a landing's passive swell is neither. */
    public static boolean isIgniting(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        return state != null && (state.direction > 0 || state.igniter != null);
    }

    /** Whether a human lit this creeper's fuse. */
    public static boolean forced(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        return state != null && state.igniter != null;
    }

    /** While this body is being killed by its own blast ({@link #detonating}): the human who lit its fuse, if one did. */
    public static Optional<UUID> litBy(ServerPlayer player) {
        return Optional.ofNullable(LIT_BY.get(player.getUUID()));
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
                && MonsterItems.matches(player, player.getUseItem(), IGNITER)
                && MonsterItems.matches(player, player.getItemInHand(player.getUsedItemHand()), IGNITER);
        int direction = held || state.igniter != null ? 1 : -1;
        setDirection(player, state, direction);
        if (direction > 0 && state.swell == 0) prime(player, state);
        state.swell = Math.max(0, state.swell + direction);
        if (state.swell >= fuse(player)) explode(player, state);
    }

    private static void prime(ServerPlayer player, State state) {
        if (state.swell != 0) return;
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.CREEPER_PRIMED, SoundSource.HOSTILE, 1f, .5f);
        player.gameEvent(GameEvent.PRIME_FUSE);
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

    public static boolean charged(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        return state != null && state.charged;
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
        return MonsterRoles.isMonster(player) && MonsterItems.matches(player, player.getUseItem(), IGNITER);
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
        UUID igniter = state.igniter;
        boolean forced = igniter != null;
        // The real powered Creeper remains the native damage source, so 26.3's
        // killedEntity/charged_creeper loot table enforces its one-head budget.
        try (var batch = CombatRuntime.explosion(player.level(), model)) {
            MonsterRoles.nativeCreeperBlast(model, player.getUUID(), player.position(), strength);
            spawnCloud(player, model);
            state.swell = 0;
            state.igniter = null;
            release(player);
            if (forced) LIT_BY.put(player.getUUID(), igniter);
            // A forced fuse credits its human through the remembered hit; the blast's own origin would name the creeper.
            try (var credit = forced ? CombatRuntime.rememberedCredit(player.level()) : CombatRuntime.Scope.NONE) {
                player.hurtServer(player.level(), player.damageSources().genericKill(), Float.MAX_VALUE);
            } finally {
                LIT_BY.remove(player.getUUID());
            }
        }
    }

    /**
     * A creeper whose fuse a human lit cannot return, so leaving the game must not save it either: the body is spent as if
     * the fuse ran out, crediting the igniter, but without a blast (docs/plan/苦力怕付费解锁.md §1). Runs before the
     * disconnect is reported, so the death is recorded first and spends a paid creeper's ticket.
     */
    public static void disconnected(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        if (state == null || state.igniter == null || state.exploding || !player.isAlive() || !available(player)) return;
        state.exploding = true;
        UUID igniter = state.igniter;
        state.swell = 0;
        state.igniter = null;
        release(player);
        LIT_BY.put(player.getUUID(), igniter);
        try (var credit = CombatRuntime.rememberedCredit(player.level())) {
            player.hurtServer(player.level(), player.damageSources().genericKill(), Float.MAX_VALUE);
        } finally {
            LIT_BY.remove(player.getUUID());
        }
    }

    private static void spawnCloud(ServerPlayer player, Creeper model) {
        // The body's own night vision is not a potion: copied, it would hand humans infinite sight, and a cloud
        // after every blast would tell a player creeper from a native one, which carries no effects.
        var effects = player.getActiveEffects().stream().filter(effect -> !MonsterRoles.intrinsic(effect)).toList();
        if (effects.isEmpty()) return;
        var cloud = new AreaEffectCloud(player.level(), player.getX(), player.getY(), player.getZ());
        cloud.setRadius(2.5f);
        cloud.setRadiusOnUse(-.5f);
        cloud.setWaitTime(10);
        cloud.setDuration(300);
        cloud.setPotionDurationScale(.25f);
        cloud.setRadiusPerTick(-cloud.getRadius() / cloud.getDuration());
        cloud.setOwner(player);
        MonsterClouds.bind(cloud, player.getUUID());
        for (var effect : effects) cloud.addEffect(new MobEffectInstance(effect));
        // Capture before killing the owner. Later life, role and connection changes must
        // not make a lingering cloud adopt a new actor or lose its original permission.
        MonsterCombatRuntime.source(model).ifPresent(source -> ((MonsterSourceHolder)cloud).hardrush$stampMonsterSource(source));
        var origin = CombatRuntime.originOf(model)
                .map(parent -> parent.child(UUID.randomUUID(), AttackKind.CREEPER, Optional.of(cloud.getUUID())));
        ((CombatOriginHolder)cloud).hardrush$sealCombatOrigin(origin);
        MonsterLegacy.cloud(player, origin);
        player.level().addFreshEntity(cloud);
    }

    /** Inside {@link #explode}: the body's own detonation is killing it. */
    public static boolean detonating(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        return state != null && state.exploding;
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
        /** The human who lit this fuse; the creeper cannot let go of it. */
        UUID igniter;
    }
}
