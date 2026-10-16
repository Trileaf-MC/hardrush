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
        var closed = new java.util.concurrent.CompletableFuture<?>[1];
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
                    closed[0] = bundle.saveAndClose().toCompletableFuture();
                })
                .thenWaitUntil(() -> {
                    bundle.tick();
                    paceAndDiagnose(test, bundle, started, lastDiagnostic);
                    if (closed[0].isCompletedExceptionally()) closed[0].join();
                    test.assertTrue(closed[0].isDone(), "Save-and-close finishes before rotation or recovery");
                    test.assertTrue(openedLevels.stream().allMatch(level -> server.getLevel(level.dimension()) == null),
                            "Fantasy unregisters every owned fixture before save-and-close succeeds");
                })
                .thenExecute(() -> {
                    test.assertTrue(bundle.diagnostics().contains("held=0"), "Closing releases all temporary preparation tickets");
                    test.assertTrue(java.nio.file.Files.exists(bundle.manifestPath()), "Successful retry cleanup retains recovery evidence");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 24000)
    public void budgetedBundlePreservesNaturalSpawnAndRecoversWithoutExitCorridors(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var snapshot = RoundEngine.create(UUID.randomUUID(), 1, 12345, RulesConfig.defaults(), () -> 0).committed();
        var bundle = P1RoundWorldBundle.open(server, snapshot);
        var recovered = new P1RoundWorldBundle[1];
        var readFailure = new P1RoundWorldBundle[1];
        var damaged = new P1RoundWorldBundle[1];
        var building = new net.minecraft.core.BlockPos[1];
        var preservedManifest = new String[1];
        var closed = new java.util.concurrent.CompletableFuture<?>[1];
        var initialLevels = new java.util.ArrayList<ServerLevel>();
        var naturalSurface = new java.util.HashMap<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState>();
        var sampledChunks = new java.util.HashSet<net.minecraft.world.level.ChunkPos>();
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
                    captureNaturalSurface(server, snapshot.roundId(), naturalSurface, sampledChunks);
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
                        initialLevels.add(level);
                        test.assertTrue(P1RoundWorldBundle.owns(level), "P1 has exact dimension ownership");
                        test.assertFalse(WorldProbe.owns(level), "P1 never reuses the P0 world bundle");
                        test.assertValueEqual(level.getSeed(), snapshot.seed(), "All dimensions share this round's seed");
                    }
                    test.assertTrue(P1RoundWorldBundle.portalDestination(overworld, Level.NETHER) == nether, "Nether route binds to the same round");
                    test.assertTrue(P1RoundWorldBundle.portalDestination(end, Level.OVERWORLD) == overworld, "End return binds to the same round");
                    test.assertTrue(P1RoundWorldBundle.portalDestination(overworld, server.overworld().dimension()) == overworld,
                            "Vanilla overworld semantic key cannot escape to the lobby");
                    test.assertTrue(end.getDragonFight() != null, "Real vanilla EnderDragonFight initialized");
                    test.assertTrue(bundle.exits().isEmpty(), "Natural spawn requires no artificial exit corridors");
                    test.assertTrue(P1RoundWorldBundle.safeStanding(overworld, bundle.spawn()), "Spawn is safe");
                    test.assertTrue(naturalSurface.containsKey(bundle.spawn().below()), "Native terrain was observed before spawn preparation");
                    for (var entry : naturalSurface.entrySet()) {
                        test.assertValueEqual(overworld.getBlockState(entry.getKey()), entry.getValue(),
                                "Spawn preparation preserves native surface blocks at " + entry.getKey());
                    }
                    test.assertTrue(bundle.metrics().changedBlocks() <= 100, "Only vanilla's 5x5 End arrival platform may be prepared");
                    test.assertTrue(bundle.metrics().candidates() > 0, "Native surface candidates were measured");
                    org.slf4j.LoggerFactory.getLogger("hardrush-p1-world-test").info("P1 initial preparation metrics: {}", bundle.metrics());
                    building[0] = bundle.spawn().offset(3, 0, 3);
                    overworld.setBlockAndUpdate(building[0], Blocks.CHEST.defaultBlockState());
                    var player = test.makeMockServerPlayerInLevel();
                    try {
                        for (var level : initialLevels) {
                            player.teleportTo(level, bundle.spawn().getX() + .5, bundle.spawn().getY(), bundle.spawn().getZ() + .5,
                                    java.util.Set.of(), 0, 0, true);
                            boolean refused = false;
                            try { bundle.saveAndClose(); }
                            catch (IllegalStateException expected) { refused = expected.getMessage().contains("玩家"); }
                            test.assertTrue(refused, "Save-and-close refuses players in each owned dimension");
                            test.assertTrue(bundle.ready(), "An occupied close refusal does not modify readiness or enqueue unloading");
                            test.assertTrue(initialLevels.stream().allMatch(owned -> server.getLevel(owned.dimension()) == owned),
                                    "Occupied refusal retains all three registered dimensions");
                        }
                    } finally {
                        player.teleportTo(test.getLevel(), test.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1)).getX(),
                                test.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1)).getY(),
                                test.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1)).getZ(), java.util.Set.of(), 0, 0, true);
                        server.getPlayerList().remove(player);
                    }
                    // Tamper only this random disposable round's manifest: reject lobby/root paths
                    // before saving or releasing even one native handle, then retry after restoring it.
                    try {
                        preservedManifest[0] = java.nio.file.Files.readString(bundle.manifestPath());
                        var json = com.google.gson.JsonParser.parseString(preservedManifest[0]).getAsJsonObject();
                        json.getAsJsonObject("dimensions").getAsJsonObject("end").addProperty("ownedPath", ".");
                        java.nio.file.Files.writeString(bundle.manifestPath(), json.toString());
                    } catch (java.io.IOException error) { throw new IllegalStateException(error); }
                    closed[0] = bundle.saveAndClose().toCompletableFuture();
                    test.assertFalse(bundle.ready(), "Closed bundle cannot accept entry");
                    boolean competingOpenRefused = false;
                    try { P1RoundWorldBundle.open(server, snapshot); }
                    catch (IllegalStateException expected) { competingOpenRefused = expected.getMessage().contains("真实关闭"); }
                    test.assertTrue(competingOpenRefused, "A pending close cannot be canceled by reopening the same ID");
                })
                .thenWaitUntil(() -> {
                    bundle.tick();
                    paceAndDiagnose(test, bundle, started, lastDiagnostic);
                    test.assertTrue(closed[0].isCompletedExceptionally(), "Tampered world ownership rejects close");
                })
                .thenExecute(() -> {
                    test.assertValueEqual(bundle.state(), P1RoundWorldBundle.State.CLOSE_FAILED, "Unsafe close has an explicit recoverable state");
                    test.assertTrue(initialLevels.stream().allMatch(level -> server.getLevel(level.dimension()) == level),
                            "Ownership failure retains all three active worlds");
                    test.assertTrue(java.nio.file.Files.isDirectory(server.getWorldPath(LevelResource.ROOT)), "Ownership refusal retains lobby and root");
                    try { java.nio.file.Files.writeString(bundle.manifestPath(), preservedManifest[0]); }
                    catch (java.io.IOException error) { throw new IllegalStateException(error); }
                    closed[0] = bundle.saveAndClose().toCompletableFuture();
                })
                .thenWaitUntil(() -> awaitClosed(test, bundle, closed[0], started, lastDiagnostic))
                .thenExecute(() -> {
                    test.assertTrue(initialLevels.stream().allMatch(level -> server.getLevel(level.dimension()) == null),
                            "Successful close completes only after all dimensions leave the server registry");
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
                    test.assertTrue(initialLevels.stream().noneMatch(level -> level == recovered[0].level(Level.OVERWORLD)),
                            "Recovery acquires a fresh native handle after releasing old region IO");
                    closed[0] = recovered[0].saveAndClose().toCompletableFuture();
                })
                .thenWaitUntil(() -> awaitClosed(test, recovered[0], closed[0], started, lastDiagnostic))
                .thenExecute(() -> {
                    try {
                        // Reopen a legacy manifest whose former elevated corridors no longer exist.
                        // Its spawn is still safe, so obsolete exit infrastructure must not gate recovery.
                        var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(recovered[0].manifestPath())).getAsJsonObject();
                        var legacyExits = new com.google.gson.JsonArray();
                        for (var direction : java.util.List.of(net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH)) {
                            var exit = json.getAsJsonObject("spawn").deepCopy();
                            exit.addProperty("z", exit.get("z").getAsInt() + direction.getStepZ() * 40);
                            exit.addProperty("y", exit.get("y").getAsInt() + 35);
                            legacyExits.add(exit);
                        }
                        json.add("exits", legacyExits);
                        preservedManifest[0] = json.toString();
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
                    test.assertValueEqual(readFailure[0].exits().size(), 2, "Legacy exit metadata remains readable without rebuilding or validating corridors");
                    test.assertTrue(overworld.getBlockState(building[0]).is(Blocks.CHEST), "Retry preserves player building files");
                    test.assertValueEqual(readFailure[0].metrics().changedBlocks(), 0, "Retry never regenerates ready world terrain");
                    org.slf4j.LoggerFactory.getLogger("hardrush-p1-world-test").info("P1 same-round retry metrics: {}", readFailure[0].metrics());
                    overworld.setBlockAndUpdate(readFailure[0].spawn().below(), Blocks.LAVA.defaultBlockState());
                    closed[0] = readFailure[0].saveAndClose().toCompletableFuture();
                })
                .thenWaitUntil(() -> awaitClosed(test, readFailure[0], closed[0], started, lastDiagnostic))
                .thenExecute(() -> {
                    damaged[0] = P1RoundWorldBundle.open(server, snapshot);
                })
                .thenWaitUntil(() -> {
                    damaged[0].tick();
                    paceAndDiagnose(test, damaged[0], started, lastDiagnostic);
                    test.assertTrue(damaged[0].failure().isPresent(), "Unsafe recovered spawn must fail closed");
                })
                .thenExecute(() -> {
                    test.assertFalse(damaged[0].ready(), "An unsafe natural spawn cannot unlock participation");
                    test.assertTrue(damaged[0].failure().orElseThrow().contains("受损"), "Recovery explains damaged spawn and manual action");
                    test.assertValueEqual(damaged[0].metrics().changedBlocks(), 0, "Unsafe recovery never silently repairs player terrain");
                    closed[0] = damaged[0].saveAndClose().toCompletableFuture();
                })
                .thenWaitUntil(() -> awaitClosed(test, damaged[0], closed[0], started, lastDiagnostic))
                .thenSucceed();
    }

    private static void captureNaturalSurface(net.minecraft.server.MinecraftServer server, UUID roundId,
            java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> surface,
            java.util.Set<net.minecraft.world.level.ChunkPos> sampled) {
        var key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(P1WorldPaths.identifier(roundId, "overworld")));
        var level = server.getLevel(key);
        if (level == null) return;
        var origin = level.getChunkSource().getGenerator().getOrigin(level.getChunkSource().randomState());
        for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) {
            var pos = new net.minecraft.world.level.ChunkPos(origin.x() + dx, origin.z() + dz);
            if (sampled.contains(pos)) continue;
            var chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
            if (chunk == null) continue;
            sampled.add(pos);
            for (int x = 2; x < 16; x += 4) for (int z = 2; z < 16; z += 4) {
                int y = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                for (int height = 0; height <= 4; height++) {
                    var block = new net.minecraft.core.BlockPos(pos.getMinBlockX() + x, y + height, pos.getMinBlockZ() + z);
                    surface.put(block, level.getBlockState(block));
                }
            }
        }
    }

    private static void awaitClosed(GameTestHelper test, P1RoundWorldBundle bundle,
            java.util.concurrent.CompletableFuture<?> completion, long started, long[] lastDiagnostic) {
        bundle.tick();
        paceAndDiagnose(test, bundle, started, lastDiagnostic);
        if (completion.isCompletedExceptionally()) completion.join();
        test.assertTrue(completion.isDone(), "Native world save, Fantasy unregister and region IO close finish before success");
        test.assertValueEqual(bundle.state(), P1RoundWorldBundle.State.CLOSED, "Successful close has no remaining world entry");
        test.assertTrue(bundle.saveAndClose().toCompletableFuture().isDone(), "Completed close is idempotent");
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
