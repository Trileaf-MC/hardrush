package dev.hardrush.p1.world;

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

final class P1WorldManifestTest {
    @TempDir Path temporary;
    private static final UUID ROUND = UUID.fromString("482f2d01-4825-4a4a-bf14-dc4d69e8d342");

    @Test void identityGeneratorsRulesAndAllOwnedPathsSurviveRestart() throws IOException {
        Path root = temporary.toRealPath();
        Path path = P1WorldPaths.manifest(root, ROUND);
        var initial = manifest();
        initial.write(root, path);
        var recovered = P1WorldManifest.read(root, path);
        var snapshot = RoundEngine.create(ROUND, 1, 12345, RulesConfig.defaults(), () -> 0).committed();
        recovered.requireSnapshot(snapshot);
        assertEquals(initial.generators, recovered.generators);
        assertEquals(initial.rules, recovered.rules);
        assertNull(recovered.spawn);
        var document = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        for (String dimension : P1WorldManifest.DIMENSIONS) {
            var entry = document.getAsJsonObject("dimensions").getAsJsonObject(dimension);
            assertEquals(P1WorldPaths.identifier(ROUND, dimension), entry.get("id").getAsString());
            assertEquals(P1WorldPaths.world(root, ROUND, dimension), root.resolve(entry.get("ownedPath").getAsString()));
            assertFalse(entry.get("id").getAsString().contains("p0_"));
        }
    }

    @Test void preparedSpawnAndFourExitsRecoverWithoutChangingIdentity() throws IOException {
        Path root = temporary.toRealPath();
        Path file = P1WorldPaths.manifest(root, ROUND);
        var spawn = new BlockPos(2, 70, 6);
        var exits = List.of(spawn.north(40), spawn.south(40), spawn.east(40), spawn.west(40));
        manifest().prepared(spawn, exits).write(root, file);
        var recovered = P1WorldManifest.read(root, file);
        recovered.requireSnapshot(RoundEngine.create(ROUND, 1, 12345, RulesConfig.defaults(), () -> 0).committed());
        assertEquals(spawn, recovered.spawn);
        assertEquals(exits, recovered.exits);
        assertEquals(ROUND, recovered.roundId);
    }

    @Test void recoveryRejectsDifferentSeedOrRulesWithoutRewritingEvidence() throws IOException {
        Path root = temporary.toRealPath(), file = P1WorldPaths.manifest(root, ROUND);
        manifest().write(root, file);
        var recovered = P1WorldManifest.read(root, file);
        String before = Files.readString(file);
        assertThrows(IOException.class, () -> recovered.requireSnapshot(
                RoundEngine.create(ROUND, 1, 54321, RulesConfig.defaults(), () -> 0).committed()));
        assertThrows(IOException.class, () -> recovered.requireSnapshot(
                RoundEngine.create(ROUND, 1, 12345, RulesConfig.defaults().withMode(dev.hardrush.core.config.GameMode.NORMAL), () -> 0).committed()));
        assertEquals(before, Files.readString(file));
    }

    @Test void tamperedDimensionAndLobbyPathsAreRejectedWithoutDeletingFiles() throws IOException {
        Path root = temporary.toRealPath(), file = P1WorldPaths.manifest(root, ROUND);
        manifest().write(root, file);
        var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        json.getAsJsonObject("dimensions").getAsJsonObject("nether").addProperty("ownedPath", ".");
        Files.writeString(file, json.toString());
        String corrupted = Files.readString(file);
        assertThrows(IOException.class, () -> P1WorldManifest.read(root, file));
        assertEquals(corrupted, Files.readString(file));
        assertTrue(Files.isDirectory(root));
    }

    @Test void symlinkInAnyAncestorOrWorldSubdirectoryStopsPreparation() throws IOException {
        Path root = temporary.toRealPath();
        var external = Files.createDirectory(root.resolve("external"));
        Files.createSymbolicLink(root.resolve("hardrush"), external);
        assertThrows(IOException.class, () -> manifest().write(root, P1WorldPaths.manifest(root, ROUND)));
        Files.delete(root.resolve("hardrush"));
        Path world = P1WorldPaths.world(root, ROUND, "overworld");
        Files.createDirectories(world);
        Files.createSymbolicLink(world.resolve("region"), external);
        assertThrows(IOException.class, () -> manifest().write(root, P1WorldPaths.manifest(root, ROUND)));
        assertTrue(Files.isSymbolicLink(world.resolve("region")));
        assertTrue(Files.isDirectory(external));
    }

    @Test void malformedManifestCannotBeReplacedByANewRound() throws IOException {
        Path root = temporary.toRealPath(), file = P1WorldPaths.manifest(root, ROUND);
        manifest().write(root, file);
        Files.writeString(file, "{partial");
        assertThrows(IOException.class, () -> P1WorldManifest.read(root, file));
        assertEquals("{partial", Files.readString(file));
    }

    @Test void cleanupCandidatesCannotNameLobbyRootP0OrAnotherRound() throws IOException {
        Path root = temporary.toRealPath();
        for (Path foreign : List.of(root, root.resolve("DIM-1"), root.resolve("hardrush-p0"),
                P1WorldPaths.world(root, UUID.randomUUID(), "overworld"), root.resolve("../escaped"))) {
            assertThrows(IOException.class, () -> P1WorldPaths.validate(root, ROUND, "overworld", foreign));
        }
        assertDoesNotThrow(() -> P1WorldPaths.validate(root, ROUND, "overworld", P1WorldPaths.world(root, ROUND, "overworld")));
    }

    private static P1WorldManifest manifest() {
        var generators = new LinkedHashMap<String, JsonElement>();
        for (String dimension : P1WorldManifest.DIMENSIONS) {
            var generator = new JsonObject();
            generator.addProperty("type", "minecraft:noise");
            generator.addProperty("settings", "minecraft:" + dimension);
            generators.put(dimension, generator);
        }
        return new P1WorldManifest(ROUND, 12345, RulesConfig.defaults().encode(), generators, null, List.of());
    }
}
