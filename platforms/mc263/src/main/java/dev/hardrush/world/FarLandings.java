package dev.hardrush.world;

import dev.hardrush.core.spawn.SpawnScatter;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mid-round lives (a late join, an altar revival) start empty-handed without spawn protection, so they land
 * far out in the round's Overworld instead of at its spawn: anywhere in the 128–1000 block ring, uniform by
 * area. Landings are generated ahead while the challenge runs and held loaded; an entrance reserves one before
 * it submits, so its 4-second rite never waits on terrain generation.
 */
public final class FarLandings {
    private static final Logger LOGGER = LoggerFactory.getLogger("HardRush");
    private static final Map<MinecraftServer, FarLandings> SERVICES = new IdentityHashMap<>();
    private static final TicketType HOLD = new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING);
    static final int POOL = 3;
    /** A refused or abandoned entrance returns its landing to the pool after this long. */
    private static final int RESERVE_TICKS = 600;
    private static final int LOAD_TIMEOUT_TICKS = 600;
    /** Keep a used landing's chunk held until the arriving player's own ticket has taken over. */
    private static final int RELEASE_DELAY_TICKS = 100;
    private static final int CAPTION_TICKS = 600;
    /** Biome lookups per attempt; they read the generator's noise, never terrain. */
    private static final int BIOME_TRIES = 16;
    private static final int BLOCKING_TRIES = 16;

    private record Reservation(BlockPos feet, int expires) {}
    private record Caption(String text, int expires) {}

    private final MinecraftServer server;
    private @Nullable RoundWorldBundle bundle;
    private final ArrayDeque<BlockPos> ready = new ArrayDeque<>();
    private final Map<UUID, Reservation> reserved = new HashMap<>();
    private final Set<ChunkPos> held = new HashSet<>();
    private final Map<ChunkPos, Integer> releases = new HashMap<>();
    private final Map<UUID, Caption> captions = new HashMap<>();
    private @Nullable BlockPos target;
    private @Nullable CompletableFuture<?> load;
    private int loadStarted;

    private FarLandings(MinecraftServer server) { this.server = server; }

    /** Prepares landings only while {@code open} (a ready round that is running or in rescue); otherwise releases them. */
    public static void tick(MinecraftServer server, @Nullable RoundWorldBundle worlds, boolean open) {
        SERVICES.computeIfAbsent(server, FarLandings::new).run(worlds, open && worlds != null && worlds.ready());
    }

    public static void close(MinecraftServer server) {
        var s = SERVICES.remove(server);
        if (s != null) s.releaseAll();
    }

    /** Claim a prepared landing for this player's entrance; false while none is ready yet. */
    public static boolean reserve(MinecraftServer server, UUID player) {
        var s = SERVICES.get(server);
        if (s == null || s.bundle == null) return false;
        int expires = server.getTickCount() + RESERVE_TICKS;
        var claim = s.reserved.get(player);
        var feet = claim != null ? claim.feet() : s.ready.poll();
        if (feet == null) return false;
        s.reserved.put(player, new Reservation(feet, expires));
        return true;
    }

    /**
     * Never fails: the player's reservation, any prepared landing, a blocking search (vanilla's own spawn
     * search also blocks), and finally the opening scatter around spawn.
     */
    public static NativeScatter.Arrival take(MinecraftServer server, RoundWorldBundle worlds, UUID player) {
        var s = SERVICES.computeIfAbsent(server, FarLandings::new);
        var level = worlds.level(Level.OVERWORLD);
        var spawn = worlds.spawn();
        BlockPos feet = null;
        if (s.bundle == worlds) {
            var claim = s.reserved.remove(player);
            if (claim != null) feet = s.use(level, claim.feet());
            while (feet == null && !s.ready.isEmpty()) feet = s.use(level, s.ready.poll());
        }
        if (feet == null) {
            LOGGER.warn("No prepared far landing for {}; searching synchronously", player);
            feet = blocking(level, spawn);
        }
        if (feet == null) return NativeScatter.opening(level, spawn);
        s.captions.put(player, new Caption(SpawnScatter.bearing(feet.getX() - spawn.getX(), feet.getZ() - spawn.getZ()),
                server.getTickCount() + CAPTION_TICKS));
        return new NativeScatter.Arrival(level, Vec3.atBottomCenterOf(feet), level.getRespawnData().yaw());
    }

    /** The bearing caption, once, after the far-landed body has loaded into its formal world. */
    public static Optional<String> arrived(ServerPlayer player) {
        var s = SERVICES.get(player.level().getServer());
        if (s == null || !s.captions.containsKey(player.getUUID()) || !RoundWorldBundle.owns(player.level())
                || dev.hardrush.player.PlayerProfiles.isLocked(player)
                || player.connection == null || !player.connection.hasClientLoaded()) return Optional.empty();
        return Optional.of(s.captions.remove(player.getUUID()).text());
    }

    /** Test seam: landings prepared and not yet reserved. */
    public static int prepared(MinecraftServer server) {
        var s = SERVICES.get(server);
        return s == null ? 0 : s.ready.size();
    }

    private void run(@Nullable RoundWorldBundle worlds, boolean open) {
        int now = server.getTickCount();
        if (worlds != bundle) { releaseAll(); captions.clear(); bundle = worlds; }
        captions.values().removeIf(caption -> now > caption.expires());
        if (!open) { releaseAll(); return; }
        var level = bundle.level(Level.OVERWORLD);
        releases.entrySet().removeIf(release -> {
            if (now < release.getValue()) return false;
            drop(level, release.getKey());
            return true;
        });
        reserved.values().removeIf(claim -> {
            if (now < claim.expires()) return false;
            ready.add(claim.feet());
            return true;
        });
        if (load != null) {
            if (load.isDone()) {
                var feet = settle(level, target);
                if (feet != null) ready.add(feet);
                else drop(level, chunk(target));
                load = null;
            } else if (now - loadStarted > LOAD_TIMEOUT_TICKS) {
                drop(level, chunk(target));
                load = null;
            }
            return;
        }
        if (ready.size() + reserved.size() >= POOL) return;
        var column = candidate(level, bundle.spawn(), held);
        if (column == null) return;
        target = column;
        loadStarted = now;
        held.add(chunk(column));
        load = level.getChunkSource().addTicketAndLoadWithRadius(HOLD, chunk(column), 0);
    }

    /** A dry-land column of the ring; oceans and rivers are skipped from biome noise before any terrain exists. */
    private static @Nullable BlockPos candidate(ServerLevel level, BlockPos spawn, Set<ChunkPos> taken) {
        var random = ThreadLocalRandom.current();
        for (int i = 0; i < BIOME_TRIES; i++) {
            var offset = SpawnScatter.farOffset(random);
            var column = new BlockPos(spawn.getX() + (int) Math.round(offset[0]), level.getSeaLevel(),
                    spawn.getZ() + (int) Math.round(offset[1]));
            if (!level.getWorldBorder().isWithinBounds(column) || taken.contains(chunk(column))) continue;
            var biome = level.getUncachedNoiseBiome(column.getX() >> 2, column.getY() >> 2, column.getZ() >> 2);
            if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)) continue;
            return column;
        }
        return null;
    }

    /** The chosen column, else the first standing column of a 4×4 grid in its chunk. */
    private static @Nullable BlockPos settle(ServerLevel level, BlockPos column) {
        if (level.getChunkSource().getChunkNow(column.getX() >> 4, column.getZ() >> 4) == null) return null;
        var feet = standing(level, column.getX(), column.getZ());
        int minX = column.getX() & ~15, minZ = column.getZ() & ~15;
        for (int i = 0; feet == null && i < 16; i++) feet = standing(level, minX + 2 + i % 4 * 4, minZ + 2 + i / 4 * 4);
        return feet;
    }

    private static @Nullable BlockPos standing(ServerLevel level, int x, int z) {
        var feet = NativeScatter.surface(level, x, z);
        return feet != null && RoundWorldBundle.safeStanding(level, feet) ? feet : null;
    }

    private static @Nullable BlockPos blocking(ServerLevel level, BlockPos spawn) {
        for (int i = 0; i < BLOCKING_TRIES; i++) {
            var column = candidate(level, spawn, Set.of());
            if (column == null) continue;
            level.getChunk(column.getX() >> 4, column.getZ() >> 4);
            var feet = settle(level, column);
            if (feet != null) return feet;
        }
        return null;
    }

    private @Nullable BlockPos use(ServerLevel level, BlockPos feet) {
        releases.put(chunk(feet), server.getTickCount() + RELEASE_DELAY_TICKS);
        return RoundWorldBundle.safeStanding(level, feet) ? feet : null;
    }

    private void drop(ServerLevel level, ChunkPos chunk) {
        if (held.remove(chunk)) level.getChunkSource().removeTicketWithRadius(HOLD, chunk, 0);
    }

    private void releaseAll() {
        if (bundle != null && bundle.ready()) {
            var level = bundle.level(Level.OVERWORLD);
            for (var chunk : held) level.getChunkSource().removeTicketWithRadius(HOLD, chunk, 0);
        }
        held.clear(); ready.clear(); reserved.clear(); releases.clear();
        load = null; target = null;
    }

    private static ChunkPos chunk(BlockPos pos) { return new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4); }
}
