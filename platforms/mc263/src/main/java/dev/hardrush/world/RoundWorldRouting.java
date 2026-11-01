package dev.hardrush.world;

import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
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
    private static Function<MinecraftServer,ServerLevel> formalOverworld=server->null;
    private RoundWorldRouting() {}
    public static void registerAdditionalWorlds(AdditionalWorlds worlds) {
        if(additional!=null)throw new IllegalStateException("Additional world routes already registered");
        additional=java.util.Objects.requireNonNull(worlds);
    }
    /** The competition runtime names its current round; worlds stay independent of round state. */
    public static void registerFormalOverworld(Function<MinecraftServer,ServerLevel> provider) {
        formalOverworld=java.util.Objects.requireNonNull(provider);
    }
    /** Console, RCON and server functions stand in the unused vanilla overworld; world-wide commands mean the round. */
    public static CommandSourceStack commandSource(CommandSourceStack source) {
        if(source.getLevel()!=source.getServer().overworld())return source;
        ServerLevel round=formalOverworld.apply(source.getServer());
        return round==null?source:source.withLevel(round);
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
