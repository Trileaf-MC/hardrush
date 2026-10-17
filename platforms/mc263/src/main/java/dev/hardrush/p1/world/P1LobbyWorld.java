package dev.hardrush.p1.world;

import java.util.IdentityHashMap;
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
            // Native async loading; never block a server tick on terrain generation.
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                var chunk = new ChunkPos(x, z);
                loading.put(chunk, level.getChunkSource().addTicketAndLoadWithRadius(SPAWN, chunk, 0));
            }
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
            if (System.nanoTime() - started > TimeUnit.MINUTES.toNanos(2)) throw new IllegalStateException("出生区加载超时");
            for (var entry : loading.entrySet()) {
                if (!entry.getValue().isDone()) return;
                if (entry.getValue().join() instanceof ChunkResult<?> result && !result.isSuccess()) {
                    throw new IllegalStateException(result.getError());
                }
                if (level.getChunkSource().getChunkNow(entry.getKey().x(), entry.getKey().z()) == null) return;
            }
            if (!P1RoundWorldBundle.safeStanding(level, spawn)) throw new IllegalStateException("大厅出生点不安全，保留已有地形等待修复");
            ready = true;
        } catch (RuntimeException error) { failure = "超平坦大厅准备失败：" + error.getMessage(); }
    }

    /** Contract positions only. The round-2 builder must load/check safety before teleporting here. */
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
