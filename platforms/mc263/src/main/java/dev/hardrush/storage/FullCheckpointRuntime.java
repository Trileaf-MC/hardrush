package dev.hardrush.storage;

import dev.hardrush.HardRushProperties;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.round.RoundSession;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.world.RoundWorldBundle;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Native saves and the copy of changed files share one server-thread barrier; gameplay cannot advance
 * between them. Filling unchanged files, hashing, syncing and publishing then run on a background writer,
 * one image at a time, so the barrier costs about one native flush instead of a full world copy.
 */
public final class FullCheckpointRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("hardrush-full-checkpoint");
    private static final Map<MinecraftServer, Schedule> SCHEDULES = new IdentityHashMap<>();
    private static final java.util.Set<MinecraftServer> SAVING = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<MinecraftServer, CompletableFuture<?>> PUBLISHING = new IdentityHashMap<>();
    /** After any failed image the next one copies everything instead of trusting the previous image. */
    private static final java.util.Set<MinecraftServer> FULL_COPY = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "HardRush checkpoint writer");
        thread.setDaemon(true);
        return thread;
    });
    /**
     * The round's next point: it may take its barrier from {@code dueNanos} on. {@code settling} marks that it has been
     * due and writable since {@code settlingSinceNanos}; the settle limit counts only that time, so a point that was
     * due while nobody could be saved (a death screen, a building body) never fires at the instant that ends.
     */
    record Schedule(UUID round, long dueNanos, boolean settling, long settlingSinceNanos) {
        static Schedule due(UUID round, long dueNanos) { return new Schedule(round, dueNanos, false, 0); }
    }
    enum Decision { WAIT, SETTLED, FORCED }
    /**
     * A point waits at most this long for {@link #settled} once it is due and writable; then it takes its barrier
     * anyway, so constant exploration (elytra, several players spreading out) can delay a point but never skip it.
     */
    static final long SETTLE_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(60);
    /**
     * Holders the barrier may still wait on when it starts. Near players these are the edge of a view distance that is
     * already mostly generated; an isolated far landing is excluded separately because one holder there is a whole
     * generation pyramid.
     */
    static final int SETTLED_CHUNKS = 8;
    private FullCheckpointRuntime() {}

    public static boolean saving(MinecraftServer server) { return SAVING.contains(server) || RecoveryRuntime.busy(server); }

    /** An image is still being written in the background; online recovery must not read the directory yet. */
    public static boolean publishing(MinecraftServer server) {
        var publication = PUBLISHING.get(server);
        return publication != null && !publication.isDone();
    }

    /** Waits for a background image so callers (stop, tests) see a settled directory; never fails. */
    public static void awaitPublished(MinecraftServer server) {
        var publication = PUBLISHING.get(server);
        if (publication == null) return;
        try { publication.get(60, TimeUnit.SECONDS); }
        catch (Exception error) { LOGGER.warn("后台完整保存点未在停服前完成，保留上一个完整保存点：{}", error.getMessage()); }
    }

    public static void close(MinecraftServer server) {
        awaitPublished(server);
        PUBLISHING.remove(server); SCHEDULES.remove(server); SAVING.remove(server); FULL_COPY.remove(server);
    }

    /**
     * A round's first point falls due as soon as it can be written, later ones one interval after the previous
     * barrier. A due point still waits until {@link #settled}: vanilla's flushing save stops the server thread for
     * every chunk still generating, which right after the opening teleport froze the server for seconds.
     */
    public static void tick(MinecraftServer server, RoundSession session, RoundWorldBundle worlds, PlayerProfiles profiles) {
        long interval = interval();
        if (interval == 0) return;
        long now = System.nanoTime();
        var round = session.snapshot().orElse(null);
        var schedule = SCHEDULES.get(server);
        if (round != null && (schedule == null || !schedule.round().equals(round.roundId()))) schedule = Schedule.due(round.roundId(), now);
        if (schedule == null) return;
        boolean eligible = eligible(server, session, worlds, profiles);
        schedule = advance(schedule, now, eligible);
        SCHEDULES.put(server, schedule);
        if (!eligible) return;
        var decision = decide(schedule, now, () -> settled(server, worlds));
        if (decision == Decision.WAIT) return;
        barrier(server, session, worlds, profiles, now, interval, now - schedule.dueNanos(), decision == Decision.FORCED);
    }

    /** Test seam: takes the barrier now if a point could be written, without waiting for arrivals to settle. */
    public static void checkpointNow(MinecraftServer server, RoundSession session, RoundWorldBundle worlds, PlayerProfiles profiles) {
        long interval = interval();
        if (interval == 0 || !eligible(server, session, worlds, profiles)) return;
        barrier(server, session, worlds, profiles, System.nanoTime(), interval, 0, false);
    }

    /** Starts settling when a due point becomes writable; a point that stops being writable starts over next time. */
    static Schedule advance(Schedule schedule, long nowNanos, boolean eligible) {
        if (!eligible || nowNanos - schedule.dueNanos() < 0) return schedule.settling() ? Schedule.due(schedule.round(), schedule.dueNanos()) : schedule;
        return schedule.settling() ? schedule : new Schedule(schedule.round(), schedule.dueNanos(), true, nowNanos);
    }

    /** Whether a settling point takes its barrier now; {@code settled} is probed only when the limit has not run out. */
    static Decision decide(Schedule schedule, long nowNanos, java.util.function.BooleanSupplier settled) {
        if (!schedule.settling()) return Decision.WAIT;
        if (nowNanos - schedule.settlingSinceNanos() >= SETTLE_LIMIT_NANOS) return Decision.FORCED;
        return settled.getAsBoolean() ? Decision.SETTLED : Decision.WAIT;
    }

    /**
     * Every player in a round world has closed its loading screen, no far landing is generating, and the flushing save
     * would wait on no more than {@link #SETTLED_CHUNKS} chunks. The loading-screen gate covers a dimension change,
     * whose view distance generates only once the client arrives; a client that never reports loaded (no timeout,
     * unlike vanilla's {@code hasClientLoaded}) holds every point for the full settle limit.
     */
    private static boolean settled(MinecraftServer server, RoundWorldBundle worlds) {
        for (var player : server.getPlayerList().getPlayers())
            if (RoundWorldBundle.owns(player.level()) && !dev.hardrush.player.ClientLevelLoad.loaded(player)) return false;
        if (dev.hardrush.world.FarLandings.loading(server)) return false;
        try { return worlds.checkpointWaiting(SETTLED_CHUNKS) <= SETTLED_CHUNKS; }
        catch (RuntimeException error) {
            // Only timing depends on the probe; the barrier itself still verifies the worlds and reports failures.
            LOGGER.warn("保存点无法判断区块生成是否空闲，按时保存：{}", error.getMessage(), error);
            return true;
        }
    }

    private static long interval() {
        long interval = HardRushProperties.fullCheckpointIntervalSeconds();
        if (interval == 0) return 0;
        return interval < 1 || interval > 3600 ? HardRushProperties.DEFAULT_FULL_CHECKPOINT_INTERVAL_SECONDS : interval;
    }

    private static boolean eligible(MinecraftServer server, RoundSession session, RoundWorldBundle worlds, PlayerProfiles profiles) {
        if (publishing(server)) return false;
        var round = session.snapshot().orElse(null);
        return round != null && round.phase() == RoundPhase.RUNNING && worlds != null && worlds.ready()
                && session.canSaveFullCheckpoint() && profiles.readyForFullCheckpoint(round);
    }

    private static void barrier(MinecraftServer server, RoundSession session, RoundWorldBundle worlds, PlayerProfiles profiles,
                                long now, long interval, long deferredNanos, boolean forced) {
        var round = session.snapshot().orElseThrow();
        SCHEDULES.put(server, Schedule.due(round.roundId(), now + TimeUnit.SECONDS.toNanos(interval)));
        if (!session.beginFullCheckpoint()) return;
        SAVING.add(server);
        FullCheckpoint checkpoint = null;
        java.nio.file.Path pending = null;
        CompletableFuture<Void> exported = null;
        try {
            var connectedBodies = java.util.List.copyOf(server.getPlayerList().getPlayers());
            var root = server.getWorldPath(LevelResource.ROOT);
            StorageGuard.requireCapacity(root);
            profiles.flushForFullCheckpoint();
            checkpoint = new FullCheckpoint(root);
            pending = checkpoint.begin();
            var export = session.exportFullCheckpointAsync(checkpoint.database(pending));
            exported = export.written();
            var snapshot = export.snapshot();
            int unsaved = worlds.checkpointUnsaved();
            int waiting = worlds.checkpointWaiting(SETTLED_CHUNKS);
            long flushStarted = System.nanoTime();
            worlds.saveForCheckpoint();
            server.getPlayerList().saveAll();
            profiles.flushForFullCheckpoint();
            long flushed = System.nanoTime();
            if (!connectedBodies.equals(server.getPlayerList().getPlayers()) || !profiles.readyForFullCheckpoint(snapshot)) {
                throw new IllegalStateException("保存期间连接或档案发生变化，本次保存点不发布");
            }
            var staged = checkpoint.stage(pending, snapshot, System.nanoTime() + TimeUnit.SECONDS.toNanos(10), !FULL_COPY.contains(server));
            var bodies = profiles.captureFullCheckpointBodies(snapshot);
            LOGGER.info("Checkpoint barrier {} ms (native save {} ms: {} unsaved chunks, {} generating); deferred {} ms{}; copied {} files ({} KiB), reusing {} unchanged",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - now), TimeUnit.NANOSECONDS.toMillis(flushed - flushStarted),
                    unsaved, waiting > SETTLED_CHUNKS ? ">" + SETTLED_CHUNKS : waiting, TimeUnit.NANOSECONDS.toMillis(deferredNanos),
                    forced ? " (settle limit reached)" : "", staged.copied(), staged.copiedBytes() / 1024, staged.reused());
            var writer = checkpoint;
            long handedOff = System.nanoTime();
            PUBLISHING.put(server, exported.orTimeout(60, TimeUnit.SECONDS).thenRunAsync(() -> {
                try { writer.complete(staged, bodies); }
                catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
            }, WRITER).whenComplete((ignored, error) -> {
                if (error == null) {
                    FULL_COPY.remove(server);
                    LOGGER.info("Complete checkpoint saved: round={} version={} (background {} ms)", snapshot.roundId(), snapshot.version(),
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - handedOff));
                } else abandon(server, writer, staged.pending(), error, staged.reused() > 0);
            }));
        } catch (Exception error) {
            if (checkpoint != null && pending != null) {
                // The SQLite image may still be landing in this directory; discard only after it settles.
                var image = pending; var writer = checkpoint;
                var settled = exported == null ? CompletableFuture.<Void>completedFuture(null) : exported.orTimeout(60, TimeUnit.SECONDS);
                PUBLISHING.put(server, settled.handleAsync((ignored, exportError) -> null, WRITER)
                        .whenComplete((ignored, cleanupError) -> abandon(server, writer, image, error, false)));
            } else LOGGER.warn("完整保存点未更新，继续保留上一个完整保存点：{}", error.getMessage(), error);
        } finally {
            SAVING.remove(server);
            session.endFullCheckpoint();
        }
    }

    /**
     * Runs on the writer thread; the previous complete image and the latest pointer stay as they were. Only a
     * failed background half can implicate that image (a reused file may be damaged), so only it forces a full copy.
     */
    private static void abandon(MinecraftServer server, FullCheckpoint checkpoint, java.nio.file.Path pending, Throwable error, boolean distrustPrevious) {
        while (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) error = error.getCause();
        LOGGER.warn("完整保存点未更新，继续保留上一个完整保存点：{}", error.getMessage(), error);
        if (distrustPrevious) FULL_COPY.add(server);
        try { checkpoint.discardIncomplete(pending); }
        catch (Exception cleanup) { LOGGER.warn("未完成的保存点副本暂留，需检查存储：{}", cleanup.getMessage()); }
    }
}
