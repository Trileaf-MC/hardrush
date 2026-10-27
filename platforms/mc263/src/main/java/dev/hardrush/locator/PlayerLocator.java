package dev.hardrush.locator;

import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.monster.MonsterRoles;
import dev.hardrush.round.RoundRuntime;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/** Receiver-specific locator visibility; vanilla still owns ranges, dimensions and sneaking. */
public final class PlayerLocator {
    private static final Map<MinecraftServer, Map<ServerPlayer, Boolean>> IDENTITIES = new IdentityHashMap<>();

    private PlayerLocator() {}

    public static boolean hideFrom(LivingEntity source, ServerPlayer receiver) {
        if (!(source instanceof ServerPlayer player)) return false;
        var round = RoundRuntime.snapshot(player.level().getServer()).orElse(null);
        return isMonster(player, round) && !isMonster(receiver, round);
    }

    private static boolean isMonster(ServerPlayer player, RoundSnapshot round) {
        var participant = round == null ? null : round.participants().get(player.getUUID());
        // Camp monsters have a human body. The committed identity takes precedence over appearance.
        return participant == null ? MonsterRoles.isMonster(player) : participant.role().isMonster();
    }

    public static void tick(MinecraftServer server) {
        var identities = IDENTITIES.computeIfAbsent(server, ignored -> new IdentityHashMap<>());
        var online = server.getPlayerList().getPlayers();
        identities.keySet().removeIf(player -> !online.contains(player));
        var round = RoundRuntime.snapshot(server).orElse(null);
        var changed = new ArrayList<ServerPlayer>();
        for (var player : online) {
            boolean monster = isMonster(player, round);
            var previous = identities.put(player, monster);
            if (previous == null || previous != monster) changed.add(player);
        }
        // Native updates normally follow movement. Refresh both directions on identity changes
        // so stationary clients also remove old markers or regain permitted connections.
        for (var player : changed) {
            var manager = player.level().getWaypointManager();
            manager.updateWaypoint(player);
            manager.updatePlayer(player);
        }
    }

    public static void stop(MinecraftServer server) { IDENTITIES.remove(server); }
}
