package dev.hardrush.gametest;

import dev.hardrush.core.round.*;
import dev.hardrush.p1.P1LobbyRuntime;
import dev.hardrush.p1.P1LobbySession;
import dev.hardrush.p1.world.P1RoundWorldBundle;
import dev.hardrush.p1.world.P1WorldPaths;
import dev.hardrush.persistence.SqliteRoundStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/** Opt-in real runtime repair and release; closed-world recovery is injected, not a process crash claim. */
public final class P3RecoveryGameTest {
    @GameTest(maxTicks = 60000)
    public void failedStorageRepairsIntoQuarantineAndRetiresLoadedAndAlreadyClosedWorlds(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.p3.testRecovery")) { test.succeed(); return; }
        var f = new Fixture(test);
        test.startSequence()
            .thenWaitUntil(() -> f.awaitReady(1))
            .thenExecute(() -> {
                f.session = f.service("SESSIONS", P1LobbySession.class);
                f.first = f.service("WORLDS", P1RoundWorldBundle.class);
                f.old = f.round();
                f.oldLevels = List.of(f.first.level(Level.OVERWORLD), f.first.level(Level.NETHER), f.first.level(Level.END));
                f.marker = f.first.spawn().offset(2, 1, 2);
                f.first.level(Level.OVERWORLD).setBlockAndUpdate(f.marker, Blocks.GOLD_BLOCK.defaultBlockState());
                f.task = f.sql("CREATE TRIGGER p3_refuse_audit BEFORE INSERT ON admin_audits BEGIN SELECT RAISE(ABORT,'P3 storage failure'); END");
            })
            .thenWaitUntil(() -> f.awaitIo())
            .thenExecute(() -> f.session.updateNextMode(f.old.rules().mode(), "fixture", "注入真实SQLite写入故障", f.reply::set))
            .thenWaitUntil(() -> f.awaitReply(false))
            .thenExecute(() -> {
                test.assertValueEqual(f.session.state(), P1LobbySession.State.FAILED, "Write failure freezes the real runtime");
                test.assertTrue(f.first.ready(), "Storage failure retains the three actual worlds");
                f.reply.set(null); f.session.recheckStorage("fixture", "故障尚未修复", f.reply::set);
            })
            .thenWaitUntil(() -> f.awaitReply(false))
            .thenExecute(() -> {
                test.assertValueEqual(f.session.state(), P1LobbySession.State.FAILED, "Unrepaired audit failure remains frozen");
                f.task = f.sql("DROP TRIGGER p3_refuse_audit");
            })
            .thenWaitUntil(() -> f.awaitIo())
            .thenExecute(() -> { f.reply.set(null); f.session.recheckStorage("fixture", "存储已修复", f.reply::set); })
            .thenWaitUntil(() -> f.awaitReply(true))
            .thenExecute(() -> {
                test.assertValueEqual(f.round().phase(), RoundPhase.RECOVERY_REQUIRED, "Repair does not resume competition");
                test.assertValueEqual(f.round().roundId(), f.old.roundId(), "Repair retains the original round");
                test.assertTrue(f.session.competitionLocked(), "All native competition gates remain closed");
                test.assertValueEqual(f.first.level(Level.OVERWORLD).getBlockState(f.marker).getBlock(), Blocks.GOLD_BLOCK, "Repair retains actual terrain");
                f.reply.set(null); f.session.closeRecovery("fixture", "核对后结束恢复轮", f.reply::set);
            })
            .thenWaitUntil(() -> f.awaitReply(true))
            .thenWaitUntil(() -> f.awaitReady(2))
            .thenExecute(() -> {
                f.assertReleased(f.first, f.oldLevels, f.old.roundId());
                f.second = f.service("WORLDS", P1RoundWorldBundle.class);
                f.closed = f.round();
                f.closedLevels = List.of(f.second.level(Level.OVERWORLD), f.second.level(Level.NETHER), f.second.level(Level.END));
                f.session.adapterFailure("模拟上一进程已关闭世界", new IllegalStateException("closed-world recovery fixture"));
                f.reply.set(null); f.session.recheckStorage("fixture", "核验第二轮原数据库", f.reply::set);
            })
            .thenWaitUntil(() -> f.awaitReply(true))
            .thenExecute(() -> {
                f.task = f.second.saveAndClose().toCompletableFuture();
            })
            .thenWaitUntil(() -> { f.second.tick(); f.awaitIo(); })
            .thenExecute(() -> {
                // Model the previous process's closed worlds without pretending this is a forced JVM exit.
                f.removeWorldService();
                f.reply.set(null); f.session.closeRecovery("fixture", "核验上一进程已关闭世界", f.reply::set);
            })
            .thenWaitUntil(() -> {
                f.assertUnloaded(f.closedLevels);
                f.awaitReply(true);
            })
            .thenWaitUntil(() -> { f.assertUnloaded(f.closedLevels); f.awaitReady(3); })
            .thenExecute(() -> {
                f.assertReleased(f.second, f.closedLevels, f.closed.roundId());
                f.task = CompletableFuture.runAsync(() -> {
                    try (var store = new SqliteRoundStore(f.database)) {
                        var audits = store.audits(null, 10, 0);
                        test.assertValueEqual(audits.size(), 4, "Only two successful repairs and two recovery decisions are audited");
                        test.assertValueEqual(store.history(10, 0).size(), 2, "Each retired round owns exactly one aborted history");
                        for (var row : store.history(10, 0)) {
                            test.assertValueEqual(row.result().kind(), RoundResult.Kind.ABORTED, "Unfinished rounds never become completed results");
                            test.assertTrue(row.timingUncertain(), "Retired unfinished rounds remain uncertain");
                        }
                    } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
                });
            })
            .thenWaitUntil(() -> f.awaitIo())
            .thenSucceed();
    }

