package dev.hardrush.notice;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;

/**
 * T3 arbiter: each player sees at most one status bar (docs/plan/玩家消息分级.md). Owners keep their own
 * {@link ServerBossEvent} and its text, but never add or remove viewers themselves; they {@link #show} or {@link #hide}
 * their slot and the highest slot a player wants is the one attached to them.
 *
 * <p>Hunt bars are not status bars: like a vanilla boss bar they show one nearby enemy's health, so they stay outside.
 */
public final class StatusBars {
    /** Highest priority first. */
    public enum Slot {
        /** The match is suspended; nothing else matters until an operator acts. */
        PAUSE,
        /** A process the player started themself: revival rite, mid-round admission. */
        PERSONAL,
        /** Round stage for everybody: rescue, result display, celebration. */
        STAGE,
        /** The start countdown of the assembly circle. */
        COUNTDOWN,
        /** Riding another player's camera. */
        OBSERVE,
        /** The dusk pact countdown. */
        PACT
    }

    private static final Map<MinecraftServer, StatusBars> SERVICES = new IdentityHashMap<>();
    private final Map<UUID, Viewer> viewers = new HashMap<>();

    private static final class Viewer {
        ServerPlayer player;
        final EnumMap<Slot, ServerBossEvent> wanted = new EnumMap<>(Slot.class);
        ServerBossEvent shown;
        Viewer(ServerPlayer player) { this.player = player; }
    }

    private StatusBars() {}

    private static StatusBars of(MinecraftServer server) { return SERVICES.computeIfAbsent(server, ignored -> new StatusBars()); }

    /** The player wants {@code bar} in {@code slot}; idempotent, so owners may call it every tick. */
    public static void show(ServerPlayer player, Slot slot, ServerBossEvent bar) {
        var service = of(player.level().getServer());
        var viewer = service.viewer(player);
        viewer.wanted.put(slot, bar);
        service.resolve(viewer);
    }

    public static void hide(ServerPlayer player, Slot slot) {
        var service = SERVICES.get(player.level().getServer());
        if (service == null) return;
        var viewer = service.viewers.get(player.getUUID());
        if (viewer == null || viewer.wanted.remove(slot) == null) return;
        service.resolve(viewer);
    }

    /** Takes {@code bar} away from everybody who wanted it, e.g. when its owner drops it. */
    public static void hideEverywhere(MinecraftServer server, ServerBossEvent bar) {
        var service = SERVICES.get(server);
        if (service != null) for (var viewer : service.viewers.values()) {
            if (viewer.wanted.values().removeIf(wanted -> wanted == bar)) service.resolve(viewer);
        }
        bar.removeAllPlayers();
    }

    /** Disconnects and respawns replace the player object; nothing wanted by the old one survives. */
    public static void forget(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        if (service == null) return;
        var viewer = service.viewers.get(player.getUUID());
        if (viewer == null || viewer.player != player) return;
        if (viewer.shown != null) viewer.shown.removePlayer(player);
        service.viewers.remove(player.getUUID());
    }

    public static void close(MinecraftServer server) { SERVICES.remove(server); }

    /** Test seam: the bar the player currently sees. */
    public static java.util.Optional<ServerBossEvent> shown(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        var viewer = service == null ? null : service.viewers.get(player.getUUID());
        return viewer == null || viewer.player != player ? java.util.Optional.empty() : java.util.Optional.ofNullable(viewer.shown);
    }

    private Viewer viewer(ServerPlayer player) {
        var viewer = viewers.computeIfAbsent(player.getUUID(), ignored -> new Viewer(player));
        if (viewer.player != player) {
            // A respawn is a new player object: the old one is gone from every bar, and so are its wishes.
            if (viewer.shown != null) viewer.shown.removePlayer(viewer.player);
            viewer.shown = null; viewer.wanted.clear(); viewer.player = player;
        }
        return viewer;
    }

    private void resolve(Viewer viewer) {
        // EnumMap iterates in slot order, so the first value is the highest wish.
        var top = viewer.wanted.isEmpty() ? null : viewer.wanted.values().iterator().next();
        if (top == viewer.shown) { if (top != null) top.addPlayer(viewer.player); return; }
        if (viewer.shown != null) viewer.shown.removePlayer(viewer.player);
        viewer.shown = top;
        if (top != null) top.addPlayer(viewer.player);
    }
}
