package dev.hardrush.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;

/** Native portal hooks. Optional tooling supplies its own ownership without shipping tool code. */
public final class RoundWorldRouting {
    public interface AdditionalWorlds {
        boolean owns(ServerLevel level);
        ResourceKey<Level> semanticDimension(ServerLevel level);
        ServerLevel portalDestination(ServerLevel source, ResourceKey<Level> requested);
        TeleportTransition boundRespawn(ServerPlayer player, TeleportTransition original);
    }
    private static AdditionalWorlds additional;
    private RoundWorldRouting() {}
    public static void registerAdditionalWorlds(AdditionalWorlds worlds) {
        if(additional!=null)throw new IllegalStateException("Additional world routes already registered");
        additional=java.util.Objects.requireNonNull(worlds);
    }
    private static boolean additionalOwns(ServerLevel level) { return additional!=null&&additional.owns(level); }
    public static boolean owns(ServerLevel level) { return RoundWorldBundle.owns(level)||additionalOwns(level); }
    public static boolean sameRound(ServerLevel source, ServerLevel destination) {
        return RoundWorldBundle.sameRound(source,destination)||additionalOwns(source)&&additionalOwns(destination);
    }
    public static ResourceKey<Level> semanticDimension(ServerLevel level) {
        return additionalOwns(level)?additional.semanticDimension(level):RoundWorldBundle.semanticDimension(level);
    }
    public static ServerLevel portalDestination(ServerLevel source, ResourceKey<Level> requested) {
        return additionalOwns(source)?additional.portalDestination(source,requested):RoundWorldBundle.portalDestination(source,requested);
    }
    public static TeleportTransition boundRespawn(ServerPlayer player, TeleportTransition original) {
        return additionalOwns(player.level())?additional.boundRespawn(player,original):RoundWorldBundle.boundRespawn(player,original);
    }
}
