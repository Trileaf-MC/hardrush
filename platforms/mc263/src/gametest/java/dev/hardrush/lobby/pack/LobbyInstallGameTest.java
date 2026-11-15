package dev.hardrush.lobby.pack;

import dev.hardrush.core.lobby.plan.*;
import dev.hardrush.world.LobbyDimension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

/**
 * {@link LobbyInstall} end to end, outside the live lobby: each test installs into its own storage directory and its own
 * dimension, so it runs in the default suite next to everything else. Restarting onto an existing install is covered by
 * the restart and crash regressions, which reopen a real {@code current.json} in a new JVM.
 */
public final class LobbyInstallGameTest {
    private static final int MAX_SIZE = 256;
    /** Every marker of the default pack: 3 zones, 23 points, 4 models, 6 triggers, the altar light and the status sign. */
    private static final int DEFAULT_MARKERS = 38;

    /** GameTests tick unthrottled; chunk generation and entity IO run on worker threads by wall clock. */
    @GameTest(maxTicks = 60000)
    public void theDefaultPackInstallsWithEveryRequiredFunction(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var directory = scratch(server, "lobby-install");
        var storage = new LobbyStorage(directory);
        var key = testKey();
        var install = new LobbyInstall(server, storage, LobbyInstall.Source.builtIn(), 1, key, MAX_SIZE, OptionalInt.empty());
        var outcome = new AtomicReference<LobbyInstall.Outcome>();
        test.startSequence()
                .thenWaitUntil(() -> {
                    if (outcome.get() == null) install.tick(true).ifPresent(outcome::set);
                    test.assertTrue(outcome.get() != null, "Installing: " + install.placedTiles() + " tiles placed");
                })
                .thenExecute(() -> {
                    if (!(outcome.get() instanceof LobbyInstall.Outcome.Installed done))
                        throw new AssertionError("The default pack installs: " + outcome.get());
                    test.assertTrue(done.fallbacks().functions().isEmpty(), "The default pack needs no stand-in");
                    try {
                        check(test, storage, key, done);
                    } catch (IOException | PackException e) {
                        throw new IllegalStateException(e);
                    } finally {
                        done.chunks().close();
                        done.handle().delete();
                        deleteRecursively(directory);
                    }
                })
                .thenSucceed();
    }

    private static void check(GameTestHelper test, LobbyStorage storage, ResourceKey<Level> key, LobbyInstall.Outcome.Installed done)
            throws IOException, PackException {
        var installation = done.installation();
        var plan = installation.plan();
        var level = done.handle().asLevel();
        test.assertTrue(level.dimension().equals(key) && installation.dimension().equals(key.identifier().toString()), "Installed into the requested dimension");
        test.assertValueEqual(installation.seq(), 1, "Sequence");
        test.assertValueEqual(installation.pack().id(), "default", "Pack id");
        test.assertValueEqual(installation.pack().name(), "默认营地", "Pack name");

        // Every required function is there, and the recorded plan passes acceptance on the placed world.
        for (var f : LobbyFunction.values())
            if (f.required()) test.assertTrue(plan.count(f) >= f.min(), "The default lobby has 【" + f.displayName() + "】");
        var problems = new ArrayList<>(LobbyPlanValidator.validate(plan, MAX_SIZE));
        problems.addAll(PlanWorldChecks.check(level, plan));
        test.assertValueEqual(problems, List.<Problem>of(), "No acceptance problem, not even a warning");
        test.assertValueEqual(done.warnings(), List.<Problem>of(), "The install reported no warning");

        // Markers are stripped; nothing unsafe was in the pack.
        test.assertValueEqual(done.sanitized().removedEntities(), DEFAULT_MARKERS, "Every marker is stripped on install");
        test.assertTrue(done.sanitized().removedIds().isEmpty() && done.sanitized().removedBlocks() == 0, "Nothing unsafe: " + done.sanitized());
        var b = plan.bounds();
        var box = new AABB(b.min().x(), b.min().y(), b.min().z(), b.max().x() + 1, b.max().y() + 1, b.max().z() + 1);
        test.assertTrue(level.getEntitiesOfClass(Entity.class, box, e -> e.entityTags().contains(MarkerTags.MARKER)).isEmpty(), "No marker armor stand is installed");

        // The camp itself: the altar's beacon under its tinted glass, the status sign's text.
        var light = plan.altarLights().getFirst();
        test.assertTrue(level.getBlockState(new BlockPos(light.x(), light.y() - 1, light.z())).is(Blocks.BEACON), "The beacon stands under the altar light");
        var status = plan.texts(LobbyFunction.STATUS).getFirst().sign();
        if (!(level.getBlockEntity(new BlockPos(status.x(), status.y(), status.z())) instanceof SignBlockEntity sign))
            throw new AssertionError("The status sign has its block entity");
        test.assertValueEqual(sign.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString(), "HardRush 营地", "The status sign keeps its text");

        // Recorded: the copy is the jar's pack byte for byte, and current.json reads back as the same install.
        byte[] shipped;
        try (var in = LobbyInstall.Source.builtIn().open()) { shipped = in.readAllBytes(); }
        test.assertTrue(Arrays.equals(Files.readAllBytes(storage.installed(1)), shipped), "installed/1.hrlobby is the shipped pack");
        test.assertValueEqual(installation.pack().sha256(), PackFile.sha256(storage.installed(1)), "current.json names the copy's SHA-256");
        test.assertValueEqual(storage.readCurrent(), Optional.of(installation), "current.json reads back as the same install");
    }

