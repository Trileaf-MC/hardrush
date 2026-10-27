package dev.hardrush.world;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.RoundEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RoundWorldManifestTest {
    @TempDir Path temporary;
    private static final UUID ROUND = UUID.fromString("482f2d01-4825-4a4a-bf14-dc4d69e8d342");

    @Test void closedRecoveryVerificationRetainsEveryFileWithoutCreatingMissingDimensions() throws IOException {
        Path root = temporary.toRealPath(), file = RoundWorldPaths.manifest(root, ROUND);
        manifest().write(root, file);
        Path world = RoundWorldPaths.world(root, ROUND, "overworld");
        Files.createDirectories(world.resolve("region"));
        Path marker = world.resolve("region/retained.mca"); Files.writeString(marker, "original-world");
        byte[] original = Files.readAllBytes(file);
        var round = RoundEngine.create(ROUND, 1, 12345, RulesConfig.defaults(), () -> 0).committed();
        ClosedWorlds.verifyRetained(root, round);
        ClosedWorlds.verifyRetained(root, round);
        assertEquals("original-world", Files.readString(marker)); assertArrayEquals(original, Files.readAllBytes(file));
        assertFalse(Files.exists(RoundWorldPaths.world(root, ROUND, "nether")));
        assertFalse(Files.exists(RoundWorldPaths.world(root, ROUND, "end")));
    }

    @Test void preManifestCrashNeedsNoWorldCreationButAnUnownedExistingWorldCannotBeSkipped() throws IOException {
        Path root = temporary.toRealPath();
        var round = RoundEngine.create(ROUND, 1, 12345, RulesConfig.defaults(), () -> 0).committed();
        ClosedWorlds.verifyRetained(root, round);
        assertFalse(Files.exists(root.resolve("hardrush")));
        Path world = RoundWorldPaths.world(root, ROUND, "end"); Files.createDirectories(world);
        Path marker = world.resolve("retained"); Files.writeString(marker, "unowned");
        assertThrows(IOException.class, () -> ClosedWorlds.verifyRetained(root, round));
        assertEquals("unowned", Files.readString(marker));
    }

    @Test void closedRecoveryRejectsTamperedManifestAndNestedSymlinksWithoutTouchingTargets() throws IOException {
        Path root = temporary.toRealPath(), file = RoundWorldPaths.manifest(root, ROUND);
        manifest().write(root, file);
        var round = RoundEngine.create(ROUND, 1, 12345, RulesConfig.defaults(), () -> 0).committed();
        var foreign = RoundEngine.create(ROUND, 1, 42, RulesConfig.defaults(), () -> 0).committed();
        assertThrows(IOException.class, () -> ClosedWorlds.verifyRetained(root, foreign));
        Path external = Files.createDirectory(root.resolve("external"));
        Path world = RoundWorldPaths.world(root, ROUND, "end"); Files.createDirectories(world);
        Files.createSymbolicLink(world.resolve("region"), external);
        assertThrows(IOException.class, () -> ClosedWorlds.verifyRetained(root, round));
        assertTrue(Files.isDirectory(external)); assertTrue(Files.isRegularFile(file));
    }

    @Test void identityGeneratorsRulesAndAllOwnedPathsSurviveRestart() throws IOException {
        Path root = temporary.toRealPath();
        Path path = RoundWorldPaths.manifest(root, ROUND);
        var initial = manifest();
        initial.write(root, path);
        var recovered = RoundWorldManifest.read(root, path);
        var snapshot = RoundEngine.create(ROUND, 1, 12345, RulesConfig.defaults(), () -> 0).committed();
        recovered.requireSnapshot(snapshot);
        assertEquals(initial.generators, recovered.generators);
        assertEquals(initial.rules, recovered.rules);
        assertNull(recovered.spawn);
        var document = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        for (String dimension : RoundWorldManifest.DIMENSIONS) {
            var entry = document.getAsJsonObject("dimensions").getAsJsonObject(dimension);
            assertEquals(RoundWorldPaths.identifier(ROUND, dimension), entry.get("id").getAsString());
            assertEquals(RoundWorldPaths.world(root, ROUND, dimension), root.resolve(entry.get("ownedPath").getAsString()));
            assertFalse(entry.get("id").getAsString().contains("development_"));
        }
    }

    @Test void naturalSpawnRecoversWithoutManufacturedExits() throws IOException {
        Path root = temporary.toRealPath(), file = RoundWorldPaths.manifest(root, ROUND);
        var spawn = new BlockPos(2, 70, 6);
        manifest().prepared(spawn, List.of()).write(root, file);
        var recovered = RoundWorldManifest.read(root, file);
        recovered.requireSnapshot(RoundEngine.create(ROUND, 1, 12345, RulesConfig.defaults(), () -> 0).committed());
        assertEquals(spawn, recovered.spawn);
        assertTrue(recovered.exits.isEmpty());
        // The new format also accepts manifests that omit the obsolete field altogether.
        var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        json.remove("exits");
        Files.writeString(file, json.toString());
        assertTrue(RoundWorldManifest.read(root, file).exits.isEmpty());
    }

    @Test void legacySpawnAndFourExitsRecoverWithoutChangingIdentity() throws IOException {
        Path root = temporary.toRealPath();
        Path file = RoundWorldPaths.manifest(root, ROUND);
        var spawn = new BlockPos(2, 70, 6);
        var exits = List.of(spawn.north(40), spawn.south(40), spawn.east(40), spawn.west(40));
        manifest().prepared(spawn, exits).write(root, file);
        var recovered = RoundWorldManifest.read(root, file);
        recovered.requireSnapshot(RoundEngine.create(ROUND, 1, 12345, RulesConfig.defaults(), () -> 0).committed());
        assertEquals(spawn, recovered.spawn);
        assertEquals(exits, recovered.exits);
        assertEquals(ROUND, recovered.roundId);
    }

    @Test void recoveryRejectsDifferentSeedOrRulesWithoutRewritingEvidence() throws IOException {
        Path root = temporary.toRealPath(), file = RoundWorldPaths.manifest(root, ROUND);
        manifest().write(root, file);
        var recovered = RoundWorldManifest.read(root, file);
        String before = Files.readString(file);
        assertThrows(IOException.class, () -> recovered.requireSnapshot(
                RoundEngine.create(ROUND, 1, 54321, RulesConfig.defaults(), () -> 0).committed()));
        assertThrows(IOException.class, () -> recovered.requireSnapshot(
                RoundEngine.create(ROUND, 1, 12345, RulesConfig.defaults().withMode(dev.hardrush.core.config.GameMode.NORMAL), () -> 0).committed()));
        assertEquals(before, Files.readString(file));
    }

    @Test void tamperedDimensionAndLobbyPathsAreRejectedWithoutDeletingFiles() throws IOException {
        Path root = temporary.toRealPath(), file = RoundWorldPaths.manifest(root, ROUND);
        manifest().write(root, file);
        var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        json.getAsJsonObject("dimensions").getAsJsonObject("nether").addProperty("ownedPath", ".");
        Files.writeString(file, json.toString());
        String corrupted = Files.readString(file);
        assertThrows(IOException.class, () -> RoundWorldManifest.read(root, file));
        assertEquals(corrupted, Files.readString(file));
        assertTrue(Files.isDirectory(root));
    }

    @Test void symlinkInAnyAncestorOrWorldSubdirectoryStopsPreparation() throws IOException {
        Path root = temporary.toRealPath();
        var external = Files.createDirectory(root.resolve("external"));
        Files.createSymbolicLink(root.resolve("hardrush"), external);
        assertThrows(IOException.class, () -> manifest().write(root, RoundWorldPaths.manifest(root, ROUND)));
        Files.delete(root.resolve("hardrush"));
        Path world = RoundWorldPaths.world(root, ROUND, "overworld");
        Files.createDirectories(world);
        Files.createSymbolicLink(world.resolve("region"), external);
        assertThrows(IOException.class, () -> manifest().write(root, RoundWorldPaths.manifest(root, ROUND)));
        assertTrue(Files.isSymbolicLink(world.resolve("region")));
        assertTrue(Files.isDirectory(external));
    }

    @Test void malformedManifestCannotBeReplacedByANewRound() throws IOException {
        Path root = temporary.toRealPath(), file = RoundWorldPaths.manifest(root, ROUND);
        manifest().write(root, file);
        Files.writeString(file, "{partial");
        assertThrows(IOException.class, () -> RoundWorldManifest.read(root, file));
        assertEquals("{partial", Files.readString(file));
    }

    @Test void cleanupCandidatesCannotNameLobbyRootP0OrAnotherRound() throws IOException {
        Path root = temporary.toRealPath();
        for (Path foreign : List.of(root, root.resolve("DIM-1"), root.resolve("hardrush-development"),
                RoundWorldPaths.world(root, UUID.randomUUID(), "overworld"), root.resolve("../escaped"))) {
            assertThrows(IOException.class, () -> RoundWorldPaths.validate(root, ROUND, "overworld", foreign));
        }
        assertDoesNotThrow(() -> RoundWorldPaths.validate(root, ROUND, "overworld", RoundWorldPaths.world(root, ROUND, "overworld")));
    }

    @Test void substitutedRoundOrDimensionOwnerCannotAuthorizeClosingTheActiveWorld() throws IOException {
        Path root = temporary.toRealPath(), file = RoundWorldPaths.manifest(root, ROUND);
        manifest().write(root, file);
        String initial = Files.readString(file);
        for (String property : List.of("id", "type", "ownedPath")) {
            var json = JsonParser.parseString(initial).getAsJsonObject();
            String foreign = switch (property) {
                case "id" -> RoundWorldPaths.identifier(UUID.randomUUID(), "nether");
                case "type" -> "overworld";
                default -> root.relativize(RoundWorldPaths.world(root, UUID.randomUUID(), "nether")).toString();
            };
            json.getAsJsonObject("dimensions").getAsJsonObject("nether").addProperty(property, foreign);
            Files.writeString(file, json.toString());
            String evidence = Files.readString(file);
            assertThrows(IOException.class, () -> RoundWorldManifest.read(root, file));
            assertEquals(evidence, Files.readString(file));
        }
        assertTrue(Files.isDirectory(root));
    }

    @Test void symlinkRootAndRegularFileInAWorldPathAreRejectedWithoutTouchingTargets() throws IOException {
        Path realRoot = temporary.toRealPath();
        Path alias = realRoot.resolve("alias");
        Files.createSymbolicLink(alias, realRoot);
        assertThrows(IOException.class, () -> RoundWorldPaths.validate(alias, ROUND, "end", RoundWorldPaths.world(alias, ROUND, "end")));
        Path world = RoundWorldPaths.world(realRoot, ROUND, "end");
        Files.createDirectories(world.getParent());
        Files.writeString(world, "operator recovery evidence");
        assertThrows(IOException.class, () -> RoundWorldPaths.validate(realRoot, ROUND, "end", world));
        assertEquals("operator recovery evidence", Files.readString(world));
        assertTrue(Files.isSymbolicLink(alias));
    }

    private static RoundWorldManifest manifest() {
        var generators = new LinkedHashMap<String, JsonElement>();
        for (String dimension : RoundWorldManifest.DIMENSIONS) {
            var generator = new JsonObject();
            generator.addProperty("type", "minecraft:noise");
            generator.addProperty("settings", "minecraft:" + dimension);
            generators.put(dimension, generator);
        }
        return new RoundWorldManifest(ROUND, 12345, RulesConfig.defaults().encode(), generators, null, List.of());
    }
}