    private static final class Fixture {
        final GameTestHelper test; final MinecraftServer server; final Path root, database;
        final AtomicReference<P1LobbySession.Reply> reply = new AtomicReference<>();
        P1LobbySession session; P1RoundWorldBundle first, second; RoundSnapshot old, closed;
        List<ServerLevel> oldLevels, closedLevels; BlockPos marker; CompletableFuture<?> task;
        Fixture(GameTestHelper test) {
            this.test = test; server = test.getLevel().getServer();
            root = server.getWorldPath(LevelResource.ROOT); database = root.resolve("hardrush/p1.sqlite");
        }
        RoundSnapshot round() { return session.snapshot().orElseThrow(); }
        boolean awaitReady(long sequence) {
            pace();
            var s = service("SESSIONS", P1LobbySession.class);
            if (s != null && s.state() == P1LobbySession.State.FAILED) throw new IllegalStateException(P1LobbyRuntime.status(server));
            test.assertTrue(s != null && s.state() == P1LobbySession.State.OPEN, "Runtime opens: " + P1LobbyRuntime.status(server));
            var r = s.snapshot().orElseThrow();
            test.assertTrue(r.sequence() == sequence && r.phase() == RoundPhase.WAITING && !s.mutationPending(), "Expected round is prepared");
            return true;
        }
        boolean awaitReply(boolean success) {
            pace(); test.assertTrue(reply.get() != null, "IO reply completed");
            if (reply.get().success() != success) throw new IllegalStateException("Unexpected recovery result: " + reply.get().message());
            return true;
        }
        boolean awaitIo() {
            pace(); if (task.isCompletedExceptionally()) task.join(); test.assertTrue(task.isDone(), "Native/SQLite IO completed"); return true;
        }
        CompletableFuture<Void> sql(String sql) {
            return CompletableFuture.runAsync(() -> {
                try (var c = DriverManager.getConnection("jdbc:sqlite:" + database); var s = c.createStatement()) { s.execute(sql); }
                catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
            });
        }
        void assertUnloaded(List<ServerLevel> levels) {
            for (var level : levels) test.assertTrue(server.getLevel(level.dimension()) == null, "Previous native dimension is never reopened");
        }
        void assertReleased(P1RoundWorldBundle bundle, List<ServerLevel> levels, UUID id) {
            assertUnloaded(levels);
            test.assertValueEqual(bundle.state(), P1RoundWorldBundle.State.CLOSED, "Native world bundle closed");
            test.assertTrue(Files.isRegularFile(bundle.manifestPath()), "Retirement retains the original manifest");
            for (String dimension : List.of("overworld", "nether", "end")) {
                test.assertTrue(Files.isDirectory(P1WorldPaths.world(root, id, dimension)), "Retirement retains the original world directory");
            }
        }
        <T> T service(String name, Class<T> type) {
            try { var field = P1LobbyRuntime.class.getDeclaredField(name); field.setAccessible(true); return type.cast(((Map<?, ?>) field.get(null)).get(server)); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        void removeWorldService() {
            try { var field = P1LobbyRuntime.class.getDeclaredField("WORLDS"); field.setAccessible(true); ((Map<?, ?>) field.get(null)).remove(server); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
    }
    private static void pace() {
        try { Thread.sleep(2); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
}
