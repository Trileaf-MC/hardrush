package dev.hardrush.p1.world;

import dev.hardrush.p0.world.WorldProbe;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;

/** Shared native portal hooks; P0 and every P1 round retain distinct ownership. */
public final class RoundWorldRouting {
    private RoundWorldRouting() {}
    public static boolean owns(ServerLevel level) { return WorldProbe.owns(level) || P1RoundWorldBundle.owns(level); }
    public static boolean sameRound(ServerLevel source, ServerLevel destination) {
        return WorldProbe.owns(source) && WorldProbe.owns(destination) || P1RoundWorldBundle.sameRound(source, destination);
    }
    public static ResourceKey<Level> semanticDimension(ServerLevel level) {
        return P1RoundWorldBundle.owns(level) ? P1RoundWorldBundle.semanticDimension(level) : WorldProbe.semanticDimension(level);
    }
    public static ServerLevel portalDestination(ServerLevel source, ResourceKey<Level> requested) {
        return P1RoundWorldBundle.owns(source) ? P1RoundWorldBundle.portalDestination(source, requested) : WorldProbe.portalDestination(source, requested);
    }
    public static TeleportTransition boundRespawn(ServerPlayer player, TeleportTransition original) {
        return P1RoundWorldBundle.owns(player.level()) ? P1RoundWorldBundle.boundRespawn(player, original) : WorldProbe.boundRespawn(player, original);
    }
}
