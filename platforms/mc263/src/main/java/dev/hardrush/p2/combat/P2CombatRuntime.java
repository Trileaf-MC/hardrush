package dev.hardrush.p2.combat;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.p1.combat.CombatOriginHolder;
import dev.hardrush.p1.combat.P1CombatRuntime;
import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p2.protection.P2ProtectionRuntime;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;

/** One admission policy shared by damage, native pre-impact side effects and explosion impulses. */
public final class P2CombatRuntime {
    public interface Access {
        Optional<RoundSnapshot> snapshot();
        boolean competitionLocked();
        boolean formalLevel(ServerLevel level);
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
    private static final Map<MinecraftServer, Access> BINDINGS = new IdentityHashMap<>();
    private static final ThreadLocal<Deque<Entity>> SOURCES = ThreadLocal.withInitial(ArrayDeque::new);
    private P2CombatRuntime() {}

    public static void configure(MinecraftServer server, Access access) { BINDINGS.put(server, Objects.requireNonNull(access)); }
    public static void unconfigure(MinecraftServer server) { BINDINGS.remove(server); }

    public static Optional<FrozenMonsterSource> source(Entity entity) {
        var stamped = entity instanceof MonsterSourceHolder holder ? holder.hardrush$monsterSource()
                : Optional.<FrozenMonsterSource>empty();
        if (stamped.isPresent()) return stamped;
        // Existing P1 entity provenance remains authoritative after upgrades. A historical hostile
        // arrow must not regain permission merely because the P2-specific field did not yet exist.
        return P1CombatRuntime.originOf(entity)
                .filter(origin -> origin.actor().identity() == ParticipantRole.MONSTER_DEPLOYED)
                .map(origin -> new FrozenMonsterSource(origin.roundId(), origin.actor().playerId(),
                        (int)Math.min(Integer.MAX_VALUE, origin.actor().lifeSequence()),
                        (int)Math.min(Integer.MAX_VALUE, origin.actor().deploymentSequence()), true));
    }
    /** Called on the native model, each child and each arrow before it enters the world. */
    public static void stampSource(Entity entity, ServerPlayer owner) {
        if (!(entity instanceof MonsterSourceHolder holder)) return;
        var access = BINDINGS.get(owner.level().getServer());
        var round = access == null ? null : access.snapshot().orElse(null);
        var actor = round == null ? null : round.participants().get(owner.getUUID());
        if (actor == null || !actor.role().isMonster()) return; // Isolated P0 prototypes retain their own scope.
        boolean admitted = actor.role() == ParticipantRole.MONSTER_DEPLOYED && actor.monsterDeploymentSequence() > 0
                && actor.online() && owner.isAlive()
                && round.phase() == RoundPhase.RUNNING && !access.competitionLocked() && access.formalLevel(owner.level())
                && !P1PlayerProfiles.isLocked(owner) && !P2ProtectionRuntime.protectedEntity(owner);
        holder.hardrush$stampMonsterSource(new FrozenMonsterSource(round.roundId(), owner.getUUID(),
                actor.lifeSequence(), actor.monsterDeploymentSequence(), admitted));
        if (admitted && entity instanceof CombatOriginHolder combat && !combat.hardrush$combatOriginSealed()) {
            combat.hardrush$sealCombatOrigin(Optional.of(new CombatOrigin(round.roundId(), UUID.randomUUID(),
                    new CombatActor(owner.getUUID(), actor.role(), actor.lifeSequence(), actor.monsterDeploymentSequence()),
                    entity instanceof AbstractArrow ? AttackKind.ARROW
                            : entity instanceof net.minecraft.world.entity.monster.Creeper ? AttackKind.CREEPER
                            : dev.hardrush.p0.monster.MonsterRoles.summonOwner(entity) != null ? AttackKind.SUMMON : AttackKind.MELEE,
                    Optional.of(entity.getUUID()), List.of())));
        }
    }
    public static Scope action(Entity entity) {
        SOURCES.get().push(entity);
        return () -> { var stack = SOURCES.get(); if (stack.pop() != entity) throw new IllegalStateException("Monster scopes closed out of order");
            if (stack.isEmpty()) SOURCES.remove(); };
    }
    private static Entity current() { return SOURCES.get().peek(); }
    private static boolean capturedHuman(Entity entity) {
        return P1CombatRuntime.originOf(entity).filter(origin -> origin.actor().identity().isActiveHuman()).isPresent();
    }
    public static boolean rejectCurrent(Entity target) { return reject(current(), target); }
    public static boolean rejectDamage(Entity target, DamageSource source) {
        if (dev.hardrush.p1.pact.DawnPactRuntime.rejectDamage(target, source)) return true;
        // A launched arrow's frozen identity wins over a mutable native owner reference.
        if (source(source.getDirectEntity()).isPresent()) return reject(source.getDirectEntity(), target);
        if (capturedHuman(source.getDirectEntity())) return false;
        if (source.getEntity() != null) return reject(source.getEntity(), target);
        return rejectCurrent(target);
    }
    public static boolean rejectEffect(Entity source, Entity target, net.minecraft.world.effect.MobEffectInstance effect) {
        var action = current();
        if (!effect.getEffect().value().isBeneficial()
                && dev.hardrush.p1.pact.DawnPactRuntime.reject(action != null ? action : source, target)) return true;
        if (action instanceof AbstractArrow && (source(action).isPresent() || capturedHuman(action))) {
            return rejectMonster(action, target);
        }
        return rejectMonster(source != null ? source : action, target);
    }
    /** Ordinary native Player.attack must never bypass the monster model's range, cooldown and attributes. */
    public static boolean formalMonsterPlayer(Entity entity) {
        if (!(entity instanceof ServerPlayer player)) return false;
        var access = BINDINGS.get(player.level().getServer());
        return access != null && access.snapshot().map(r -> r.participants().get(player.getUUID()))
                .filter(p -> p.role().isMonster()).isPresent();
    }
    public static boolean reject(Entity entity, Entity target) {
        return dev.hardrush.p1.pact.DawnPactRuntime.reject(entity, target) || rejectMonster(entity, target);
    }
    private static boolean rejectMonster(Entity entity, Entity target) {
        var frozen = source(entity).orElse(null);
        if (frozen == null) return formalMonsterPlayer(entity); // Only the stamped native model may attack.
        if (!(target.level() instanceof ServerLevel level) || entity.level() != level) return true;
        var access = BINDINGS.get(level.getServer());
        var round = access == null ? null : access.snapshot().orElse(null);
        if (!frozen.admitted() || round == null || !round.roundId().equals(frozen.roundId())
                || round.phase() != RoundPhase.RUNNING || access.competitionLocked() || !access.formalLevel(level)) return true;
        if (target instanceof net.minecraft.world.entity.Mob mob) {
            return dev.hardrush.p0.monster.MonsterRoles.summonOwner(entity) != null
                    || mob instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon
                    || dev.hardrush.p0.monster.MonsterRoles.isNativeBody(mob)
                    || dev.hardrush.p0.monster.MonsterRoles.summonOwner(mob) != null
                    || frozen.ownerId().equals(mob.getUUID()) || !mob.isAlive() || mob.isRemoved() || mob.isInvulnerable()
                    || P2ProtectionRuntime.protectedEntity(mob) || P2ProtectionRuntime.protectedEntity(entity);
        }
        if (!(target instanceof ServerPlayer player) || !player.isAlive() || player.isRemoved() || player.isSpectator()
                || level.getServer().getPlayerList().getPlayer(player.getUUID()) != player
                || player.getAbilities().invulnerable || P1PlayerProfiles.isLocked(player)
                || frozen.ownerId().equals(player.getUUID()) || P2ProtectionRuntime.protectedEntity(target)
                || P2ProtectionRuntime.protectedEntity(entity)) return true;
        return Optional.ofNullable(round.participants().get(player.getUUID()))
                .filter(p -> p.online() && p.role() == ParticipantRole.HUMAN_ACTIVE).isEmpty();
    }
}