    @GameTest(maxTicks = 60000)
    public void aPackMissingARequiredFunctionFailsAndLeavesNothingBehind(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var directory = scratch(server, "lobby-install-broken");
        var storage = new LobbyStorage(directory);
        var key = testKey();
        Path broken;
        try (var in = LobbyInstall.Source.builtIn().open()) {
            var contents = PackFile.read(in, PackLimits.forServer(MAX_SIZE));
            var plan = contents.plan();
            // Without its courtyard landing: a function with no stand-in, so this must fail acceptance.
            var points = new EnumMap<>(plan.points());
            points.remove(LobbyFunction.LANDING_MONSTER);
            var withoutLanding = new LobbyPlan(plan.bounds(), plan.triggers(), plan.models(), points, plan.zones(), plan.altarLights(), plan.texts());
            broken = directory.resolve("packs/broken.hrlobby");
            PackFile.write(broken, contents.manifest(), withoutLanding, contents.tiles());
        } catch (IOException | PackException e) {
            throw new IllegalStateException(e);
        }
        var install = new LobbyInstall(server, storage, LobbyInstall.Source.file(broken), 1, key, MAX_SIZE, OptionalInt.empty());
        var outcome = new AtomicReference<LobbyInstall.Outcome>();
        test.startSequence()
                .thenWaitUntil(() -> {
                    if (outcome.get() == null) install.tick(true).ifPresent(outcome::set);
                    test.assertTrue(outcome.get() != null, "Installing: " + install.placedTiles() + " tiles placed");
                })
                .thenExecute(() -> {
                    if (!(outcome.get() instanceof LobbyInstall.Outcome.Failed failed)) throw new AssertionError("A pack without a courtyard landing fails: " + outcome.get());
                    test.assertTrue(failed.reason().contains("缺少【庭院落点】"), "The reason names the missing function: " + failed.reason());
                    test.assertTrue(install.tick(true).isEmpty(), "The outcome is reported once");
                })
                // Fantasy deletes the dimension on a later tick.
                .thenWaitUntil(() -> test.assertTrue(server.getLevel(key) == null, "The new dimension is deleted"))
                .thenExecute(() -> {
                    test.assertFalse(Files.exists(storage.current()) || Files.exists(storage.installed(1)), "Nothing was recorded");
                    deleteRecursively(directory);
                })
                .thenSucceed();
    }

