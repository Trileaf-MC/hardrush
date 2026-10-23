package dev.hardrush;

import dev.hardrush.p0.disguise.DisguiseProbe;
import dev.hardrush.p0.monster.MonsterRoles;
import dev.hardrush.p1.P1LobbyRuntime;
import dev.hardrush.p2.lobby.P2LobbyRuntime;
import dev.hardrush.p2.locator.PlayerLocator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/** Production bootstrap. The historically named DisguiseProbe is the live observer appearance service. */
public final class HardRush implements ModInitializer {
    private static boolean initialized;
    @Override public void onInitialize() { initialize(); }
    public static void initialize() {
        if(initialized)return;
        initialized=true;
        MonsterRoles.initialize();P2LobbyRuntime.initialize();P1LobbyRuntime.initialize();
        ServerTickEvents.END_SERVER_TICK.register(server->{MonsterRoles.tick(server);DisguiseProbe.tick(server);PlayerLocator.tick(server);});
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{MonsterRoles.onDisconnect(handler.player);DisguiseProbe.onDisconnect(handler.player);});
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{MonsterRoles.onJoin(handler.player);DisguiseProbe.onJoin(handler.player);});
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{MonsterRoles.stop(server);DisguiseProbe.stop(server);PlayerLocator.stop(server);});
        org.slf4j.LoggerFactory.getLogger("hardrush").info("HardRush production runtime; competition enabled={}",Boolean.getBoolean("hardrush.p1.enabled"));
    }
}
