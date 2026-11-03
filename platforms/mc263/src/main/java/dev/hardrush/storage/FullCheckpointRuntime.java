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
    private record Schedule(UUID round, long nextNanos) {}
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

    public static void tick(MinecraftServer server, RoundSession session, RoundWorldBundle worlds, PlayerProfiles profiles) {
        long interval = HardRushProperties.fullCheckpointIntervalSeconds();
        if (interval == 0) return;
        if (interval < 1 || interval > 3600) interval = HardRushProperties.DEFAULT_FULL_CHECKPOINT_INTERVAL_SECONDS;
        if (publishing(server)) return;
        var round = session.snapshot().orElse(null);
        if (round == null || round.phase() != RoundPhase.RUNNING || worlds == null || !worlds.ready()
                || !session.canSaveFullCheckpoint() || !profiles.readyForFullCheckpoint(round)) return;
        long now = System.nanoTime();
        var schedule = SCHEDULES.get(server);
        if (schedule != null && schedule.round().equals(round.roundId()) && now - schedule.nextNanos() < 0) return;
        SCHEDULES.put(server, new Schedule(round.roundId(), now + TimeUnit.SECONDS.toNanos(interval)));
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
            long flushStarted = System.nanoTime();
            worlds.save();
            server.getPlayerList().saveAll();
            profiles.flushForFullCheckpoint();
            long flushed = System.nanoTime();
            if (!connectedBodies.equals(server.getPlayerList().getPlayers()) || !profiles.readyForFullCheckpoint(snapshot)) {
                throw new IllegalStateException("保存期间连接或档案发生变化，本次保存点不发布");
            }
            var staged = checkpoint.stage(pending, snapshot, System.nanoTime() + TimeUnit.SECONDS.toNanos(10), !FULL_COPY.contains(server));
            var bodies = profiles.captureFullCheckpointBodies(snapshot);
            LOGGER.info("Checkpoint barrier {} ms (native save {} ms); copied {} files ({} KiB), reusing {} unchanged",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - now), TimeUnit.NANOSECONDS.toMillis(flushed - flushStarted),
                    staged.copied(), staged.copiedBytes() / 1024, staged.reused());
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
