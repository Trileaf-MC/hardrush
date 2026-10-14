package dev.hardrush.gametest;

import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.RoundEngine;
import dev.hardrush.p0.world.RoundRuntimeLevel;
import dev.hardrush.p0.world.WorldProbe;
import dev.hardrush.p1.world.P1RoundRuntimeLevel;
import dev.hardrush.p1.world.P1RoundWorldBundle;
import dev.hardrush.p1.world.P1WorldPaths;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;

/** Dedicated-server world/portal integration only; no client or graphical interaction. */
public final class P1WorldPreparationGameTest {
    @GameTest(maxTicks = 24000)
    public void singleDimensionOpenFailureRetainsPartialBundleAndRetriesSameRound(GameTestHelper test) {
        var server = test.getLevel().getServer();
        // A new UUID owns only this disposable test fixture, never an existing runtime/user round.
        var snapshot = RoundEngine.create(UUID.randomUUID(), 1, 12345, RulesConfig.defaults(), () -> 0).committed();
        var root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        var overworldKey = ResourceKey.create(Registries.DIMENSION,
                Identifier.parse(P1WorldPaths.identifier(snapshot.roundId(), "overworld")));
        var endKey = ResourceKey.create(Registries.DIMENSION,
                Identifier.parse(P1WorldPaths.identifier(snapshot.roundId(), "end")));
        var bundle = P1RoundWorldBundle.open(server, snapshot);
        var partialOverworld = new ServerLevel[1];
        var occupiedEnd = new RuntimeLevelHandle[1];
        var openedLevels = new java.util.ArrayList<ServerLevel>();
        var manifestBeforeFailure = new String[1];
        long started = System.nanoTime();
        var lastDiagnostic = new long[1];
        test.startSequence()
                .thenWaitUntil(() -> {
                    bundle.tick();
                    paceAndDiagnose(test, bundle, started, lastDiagnostic);
                    if (bundle.failure().isPresent()) throw new IllegalStateException(bundle.failure().orElseThrow());
                    test.assertTrue(server.getLevel(overworldKey) != null,
                            "Manifest and first dimension finish before the injected End conflict");
                })
                .thenExecute(() -> {
                    partialOverworld[0] = server.getLevel(overworldKey);
                    test.assertTrue(partialOverworld[0] instanceof P1RoundRuntimeLevel, "First dimension is a real P1 runtime level");
                    test.assertTrue(server.getLevel(endKey) == null, "End has not opened yet");
                    bundle.save();
                    try {
                        manifestBeforeFailure[0] = java.nio.file.Files.readString(bundle.manifestPath());
                    } catch (java.io.IOException error) { throw new IllegalStateException(error); }
                    // Real Fantasy registration, not a production fault switch or mocked failure.
                    // The old class belongs solely to this random fixture key and same native seed/config.
                    var dimensions = WorldPresets.createNormalWorldDimensions(server.registryAccess());
                    var config = new RuntimeLevelConfig().setSeed(snapshot.seed())
                            .setGenerator(dimensions.get(LevelStem.END).orElseThrow().generator())
                            .setDimensionType(BuiltinDimensionTypes.END).setDifficulty(Difficulty.HARD)
                            .setShouldTickTime(true).setMirrorOverworldGameRules(false)
                            .setLevelConstructor(RoundRuntimeLevel::new);
                    occupiedEnd[0] = Fantasy.get(server).getOrOpenPersistentLevel(endKey.identifier(), config);
                    test.assertTrue(occupiedEnd[0].asLevel() instanceof RoundRuntimeLevel, "Fixture occupies End using the legacy runtime implementation");
                    test.assertFalse(P1RoundWorldBundle.owns(occupiedEnd[0].asLevel()), "P1 cannot claim the foreign implementation");
                })
                .thenWaitUntil(() -> {
                    bundle.tick();
                    paceAndDiagnose(test, bundle, started, lastDiagnostic);
                    test.assertTrue(bundle.failure().isPresent(), "Third dimension conflict stops the partial bundle");
                })
                .thenExecute(() -> {
                    test.assertValueEqual(bundle.state(), P1RoundWorldBundle.State.FAILED, "Partial creation records failure");
                    test.assertTrue(bundle.failure().orElseThrow().contains("其他实现占用"), "Failure identifies the actual occupied dimension");
                    test.assertFalse(bundle.ready(), "Partial dimensions never announce readiness");
                    assertUnpreparedEntry(test, bundle);
                    test.assertTrue(P1RoundWorldBundle.portalDestination(partialOverworld[0], Level.END) == null,
                            "Partial source cannot enter the foreign End or another world");
                    test.assertTrue(server.getLevel(overworldKey) == partialOverworld[0], "Completed first dimension remains registered after failure");
                    test.assertTrue(java.nio.file.Files.isDirectory(P1WorldPaths.world(root, snapshot.roundId(), "overworld")),
                            "Completed first dimension's owned files are retained");
                    test.assertTrue(partialOverworld[0].players().isEmpty() && occupiedEnd[0].asLevel().players().isEmpty(),
                            "Fault fixture never admits any players");
                    try {
                        test.assertValueEqual(java.nio.file.Files.readString(bundle.manifestPath()), manifestBeforeFailure[0],
                                "Opening failure preserves the original identity and generator manifest");
                    } catch (java.io.IOException error) { throw new IllegalStateException(error); }
                    org.slf4j.LoggerFactory.getLogger("hardrush-p1-world-test").info("P1 W05 single-dimension open failure: {}", bundle.diagnostics());
                    occupiedEnd[0].unload();
                })
                .thenWaitUntil(() -> {
                    paceAndDiagnose(test, bundle, started, lastDiagnostic);
                    test.assertTrue(server.getLevel(endKey) == null, "Fantasy fully unregisters the disposable foreign End before retry");
                })
                .thenExecute(() -> {
                    // Fantasy unload does not close native region IO; finish this fixture only after unregistering it.
                    closeNativeFixture(occupiedEnd[0].asLevel());
                    test.assertTrue(java.nio.file.Files.isDirectory(P1WorldPaths.world(root, snapshot.roundId(), "end")),
                            "Removing the conflict retains files and never deletes the owned path");
                    bundle.retry();
                })
                .thenWaitUntil(() -> {
                    bundle.tick();
                    paceAndDiagnose(test, bundle, started, lastDiagnostic);
                    if (bundle.failure().isPresent()) throw new IllegalStateException(bundle.failure().orElseThrow());
                    test.assertTrue(bundle.ready(), "Same partial bundle completes after the End conflict is removed");
                })
                .thenExecute(() -> {
                    test.assertValueEqual(bundle.roundId(), snapshot.roundId(), "Dimension-failure retry preserves round identity");
                    test.assertTrue(bundle.level(Level.OVERWORLD) == partialOverworld[0], "Retry reuses the completed first dimension");
                    for (var semantic : java.util.List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
                        var level = bundle.level(semantic);
                        openedLevels.add(level);
                        test.assertTrue(level instanceof P1RoundRuntimeLevel, "All ready dimensions use the authoritative P1 implementation");
                        test.assertValueEqual(level.getSeed(), snapshot.seed(), "Retry retains the same native seed in every dimension");
                        test.assertTrue(level.players().isEmpty(), "Retry has no fixture player references");
                    }
                    test.assertTrue(P1RoundWorldBundle.portalDestination(partialOverworld[0], Level.END) == bundle.level(Level.END),
                            "Ready routing opens only the replacement P1 End in the same round");
                    org.slf4j.LoggerFactory.getLogger("hardrush-p1-world-test").info("P1 W05 single-dimension retry metrics: {}", bundle.metrics());
                    bundle.close();
                    test.assertTrue(bundle.diagnostics().contains("held=0"), "Closing releases all temporary preparation tickets");
                })
                .thenWaitUntil(() -> {
                    test.assertTrue(openedLevels.stream().allMatch(level -> server.getLevel(level.dimension()) == null),
                            "Fantasy unregisters every disposable fixture level before native IO cleanup");
                })
                .thenExecute(() -> {
                    openedLevels.forEach(P1WorldPreparationGameTest::closeNativeFixture);
                    test.assertTrue(java.nio.file.Files.exists(bundle.manifestPath()), "Successful retry cleanup retains recovery evidence");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 24000)
    public void budgetedBundleRemainsClosedUntilThreeDimensionsAndFourExitsAreReady(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var snapshot = RoundEngine.create(UUID.randomUUID(), 1, 12345, RulesConfig.defaults(), () -> 0).committed();
        var bundle = P1RoundWorldBundle.open(server, snapshot);
        var recovered = new P1RoundWorldBundle[1];
        var readFailure = new P1RoundWorldBundle[1];
        var damaged = new P1RoundWorldBundle[1];
        var building = new net.minecraft.core.BlockPos[1];
        var preservedManifest = new String[1];
        long started = System.nanoTime();
        var lastDiagnostic = new long[1];
        test.assertFalse(bundle.ready(), "Opening an identity cannot announce world readiness");
        try {
            bundle.spawn();
            throw new IllegalStateException("Half-prepared spawn was exposed");
        } catch (IllegalStateException expected) {
            test.assertTrue(expected.getMessage().contains("尚未就绪"), "Unprepared entry explicitly stays in lobby");
        }
        test.startSequence()
                .thenWaitUntil(() -> {
                    bundle.tick();
                    paceAndDiagnose(test, bundle, started, lastDiagnostic);
                    if (bundle.failure().isPresent()) throw new IllegalStateException(bundle.failure().orElseThrow());
                    test.assertTrue(bundle.ready(), "Preparation continues across server ticks: " + bundle.state());
                })
                .thenExecute(() -> {
                    var overworld = bundle.level(Level.OVERWORLD);
                    var nether = bundle.level(Level.NETHER);
                    var end = bundle.level(Level.END);
                    for (var level : java.util.List.of(overworld, nether, end)) {
                        test.assertTrue(P1RoundWorldBundle.owns(level), "P1 has exact dimension ownership");
                        test.assertFalse(WorldProbe.owns(level), "P1 never reuses the P0 world bundle");
                        test.assertValueEqual(level.getSeed(), snapshot.seed(), "All dimensions share this round's seed");
                    }
                    test.assertTrue(P1RoundWorldBundle.portalDestination(overworld, Level.NETHER) == nether, "Nether route binds to the same round");
                    test.assertTrue(P1RoundWorldBundle.portalDestination(end, Level.OVERWORLD) == overworld, "End return binds to the same round");
                    test.assertTrue(P1RoundWorldBundle.portalDestination(overworld, server.overworld().dimension()) == overworld,
                            "Vanilla overworld semantic key cannot escape to the lobby");
                    test.assertTrue(end.getDragonFight() != null, "Real vanilla EnderDragonFight initialized");
                    test.assertTrue(bundle.exits().size() >= 2, "Multiple traversable exit corridors were prepared");
                    test.assertTrue(P1RoundWorldBundle.safeStanding(overworld, bundle.spawn()), "Spawn is safe");
                    for (var exit : bundle.exits()) {
                        test.assertTrue(P1RoundWorldBundle.safeStanding(overworld, exit), "Each exit stands on dry, solid ground");
                        long dx = exit.getX() - bundle.spawn().getX(), dz = exit.getZ() - bundle.spawn().getZ();
                        test.assertTrue(dx * dx + dz * dz > 32 * 32, "Exit reaches beyond protection");
                    }
                    test.assertTrue(bundle.metrics().changedBlocks() > 0, "Budgeted infrastructure modifications were measured");
                    test.assertTrue(bundle.metrics().candidates() > 0, "Native surface candidates were measured");
                    org.slf4j.LoggerFactory.getLogger("hardrush-p1-world-test").info("P1 initial preparation metrics: {}", bundle.metrics());
                    building[0] = bundle.spawn().offset(3, 0, 3);
                    overworld.setBlockAndUpdate(building[0], Blocks.CHEST.defaultBlockState());
                    bundle.close();
                    test.assertFalse(bundle.ready(), "Closed bundle cannot accept entry");
                    test.assertTrue(java.nio.file.Files.exists(bundle.manifestPath()), "Closing retains recovery and ownership manifest");
                    recovered[0] = P1RoundWorldBundle.open(server, snapshot);
                })
                .thenWaitUntil(() -> {
                    recovered[0].tick();
                    paceAndDiagnose(test, recovered[0], started, lastDiagnostic);
                    if (recovered[0].failure().isPresent()) throw new IllegalStateException(recovered[0].failure().orElseThrow());
                    test.assertTrue(recovered[0].ready(), "Same ready bundle restores without changing terrain");
                })
                .thenExecute(() -> {
                    var overworld = recovered[0].level(Level.OVERWORLD);
                    test.assertTrue(overworld.getBlockState(building[0]).is(Blocks.CHEST), "Recovered preparation preserves player buildings");
                    test.assertValueEqual(recovered[0].metrics().changedBlocks(), 0, "Restoring a ready manifest performs no world edits");
                    test.assertValueEqual(recovered[0].roundId(), snapshot.roundId(), "Restore keeps the same round identity");
                    test.assertValueEqual(overworld.getSeed(), snapshot.seed(), "Restore keeps the same seed");
                    org.slf4j.LoggerFactory.getLogger("hardrush-p1-world-test").info("P1 ready-world recovery metrics: {}", recovered[0].metrics());
                    recovered[0].close();
                    try {
                        preservedManifest[0] = java.nio.file.Files.readString(recovered[0].manifestPath());
                        java.nio.file.Files.writeString(recovered[0].manifestPath(), "{partial");
                    } catch (java.io.IOException error) { throw new IllegalStateException(error); }
                    readFailure[0] = P1RoundWorldBundle.open(server, snapshot);
                })
                .thenWaitUntil(() -> {
                    readFailure[0].tick();
                    paceAndDiagnose(test, readFailure[0], started, lastDiagnostic);
                    test.assertTrue(readFailure[0].failure().isPresent(), "Malformed manifest fails before worlds can be entered");
                })
                .thenExecute(() -> {
                    test.assertFalse(readFailure[0].ready(), "Manifest failure remains in lobby");
                    test.assertValueEqual(readFailure[0].roundId(), snapshot.roundId(), "Partial failure retains identity");
                    try {
                        java.nio.file.Files.writeString(readFailure[0].manifestPath(), preservedManifest[0]);
                    } catch (java.io.IOException error) { throw new IllegalStateException(error); }
                    readFailure[0].retry();
                })
                .thenWaitUntil(() -> {
                    readFailure[0].tick();
                    paceAndDiagnose(test, readFailure[0], started, lastDiagnostic);
                    if (readFailure[0].failure().isPresent()) throw new IllegalStateException(readFailure[0].failure().orElseThrow());
                    test.assertTrue(readFailure[0].ready(), "Same failed preparation resumes after manifest recovery");
                })
                .thenExecute(() -> {
                    var overworld = readFailure[0].level(Level.OVERWORLD);
                    test.assertValueEqual(readFailure[0].roundId(), snapshot.roundId(), "Retry keeps the same round identity");
                    test.assertValueEqual(overworld.getSeed(), snapshot.seed(), "Retry keeps the same seed");
                    test.assertTrue(overworld.getBlockState(building[0]).is(Blocks.CHEST), "Retry preserves player building files");
                    test.assertValueEqual(readFailure[0].metrics().changedBlocks(), 0, "Retry never regenerates ready world terrain");
                    org.slf4j.LoggerFactory.getLogger("hardrush-p1-world-test").info("P1 same-round retry metrics: {}", readFailure[0].metrics());
                    overworld.setBlockAndUpdate(readFailure[0].spawn().below(), Blocks.LAVA.defaultBlockState());
                    readFailure[0].close();
                    damaged[0] = P1RoundWorldBundle.open(server, snapshot);
                })
                .thenWaitUntil(() -> {
                    damaged[0].tick();
                    paceAndDiagnose(test, damaged[0], started, lastDiagnostic);
                    test.assertTrue(damaged[0].failure().isPresent(), "Unsafe recovered spawn must fail closed");
                })
                .thenExecute(() -> {
                    test.assertFalse(damaged[0].ready(), "Damaged ready-world infrastructure cannot unlock participation");
                    test.assertTrue(damaged[0].failure().orElseThrow().contains("受损"), "Recovery explains damaged spawn and manual action");
                    test.assertValueEqual(damaged[0].metrics().changedBlocks(), 0, "Unsafe recovery never silently repairs player terrain");
                    damaged[0].close();
                })
                .thenSucceed();
    }

    private static void paceAndDiagnose(GameTestHelper test, P1RoundWorldBundle bundle, long started, long[] lastDiagnostic) {
        long now = System.nanoTime();
        if (now - lastDiagnostic[0] >= java.util.concurrent.TimeUnit.SECONDS.toNanos(1)) {
            org.slf4j.LoggerFactory.getLogger("hardrush-p1-world-test").info("P1 preparation progress: {}", bundle.diagnostics());
            lastDiagnostic[0] = now;
        }
        test.assertTrue(now - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(120),
                "World preparation and restore finish within the explicit two-minute wall-time budget: " + bundle.diagnostics());
        // GameTestServer runs much faster than 20 TPS. Give native chunk/IO workers real time
        // instead of exhausting thousands of synthetic ticks within a few wall seconds.
        // This pacing belongs only to the server test; production tick() never parks or blocks.
        if (!bundle.ready() && bundle.failure().isEmpty()) {
            try { Thread.sleep(4); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        }
    }

    private static void assertUnpreparedEntry(GameTestHelper test, P1RoundWorldBundle bundle) {
        for (Runnable entry : java.util.List.<Runnable>of(bundle::spawn, () -> bundle.level(Level.OVERWORLD))) {
            boolean rejected = false;
            try { entry.run(); }
            catch (IllegalStateException expected) { rejected = expected.getMessage().contains("尚未就绪"); }
            test.assertTrue(rejected, "Failed partial world denies both spawn lookup and entry-level access");
        }
    }

    private static void closeNativeFixture(ServerLevel level) {
        if (level.getServer().getLevel(level.dimension()) == level) {
            throw new IllegalStateException("Cannot close a registered native fixture before Fantasy finishes unloading it");
        }
        try { level.close(); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
}
