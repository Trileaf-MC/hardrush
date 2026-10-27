package dev.hardrush;

import dev.hardrush.disguise.MonsterDisguise;
import dev.hardrush.monster.MonsterRoles;
import dev.hardrush.round.RoundRuntime;
import dev.hardrush.lobby.LobbyRuntime;
import dev.hardrush.locator.PlayerLocator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/** Production bootstrap. MonsterDisguise is the live observer appearance service. */
public final class HardRush implements ModInitializer {
    private static boolean initialized;
    @Override public void onInitialize() { initialize(); }
    public static void initialize() {
        if(initialized)return;
        initialized=true;
        MonsterRoles.initialize();LobbyRuntime.initialize();RoundRuntime.initialize();
        ServerTickEvents.END_SERVER_TICK.register(server->{MonsterRoles.tick(server);MonsterDisguise.tick(server);PlayerLocator.tick(server);});
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{MonsterRoles.onDisconnect(handler.player);MonsterDisguise.onDisconnect(handler.player);});
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{MonsterRoles.onJoin(handler.player);MonsterDisguise.onJoin(handler.player);});
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{MonsterRoles.stop(server);MonsterDisguise.stop(server);PlayerLocator.stop(server);});
        org.slf4j.LoggerFactory.getLogger("hardrush").info("HardRush production runtime; competition enabled={}",HardRushProperties.competitionEnabled());
    }
}
