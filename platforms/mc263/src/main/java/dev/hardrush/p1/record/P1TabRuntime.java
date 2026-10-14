package dev.hardrush.p1.record;

import dev.hardrush.core.record.RoundTab;
import dev.hardrush.core.round.RoundSnapshot;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Only the native header/footer packet is sent; vanilla player entries remain intact. */
public final class P1TabRuntime {
    private final Map<ServerGamePacketListenerImpl, RoundTab> displayed = new IdentityHashMap<>();
    private long lastRefresh = Long.MIN_VALUE;

    public void refresh(MinecraftServer server, RoundSnapshot round, long endEntrants, String problem, boolean immediate) {
        long now = System.nanoTime();
        if (!immediate && lastRefresh != Long.MIN_VALUE && now - lastRefresh < 1_000_000_000L) return;
        lastRefresh = now;
        var text = RoundTab.from(round, endEntrants, problem);
        displayed.keySet().removeIf(connection -> server.getPlayerList().getPlayers().stream()
                .noneMatch(player -> player.connection == connection));
        for (var player : server.getPlayerList().getPlayers()) {
            if (text.equals(displayed.get(player.connection))) continue;
            player.connection.send(new ClientboundTabListPacket(Component.literal(text.header()), Component.literal(text.footer())));
            displayed.put(player.connection, text);
        }
    }
}
