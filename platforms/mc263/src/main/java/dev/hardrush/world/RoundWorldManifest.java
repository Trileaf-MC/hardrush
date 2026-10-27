package dev.hardrush.world;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hardrush.core.round.RoundSnapshot;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/** Persisted identity before opening any dimension. Only immutable data crosses the IO worker. */
final class RoundWorldManifest {
    static final List<String> DIMENSIONS = List.of("overworld", "nether", "end");
    private static final int MAX_BYTES = 1_048_576;
    final UUID roundId;
    final long seed;
    final String rules;
    final Map<String, JsonElement> generators;
    final BlockPos spawn;
    final List<BlockPos> exits;

    RoundWorldManifest(UUID roundId, long seed, String rules, Map<String, JsonElement> generators,
            BlockPos spawn, List<BlockPos> exits) {
        this.roundId = roundId;
        this.seed = seed;
        this.rules = rules;
        var copy = new LinkedHashMap<String, JsonElement>();
        generators.forEach((name, generator) -> copy.put(name, generator.deepCopy()));
        if (!copy.keySet().equals(java.util.Set.copyOf(DIMENSIONS))) throw new IllegalArgumentException("清单需三维度");
        this.generators = Map.copyOf(copy);
        this.spawn = spawn == null ? null : spawn.immutable();
        this.exits = exits.stream().map(BlockPos::immutable).toList();
        // Empty exits describe a natural spawn. Keep reading the old two-to-four-exit format.
        if (spawn == null && !exits.isEmpty() || !exits.isEmpty() && (exits.size() < 2 || exits.size() > 4)) {
            throw new IllegalArgumentException("旧版出生点出口必须成组记录两至四个");
        }
    }

    RoundWorldManifest prepared(BlockPos spawn, List<BlockPos> exits) {
        return new RoundWorldManifest(roundId, seed, rules, generators, spawn, exits);
    }

    void requireSnapshot(RoundSnapshot snapshot) throws IOException {
        if (!roundId.equals(snapshot.roundId()) || seed != snapshot.seed() || !rules.equals(snapshot.rules().encode())) {
            throw new IOException("本轮清单 ID、种子或规则与已持久化快照不符；拒绝覆盖");
        }
        if (spawn != null) {
            int distance = (int) Math.ceil(snapshot.rules().protectionRadius()) + 8;
            var directions = new java.util.HashSet<String>();
            for (BlockPos exit : exits) {
                long dx = (long) exit.getX() - spawn.getX(), dz = (long) exit.getZ() - spawn.getZ();
                if (!(dx == 0 && Math.abs(dz) == distance || dz == 0 && Math.abs(dx) == distance)
                        || Math.abs((long) exit.getY() - spawn.getY()) > distance - 4) {
                    throw new IOException("出生出口清单与本轮保护半径不符");
                }
                if (!directions.add(dx + ":" + dz)) throw new IOException("出生出口方向重复");
            }
        }
    }

    static RoundWorldManifest read(Path root, Path path) throws IOException {
        RoundWorldPaths.checkAncestors(path, false);
        if (Files.size(path) > MAX_BYTES) throw new IOException("世界清单过大");
        try {
            var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (json.get("schema").getAsInt() != 1 || !json.get("minecraft").getAsString().equals("26.3")) {
                throw new IllegalArgumentException("清单版本不支持");
            }
            UUID round = UUID.fromString(json.get("roundId").getAsString());
            if (!RoundWorldPaths.manifest(root, round).equals(path.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("清单文件与轮次 ID 不符");
            }
            var dimensions = json.getAsJsonObject("dimensions");
            if (dimensions.size() != 3) throw new IllegalArgumentException("维度数量不符");
            var generators = new LinkedHashMap<String, JsonElement>();
            for (String name : DIMENSIONS) {
                var dimension = dimensions.getAsJsonObject(name);
                if (!RoundWorldPaths.identifier(round, name).equals(dimension.get("id").getAsString())
                        || !name.equals(dimension.get("type").getAsString())) throw new IllegalArgumentException("维度身份不符");
                Path claimed = root.resolve(dimension.get("ownedPath").getAsString());
                RoundWorldPaths.validate(root, round, name, claimed);
                generators.put(name, dimension.get("generator"));
            }
            BlockPos spawn = json.has("spawn") ? pos(json.getAsJsonObject("spawn")) : null;
            var exits = new java.util.ArrayList<BlockPos>();
            if (json.has("exits")) json.getAsJsonArray("exits").forEach(value -> exits.add(pos(value.getAsJsonObject())));
            return new RoundWorldManifest(round, json.get("seed").getAsLong(), json.get("rules").getAsString(),
                    generators, spawn, exits);
        } catch (RuntimeException error) {
            throw new IOException("世界清单损坏，保留文件并停止准备: " + path, error);
        }
    }

    void write(Path root, Path path) throws IOException {
        if (!RoundWorldPaths.manifest(root, roundId).equals(path.toAbsolutePath().normalize())) {
            throw new IOException("清单路径未登记");
        }
        RoundWorldPaths.checkAncestors(path, false);
        for (String dimension : DIMENSIONS) RoundWorldPaths.validate(root, roundId, dimension, RoundWorldPaths.world(root, roundId, dimension));
        Files.createDirectories(path.getParent());
        var json = new JsonObject();
        json.addProperty("schema", 1);
        json.addProperty("minecraft", "26.3");
        json.addProperty("roundId", roundId.toString());
        json.addProperty("seed", seed);
        json.addProperty("rules", rules);
        var dimensions = new JsonObject();
        for (String name : DIMENSIONS) {
            var dimension = new JsonObject();
            dimension.addProperty("id", RoundWorldPaths.identifier(roundId, name));
            dimension.addProperty("type", name);
            dimension.addProperty("ownedPath", root.toAbsolutePath().normalize().relativize(RoundWorldPaths.world(root, roundId, name)).toString());
            dimension.add("generator", generators.get(name).deepCopy());
            dimensions.add(name, dimension);
        }
        json.add("dimensions", dimensions);
        if (spawn != null) {
            json.add("spawn", jsonPos(spawn));
            var list = new com.google.gson.JsonArray();
            exits.forEach(exit -> list.add(jsonPos(exit)));
            json.add("exits", list);
        }
        Path temporary = Files.createTempFile(path.getParent(), "world-manifest-", ".tmp");
        try {
            byte[] bytes = (new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n").getBytes(StandardCharsets.UTF_8);
            try (var file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static BlockPos pos(JsonObject json) {
        return new BlockPos(json.get("x").getAsInt(), json.get("y").getAsInt(), json.get("z").getAsInt());
    }

    private static JsonObject jsonPos(BlockPos position) {
        var json = new JsonObject();
        json.addProperty("x", position.getX());
        json.addProperty("y", position.getY());
        json.addProperty("z", position.getZ());
        return json;
    }
}
