package dev.hardrush.hunt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side footprints of every live player: where the body actually stood, sampled every half second.
 * A hunt can then appear "on the way you came", which is connected to the quarry by construction.
 */
public final class HuntTrails {
    public static final int SAMPLE_TICKS = 10;
    /** Sixty seconds of footprints. */
    public static final int TRAIL_SAMPLES = 120;
    /** About three seconds of motion for the travelling direction. */
    public static final int MOTION_SAMPLES = 7;
    private static final Map<MinecraftServer, Map<ServerPlayer, Track>> TRACKS = new IdentityHashMap<>();

    private static final class Track {
        ServerLevel level;
        final ArrayDeque<Vec3> trail = new ArrayDeque<>();
        final ArrayDeque<Vec3> motion = new ArrayDeque<>();
    }
    private HuntTrails() {}

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % SAMPLE_TICKS != 0) return;
        var tracks = TRACKS.computeIfAbsent(server, ignored -> new IdentityHashMap<>());
        var online = server.getPlayerList().getPlayers();
        tracks.keySet().removeIf(player -> !online.contains(player));
        for (var player : online) record(player);
    }

    /** One sample for one body; GameTests drive this directly. */
    public static void record(ServerPlayer player) {
        var tracks = TRACKS.computeIfAbsent(player.level().getServer(), ignored -> new IdentityHashMap<>());
        if (!player.isAlive() || player.isSpectator() || !(player.level() instanceof ServerLevel level)) {
            tracks.remove(player); return;
        }
        var track = tracks.computeIfAbsent(player, ignored -> new Track());
        if (track.level != level) { track.level = level; track.trail.clear(); track.motion.clear(); }
        push(track.motion, player.position(), MOTION_SAMPLES);
        // Only real footing: never a boat, an elytra glide, a swim or a mid-air jump.
        if (player.onGround() && !player.isPassenger() && !player.isFallFlying() && !player.isSwimming())
            push(track.trail, player.position(), TRAIL_SAMPLES);
    }

    /** Newest first, in the player's current dimension only. */
    public static List<Vec3> trail(ServerPlayer player) {
        var track = track(player);
        if (track == null) return List.of();
        var newest = new ArrayList<>(track.trail);
        java.util.Collections.reverse(newest);
        return newest;
    }

    /** Average horizontal velocity in blocks per second over the last few samples. */
    public static Vec3 velocity(ServerPlayer player) {
        var track = track(player);
        if (track == null || track.motion.size() < 2) return Vec3.ZERO;
        var delta = track.motion.getLast().subtract(track.motion.getFirst());
        double seconds = (track.motion.size() - 1) * SAMPLE_TICKS / 20.0;
        return new Vec3(delta.x / seconds, 0, delta.z / seconds);
    }

    public static void clear(MinecraftServer server) { TRACKS.remove(server); }

    private static Track track(ServerPlayer player) {
        var tracks = TRACKS.get(player.level().getServer());
        var track = tracks == null ? null : tracks.get(player);
        return track != null && track.level == player.level() ? track : null;
    }
    private static void push(ArrayDeque<Vec3> samples, Vec3 point, int limit) {
        samples.addLast(point);
        while (samples.size() > limit) samples.removeFirst();
    }
}
