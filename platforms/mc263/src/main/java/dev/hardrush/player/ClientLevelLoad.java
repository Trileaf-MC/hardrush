package dev.hardrush.player;

import net.minecraft.server.level.ServerPlayer;

/**
 * Whether the native client has closed the loading screen of the level it is currently in.
 *
 * <p>Vanilla's {@code hasClientLoaded()} only restarts after login and death-respawn. A dimension change also sends
 * {@code ClientboundRespawnPacket}, and the native client answers it with a fresh {@code ServerboundPlayerLoadedPacket}
 * once its loading screen closes, but the server ignores that answer. Anything meant to be seen on arrival (titles,
 * the elder guardian ghost, sounds) must wait for this instead.
 */
public interface ClientLevelLoad {
    boolean hardrush$levelLoaded();
    /** A respawn packet reached the wire: the client is now on a loading screen. */
    void hardrush$levelChanged();
    /** A position packet reached the wire; the first one after a level change marks the client's arrival. */
    void hardrush$teleportSent(int id);

    static boolean loaded(ServerPlayer player) {
        return player.connection instanceof ClientLevelLoad load && load.hardrush$levelLoaded();
    }
}
