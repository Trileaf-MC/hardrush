package dev.hardrush.p0.world;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;

/** Operator-only P0: one durable three-dimension bundle, without rotation or deletion. */
public final class WorldProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldProbe.class);
    private static Context context;
    private static String lastStatus = "世界原型尚未初始化";

    private WorldProbe() {}

    public static void initialize(MinecraftServer server) {
        requireMainThread(server);
        context = new Context(server, server.getWorldPath(LevelResource.ROOT).resolve("hardrush-p0/world-manifest.json"));
        if (!Files.exists(context.path)) {
            lastStatus = "尚无 P0 轮次；create 将创建三个持久维度";
            return;
        }
        try {
            context.manifest = WorldManifest.read(context.path);
            openBundle(context);
        } catch (RuntimeException | IOException failure) {
            context.failed = true;
            lastStatus = "世界恢复失败，保留文件并停止进入: " + failure.getMessage();
            LOGGER.error(lastStatus, failure);
        }
    }

    public static String create(MinecraftServer server, long seed) {
        requireMainThread(server);
        Context current = requireContext(server);
        if (current.failed) throw new IllegalStateException(lastStatus);
        try {
            if (current.manifest == null) {
                var dimensions = WorldPresets.createNormalWorldDimensions(server.registryAccess());
                var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
                Map<String, JsonElement> generators = new LinkedHashMap<>();
                for (String dimension : WorldManifest.DIMENSIONS) {
                    var stem = dimensions.get(stemKey(dimension)).orElseThrow();
                    generators.put(dimension, ChunkGenerator.CODEC.encodeStart(ops, stem.generator()).getOrThrow());
                }
                current.manifest = new WorldManifest(UUID.randomUUID(), seed, generators, null);
                // Save identity before any world is created; partial creation reuses the same identity.
                current.manifest.write(current.path);
            } else if (current.manifest.seed != seed) {
                throw new IllegalArgumentException("已有 P0 轮次 seed=" + current.manifest.seed + "；不会换种子或覆盖存档");
            }
            openBundle(current);
            return lastStatus;
        } catch (IOException failure) {
            current.failed = true;
            lastStatus = "世界清单写入失败，停止进入: " + failure.getMessage();
            throw new IllegalStateException(lastStatus, failure);
        } catch (RuntimeException failure) {
            if (!(failure instanceof IllegalArgumentException)) {
                current.failed = true;
                lastStatus = "世界创建未完成，保留清单及世界: " + failure.getMessage();
            }
            throw failure;
        }
    }

    private static void openBundle(Context current) throws IOException {
        var ops = RegistryOps.create(JsonOps.INSTANCE, current.server.registryAccess());
        Fantasy fantasy = Fantasy.get(current.server);
        for (String dimension : WorldManifest.DIMENSIONS) {
            ResourceKey<Level> key = levelKey(current.manifest, dimension);
            ServerLevel existing = current.server.getLevel(key);
            if (existing != null && !(existing instanceof RoundRuntimeLevel)) {
                throw new IllegalStateException("登记维度已被其他实现占用: " + key.identifier());
            }
            ChunkGenerator generator = ChunkGenerator.CODEC.parse(ops, current.manifest.generators.get(dimension)).getOrThrow();
            RuntimeLevelConfig config = new RuntimeLevelConfig()
                    .setSeed(current.manifest.seed)
                    .setGenerator(generator)
                    .setDimensionType(switch (dimension) {
                        case "nether" -> BuiltinDimensionTypes.NETHER;
                        case "end" -> BuiltinDimensionTypes.END;
                        default -> BuiltinDimensionTypes.OVERWORLD;
                    })
                    .setDifficulty(Difficulty.HARD)
                    .setShouldTickTime(true)
                    .setGameRule(GameRules.KEEP_INVENTORY, false)
                    .setLevelConstructor(RoundRuntimeLevel::new);
            RuntimeLevelHandle handle = fantasy.getOrOpenPersistentLevel(key.identifier(), config);
            current.handles.put(dimension, handle);
            if (handle.asLevel().getSeed() != current.manifest.seed) {
                throw new IllegalStateException("维度种子不匹配: " + dimension);
            }
        }
        ServerLevel overworld = current.handles.get("overworld").asLevel();
        for (RuntimeLevelHandle handle : current.handles.values()) {
            ((RoundRuntimeLevel) handle.asLevel()).initializeRoundClocks((RoundRuntimeLevel) overworld);
        }
        if (current.manifest.spawn == null) {
            // Match vanilla's seed-dependent spawn region instead of assuming (0, 0) is land.
            var source = overworld.getChunkSource();
            var origin = source.getGenerator().getOrigin(source.randomState());
            BlockPos spawn = findSafeSurface(overworld, origin.getMinBlockX() + 8, origin.getMinBlockZ() + 8);
            if (spawn == null) throw new IllegalStateException("P0 主世界附近未找到安全出生点，未开放进入");
            current.manifest = current.manifest.withSpawn(spawn);
            current.manifest.write(current.path);
        }
        if (current.handles.get("end").asLevel().getDragonFight() == null) {
            throw new IllegalStateException("末地未初始化原版 EnderDragonFight");
        }
        current.ready = true;
        lastStatus = describe(current);
        LOGGER.info(lastStatus);
    }

    public static String teleport(ServerPlayer player, String dimension) {
        MinecraftServer server = player.level().getServer();
        requireMainThread(server);
        Context current = requireContext(server);
        if (!current.ready || current.failed) throw new IllegalStateException("三个维度尚未就绪: " + lastStatus);
        dimension = dimension.toLowerCase(Locale.ROOT);
        WorldManifest.requireDimension(dimension);
        ServerLevel destination = current.handles.get(dimension).asLevel();
        BlockPos stand;
        if (dimension.equals("end")) {
            EndPlatformFeature.createEndPlatform(destination, ServerLevel.END_SPAWN_POINT.below(), true);
            stand = ServerLevel.END_SPAWN_POINT.below();
        } else if (dimension.equals("nether")) {
            stand = findSafeNether(destination);
        } else {
            stand = current.manifest.spawn;
            if (!safeStanding(destination, stand)) stand = findSafeSurface(destination, stand.getX(), stand.getZ());
        }
        if (stand == null) throw new IllegalStateException("未找到安全站位；保持原位置，未生成战术地形");
        player.teleport(new TeleportTransition(destination, Vec3.atBottomCenterOf(stand), Vec3.ZERO,
                player.getYRot(), player.getXRot(), TeleportTransition.DO_NOTHING));
        return "已进入 " + dimension + " / " + destination.dimension().identifier();
    }

    public static String status() {
        return context != null && context.ready && !context.failed ? describe(context) : lastStatus;
    }

    /** Flush only. Vanilla subsequently closes registered levels during normal server shutdown. */
    public static void close(MinecraftServer server) {
        requireMainThread(server);
        if (context == null || context.server != server) return;
        for (RuntimeLevelHandle handle : new ArrayList<>(context.handles.values())) {
            handle.asLevel().save(null, true, false);
        }
        LOGGER.info("P0 世界已保存；未删除或替换任何世界文件");
    }

    public static boolean owns(ServerLevel level) {
        Context current = context;
        if (current == null || current.server != level.getServer() || current.manifest == null) return false;
        for (String dimension : WorldManifest.DIMENSIONS) {
            if (level.dimension().equals(levelKey(current.manifest, dimension))) return true;
        }
        return false;
    }

    /** Semantic keys are used only inside the original portal algorithm, never for world storage. */
    public static ResourceKey<Level> semanticDimension(ServerLevel level) {
        if (!owns(level)) return level.dimension();
        if (level.dimension().equals(levelKey(context.manifest, "nether"))) return Level.NETHER;
        if (level.dimension().equals(levelKey(context.manifest, "end"))) return Level.END;
        return Level.OVERWORLD;
    }

    public static ServerLevel portalDestination(ServerLevel source, ResourceKey<Level> requested) {
        if (!owns(source)) return source.getServer().getLevel(requested);
        if (requested.equals(Level.OVERWORLD)) requested = levelKey(context.manifest, "overworld");
        else if (requested.equals(Level.NETHER)) requested = levelKey(context.manifest, "nether");
        else if (requested.equals(Level.END)) requested = levelKey(context.manifest, "end");
        ServerLevel destination = source.getServer().getLevel(requested);
        return destination != null && owns(destination) ? destination : null;
    }

    static LevelData.RespawnData respawnData(ServerLevel source) {
        if (!owns(source)) return null;
        BlockPos spawn = context.manifest.spawn == null ? new BlockPos(0, 64, 0) : context.manifest.spawn;
        return LevelData.RespawnData.of(levelKey(context.manifest, "overworld"), spawn, 0.0F, 0.0F);
    }

    public static TeleportTransition boundRespawn(ServerPlayer player, TeleportTransition original) {
        if (!owns(player.level()) || owns(original.newLevel())) return original;
        ServerLevel destination = portalDestination(player.level(), Level.OVERWORLD);
        if (destination == null || context.manifest.spawn == null) {
            throw new IllegalStateException("P0 本轮主世界未就绪，拒绝回退至大厅存档");
        }
        BlockPos spawn = context.manifest.spawn;
        if (!safeStanding(destination, spawn)) spawn = findSafeSurface(destination, spawn.getX(), spawn.getZ());
        if (spawn == null) throw new IllegalStateException("本轮重生点已损坏且无安全替代位置");
        return new TeleportTransition(destination, Vec3.atBottomCenterOf(spawn), Vec3.ZERO, 0.0F, 0.0F,
                original.missingRespawnBlock(), false, java.util.Set.of(), original.postTeleportTransition());
    }

    private static BlockPos findSafeSurface(ServerLevel level, int centerX, int centerZ) {
        // Deliberately bounded operator probe; production spawn preparation will be budgeted across ticks.
        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;
        for (int radius = 0; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius != 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    int chunkX = centerChunkX + dx, chunkZ = centerChunkZ + dz;
                    // Level.getHeight returns minY for an unloaded chunk. Obtain the actual
                    // generated chunk first and read its heightmap, whose result is floor Y.
                    var chunk = level.getChunk(chunkX, chunkZ);
                    for (int localX = 0; localX < 16; localX++) {
                        for (int localZ = 0; localZ < 16; localZ++) {
                            int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, localX, localZ) + 1;
                            BlockPos position = new BlockPos((chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
                            if (safeStanding(level, position)) return position;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static BlockPos findSafeNether(ServerLevel level) {
        for (int radius = 0; radius <= 32; radius += 8) {
            for (int x = -radius; x <= radius; x += 8) {
                for (int z = -radius; z <= radius; z += 8) {
                    if (radius != 0 && Math.abs(x) != radius && Math.abs(z) != radius) continue;
                    for (int y = 100; y >= 32; --y) {
                        BlockPos position = new BlockPos(x, y, z);
                        if (safeStanding(level, position)) return position;
                    }
                }
            }
        }
        return null;
    }

    private static boolean safeStanding(ServerLevel level, BlockPos feet) {
        BlockPos floor = feet.below();
        if (!level.isInsideBuildHeight(floor) || !level.isInsideBuildHeight(feet.above())
                || !level.getWorldBorder().isWithinBounds(feet)) return false;
        var floorState = level.getBlockState(floor);
        return floorState.isFaceSturdy(level, floor, Direction.UP)
                && !floorState.is(Blocks.MAGMA_BLOCK) && !floorState.is(Blocks.CACTUS)
                && !floorState.is(Blocks.CAMPFIRE) && !floorState.is(Blocks.SOUL_CAMPFIRE)
                && level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir()
                && level.getFluidState(feet).isEmpty() && level.getFluidState(floor).isEmpty();
    }

    private static String describe(Context current) {
        StringBuilder text = new StringBuilder("P0 世界就绪；round=").append(current.manifest.roundId)
                .append("；seed=").append(current.manifest.seed)
                .append("；spawn=").append(current.manifest.spawn.toShortString());
        for (String dimension : WorldManifest.DIMENSIONS) {
            ServerLevel level = current.handles.get(dimension).asLevel();
            text.append("；").append(dimension).append('=').append(level.dimension().identifier());
        }
        var dragon = current.handles.get("end").asLevel().getDragonFight();
        text.append("；dragonFight=").append(dragon != null)
                .append("；dragonUUID=").append(dragon == null ? null : dragon.dragonUUID())
                .append("；previouslyKilled=").append(dragon != null && dragon.hasPreviouslyKilledDragon());
        return text.toString();
    }

    private static ResourceKey<Level> levelKey(WorldManifest manifest, String dimension) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse(manifest.identifier(dimension)));
    }

    private static ResourceKey<LevelStem> stemKey(String dimension) {
        return switch (dimension) {
            case "nether" -> LevelStem.NETHER;
            case "end" -> LevelStem.END;
            default -> LevelStem.OVERWORLD;
        };
    }

    private static Context requireContext(MinecraftServer server) {
        if (context == null || context.server != server) throw new IllegalStateException("WorldProbe 尚未为本服务端初始化");
        return context;
    }

    private static void requireMainThread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("世界原型必须在服务端主线程调用");
    }

    private static final class Context {
        final MinecraftServer server;
        final Path path;
        final Map<String, RuntimeLevelHandle> handles = new LinkedHashMap<>();
        WorldManifest manifest;
        boolean ready;
        boolean failed;

        Context(MinecraftServer server, Path path) {
            this.server = server;
            this.path = path;
        }
    }
}
