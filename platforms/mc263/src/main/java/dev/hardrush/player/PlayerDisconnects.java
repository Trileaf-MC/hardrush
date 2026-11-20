package dev.hardrush.player;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
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
    @FunctionalInterface
    public interface Listener { void disconnected(ServerPlayer player, MinecraftServer server); }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private PlayerDisconnects() {}

    public static void register(Listener listener) { LISTENERS.add(Objects.requireNonNull(listener)); }

    /** Vanilla only reaches onDisconnect from the server thread (the connection tick or a blocking kick). */
    public static void fire(ServerPlayer player) {
        var server = player.level().getServer();
        if (!server.isSameThread()) throw new IllegalStateException("Player disconnects are handled on the server thread");
        for (var listener : LISTENERS) listener.disconnected(player, server);
    }
}
