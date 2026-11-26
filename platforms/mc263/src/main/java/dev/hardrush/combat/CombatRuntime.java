package dev.hardrush.combat;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.Explosion;

/**
 * Main-thread authoritative capture. Attack provenance never falls back to a last attacker, proximity
 * or online owner; only the revival kill credit remembers the last attributed hit, as vanilla does.
 */
public final class CombatRuntime {
    public interface RoundAccess {
        Optional<UUID> roundOf(ServerLevel level);
        Optional<CombatActor> actor(UUID roundId, UUID playerId);
        /** A captured terminal event seals new batches; the already running batch may finish. */
        default boolean mayDamage(ServerLevel level, UUID currentBatch) { return true; }
        /** Packet admission never inherits the currently executing damage batch. */
        default boolean competitionFrozen(ServerLevel level) { return false; }
        /** Runs synchronously after the outermost scope for this batch has completed. */
        default void batchClosed(UUID roundId, UUID batchId) {}
        /**
         * One monster hit a player took: {@code incomingDamage} is what it brought before armour, enchantments and
         * absorption, after a shield's block; the round decides any coin credit from it.
         */
        default void playerDamaged(ServerLevel level, net.minecraft.server.level.ServerPlayer victim,
                CombatOrigin origin, float incomingDamage) {}
    }
    public interface Scope extends AutoCloseable {
        Scope NONE = () -> {};
        @Override void close();
    }
    private record Binding(RoundAccess rounds, Consumer<CombatEvent> events) {}
    /** {@code effect}: a lingering effect ticking on its victim, whose sourceless magic damage is that origin's hit. */
    private record Cause(ServerLevel level, UUID roundId, Optional<CombatOrigin> origin, UUID batchId, boolean effect) {
        Cause(ServerLevel level, UUID roundId, Optional<CombatOrigin> origin, UUID batchId) { this(level, roundId, origin, batchId, false); }
    }
    private static final Map<MinecraftServer, Binding> BINDINGS = new IdentityHashMap<>();
    private static final ThreadLocal<Deque<Cause>> CAUSES = ThreadLocal.withInitial(ArrayDeque::new);
    private static final Map<MinecraftServer, Long> SEQUENCES = new IdentityHashMap<>();
    private static final Map<MinecraftServer, KillCreditMemory> CREDITS = new IdentityHashMap<>();
    // Main thread only; a mark lives from the totem pop to the end of the same hurtServer call.
    private static final Set<ServerPlayer> REVIVED = Collections.newSetFromMap(new IdentityHashMap<>());
    private CombatRuntime() {}

    public static void configure(MinecraftServer server, RoundAccess access, Consumer<CombatEvent> events) {
        BINDINGS.put(Objects.requireNonNull(server), new Binding(Objects.requireNonNull(access), Objects.requireNonNull(events)));
        CREDITS.put(server, new KillCreditMemory());
    }
    public static void unconfigure(MinecraftServer server) { BINDINGS.remove(server); SEQUENCES.remove(server); CREDITS.remove(server); }

