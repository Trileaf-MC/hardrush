package dev.hardrush.p2.protection;

import dev.hardrush.core.protection.ProtectionBoundary;
import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.p0.monster.MonsterRoles;
import dev.hardrush.p1.world.P1RoundWorldBundle;
import dev.hardrush.p1.player.P1PlayerProfiles;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/** Server-thread protection for every input path; persisted identity owns the one-way exit permission. */
public final class P2ProtectionRuntime {
    public record Zone(UUID roundId, Vec3 center, double radius, boolean lobby) {
        public Zone { Objects.requireNonNull(roundId); Objects.requireNonNull(center); new ProtectionBoundary(center.x, center.z, radius); }
        public ProtectionBoundary boundary() { return new ProtectionBoundary(center.x, center.z, radius); }
        public boolean contains(Vec3 point) { return lobby || boundary().contains(point.x, point.z); }
    }
    public interface Access {
        Optional<Zone> zone(ServerLevel level);
        Optional<Participant> participant(UUID playerId);
        /** False means another durable mutation is pending; retry without granting an exit. */
        boolean leaveProtection(UUID roundId, UUID playerId, int lifeSequence);
        /** No safe loaded standing point exists: retain the identity, hold the player in the lobby. */
        default void quarantine(ServerPlayer player) { throw new IllegalStateException("保护边界无安全回退站位，需要大厅隔离"); }
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
    private record Standing(ServerLevel level, Vec3 position) {}
    private static final class PlayerState {
        ServerPlayer player;
        UUID roundId;
        int lifeSequence;
        boolean pendingExit;
        Standing safe;
        Standing outside;
    }
    private record Binding(Access access, Map<UUID, PlayerState> players) {}
    private static final Map<MinecraftServer, Binding> BINDINGS = new IdentityHashMap<>();
    private static final ThreadLocal<Integer> RELOCATION = ThreadLocal.withInitial(() -> 0);
    private P2ProtectionRuntime() {}

