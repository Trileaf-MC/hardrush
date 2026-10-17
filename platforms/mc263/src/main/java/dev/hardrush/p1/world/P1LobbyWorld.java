package dev.hardrush.p1.world;

import java.util.IdentityHashMap;
import dev.hardrush.core.lobby.LobbyLayout;
import java.nio.file.*;
import java.io.IOException;
import java.util.ArrayDeque;
import net.minecraft.world.level.storage.LevelResource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevel;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;

/** One permanent shared lobby, independent of both the original overworld and round rotation. */
public final class P1LobbyWorld {
    public static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION, Identifier.parse("hardrush:lobby"));
    private static final Map<MinecraftServer, P1LobbyWorld> SERVICES = new IdentityHashMap<>();
    private static final TicketType SPAWN = new TicketType(TicketType.NO_TIMEOUT,
            TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    private final MinecraftServer server;
    private final Map<ChunkPos, CompletableFuture<?>> loading = new LinkedHashMap<>();
    private ServerLevel level;
    private BlockPos spawn;
    private boolean ready;
    private final ArrayDeque<ChunkPos> queued = new ArrayDeque<>();
    private final ArrayDeque<Map.Entry<BlockPos, net.minecraft.world.level.block.state.BlockState>> construction = new ArrayDeque<>();
    private Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blueprint;
    private CompletableFuture<String> manifestRead;
    private CompletableFuture<Void> manifestWrite;
    private Path manifest;
    private boolean inspected, completed;
    private int built;
    public static final int LOAD_BUDGET = 4, BUILD_BUDGET = 128;
    public int loadedChunks() { return loading.size(); }
    public int builtBlocks() { return built; }
    public int layoutVersion() { return ready ? LobbyLayout.VERSION : 0; }
    private String failure;
    private long started;

    private P1LobbyWorld(MinecraftServer server) {
        this.server = server;
        try {
            var existing = server.getLevel(KEY);
            if (existing != null && !(existing instanceof LobbyLevel)) throw new IllegalStateException("大厅维度已被其他世界占用");
            var settings = new FlatLevelGeneratorSettings(Optional.of(HolderSet.direct()),
                    server.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS), java.util.List.of());
            settings.getLayersInfo().add(new FlatLayerInfo(1, Blocks.BEDROCK));
            settings.getLayersInfo().add(new FlatLayerInfo(2, Blocks.DIRT));
            settings.getLayersInfo().add(new FlatLayerInfo(1, Blocks.GRASS_BLOCK));
            settings.updateLayers();
            var generator = new FlatLevelSource(settings);
            var config = new RuntimeLevelConfig().setSeed(0).setGenerator(generator).setFlat(true)
                    .setDimensionType(BuiltinDimensionTypes.OVERWORLD).setDifficulty(Difficulty.PEACEFUL)
                    .setMirrorOverworldGameRules(false).setShouldTickTime(false)
                    .setGameRule(GameRules.SPAWN_MOBS, false).setGameRule(GameRules.ADVANCE_WEATHER, false)
                    .setGameRule(GameRules.ALLOW_ENTERING_NETHER_USING_PORTALS, false)
                    .setLevelConstructor(LobbyLevel::new);
            level = Fantasy.get(server).getOrOpenPersistentLevel(KEY.identifier(), config).asLevel();
            spawn = new BlockPos(8, generator.getSpawnHeight(level), 8);
            ((LobbyLevel) level).spawn = spawn;
            started = System.nanoTime();
            // Cover the complete actual footprint, including its positive/negative chunk edges.
            for (int x = (spawn.getX() + LobbyLayout.MIN_X) >> 4; x <= (spawn.getX() + LobbyLayout.MAX_X) >> 4; x++)
                for (int z = (spawn.getZ() + LobbyLayout.MIN_Z) >> 4; z <= (spawn.getZ() + LobbyLayout.MAX_Z) >> 4; z++) queued.add(new ChunkPos(x, z));
            blueprint = P1CampBlueprint.blocks(spawn);
            manifest = server.getWorldPath(LevelResource.ROOT).resolve("hardrush/lobby-layout.txt");
            manifestRead = CompletableFuture.supplyAsync(() -> {
                try {
                    P1WorldPaths.checkAncestors(manifest, false);
                    return Files.exists(manifest) ? Files.readString(manifest) : "";
                } catch (IOException error) { throw new java.util.concurrent.CompletionException(error); }
            });
        } catch (RuntimeException error) { failure = "超平坦大厅准备失败：" + error.getMessage(); }
    }

    public static P1LobbyWorld open(MinecraftServer server) {
        return SERVICES.computeIfAbsent(server, P1LobbyWorld::new);
    }

    public static boolean isLobby(ServerLevel level) {
        var service = SERVICES.get(level.getServer());
        return service != null && service.ready && service.level == level;
    }

    public void tick() {
        if (ready || failure != null) return;
        try {
            if (System.nanoTime() - started > TimeUnit.MINUTES.toNanos(2)) throw new IllegalStateException("营地加载／施工超时");
            long active = loading.values().stream().filter(f -> !f.isDone()).count();
            while (active < LOAD_BUDGET && !queued.isEmpty()) {
                var chunk = queued.remove();
                loading.put(chunk, level.getChunkSource().addTicketAndLoadWithRadius(SPAWN, chunk, 0)); active++;
            }
            if (!queued.isEmpty()) return;
            for (var entry : loading.entrySet()) {
                if (!entry.getValue().isDone()) return;
                if (entry.getValue().join() instanceof ChunkResult<?> result && !result.isSuccess()) throw new IllegalStateException(result.getError());
                if (level.getChunkSource().getChunkNow(entry.getKey().x(), entry.getKey().z()) == null) return;
            }
            if (!manifestRead.isDone()) return;
            String identity = "hardrush:lobby\nversion=" + LobbyLayout.VERSION + "\nanchor=" + spawn.asLong() + "\n";
            if (!inspected) {
                String saved = manifestRead.join();
                if (!saved.isEmpty() && !saved.equals(identity + "PREPARED\n") && !saved.equals(identity + "COMPLETE\n"))
                    throw new IllegalStateException("未知营地布局清单，保留地形等待显式升级");
                completed = saved.equals(identity + "COMPLETE\n");
                if (!completed) {
                    // Fresh ownership may claim only untouched flat terrain. A prepared build may
                    // resume its exact planned blocks, but never overwrite an administrator's edit.
                    for (var entry : blueprint.entrySet()) {
                        var actual = level.getBlockState(entry.getKey());
                        boolean natural = entry.getKey().getY() == spawn.getY() - 1 ? actual.is(Blocks.GRASS_BLOCK) : actual.isAir();
                        if (!natural && !(saved.equals(identity + "PREPARED\n") && actual.equals(entry.getValue())))
                            throw new IllegalStateException("未拥有或施工期间被修改的营地方块：" + entry.getKey());
                    }
                    // Check the entire walking volume before claiming it, not just the blocks we place.
                    for (int x = LobbyLayout.MIN_X; x <= LobbyLayout.MAX_X; x++) for (int z = LobbyLayout.MIN_Z; z <= LobbyLayout.MAX_Z; z++)
                        for (int y = 0; y < 4; y++) {
                            var pos = spawn.offset(x, y, z); var actual = level.getBlockState(pos);
                            if (!actual.isAir() && !(saved.equals(identity + "PREPARED\n") && actual.equals(blueprint.get(pos))))
                                throw new IllegalStateException("营地行走空间存在未拥有方块：" + pos);
                        }
                    construction.addAll(blueprint.entrySet());
                    manifestWrite = writeManifest(identity + "PREPARED\n");
                }
                inspected = true;
            }
            if (manifestWrite != null) { if (!manifestWrite.isDone()) return; manifestWrite.join(); manifestWrite = null; }
            if (!completed) {
                for (int i = 0; i < BUILD_BUDGET && !construction.isEmpty(); i++) {
                    var entry = construction.remove();
                    if (!level.getBlockState(entry.getKey()).equals(entry.getValue())) {
                        level.setBlockAndUpdate(entry.getKey(), entry.getValue()); built++;
                    }
                }
                if (!construction.isEmpty()) return;
                P1CampBlueprint.labels(spawn).forEach(this::label);
                level.save(null, true, false);
                completed = true; manifestWrite = writeManifest(identity + "COMPLETE\n"); return;
            }
            for (var purpose : new LobbyLayout.Landing[]{LobbyLayout.Landing.CENTRAL, LobbyLayout.Landing.MONSTER, LobbyLayout.Landing.WAIT_OUTSIDE})
                if (!safeLanding(purpose)) throw new IllegalStateException("营地落点不安全，保留地形等待修复：" + purpose);
            ready = true;
        } catch (RuntimeException error) { failure = "超平坦大厅准备失败：" + error.getMessage(); }
    }

    private void label(BlockPos pos, String[] lines) {
        if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign)) return;
        var messages = java.util.Arrays.stream(lines).map(net.minecraft.network.chat.Component::literal).map(c -> (net.minecraft.network.chat.Component)c).toList();
        var text = new net.minecraft.world.level.block.entity.SignText(messages, messages, net.minecraft.world.item.DyeColor.BLACK, false);
        sign.setText(text, net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
        sign.setText(text, net.minecraft.world.level.block.entity.SignTextSlot.BACK); sign.setWaxed(true); sign.setChanged();
    }
    public void statusLabel(String stage, String mode) {
        if (!ready) return;
        label(spawn.offset(4, 0, 1), new String[]{"HardRush 营地", stage, mode, "前方集结／右侧中途门"});
    }
    private CompletableFuture<Void> writeManifest(String value) {
        return CompletableFuture.runAsync(() -> {
            try {
                P1WorldPaths.checkAncestors(manifest, false); Files.createDirectories(manifest.getParent());
                var temp = Files.createTempFile(manifest.getParent(), "lobby-layout-", ".tmp");
                try (var channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) {
                    var bytes = java.nio.ByteBuffer.wrap(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    while (bytes.hasRemaining()) channel.write(bytes);
                    channel.force(true);
                }
                Files.move(temp, manifest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException error) { throw new java.util.concurrent.CompletionException(error); }
        });
    }
    public boolean safeLanding(LobbyLayout.Landing purpose) {
        if (level == null || spawn == null) return false;
        var point = LobbyLayout.landing(purpose);
        return P1RoundWorldBundle.safeStanding(level, BlockPos.containing(Vec3.atBottomCenterOf(spawn).add(point.x(), 0, point.z())));
    }
    public boolean inAssembly(net.minecraft.server.level.ServerPlayer player, boolean staying) {
        if (!ready || player.level() != level || !player.isAlive()) return false;
        var relative = player.position().subtract(Vec3.atBottomCenterOf(spawn));
        return relative.y >= -.1 && relative.y <= 4
                && P1RoundWorldBundle.safeStanding(level, new BlockPos(net.minecraft.util.Mth.floor(player.getX()), spawn.getY(), net.minecraft.util.Mth.floor(player.getZ())))
                && Math.hypot(relative.x - LobbyLayout.ASSEMBLY.x(), relative.z - LobbyLayout.ASSEMBLY.z())
                <= LobbyLayout.ASSEMBLY.radius() + (staying ? .35 : -.15);
    }
    /** Loaded, built camp coordinates; teleport callers must still recheck standing safety. */
    public Vec3 layoutPosition(dev.hardrush.core.lobby.LobbyLayout.Point point) {
        if (!ready) throw new IllegalStateException("大厅尚未就绪");
        return Vec3.atBottomCenterOf(spawn).add(point.x(), 0, point.z());
    }
    public Vec3 landing(dev.hardrush.core.lobby.LobbyLayout.Landing purpose) {
        return layoutPosition(dev.hardrush.core.lobby.LobbyLayout.landing(purpose));
    }

    public boolean ready() { return ready; }
    public Optional<String> failure() { return Optional.ofNullable(failure); }
    public static String status(MinecraftServer server) {
        var service = SERVICES.get(server);
        return service == null ? "未启用" : service.ready ? "已完成" : service.failure().orElse("进行中");
    }
    /** Before readiness, only frozen profile quarantine may use the original overworld. */
    public ServerLevel holdingLevel() { return ready ? level : server.overworld(); }
    public Vec3 holdingSpawn() { return Vec3.atBottomCenterOf(ready ? spawn : server.overworld().getRespawnData().pos()); }

    public static void shutdown(MinecraftServer server) {
        var service = SERVICES.remove(server);
        if (service == null || service.level == null) return;
        for (var chunk : service.loading.keySet()) service.level.getChunkSource().removeTicketWithRadius(SPAWN, chunk, 0);
        // Leave the persistent level registered for vanilla's save/close and region-IO drain.
    }

    private static final class LobbyLevel extends RuntimeLevel {
        private BlockPos spawn;
        LobbyLevel(MinecraftServer server, ResourceKey<Level> key, RuntimeLevelConfig config, Style style) {
            super(server, key, config, style);
        }
        @Override public LevelData.RespawnData getRespawnData() {
            return spawn == null ? super.getRespawnData() : LevelData.RespawnData.of(KEY, spawn, 0, 0);
        }
    }
}
