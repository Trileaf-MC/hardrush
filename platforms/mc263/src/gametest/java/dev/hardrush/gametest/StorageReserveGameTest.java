package dev.hardrush.gametest;

import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.RoundEngine;
import dev.hardrush.storage.StorageGuard;
import dev.hardrush.world.RoundWorldBundle;
import dev.hardrush.world.RoundWorldPaths;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/** Isolated reserve admission failure; does not fill a real disk or touch an existing round. */
public final class StorageReserveGameTest {
    @GameTest(maxTicks = 24000)
    public void unavailableReserveRetainsIdentityAndRequiresExplicitSameRoundRetry(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.p3.testStorage")) { test.succeed(); return; }
        var server = test.getLevel().getServer();
        var root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        var snapshot = RoundEngine.create(UUID.randomUUID(), 1, 12345, RulesConfig.defaults(), () -> 0).committed();
        String previous = System.getProperty(StorageGuard.MINIMUM_FREE_PROPERTY);
        System.setProperty(StorageGuard.MINIMUM_FREE_PROPERTY, Long.toString(Long.MAX_VALUE));
        var bundle = RoundWorldBundle.open(server, snapshot);
        var closed = new CompletableFuture<?>[1];
        test.startSequence()
                .thenWaitUntil(() -> {
                    bundle.tick();
                    pace();
                    test.assertTrue(bundle.state() == RoundWorldBundle.State.FAILED, "Insufficient reserve rejects preparation");
                })
                .thenExecute(() -> {
                    try {
                        test.assertTrue(bundle.failure().orElseThrow().contains("最低储备"), "Diagnostic identifies reserve and usable bytes");
                        test.assertFalse(bundle.ready(), "Storage fault cannot admit players");
                        test.assertFalse(Files.exists(bundle.manifestPath()), "No ownership manifest is written before storage admission");
                        for (String name : java.util.List.of("overworld", "nether", "end")) {
                            var key = ResourceKey.create(Registries.DIMENSION,
                                    Identifier.parse(RoundWorldPaths.identifier(snapshot.roundId(), name)));
                            test.assertTrue(server.getLevel(key) == null, "No native dimension opens without reserve");
                            test.assertFalse(Files.exists(RoundWorldPaths.world(root, snapshot.roundId(), name)), "Fault does not create a world directory");
                        }
                    } finally {
                        if (previous == null) System.clearProperty(StorageGuard.MINIMUM_FREE_PROPERTY);
                        else System.setProperty(StorageGuard.MINIMUM_FREE_PROPERTY, previous);
                    }
                    bundle.tick();
                    test.assertValueEqual(bundle.state(), RoundWorldBundle.State.FAILED, "Repairing capacity alone never clears the failure");
                    bundle.retry();
                })
                .thenWaitUntil(() -> {
                    bundle.tick();
                    pace();
                    if (bundle.failure().isPresent()) throw new IllegalStateException(bundle.failure().orElseThrow());
                    test.assertTrue(bundle.ready(), "Explicit retry prepares the same round after capacity is repaired");
                })
                .thenExecute(() -> {
                    test.assertValueEqual(bundle.roundId(), snapshot.roundId(), "Retry preserves the durable round ID");
                    for (var dimension : java.util.List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
                        test.assertValueEqual(bundle.level(dimension).getSeed(), snapshot.seed(), "Retry preserves all native seeds");
                    }
                    test.assertTrue(Files.isRegularFile(bundle.manifestPath()), "Ownership manifest exists only after admitted retry");
                    closed[0] = bundle.saveAndClose().toCompletableFuture();
                })
                .thenWaitUntil(() -> {
                    bundle.tick();
                    pace();
                    if (closed[0].isCompletedExceptionally()) closed[0].join();
                    test.assertTrue(closed[0].isDone(), "All native world handles close before fixture success");
                    test.assertTrue(Files.isRegularFile(bundle.manifestPath()), "Closing retains all recovery evidence");
                })
                .thenExecute(() -> {
                    // Deliberately never tick/join this bundle: shutdown itself must see the
                    // worker's failure rather than treat executor termination as successful IO.
                    System.setProperty(StorageGuard.MINIMUM_FREE_PROPERTY, Long.toString(Long.MAX_VALUE));
                    var stoppingSnapshot = RoundEngine.create(UUID.randomUUID(), 2, 34567,
                            RulesConfig.defaults(), () -> 0).committed();
                    var stoppingBundle = RoundWorldBundle.open(server, stoppingSnapshot);
                    boolean rejected = false;
                    try { stoppingBundle.shutdown(); }
                    catch (IllegalStateException expected) {
                        rejected = expected.getMessage().contains("不能标记正常停服");
                    } finally {
                        if (previous == null) System.clearProperty(StorageGuard.MINIMUM_FREE_PROPERTY);
                        else System.setProperty(StorageGuard.MINIMUM_FREE_PROPERTY, previous);
                    }
                    test.assertTrue(rejected, "Shutdown propagates a storage failure before any tick observes it");
                    test.assertFalse(Files.exists(stoppingBundle.manifestPath()), "Failed stop preserves absence of an ownership claim");
                })
                .thenSucceed();
    }

    private static void pace() {
        try { Thread.sleep(2); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
}
