package dev.hardrush.p1.combat;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.Explosion;

/** Main-thread authoritative capture. No kill credit, last attacker, proximity or online-owner fallback. */
public final class P1CombatRuntime {
    public interface RoundAccess {
        Optional<UUID> roundOf(ServerLevel level);
        Optional<CombatActor> actor(UUID roundId, UUID playerId);
        /** A captured terminal event seals new batches; the already running batch may finish. */
        default boolean mayDamage(ServerLevel level, UUID currentBatch) { return true; }
        /** Packet admission never inherits the currently executing damage batch. */
        default boolean competitionFrozen(ServerLevel level) { return false; }
        /** Runs synchronously after the outermost scope for this batch has completed. */
        default void batchClosed(UUID roundId, UUID batchId) {}
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
    private record Binding(RoundAccess rounds, Consumer<CombatEvent> events) {}
    private record Cause(ServerLevel level, UUID roundId, Optional<CombatOrigin> origin, UUID batchId) {}
    private static final Map<MinecraftServer, Binding> BINDINGS = new IdentityHashMap<>();
    private static final ThreadLocal<Deque<Cause>> CAUSES = ThreadLocal.withInitial(ArrayDeque::new);
    private static final Map<MinecraftServer, Long> SEQUENCES = new IdentityHashMap<>();
    private P1CombatRuntime() {}

    public static void configure(MinecraftServer server, RoundAccess access, Consumer<CombatEvent> events) {
        BINDINGS.put(Objects.requireNonNull(server), new Binding(Objects.requireNonNull(access), Objects.requireNonNull(events)));
    }
    public static void unconfigure(MinecraftServer server) { BINDINGS.remove(server); SEQUENCES.remove(server); }
    /** Synchronous automation fixture; restoring preserves the production session's binding. */
    public static Scope temporaryConfiguration(MinecraftServer server, RoundAccess access, Consumer<CombatEvent> events) {
        var previous = BINDINGS.get(server);
        configure(server, access, events);
        return () -> { if (previous == null) BINDINGS.remove(server); else BINDINGS.put(server, previous); };
    }
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
        var round = round(level);
        if (round.isEmpty() || !(owner instanceof ServerPlayer player) || owner.level() != level
                || owner.isRemoved() || !player.isAlive() || rejectDamage(level)) return Optional.empty();
        var actor = BINDINGS.get(level.getServer()).rounds.actor(round.get(), owner.getUUID());
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
        return !binding.rounds.mayDamage(level, cause == null ? null : cause.batchId);
    }
    public static Optional<UUID> currentBatch(ServerLevel level) {
        var cause = current(level);
        return cause == null ? Optional.empty() : Optional.of(cause.batchId);
    }
    private static Optional<CombatOrigin> child(Optional<CombatOrigin> parent, AttackKind kind, UUID direct) {
        if (parent.isEmpty()) return Optional.empty();
        try { return Optional.of(parent.get().child(UUID.randomUUID(), kind, Optional.of(direct))); }
        catch (IllegalArgumentException chainOverflow) { return Optional.empty(); }
    }
    public static void projectileCreated(Projectile projectile, Entity owner) {
        if (!(projectile instanceof AbstractArrow) || !(projectile.level() instanceof ServerLevel level)
                || !(projectile instanceof CombatOriginHolder holder) || holder.hardrush$combatOriginSealed()) return;
        holder.hardrush$sealCombatOrigin(created(level, owner, AttackKind.ARROW, Optional.of(projectile.getUUID())));
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
    public static Scope explosion(ServerLevel level, Entity direct) {
        var parent = current(level);
        var origin = direct != null ? originOf(direct) : parent == null ? Optional.<CombatOrigin>empty() : parent.origin;
        return push(level, origin, parent == null ? null : parent.batchId);
    }
    public static Scope damage(ServerLevel level, DamageSource source) {
        var parent = current(level);
        return push(level, resolve(level, source), parent == null ? null : parent.batchId);
    }
    private static Scope push(ServerLevel level, Optional<CombatOrigin> origin, UUID batch) {
        var round = round(level);
        if (round.isEmpty()) return () -> {};
        var cause = new Cause(level, round.get(), origin, batch == null ? UUID.randomUUID() : batch);
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
        if (direct instanceof Projectile || direct instanceof PrimedTnt
                || direct instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal) return originOf(direct);
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            var cause = current(level);
            return cause == null ? Optional.empty() : cause.origin;
        }
        if ((source.is(DamageTypes.PLAYER_ATTACK) || source.is(DamageTypes.MOB_ATTACK)) && direct instanceof ServerPlayer) {
            var action = current(level);
            if (action != null && action.origin.filter(origin -> origin.kind() == AttackKind.MELEE
                    && origin.actor().playerId().equals(direct.getUUID()) && origin.roundId().equals(action.roundId)).isPresent()) {
                return action.origin;
            }
            return created(level, direct, AttackKind.MELEE, Optional.of(direct.getUUID()));
        }
        return Optional.empty();
    }
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
        return round.isPresent() && origin.filter(value -> !value.isHumanAttack(round.get())).isPresent();
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
