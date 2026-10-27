package dev.hardrush.record;

import dev.hardrush.core.record.RoundTab;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.participation.ParticipationControl;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Only the native header/footer packet is sent; vanilla player entries remain intact. */
public final class TabListRuntime {
    private record TabText(Component header, Component footer) {}
    private final Map<ServerGamePacketListenerImpl, TabText> displayed = new IdentityHashMap<>();
    public void close() { displayed.clear(); }
    private long lastRefresh = Long.MIN_VALUE;

    public void refresh(MinecraftServer server, RoundSnapshot round, long endEntrants, String problem, boolean immediate) {
        long now = System.nanoTime();
        if (!immediate && lastRefresh != Long.MIN_VALUE && now - lastRefresh < 1_000_000_000L) return;
        lastRefresh = now;
        var text = RoundTab.from(round, endEntrants, problem);
        displayed.keySet().removeIf(connection -> server.getPlayerList().getPlayers().stream()
                .noneMatch(player -> player.connection == connection));
        for (var player : server.getPlayerList().getPlayers()) {
            var footer = Component.literal(text.footer());
            if (ParticipationControl.canWithdraw(player)) footer.append("\n").append(ParticipationControl.shortcutHint());
            var personal = new TabText(Component.literal(text.header()), footer);
            if (personal.equals(displayed.get(player.connection))) continue;
            player.connection.send(new ClientboundTabListPacket(personal.header(), personal.footer()));
            displayed.put(player.connection, personal);
        }
    }
}
