package dev.hardrush.gametest;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;

/**
 * Fixture clients that see the world the way a real vanilla client does.
 *
 * <p>Vanilla sends an entity to a player only within {@code min(entity tracking range, clamp(requested view
 * distance, 2, server view distance) × 16)} horizontal blocks, inside chunks that player tracks. Two defaults
 * shrink that to 32 blocks in headless GameTests: {@link ClientInformation#createDefault()} requests 2 chunks,
 * and the vanilla {@link GameTestServer} never sets its player list's view distance, so every level clamps it
 * to 2 as well. A test asserting that one player receives another's entity then fails whenever the two happen
 * to stand more than 32 blocks apart. Every fixture connection therefore requests the vanilla client's default
 * render distance, and the GameTest server runs with the dedicated server's default view distance.
 *
 * <p>Tests that need a short-sighted client say so explicitly through {@link TestClient#viewDistance(int)}.
 *
 * <p>This initializer also runs the vanilla client replies of every live {@link TestClient} at the end of each server
 * tick, on the headless GameTest server and on the dedicated servers of the restart and crash regressions alike. Their
 * connections tick earlier in the tick, alongside the server's own ({@code TestClientConnectionTickMixin}).
 */
public final class TestClients implements ModInitializer {
    /** {@code Options.renderDistance} default of the vanilla client. */
    public static final int VIEW_DISTANCE = 12;
    /** {@code view-distance} default of {@code server.properties}. */
    public static final int SERVER_VIEW_DISTANCE = 10;

    /** Client options of a fixture player: vanilla defaults with a real client's render distance. */
    public static ClientInformation information() {
        return information(VIEW_DISTANCE);
    }

    public static ClientInformation information(int viewDistance) {
        var defaults = ClientInformation.createDefault();
        return new ClientInformation(defaults.language(), viewDistance, defaults.chatVisibility(), defaults.chatColors(),
                defaults.modelCustomisation(), defaults.mainHand(), defaults.textFilteringEnabled(),
                defaults.allowsListing(), defaults.particleStatus());
    }

    /** The login cookie of a fresh, non-transferred fixture connection. */
    public static CommonListenerCookie cookie(GameProfile profile) {
        return cookie(profile, VIEW_DISTANCE);
    }

    public static CommonListenerCookie cookie(GameProfile profile, int viewDistance) {
        return new CommonListenerCookie(profile, 0, information(viewDistance), false);
    }

    /**
     * Dedicated-server regressions configure {@code view-distance} through their own {@code server.properties};
     * only the headless GameTest server is left at vanilla's unset value.
     */
    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (server instanceof GameTestServer) server.getPlayerList().setViewDistance(SERVER_VIEW_DISTANCE);
        });
        ServerTickEvents.END_SERVER_TICK.register(TestClient::tick);
    }
}