    public static Optional<CombatOrigin> originOf(Entity entity) {
        if (!(entity instanceof CombatOriginHolder holder)) return Optional.empty();
        return holder.hardrush$combatOrigin().filter(origin -> origin.directEntityId().isPresent()
                && origin.directEntityId().get().equals(entity.getUUID()));
    }
    private static Optional<UUID> round(ServerLevel level) {
        var binding = BINDINGS.get(level.getServer());
        return binding == null ? Optional.empty() : binding.rounds.roundOf(level);
    }
    private static Optional<CombatOrigin> created(ServerLevel level, Entity owner, AttackKind kind, Optional<UUID> direct) {
        if (!(owner instanceof ServerPlayer player) || owner.level() != level
                || owner.isRemoved() || !player.isAlive() || rejectDamage(level)) return Optional.empty();
        var round = round(level);
        if (round.isEmpty() || dev.hardrush.participation.ParticipationControl.disabled(level.getServer(), player.getUUID())) return Optional.empty();
        var actor = BINDINGS.get(level.getServer()).rounds.actor(round.get(), player.getUUID());
        if (actor.isEmpty() || !actor.get().mayCreateAttack()) return Optional.empty();
        return Optional.of(new CombatOrigin(round.get(), UUID.randomUUID(), actor.get(), kind, direct, List.of()));
    }
    private static Cause current(ServerLevel level) {
        var top = CAUSES.get().peek();
        return top != null && top.level == level ? top : null;
    }
    public static boolean competitionFrozen(ServerLevel level) {
        var binding = BINDINGS.get(level.getServer());
        return binding != null && binding.rounds.roundOf(level).isPresent() && binding.rounds.competitionFrozen(level);
    }
    public static boolean rejectDamage(ServerLevel level) {
        var binding = BINDINGS.get(level.getServer());
        if (binding == null || binding.rounds.roundOf(level).isEmpty()) return false;
        var cause = current(level);
        if (cause != null && participationRevoked(level, cause.origin)) return true;
        return !binding.rounds.mayDamage(level, cause == null ? null : cause.batchId);
    }
    public static Optional<UUID> currentBatch(ServerLevel level) {
        var cause = current(level);
        return cause == null ? Optional.empty() : Optional.of(cause.batchId);
    }
    public static Optional<CombatOrigin> currentOrigin(ServerLevel level) {
        var cause = current(level);
        return cause == null ? Optional.empty() : cause.origin;
    }
    public static Optional<CombatOrigin> damageOrigin(ServerLevel level, DamageSource source) {
        return resolve(level, source);
    }

