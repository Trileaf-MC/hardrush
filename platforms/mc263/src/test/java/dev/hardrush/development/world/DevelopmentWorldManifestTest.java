package dev.hardrush.development.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real file round trips and fail-closed recovery; no running Minecraft server is required. */
final class DevelopmentWorldManifestTest {
    @TempDir
    Path directory;

    @Test
    void initialWritePreservesIdentitySeedAndGeneratorConfiguration() throws IOException {
        Path file = directory.resolve("world-manifest.json");
        DevelopmentWorldManifest original = manifest();

        original.write(file);
        DevelopmentWorldManifest recovered = DevelopmentWorldManifest.read(file);

        assertEquals(original.roundId, recovered.roundId);
        assertEquals(Long.MIN_VALUE, recovered.seed);
        assertEquals(original.generators, recovered.generators);
        assertNull(recovered.spawn);
    }

    @Test
    void savingPreparedSpawnPreservesTheExistingRound() throws IOException {
        Path file = directory.resolve("world-manifest.json");
        DevelopmentWorldManifest original = manifest();
        original.write(file);
        BlockPos spawn = new BlockPos(123, 72, -456);

        DevelopmentWorldManifest.read(file).withSpawn(spawn).write(file);
        DevelopmentWorldManifest recovered = DevelopmentWorldManifest.read(file);

        assertEquals(original.roundId, recovered.roundId);
        assertEquals(original.seed, recovered.seed);
        assertEquals(original.generators, recovered.generators);
        assertEquals(spawn, recovered.spawn);
    }

    @Test
    void foreignDimensionIdentifierIsRejectedWithoutRewritingEvidence() throws IOException {
        Path file = writeManifest();
        JsonObject document = readDocument(file);
        document.getAsJsonObject("dimensions").getAsJsonObject("nether")
                .addProperty("id", "minecraft:the_nether");
        Files.writeString(file, document.toString());

        assertRejectedAndUnchanged(file);
    }

    @Test
    void truncatedManifestIsRejectedWithoutRewritingEvidence() throws IOException {
        Path file = writeManifest();
        Files.writeString(file, "{broken");

        assertRejectedAndUnchanged(file);
    }

    @Test
    void unsupportedSchemaCannotBeSilentlyRecreated() throws IOException {
        Path file = writeManifest();
        JsonObject document = readDocument(file);
        document.addProperty("schema", 999);
        Files.writeString(file, document.toString());

        assertRejectedAndUnchanged(file);
    }

    @Test
    void missingDimensionCannotBecomeADifferentRoundOnRecovery() throws IOException {
        Path file = writeManifest();
        JsonObject document = readDocument(file);
        document.getAsJsonObject("dimensions").remove("end");
        Files.writeString(file, document.toString());

        assertRejectedAndUnchanged(file);
    }

    @Test
    void readingThroughASymlinkIsRejectedAndTargetIsUnchanged() throws IOException {
        Path target = writeManifest();
        String before = Files.readString(target);
        Path link = directory.resolve("linked.json");
        Files.createSymbolicLink(link, target);

        assertThrows(IOException.class, () -> DevelopmentWorldManifest.read(link));

        assertEquals(before, Files.readString(target));
    }

    @Test
    void writingThroughASymlinkDoesNotReplaceOrModifyTheTarget() throws IOException {
        Path target = writeManifest();
        String before = Files.readString(target);
        Path link = directory.resolve("linked.json");
        Files.createSymbolicLink(link, target);

        assertThrows(IOException.class, () -> manifest().write(link));

        assertEquals(target, Files.readSymbolicLink(link));
        assertEquals(before, Files.readString(target));
    }

    @Test
    void symlinkedManifestDirectoryIsRejectedBeforeReadOrWrite() throws IOException {
        Path targetDirectory = Files.createDirectory(directory.resolve("external"));
        Path target = targetDirectory.resolve("world-manifest.json");
        manifest().write(target);
        String before = Files.readString(target);
        Path link = directory.resolve("hardrush-p0");
        Files.createSymbolicLink(link, targetDirectory);
        Path linkedManifest = link.resolve("world-manifest.json");

        assertThrows(IOException.class, () -> DevelopmentWorldManifest.read(linkedManifest));
        assertThrows(IOException.class, () -> manifest().write(linkedManifest));

        assertEquals(before, Files.readString(target));
    }

    private Path writeManifest() throws IOException {
        Path file = directory.resolve("world-manifest.json");
        manifest().write(file);
        return file;
    }

    private static JsonObject readDocument(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    private static void assertRejectedAndUnchanged(Path file) throws IOException {
        String before = Files.readString(file);
        assertThrows(IOException.class, () -> DevelopmentWorldManifest.read(file));
        assertEquals(before, Files.readString(file));
    }

    private static DevelopmentWorldManifest manifest() {
        Map<String, JsonElement> generators = new LinkedHashMap<>();
        for (String dimension : new String[]{"overworld", "nether", "end"}) {
            JsonObject generator = new JsonObject();
            generator.addProperty("type", "minecraft:noise");
            generator.addProperty("settings", "minecraft:" + dimension);
            generators.put(dimension, generator);
        }
        return new DevelopmentWorldManifest(UUID.fromString("3807be1e-4aa0-43e0-8682-51c3d4d0f7dd"),
                Long.MIN_VALUE, generators, null);
    }
}
