package dev.hardrush.protection;

import dev.hardrush.player.PlayerProfiles;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * Server-thread protection for every input path. A protected level (the camp) is sealed as a whole:
 * formal round worlds use ordinary survival rules, including their spawn.
 */
public final class ProtectionRuntime {
    @FunctionalInterface
    public interface Access {
        boolean protectedLevel(ServerLevel level);
    }
    private static final Map<MinecraftServer, Access> BINDINGS = new IdentityHashMap<>();
    private static final Map<ServerPlayer, Long> SPECTATOR_FEEDBACK = new WeakHashMap<>();
    private ProtectionRuntime() {}

    public static void configure(MinecraftServer server, Access access) {
        BINDINGS.put(server, Objects.requireNonNull(access));
    }
    public static void unconfigure(MinecraftServer server) { BINDINGS.remove(server); }

    public static boolean protectedLevel(ServerLevel level) {
        var access = BINDINGS.get(level.getServer());
        return access != null && access.protectedLevel(level);
    }
    public static boolean protectedEntity(Entity entity) {
        return entity != null && entity.level() instanceof ServerLevel level && protectedLevel(level);
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
    public static boolean rejectInteraction(ServerPlayer player, ServerLevel level) {
        return protectedEntity(player) || protectedLevel(level);
    }
    public static void projectileCreated(Projectile projectile, Entity owner) {
        if (projectile instanceof ProtectionOriginHolder holder && protectedEntity(owner)) holder.hardrush$protectedOrigin(true);
    }
    /** All movements, including passengers and teleport targets, use the administrative spectator boundary. */
    public static boolean rejectMove(Entity entity, ServerLevel destination) {
        if (entity instanceof ServerPlayer player && PlayerProfiles.rejectAdministrativeSpectatorMove(player, destination)) return true;
        for (var passenger : entity.getPassengers()) if (rejectMove(passenger, destination)) return true;
        return false;
    }
    public static boolean rejectPosition(Entity entity) {
        return entity.level() instanceof ServerLevel level && rejectMove(entity, level);
    }
    /** Network rejection always resynchronizes the client instead of allowing a predicted illegal position. */
    public static void correct(ServerPlayer player) {
        // Spectators may hover or occupy solid blocks; rejection must not require a survival landing.
        if (PlayerProfiles.isAdministrativeSpectator(player)) spectatorRejected(player);
        player.stopRiding();
        player.resetFallDistance(); player.resetCurrentImpulseContext();
        player.setDeltaMovement(Vec3.ZERO);
        if (player.connection != null) player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }
    /** Explain the pull-back instead of a silent snap; the vault refusal plays at most once per two seconds. */
    private static void spectatorRejected(ServerPlayer player) {
        long now = System.nanoTime() / 1_000_000;
        dev.hardrush.lobby.LobbyInteractions.get(player.level().getServer()).ifPresent(router -> router.hints(player).offer("spectator",
                dev.hardrush.core.lobby.LobbyHints.Priority.RECOVERY, "管理旁观只能前往大厅与本轮世界", now + 2000));
        Long last = SPECTATOR_FEEDBACK.get(player);
        if (last != null && now - last < 2000 || player.connection == null) return;
        SPECTATOR_FEEDBACK.put(player, now);
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                net.minecraft.core.Holder.direct(net.minecraft.sounds.SoundEvents.VAULT_REJECT_REWARDED_PLAYER), net.minecraft.sounds.SoundSource.PLAYERS,
                player.getX(), player.getY(), player.getZ(), 1, 1, 0));
    }
    public static boolean rejectProjectilePath(Projectile projectile) {
        return protectedEntity(projectile);
    }
}