    public static boolean participationRevoked(ServerLevel level, Optional<CombatOrigin> origin) {
        return origin.filter(value -> dev.hardrush.participation.ParticipationControl.disabled(
                level.getServer(), value.actor().playerId())).isPresent();
    }
    private static Optional<CombatOrigin> child(Optional<CombatOrigin> parent, AttackKind kind, UUID direct) {
        if (parent.isEmpty()) return Optional.empty();
        try { return Optional.of(parent.get().child(UUID.randomUUID(), kind, Optional.of(direct))); }
        catch (IllegalArgumentException chainOverflow) { return Optional.empty(); }
    }
    public static void projectileCreated(Projectile projectile, Entity owner) {
        dev.hardrush.monster.MonsterSoulcatcher.projectileLaunched(projectile, owner);
        if (!(projectile instanceof AbstractArrow) || !(projectile.level() instanceof ServerLevel level)
                || !(projectile instanceof CombatOriginHolder holder) || holder.hardrush$combatOriginSealed()) return;
        // ProjectileUtil.getMobArrow assigns the native model before MonsterRoles replaces its
        // collision owner with the real player. Capture that model's frozen actor on the first call.
        var model = owner instanceof Mob && owner.level() == level && owner.isAlive() && !rejectDamage(level)
                ? originOf(owner).filter(origin -> origin.actor().identity()
                        == dev.hardrush.core.round.ParticipantRole.MONSTER_DEPLOYED
                        && round(level).filter(origin.roundId()::equals).isPresent())
                : Optional.<CombatOrigin>empty();
        holder.hardrush$sealCombatOrigin(model.isPresent()
                ? child(model, AttackKind.ARROW, projectile.getUUID())
                : created(level, owner, AttackKind.ARROW, Optional.of(projectile.getUUID())));
    }
    public static void tntCreated(PrimedTnt tnt, Entity owner) {
        if (!(tnt.level() instanceof ServerLevel level) || !(tnt instanceof CombatOriginHolder holder)) return;
        var cause = current(level);
        holder.hardrush$sealCombatOrigin(cause == null
                ? created(level, owner, AttackKind.TNT, Optional.of(tnt.getUUID()))
                : child(cause.origin, AttackKind.TNT, tnt.getUUID()));
    }
    public static Scope playerAction(ServerLevel level, Entity player, AttackKind kind) {
        return push(level, created(level, player, kind, Optional.empty()), null);
    }
    public static Scope projectileAction(ServerLevel level, Projectile projectile) {
        // Even absent/malformed projectile provenance is authoritative unknown, never a current owner lookup.
        return push(level, originOf(projectile), null);
    }
    public static Scope tntChain(ServerLevel level, Explosion explosion) {
        var current = current(level);
        var origin = current != null ? current.origin : originOf(explosion.getDirectSourceEntity());
        return push(level, origin, current == null ? null : current.batchId);
    }
    public static void crystalTriggered(ServerLevel level, Entity crystal, DamageSource source) {
        if (crystal instanceof CombatOriginHolder holder && !holder.hardrush$combatOriginSealed()) {
            holder.hardrush$sealCombatOrigin(child(resolve(level, source), AttackKind.END_CRYSTAL, crystal.getUUID()));
        }
    }
    /**
     * Remembers {@code attacker} as the last human to hurt {@code victim}, as a native hit would, without dealing
     * damage: a human lighting a creeper player's fuse. Nothing is remembered outside a round or when the attacker
     * may not attack right now.
     */
    public static void creditHit(ServerLevel level, ServerPlayer victim, ServerPlayer attacker) {
        var memory = CREDITS.get(level.getServer());
        if (memory == null || !victim.isAlive()) return;
        created(level, attacker, AttackKind.MELEE, Optional.empty())
                .ifPresent(credit -> memory.hurt(victim.getUUID(), credit, level.getServer().getTickCount()));
    }
    /**
     * Inside the current batch, a hit with no provenance of its own, so a death in it is credited to whoever hurt the
     * victim last ({@link #creditHit}), not to the enclosing cause.
     */
    public static Scope rememberedCredit(ServerLevel level) {
        var parent = current(level);
        return push(level, Optional.empty(), parent == null ? null : parent.batchId);
    }
    public static Scope explosion(ServerLevel level, Entity direct) {
        var parent = current(level);
        var origin = direct != null ? originOf(direct) : parent == null ? Optional.<CombatOrigin>empty() : parent.origin;
        return push(level, origin, parent == null ? null : parent.batchId);
    }
    /**
     * One tick of an effect a monster's lingering cloud put on {@code origin}'s victim: a poison tick has no source of its
     * own, so it is that cloud's hit (docs/plan/女巫药水与药水盲盒.md §5).
     */
    public static Scope effectTick(ServerLevel level, Optional<CombatOrigin> origin) {
        if (origin.isEmpty()) return Scope.NONE;
        var parent = current(level);
        var round = round(level);
        if (round.isEmpty()) return Scope.NONE;
        return push(level, new Cause(level, round.get(), origin, parent == null ? UUID.randomUUID() : parent.batchId, true));
    }
    /**
     * Remembers {@code origin} as the last attack on {@code victim} without damage, as {@link #creditHit} does for a human:
     * a monster's posthumous gust that throws a human off a ledge.
     */
    public static void creditOrigin(ServerLevel level, ServerPlayer victim, CombatOrigin origin) {
        var memory = CREDITS.get(level.getServer());
        if (memory == null || !victim.isAlive() || round(level).filter(origin.roundId()::equals).isEmpty()) return;
        memory.hurt(victim.getUUID(), origin, level.getServer().getTickCount());
    }
    public static Scope damage(ServerLevel level, DamageSource source) {
        var parent = current(level);
        return push(level, resolve(level, source), parent == null ? null : parent.batchId);
    }
    private static Scope push(ServerLevel level, Optional<CombatOrigin> origin, UUID batch) {
        var round = round(level);
        if (round.isEmpty()) return () -> {};
        return push(level, new Cause(level, round.get(), origin, batch == null ? UUID.randomUUID() : batch));
    }
    private static Scope push(ServerLevel level, Cause cause) {
        CAUSES.get().push(cause);
        return () -> {
            var stack = CAUSES.get();
            if (stack.peek() != cause) throw new IllegalStateException("Combat cause scopes closed out of order");
            stack.pop();
            boolean batchFinished = stack.stream().noneMatch(parent -> parent.level == cause.level
                    && parent.roundId.equals(cause.roundId) && parent.batchId.equals(cause.batchId));
            if (stack.isEmpty()) CAUSES.remove();
            if (batchFinished) {
                var binding = BINDINGS.get(level.getServer());
                if (binding != null) binding.rounds.batchClosed(cause.roundId, cause.batchId);
            }
        };
    }
    private static Optional<CombatOrigin> resolve(ServerLevel level, DamageSource source) {
        Entity direct = source.getDirectEntity();
        // Native monster templates and summoned mobs carry their owner's immutable creation
        // identity, rather than reconstructing it from a present-day player with the same UUID.
        var monster = originOf(direct).filter(origin -> origin.actor().identity()
                == dev.hardrush.core.round.ParticipantRole.MONSTER_DEPLOYED);
        if (monster.isPresent()) return monster;
        if (direct instanceof Projectile || direct instanceof PrimedTnt
                || direct instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal) return originOf(direct);
        if (direct == null && source.getEntity() == null) {
            var cause = current(level);
            if (cause != null && cause.effect) return cause.origin;
        }
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            var cause = current(level);
            return cause == null ? Optional.empty() : cause.origin;
        }
        if (direct instanceof ServerPlayer && direct == source.getEntity()) {
            // Inside this player's own weapon action, whatever damage type the weapon deals (a spear's own type, a
            // mace smash, an item's DAMAGE_TYPE component) is that action's hit. Thorns name the wearer, not the
            // acting player, so they never match.
            var action = current(level);
            if (action != null && action.origin.filter(origin -> origin.kind() == AttackKind.MELEE
                    && origin.actor().playerId().equals(direct.getUUID()) && origin.roundId().equals(action.roundId)).isPresent()) {
                return action.origin;
            }
            if (MELEE_TYPES.stream().anyMatch(source::is)) return created(level, direct, AttackKind.MELEE, Optional.of(direct.getUUID()));
        }
        return Optional.empty();
    }
    /** A player's own melee hit reaching damage without a weapon action around it. */
    private static final List<ResourceKey<DamageType>> MELEE_TYPES = List.of(
            DamageTypes.PLAYER_ATTACK, DamageTypes.MOB_ATTACK, DamageTypes.SPEAR, DamageTypes.MACE_SMASH);
    /** Only the dragon's native arrow acceptance branch may use this; the raw damage type remains unchanged. */
    public static boolean trustedHumanArrow(ServerLevel level, DamageSource source) {
        var round = round(level);
        return round.isPresent() && source.is(DamageTypes.ARROW) && source.getDirectEntity() instanceof AbstractArrow
                && originOf(source.getDirectEntity()).filter(origin -> origin.kind() == AttackKind.ARROW
                        && origin.isHumanAttack(round.get())).isPresent();
    }
    /** Frozen hostile/old-round sources cannot reach dragon damage even after their owner changes identity. */
    public static boolean rejectDragonAttack(ServerLevel level, DamageSource source) {
        if (rejectDamage(level)) return true;
        var round = round(level);
        var cause = current(level);
        var origin = cause == null ? resolve(level, source) : cause.origin;
        return participationRevoked(level, origin)
                || round.isPresent() && origin.filter(value -> !value.isHumanAttack(round.get())).isPresent();
    }
    /**
     * Every hit a player actually took. A survived hit with a kill credit is remembered for the vanilla
     * kill-credit window; monster attacks of the same direct kinds that earn revival credit pay coins.
     */
    /** Damage each player's current hit brought before armour; written by {@code CombatPlayerHurtMixin}. */
    private static final java.util.Map<ServerPlayer, Float> INCOMING = new java.util.WeakHashMap<>();

    public static void incomingDamage(net.minecraft.world.entity.player.Player player, float damage) {
        if (player instanceof ServerPlayer server && damage > 0 && Float.isFinite(damage)) INCOMING.merge(server, damage, Float::sum);
    }

    /** What the hit in progress brought so far, cleared. */
    public static float takeIncomingDamage(ServerPlayer player) {
        Float value = INCOMING.remove(player);
        return value == null ? 0 : value;
    }

    public static void playerDamaged(ServerLevel level, ServerPlayer victim, DamageSource source, float actualDamage, float incomingDamage) {
        if (actualDamage <= 0 && incomingDamage <= 0) return;
        var binding = BINDINGS.get(level.getServer()); var round = round(level);
        if (binding == null || round.isEmpty()) return;
        var cause = current(level);
        var origin = cause == null ? Optional.<CombatOrigin>empty() : cause.origin;
        var memory = CREDITS.get(level.getServer());
        if (memory != null && victim.isAlive() && actualDamage > 0) {
            killCredit(source, origin).ifPresent(credit -> memory.hurt(victim.getUUID(), credit, level.getServer().getTickCount()));
        }
        if (origin.isEmpty() || !origin.get().roundId().equals(round.get())
                || origin.get().actor().identity() != dev.hardrush.core.round.ParticipantRole.MONSTER_DEPLOYED) return;
        switch (origin.get().kind()) {
            case MELEE, ARROW, CREEPER, SUMMON -> {
                if (incomingDamage > 0) binding.rounds.playerDamaged(level, victim, origin.get(), incomingDamage);
                if (actualDamage > 0) dev.hardrush.monster.MonsterLegacy.damaged(level, victim, origin.get());
            }
            default -> {}
        }
    }
    /**
     * Whom a player's death is credited to, resolved during native AFTER_DEATH like vanilla
     * getKillCredit: the lethal hit's own credit, otherwise the last credited hit within
     * {@link KillCreditMemory#WINDOW_TICKS}. Forgets the victim's memory either way.
     */
    public static Optional<CombatOrigin> killCredit(ServerLevel level, ServerPlayer victim, DamageSource source) {
        var memory = CREDITS.get(level.getServer());
        if (memory == null || round(level).isEmpty()) return Optional.empty();
        return memory.died(victim.getUUID(), lethalCredit(level, source), level.getServer().getTickCount());
    }
    /**
     * The credit {@link #killCredit(ServerLevel, ServerPlayer, DamageSource)} would resolve for this lethal hit right now,
     * by the same rule, but forgetting nothing: an in-place revival is not a death, and the body keeps its memory.
     */
    public static Optional<CombatOrigin> peekKillCredit(ServerLevel level, ServerPlayer victim, DamageSource source) {
        var memory = CREDITS.get(level.getServer());
        if (memory == null || round(level).isEmpty()) return Optional.empty();
        return memory.peek(victim.getUUID(), lethalCredit(level, source), level.getServer().getTickCount());
    }
    private static Optional<CombatOrigin> lethalCredit(ServerLevel level, DamageSource source) {
        var cause = current(level);
        return killCredit(source, cause == null ? resolve(level, source) : cause.origin);
    }
    /**
     * A revival totem has just saved {@code victim} inside the hit being taken. The pop restores health and grants
     * absorption, so the hit's before-minus-after loss would read near zero; {@link #takeRevivedInPlace} tells the
     * damage wrapper to count everything the body had before the hit instead.
     */
    public static void revivedInPlace(ServerPlayer victim) { REVIVED.add(victim); }
    /** Whether {@link #revivedInPlace} marked {@code victim} since the last call; forgets the mark. */
    public static boolean takeRevivedInPlace(ServerPlayer victim) { return REVIVED.remove(victim); }
    /** The hit's own provenance; an unattributed soulcatcher creature credits the identity that released it. */
    private static Optional<CombatOrigin> killCredit(DamageSource source, Optional<CombatOrigin> origin) {
        return dev.hardrush.monster.MonsterSoulcatcher.killCredit(source, origin);
    }
    /** Called once after actual damage; dragon parts use the parent dragon UUID. */
    public static void observed(ServerLevel level, Entity victim, DamageSource source, float actualDamage, boolean lethal) {
        if (actualDamage <= 0 && !lethal) return;
        var binding = BINDINGS.get(level.getServer()); var round = round(level);
        if (binding == null || round.isEmpty()) return;
        var cause = current(level);
        var origin = cause == null ? resolve(level, source) : cause.origin;
        long sequence = SEQUENCES.merge(level.getServer(), 1L, Math::addExact);
        var raw = source.typeHolder().unwrapKey().map(key -> key.identifier().toString()).orElse("unregistered");
        binding.events.accept(new CombatEvent(round.get(), UUID.randomUUID(), cause == null ? UUID.randomUUID() : cause.batchId,
                sequence, victim.getUUID(), origin, CombatEvent.classify(round.get(), origin), raw,
                Optional.ofNullable(source.getDirectEntity()).map(Entity::getUUID),
                Optional.ofNullable(source.getEntity()).map(Entity::getUUID),
                Math.max(0, actualDamage), lethal, System.nanoTime()));
    }
}
