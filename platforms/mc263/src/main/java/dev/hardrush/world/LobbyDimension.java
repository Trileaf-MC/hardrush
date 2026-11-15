package dev.hardrush.world;

import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelData;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevel;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;
import xyz.nucleoid.fantasy.util.VoidChunkGenerator;

/**
 * The persistent void dimensions an installed lobby lives in, {@code hardrush:lobby_<seq>} (design 10.1). Each install
 * gets a new one; nothing in it is generated, everything comes from the pack.
 */
public final class LobbyDimension {
    public static final String NAMESPACE = "hardrush";
    public static final String PREFIX = "lobby_";
    private static final Pattern PATH = Pattern.compile(PREFIX + "([1-9][0-9]{0,8})");

    private LobbyDimension() {}

    public static ResourceKey<Level> key(int seq) {
        if (seq < 1) throw new IllegalArgumentException("Lobby sequence starts at 1: " + seq);
        return ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(NAMESPACE, PREFIX + seq));
    }

    /** The install sequence of a {@code hardrush:lobby_<seq>} key or directory name, if it is one. */
    public static OptionalInt seq(String path) {
        var m = PATH.matcher(path);
        return m.matches() ? OptionalInt.of(Integer.parseInt(m.group(1))) : OptionalInt.empty();
    }
    public static Optional<ResourceKey<Level>> parse(String dimension) {
        var id = Identifier.tryParse(dimension);
        if (id == null || !id.getNamespace().equals(NAMESPACE) || seq(id.getPath()).isEmpty()) return Optional.empty();
        return Optional.of(ResourceKey.create(Registries.DIMENSION, id));
    }

    /** Where vanilla keeps the dimension's region files: {@code <world>/dimensions/<namespace>/<path>}. */
    public static Path folder(Path worldRoot, ResourceKey<Level> key) {
        return DimensionType.getStorageFolder(key, worldRoot.toAbsolutePath().normalize());
    }
    /** {@code <world>/dimensions/hardrush}, where every lobby and workshop dimension lives. */
    public static Path namespaceFolder(Path worldRoot) { return folder(worldRoot, key(1)).getParent(); }

    /**
     * Opens (creating if needed) the void lobby dimension. Plains biome, flat sky ({@code setFlat}: low ground would
     * otherwise darken the sky), peaceful, no mob spawning, frozen time and weather, no random ticks (grass under the
     * camp's posts would decay to dirt) and no nether portals.
     */
    public static RuntimeLevelHandle open(MinecraftServer server, ResourceKey<Level> key) {
        var config = new RuntimeLevelConfig().setSeed(0).setGenerator(new VoidChunkGenerator(server, Biomes.PLAINS)).setFlat(true)
                .setDimensionType(BuiltinDimensionTypes.OVERWORLD).setDifficulty(Difficulty.PEACEFUL)
                .setMirrorOverworldGameRules(false).setShouldTickTime(false)
                .setGameRule(GameRules.SPAWN_MOBS, false).setGameRule(GameRules.ADVANCE_WEATHER, false)
                .setGameRule(GameRules.RANDOM_TICK_SPEED, 0)
                .setGameRule(GameRules.ALLOW_ENTERING_NETHER_USING_PORTALS, false)
                .setLevelConstructor(LobbyLevel::new);
        var existing = server.getLevel(key);
        if (existing != null && !(existing instanceof LobbyLevel)) throw new IllegalStateException("大厅维度已被其他世界占用：" + key.identifier());
        return Fantasy.get(server).getOrOpenPersistentLevel(key.identifier(), config);
    }

    /** Where vanilla respawns and joins land in this dimension. */
    public static void spawn(net.minecraft.server.level.ServerLevel level, BlockPos spawn) {
        if (level instanceof LobbyLevel lobby) lobby.spawn = spawn.immutable();
    }

    private static final class LobbyLevel extends RuntimeLevel {
        private BlockPos spawn;
        LobbyLevel(MinecraftServer server, ResourceKey<Level> key, RuntimeLevelConfig config, Style style) {
            super(server, key, config, style);
        }
        @Override public LevelData.RespawnData getRespawnData() {
            return spawn == null ? super.getRespawnData() : LevelData.RespawnData.of(dimension(), spawn, 0, 0);
        }
    }
}
