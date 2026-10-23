package dev.hardrush.p1.storage;

import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.p1.P1LobbySession;
import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p1.world.P1RoundWorldBundle;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Native saves and copies share one server-thread barrier; gameplay cannot advance between them. */
public final class P1FullCheckpointRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("hardrush-full-checkpoint");
    private static final Map<MinecraftServer, Schedule> SCHEDULES = new IdentityHashMap<>();
    private static final java.util.Set<MinecraftServer> SAVING = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private record Schedule(UUID round, long nextNanos) {}
    private P1FullCheckpointRuntime() {}

    public static boolean saving(MinecraftServer server) { return SAVING.contains(server) || P1RecoveryRuntime.busy(server); }
    public static void close(MinecraftServer server) { SCHEDULES.remove(server); SAVING.remove(server); }

    public static void tick(MinecraftServer server, P1LobbySession session, P1RoundWorldBundle worlds, P1PlayerProfiles profiles) {
        long interval = Long.getLong("hardrush.p1.fullCheckpointIntervalSeconds", 60L);
        if (interval == 0) return;
        if (interval < 1 || interval > 3600) interval = 60;
        var round = session.snapshot().orElse(null);
        if (round == null || round.phase() != RoundPhase.RUNNING || worlds == null || !worlds.ready()
                || !session.canSaveFullCheckpoint() || !profiles.readyForFullCheckpoint(round)) return;
        long now = System.nanoTime();
        var schedule = SCHEDULES.get(server);
        if (schedule != null && schedule.round().equals(round.roundId()) && now - schedule.nextNanos() < 0) return;
        SCHEDULES.put(server, new Schedule(round.roundId(), now + TimeUnit.SECONDS.toNanos(interval)));
        if (!session.beginFullCheckpoint()) return;
        SAVING.add(server);
        P1FullCheckpoint checkpoint = null;
        java.nio.file.Path pending = null;
        try {
            var connectedBodies = java.util.List.copyOf(server.getPlayerList().getPlayers());
            var root = server.getWorldPath(LevelResource.ROOT);
            P1StorageGuard.requireCapacity(root);
            profiles.flushForFullCheckpoint();
            checkpoint = new P1FullCheckpoint(root);
            pending = checkpoint.begin();
            var snapshot = session.exportFullCheckpoint(checkpoint.database(pending));
            worlds.save();
            server.getPlayerList().saveAll();
            profiles.flushForFullCheckpoint();
            if (!connectedBodies.equals(server.getPlayerList().getPlayers()) || !profiles.readyForFullCheckpoint(snapshot)) {
                throw new IllegalStateException("保存期间连接或档案发生变化，本次保存点不发布");
            }
            checkpoint.publish(pending, snapshot, System.nanoTime() + TimeUnit.SECONDS.toNanos(10), profiles.captureFullCheckpointBodies(snapshot));
            LOGGER.info("Complete checkpoint saved: round={} version={}", snapshot.roundId(), snapshot.version());
        } catch (Exception error) {
            LOGGER.warn("完整保存点未更新，继续保留上一个完整保存点：{}", error.getMessage(), error);
            if (checkpoint != null && pending != null) {
                try { checkpoint.discardIncomplete(pending); }
                catch (Exception cleanup) { LOGGER.warn("未完成的保存点副本暂留，需检查存储：{}", cleanup.getMessage()); }
            }
        } finally {
            SAVING.remove(server);
            session.endFullCheckpoint();
        }
    }
}