    /**
     * A pack lacking four functions with different stand-ins installs: a lectern, a written sign, a model point and a
     * vault beside the central landing. Nothing is recorded while the switch is not allowed.
     */
    @GameTest(maxTicks = 60000)
    public void missingFunctionsGetStandInsAndNothingIsRecordedBeforeTheSwitch(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var directory = scratch(server, "lobby-install-fallback");
        var storage = new LobbyStorage(directory);
        var key = testKey();
        var missing = List.of(LobbyFunction.HISTORY, LobbyFunction.PAGE_NEXT, LobbyFunction.ROLE_ZOMBIE, LobbyFunction.OMINOUS_VAULT);
        Path partial;
        try (var in = LobbyInstall.Source.builtIn().open()) {
            var contents = PackFile.read(in, PackLimits.forServer(MAX_SIZE));
            var plan = contents.plan();
            var reduced = new LobbyPlan(plan.bounds(), plan.triggers().stream().filter(t -> !missing.contains(t.function())).toList(),
                    plan.models().stream().filter(m -> !missing.contains(m.function())).toList(), plan.points(), plan.zones(),
                    plan.altarLights(), plan.texts());
            partial = directory.resolve("packs/partial.hrlobby");
            PackFile.write(partial, contents.manifest(), reduced, contents.tiles());
        } catch (IOException | PackException e) {
            throw new IllegalStateException(e);
        }
        var install = new LobbyInstall(server, storage, LobbyInstall.Source.file(partial), 1, key, MAX_SIZE, OptionalInt.empty());
        var outcome = new AtomicReference<LobbyInstall.Outcome>();
        var allowed = new boolean[1];
        test.startSequence()
                .thenWaitUntil(() -> {
                    if (outcome.get() == null) install.tick(false).ifPresent(outcome::set);
                    test.assertTrue(outcome.get() != null || install.stage() == LobbyInstall.Stage.READY, "Building: " + install.stage());
                })
                .thenExecute(() -> {
                    test.assertTrue(outcome.get() == null, "Built, waiting: " + outcome.get());
                    for (int i = 0; i < 5; i++) test.assertTrue(install.tick(false).isEmpty(), "Still waiting for the switch");
                    test.assertTrue(install.waitingNanos() > 0, "Counts how long it has waited");
                    test.assertFalse(Files.exists(storage.current()) || Files.exists(storage.installed(1)), "Nothing is recorded while waiting");
                    allowed[0] = true;
                })
                .thenWaitUntil(() -> {
                    if (outcome.get() == null) install.tick(allowed[0]).ifPresent(outcome::set);
                    test.assertTrue(outcome.get() != null, "Committing");
                })
                .thenExecute(() -> {
                    if (!(outcome.get() instanceof LobbyInstall.Outcome.Installed done)) throw new AssertionError("The partial pack installs: " + outcome.get());
                    try {
                        var level = done.handle().asLevel();
                        var plan = done.installation().plan();
                        test.assertValueEqual(EnumSet.copyOf(done.fallbacks().functions()), EnumSet.copyOf(missing), "Every missing function got a stand-in");
                        var central = LandingRules.candidates(plan, Landing.CENTRAL).getFirst();
                        for (var f : missing) test.assertValueEqual(plan.count(f), 1, "The effective plan has 【" + f.displayName() + "】");
                        var expected = Map.of(LobbyFunction.HISTORY, Blocks.LECTERN, LobbyFunction.PAGE_NEXT, Blocks.OAK_SIGN, LobbyFunction.OMINOUS_VAULT, Blocks.VAULT);
                        expected.forEach((f, block) -> {
                            var cell = plan.triggers(f).getFirst().block();
                            var pos = new BlockPos(cell.x(), cell.y(), cell.z());
                            test.assertTrue(level.getBlockState(pos).is(block), f.id() + " stand-in is " + block + " at " + pos);
                            double dx = cell.x() + .5 - central.x(), dz = cell.z() + .5 - central.z();
                            test.assertTrue(dx * dx + dz * dz <= 81, f.id() + " stands beside the central landing");
                        });
                        var vault = plan.triggers(LobbyFunction.OMINOUS_VAULT).getFirst().block();
                        test.assertTrue(level.getBlockState(new BlockPos(vault.x(), vault.y(), vault.z()))
                                .getValue(net.minecraft.world.level.block.VaultBlock.OMINOUS), "The ominous vault stand-in is ominous");
                        var sign = plan.triggers(LobbyFunction.PAGE_NEXT).getFirst().block();
                        if (!(level.getBlockEntity(new BlockPos(sign.x(), sign.y(), sign.z())) instanceof SignBlockEntity entity))
                            throw new AssertionError("The paging sign has its block entity");
                        test.assertValueEqual(entity.getText(SignTextSlot.FRONT).getMessages(false).get(1).getString(), "下一页", "The sign names its page");
                        test.assertValueEqual(plan.models(LobbyFunction.ROLE_ZOMBIE).size(), 1, "The zombie role gets a model point");
                        test.assertValueEqual(storage.readCurrent().orElseThrow().plan(), plan, "current.json records the effective plan");
                    } catch (IOException | PackException e) {
                        throw new IllegalStateException(e);
                    } finally {
                        done.chunks().close();
                        done.handle().delete();
                        deleteRecursively(directory);
                    }
                })
                .thenSucceed();
    }

