package dev.hardrush.player;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * A player's play connection has ended: called on the server thread at the head of vanilla's
 * {@code ServerGamePacketListenerImpl.onDisconnect}, while the body is still in its level and in the player list,
 * before vanilla saves and removes it. Listeners run in registration order.
 *
 * <p>Fabric's {@code ServerPlayConnectionEvents.DISCONNECT} is deliberately not used. Fabric fires it from whichever
 * comes first of Netty's {@code channelInactive} and the server's {@code Connection.handleDisconnection}; for a real
 * socket that is nearly always {@code channelInactive}, on the network thread. There, round, profile and world state
 * must not be touched: the profile adapter's thread check threw, the network pipeline swallowed the error, the
 * session kept the player online (no rescue when the last human left) and the profile kept the departed body (whose
 * teleport to the camp at the next generation then failed the session).
 */
public final class PlayerDisconnects {
    /** Who ended the connection, as far as the game's rules care. */
    public enum Cause {
        /** The player's side: quitting, a lost connection, a keep-alive timeout, the same account logging in elsewhere. */
        PLAYER,
        /** The server's own decision: a stop, {@code /kick}, {@code /ban}, the whitelist, an idle or protocol kick. */
        SERVER
    }

    @FunctionalInterface
    public interface Listener { void disconnected(ServerPlayer player, MinecraftServer server, Cause cause); }

    /**
     * Server-sent reasons that are still the player's doing: an unanswered keep-alive is a lost connection, and a second
     * login of the same account is the player's own choice (it would otherwise be a free way out of a fight).
     */
    private static final Set<String> PLAYER_SIDE_KICKS = Set.of("disconnect.timeout", "multiplayer.disconnect.duplicate_login");

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private PlayerDisconnects() {}

    public static void register(Listener listener) { LISTENERS.add(Objects.requireNonNull(listener)); }

    /**
     * What {@code ServerCommonPacketListenerImpl.disconnect} was given, or null when the server never called it (the
     * client closed the connection, or the socket broke).
     */
    public static Cause cause(DisconnectionDetails serverKick, MinecraftServer server) {
        if (!server.isRunning()) return Cause.SERVER;
        if (serverKick == null) return Cause.PLAYER;
        return serverKick.reason().getContents() instanceof TranslatableContents key && PLAYER_SIDE_KICKS.contains(key.getKey())
                ? Cause.PLAYER : Cause.SERVER;
    }

    /** Vanilla only reaches onDisconnect from the server thread (the connection tick or a blocking kick). */
    public static void fire(ServerPlayer player, DisconnectionDetails serverKick) {
        var server = player.level().getServer();
        if (!server.isSameThread()) throw new IllegalStateException("Player disconnects are handled on the server thread");
        var cause = cause(serverKick, server);
        for (var listener : LISTENERS) listener.disconnected(player, server, cause);
    }
}