    public static void configure(MinecraftServer server, Access access) {
        BINDINGS.put(server, new Binding(Objects.requireNonNull(access), new HashMap<>()));
    }
    public static void unconfigure(MinecraftServer server) { BINDINGS.remove(server); }
    public static Scope temporaryConfiguration(MinecraftServer server, Access access) {
        var previous = BINDINGS.get(server); configure(server, access);
        return () -> { if (previous == null) BINDINGS.remove(server); else BINDINGS.put(server, previous); };
    }
    public static Optional<Zone> zone(ServerLevel level) {
        var binding = BINDINGS.get(level.getServer());
        return binding == null ? Optional.empty() : binding.access.zone(level);
    }
    public static boolean protectedAt(ServerLevel level, Vec3 position) {
        return zone(level).filter(value -> value.contains(position)).isPresent();
    }
    public static boolean protectedEntity(Entity entity) {
        return entity != null && entity.level() instanceof ServerLevel level && protectedAt(level, entity.position());
    }
    public static boolean protectedBlock(ServerLevel level, BlockPos position) {
        return zone(level).filter(value -> value.lobby || value.boundary().intersectsBlock(position.getX(), position.getZ())).isPresent();
    }
    public static boolean rejectDamage(Entity victim, DamageSource source) {
        Entity direct = source.getDirectEntity();
        if (protectedEntity(victim) || protectedEntity(direct)) return true;
        if (direct instanceof ProtectionOriginHolder holder && holder.hardrush$protectedOrigin()) return true;
        // A projectile retains launch-time permission even if its owner later returns to a protected area.
        return !(direct instanceof Projectile) && protectedEntity(source.getEntity());
    }
    public static boolean rejectEffect(LivingEntity target, MobEffectInstance effect, Entity source) {
        return !effect.getEffect().value().isBeneficial()
                && (protectedEntity(target) || protectedEntity(source)
                    || source instanceof ProtectionOriginHolder holder && holder.hardrush$protectedOrigin());
    }
    public static boolean rejectInteraction(ServerPlayer player, ServerLevel level, BlockPos target) {
        return protectedEntity(player) || protectedBlock(level, target);
    }
    public static void projectileCreated(Projectile projectile, Entity owner) {
        if (projectile instanceof ProtectionOriginHolder holder && protectedEntity(owner)) holder.hardrush$protectedOrigin(true);
    }
    private static boolean hostile(Entity entity, Binding binding) {
        return entity instanceof ServerPlayer player
                ? binding.access.participant(player.getUUID()).map(p -> p.role() == ParticipantRole.MONSTER_LOBBY
                        || p.role() == ParticipantRole.MONSTER_DEPLOYED).orElse(false) || MonsterRoles.isMonster(player)
                : MonsterRoles.summonOwner(entity) != null;
    }
    private static PlayerState state(Binding binding, ServerPlayer player, Zone zone, Participant participant) {
        var state = binding.players.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        if (state.player != player || state.roundId != null && !zone.roundId.equals(state.roundId)
                || state.lifeSequence != participant.lifeSequence()) {
            state.player = player;
            state.roundId = zone.roundId; state.lifeSequence = participant.lifeSequence();
            state.pendingExit = false; state.safe = null; state.outside = null;
        }
        state.roundId = zone.roundId;
        return state;
    }
    /** All movements, including passengers and teleport targets, use the same durable identity check. */
    public static boolean rejectMove(Entity entity, ServerLevel destination, Vec3 target) {
        if (RELOCATION.get() > 0) return false;
        var binding = BINDINGS.get(destination.getServer());
        if (binding == null) return false;
        var destinationZone = binding.access.zone(destination).orElse(null);
        // Profile quarantine/round rotation owns this trusted transition. Its destination must be the lobby.
        if (entity instanceof ServerPlayer held && destinationZone != null && P1PlayerProfiles.isLocked(held)
                && (destinationZone.lobby || binding.access.participant(held.getUUID())
                    .filter(p -> p.role() == ParticipantRole.HUMAN_ACTIVE).isPresent())) return false;
        if (destinationZone != null && !destinationZone.lobby && hostile(entity, binding)
                && destinationZone.contains(target)) return true;
        // A vehicle may never carry a human through the boundary while bypassing player packet admission.
        for (var passenger : entity.getPassengers()) if (rejectMove(passenger, destination, target)) return true;
        if (!(entity instanceof ServerPlayer player) || !(entity.level() instanceof ServerLevel source)) return false;
        var participant = binding.access.participant(player.getUUID()).orElse(null);
        if (participant == null || participant.role() != ParticipantRole.HUMAN_ACTIVE) return false;
        Zone sourceZone = binding.access.zone(source).orElse(null);
        Zone spawn = destinationZone != null && !destinationZone.lobby ? destinationZone
                : sourceZone != null && !sourceZone.lobby ? sourceZone : null;
        if (spawn == null) return false;
        var state = state(binding, player, spawn, participant);
        boolean fromInside = sourceZone != null && !sourceZone.lobby && sourceZone.contains(player.position());
        boolean toInside = destinationZone != null && !destinationZone.lobby && destinationZone.contains(target);
        if (!participant.hasLeftProtection()) {
            // An outside bed/anchor is already outside; it cannot grant an extra trip into spawn.
            if (!fromInside && toInside && (sourceZone == null || !sourceZone.lobby)) {
                if (!state.pendingExit) state.pendingExit = binding.access.leaveProtection(spawn.roundId, player.getUUID(), participant.lifeSequence());
                return true;
            }
            // Admission/new-life respawn enters from profile quarantine. Commit an actual exit first.
            if (fromInside && !toInside) {
                if (!state.pendingExit) state.pendingExit = binding.access.leaveProtection(spawn.roundId, player.getUUID(), participant.lifeSequence());
                return true;
            }
            return false;
        }
        if (fromInside && state.pendingExit) {
            return false;
        }
        if (toInside) return true;
        return source == destination && destinationZone != null && !destinationZone.lobby
                && destinationZone.boundary().crosses(player.getX(), player.getZ(), target.x, target.z);
    }
    public static boolean rejectPosition(Entity entity, Vec3 target) {
        return entity.level() instanceof ServerLevel level && rejectMove(entity, level, target);
    }
    /** Network rejection always resynchronizes the client instead of allowing a predicted illegal position. */
    public static void correct(ServerPlayer player) {
        var binding = BINDINGS.get(player.level().getServer());
        var state = binding == null ? null : binding.players.get(player.getUUID());
        if (state != null && state.player != player) state = null;
        var zone = binding == null ? null : binding.access.zone(player.level()).orElse(null);
        var participant = binding == null ? null : binding.access.participant(player.getUUID()).orElse(null);
        boolean inside = zone != null && zone.contains(player.position());
        boolean mayStandInside = participant == null || !participant.hasLeftProtection()
                || inside && state != null && state.pendingExit;
        var current = new Standing(player.level(), player.position());
        // A denied Nether portal must stay in its source dimension. A just-exited human must never
        // be corrected back into spawn using the previous tick's protected-side anchor.
        Standing target = safe(current) && (mayStandInside || !inside) ? current : null;
        if (target == null && state != null && safe(state.safe) && state.safe.level == player.level()
                && (mayStandInside || !protectedAt(state.safe.level, state.safe.position))) target = state.safe;
        if (target == null && state != null && safe(state.outside) && state.outside.level == player.level()) target = state.outside;
        if (target == null && zone != null && !zone.lobby) target = outsideStanding(player.level(), zone);
        if (target == null && binding != null && (zone == null || !zone.lobby)) {
            binding.access.quarantine(player); return;
        }
        player.stopRiding(); stopUnsafeMotion(player);
        RELOCATION.set(RELOCATION.get() + 1);
        try {
            if (target != null && !target.position.equals(player.position())) {
                player.teleportTo(target.level, target.position.x, target.position.y, target.position.z, Set.of(), player.getYRot(), player.getXRot(), true);
            } else if (player.connection != null) {
                player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
            }
        } finally { endRelocation(); }
    }
    /** Consume the in-memory pending exit at the actual native position change, including same-tick input. */
    public static void positionAccepted(Entity entity) {
        if (!(entity instanceof ServerPlayer player) || !(entity.level() instanceof ServerLevel level)) return;
        var binding = BINDINGS.get(level.getServer());
        if (binding == null) return;
        var state = binding.players.get(player.getUUID());
        var participant = binding.access.participant(player.getUUID()).orElse(null);
        if (state == null || state.player != player || participant == null || !participant.hasLeftProtection()) return;
        var zone = binding.access.zone(level).orElse(null);
        if (zone == null || !zone.lobby && !zone.contains(player.position())) state.pendingExit = false;
    }
    private static void endRelocation() {
        int depth = RELOCATION.get() - 1;
        if (depth == 0) RELOCATION.remove(); else RELOCATION.set(depth);
    }
    public static boolean rejectProjectilePath(Projectile projectile, Vec3 target) {
        return projectile.level() instanceof ServerLevel level && zone(level).filter(zone -> zone.lobby
                || zone.boundary().crosses(projectile.getX(), projectile.getZ(), target.x, target.z)).isPresent();
    }
    public static void sanitize(ServerPlayer player) {
        player.clearFire(); stopUnsafeMotion(player);
        for (var effect : List.copyOf(player.getActiveEffects())) {
            if (!effect.getEffect().value().isBeneficial()) player.removeEffect(effect.getEffect());
        }
    }
    private static void stopUnsafeMotion(ServerPlayer player) {
        player.resetFallDistance(); player.resetCurrentImpulseContext();
        player.setDeltaMovement(Vec3.ZERO);
    }
    private static boolean safe(Standing standing) {
        return standing != null && P1RoundWorldBundle.safeStanding(standing.level, BlockPos.containing(standing.position));
    }
    private static Standing outsideStanding(ServerLevel level, Zone zone) {
        int radius = (int)Math.ceil(zone.radius);
        var center = BlockPos.containing(zone.center);
        for (int distance = radius + 2; distance <= radius + 12; distance++) {
            for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                var column = center.relative(direction, distance);
                for (int dy = 0; dy <= 16; dy++) for (int sign : new int[]{1, -1}) {
                    var feet = column.offset(0, dy * sign, 0);
                    if (P1RoundWorldBundle.safeStanding(level, feet)) return new Standing(level, Vec3.atBottomCenterOf(feet));
                }
            }
        }
        return null;
    }
    private static void restore(ServerPlayer player, Binding binding, PlayerState state, Zone spawn) {
        Standing target = safe(state.outside) && state.outside.level == player.level()
                ? state.outside : outsideStanding(player.level(), spawn);
        if (target == null) { binding.access.quarantine(player); return; }
        RELOCATION.set(RELOCATION.get() + 1);
        try {
            player.stopRiding(); stopUnsafeMotion(player);
            player.teleportTo(target.level, target.position.x, target.position.y, target.position.z, Set.of(), player.getYRot(), player.getXRot(), true);
        } finally {
            endRelocation();
        }
    }
    /** Defense against direct setPosRaw/load paths; no block is placed to manufacture a fallback. */
    public static void tick(MinecraftServer server) {
        var binding = BINDINGS.get(server);
        if (binding == null) return;
        // Safe anchors are live native objects, never retained for disconnected players or old rounds.
        binding.players.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        for (var player : server.getPlayerList().getPlayers()) {
            var zone = binding.access.zone(player.level()).orElse(null);
            if (zone != null && zone.lobby) {
                var old = binding.players.get(player.getUUID());
                if (old != null && (!zone.roundId.equals(old.roundId) || old.player != player)) binding.players.remove(player.getUUID());
                continue;
            }
            var participant = binding.access.participant(player.getUUID()).orElse(null);
            if (participant == null || P1PlayerProfiles.isLocked(player)
                    || participant.role() == ParticipantRole.HUMAN_RESPAWNING) continue;
            // Keep a source-dimension anchor while outside spawn (Nether/End), without manufacturing
            // a round identifier or granting a new spawn entry to an outside respawn.
            if (zone == null) {
                var state = binding.players.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
                if (state.player != player || state.lifeSequence != participant.lifeSequence()) {
                    state.player = player; state.lifeSequence = participant.lifeSequence(); state.pendingExit = false;
                    state.safe = null; state.outside = null;
                }
                var standing = new Standing(player.level(), player.position());
                if (safe(standing)) { state.safe = standing; state.outside = standing; }
                continue;
            }
            var state = state(binding, player, zone, participant);
            boolean inside = zone.contains(player.position());
            if (inside && (hostile(player, binding) || participant.hasLeftProtection() && !state.pendingExit)) {
                restore(player, binding, state, zone); continue;
            }
            if (!inside && participant.hasLeftProtection()) state.pendingExit = false;
            if (!inside && participant.role() == ParticipantRole.HUMAN_ACTIVE && !participant.hasLeftProtection() && !state.pendingExit) {
                state.pendingExit = binding.access.leaveProtection(zone.roundId, player.getUUID(), participant.lifeSequence());
            }
            var standing = new Standing(player.level(), player.position());
            if (safe(standing)) {
                state.safe = standing;
                if (!inside) state.outside = standing;
            }
        }
    }
}
