package dev.hardrush.p0.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/** The P0 never rotates or deletes a manifest; repeated creation reopens this identity. */
final class WorldManifest {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    static final String[] DIMENSIONS = {"overworld", "nether", "end"};
    final UUID roundId;
    final long seed;
    final Map<String, JsonElement> generators;
    final BlockPos spawn;

    WorldManifest(UUID roundId, long seed, Map<String, JsonElement> generators, BlockPos spawn) {
        this.roundId = roundId;
        this.seed = seed;
        this.generators = Map.copyOf(generators);
        this.spawn = spawn == null ? null : spawn.immutable();
    }

    String identifier(String dimension) {
        requireDimension(dimension);
        return "hardrush:p0_" + roundId.toString().replace("-", "") + "/" + dimension;
    }

    WorldManifest withSpawn(BlockPos position) {
        return new WorldManifest(roundId, seed, generators, position);
    }

    static void requireDimension(String dimension) {
        if (!dimension.equals("overworld") && !dimension.equals("nether") && !dimension.equals("end")) {
            throw new IllegalArgumentException("维度必须是 overworld、nether 或 end");
        }
    }

    static WorldManifest read(Path path) throws IOException {
        rejectSymlink(path.getParent());
        rejectSymlink(path);
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (root.get("schema").getAsInt() != 1 || !root.get("minecraft").getAsString().equals("26.3")) {
                throw new IllegalArgumentException("不支持的清单版本；保留文件并停止恢复");
            }
            UUID roundId = UUID.fromString(root.get("roundId").getAsString());
            long seed = root.get("seed").getAsLong();
            Map<String, JsonElement> generators = new LinkedHashMap<>();
            JsonObject dimensions = root.getAsJsonObject("dimensions");
            if (dimensions.size() != 3) throw new IllegalArgumentException("清单必须恰好登记三个维度");
            WorldManifest manifest = new WorldManifest(roundId, seed, generators, null);
            for (String dimension : DIMENSIONS) {
                JsonObject entry = dimensions.getAsJsonObject(dimension);
                if (!manifest.identifier(dimension).equals(entry.get("id").getAsString())) {
                    throw new IllegalArgumentException("维度 ID 与轮次不一致");
                }
                generators.put(dimension, entry.get("generator").deepCopy());
            }
            BlockPos spawn = null;
            if (root.has("spawn")) {
                JsonObject pos = root.getAsJsonObject("spawn");
                spawn = new BlockPos(pos.get("x").getAsInt(), pos.get("y").getAsInt(), pos.get("z").getAsInt());
            }
            return new WorldManifest(roundId, seed, generators, spawn);
        } catch (RuntimeException malformed) {
            throw new IOException("世界清单损坏，拒绝覆盖或另建同名轮次: " + path, malformed);
        }
    }

    void write(Path path) throws IOException {
        rejectSymlink(path.getParent());
        rejectSymlink(path);
        Files.createDirectories(path.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("minecraft", "26.3");
        root.addProperty("roundId", roundId.toString());
        root.addProperty("seed", seed);
        JsonObject dimensions = new JsonObject();
        for (String dimension : DIMENSIONS) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", identifier(dimension));
            entry.add("generator", generators.get(dimension).deepCopy());
            dimensions.add(dimension, entry);
        }
        root.add("dimensions", dimensions);
        if (spawn != null) {
            JsonObject position = new JsonObject();
            position.addProperty("x", spawn.getX());
            position.addProperty("y", spawn.getY());
            position.addProperty("z", spawn.getZ());
            root.add("spawn", position);
        }
        Path temporary = Files.createTempFile(path.getParent(), "manifest-", ".tmp");
        try {
            byte[] bytes = (JSON.toJson(root) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // Failure is preferable to a non-atomic overwrite of the only round identity.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("清单文件系统不支持原子替换，已保留原清单", unsupported);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw new IOException("拒绝符号链接清单路径: " + path);
        }
    }
}