    @GameTest
    public void startupCleanupKeepsOnlyTheCurrentAndLoadedLobbies(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var root = scratch(server, "lobby-cleanup");
        var dimensions = LobbyDimension.namespaceFolder(root);
        try {
            for (var name : List.of("lobby_1/region", "lobby_3/region", "lobby_4", "lobby/region", "workshop_draft/region", "round_x/overworld"))
                Files.createDirectories(dimensions.resolve(name));
            Files.writeString(dimensions.resolve("lobby_1/region/r.0.0.mca"), "old");
            Files.createDirectories(root.resolve("hardrush"));
            Files.writeString(root.resolve("hardrush/lobby-layout.txt"), "hardrush:lobby\nCOMPLETE\n");
            var storage = new LobbyStorage(root.resolve("hardrush/lobby"));
            Files.createDirectories(storage.installed(7).getParent());
            Files.writeString(storage.installed(7), "copy");

            int highest = storage.cleanup(root, OptionalInt.of(3), Set.of("lobby_4"));
            test.assertValueEqual(highest, 7, "The highest sequence counts installed copies too, so numbers are never reused");
            test.assertTrue(!Files.exists(dimensions.resolve("lobby_1")) && !Files.exists(dimensions.resolve("lobby")), "Stale and legacy lobbies are deleted");
            test.assertTrue(Files.isDirectory(dimensions.resolve("lobby_3/region")), "The current lobby is kept");
            test.assertTrue(Files.isDirectory(dimensions.resolve("lobby_4")), "A loaded dimension is never deleted");
            test.assertTrue(Files.isDirectory(dimensions.resolve("workshop_draft/region")) && Files.isDirectory(dimensions.resolve("round_x/overworld")),
                    "Other dimensions are not the lobby's to delete");
            test.assertFalse(Files.exists(root.resolve("hardrush/lobby-layout.txt")), "The old camp manifest is deleted");

            test.assertValueEqual(storage.cleanup(root, OptionalInt.empty(), Set.of()), 7, "Without a current lobby every lobby goes");
            test.assertFalse(Files.exists(dimensions.resolve("lobby_3")) || Files.exists(dimensions.resolve("lobby_4")), "Every lobby directory is gone");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            deleteRecursively(root);
        }
        test.succeed();
    }

    private static Path scratch(MinecraftServer server, String name) {
        try {
            return Files.createDirectories(server.getWorldPath(LevelResource.ROOT).resolve("hardrush-test").resolve(name + "-" + UUID.randomUUID()))
                    .toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResourceKey<Level> testKey() {
        return ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("hardrush_test", "lobby_" + UUID.randomUUID().toString().replace("-", "")));
    }

    private static void deleteRecursively(Path directory) {
        try (var walk = Files.walk(directory)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover test directory is harmless.
        }
    }
}
