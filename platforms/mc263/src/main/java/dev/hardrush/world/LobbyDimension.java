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
 * The persistent void dimensions lobbies live in (design 10.1): an installed lobby in {@code hardrush:lobby_<seq>}, each
 * install a new one, and a workshop draft in {@code hardrush:workshop_<draftId>}. Both use the same void configuration;
 * nothing in them is generated. Only {@code LobbyWorld.isLobby} makes one of them a protected level.
 */
public final class LobbyDimension {
    public static final String NAMESPACE = "hardrush";
    public static final String PREFIX = "lobby_";
    public static final String WORKSHOP_PREFIX = "workshop_";
    private static final Pattern PATH = Pattern.compile(PREFIX + "([1-9][0-9]{0,8})");
    /** Draft ids are {@code [a-z0-9_-]{1,32}}. */
    public static final Pattern DRAFT_ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern WORKSHOP_PATH = Pattern.compile(WORKSHOP_PREFIX + "(" + DRAFT_ID.pattern() + ")");

    private LobbyDimension() {}

    public static ResourceKey<Level> key(int seq) {
        if (seq < 1) throw new IllegalArgumentException("Lobby sequence starts at 1: " + seq);
        return ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(NAMESPACE, PREFIX + seq));
    }

    public static ResourceKey<Level> workshopKey(String draftId) {
        if (!DRAFT_ID.matcher(draftId).matches()) throw new IllegalArgumentException("Invalid draft id: " + draftId);
        return ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(NAMESPACE, WORKSHOP_PREFIX + draftId));
    }
    /** The draft id of a {@code workshop_<draftId>} directory or key path, if it is one. */
    public static Optional<String> workshopDraft(String path) {
        var m = WORKSHOP_PATH.matcher(path);
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** A workshop draft's dimension, loaded or not. */
    public static boolean isWorkshop(net.minecraft.server.level.ServerLevel level) {
        var id = level.dimension().identifier();
        return id.getNamespace().equals(NAMESPACE) && workshopDraft(id.getPath()).isPresent();
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
     * Opens (creating if needed) a void lobby or workshop dimension. Plains biome, flat sky ({@code setFlat}: low ground would
     * otherwise darken the sky), peaceful, no mob spawning, frozen time and weather, no random ticks (grass under the
     * camp's posts would decay to dirt) and no nether portals.
     */
    public static RuntimeLevelHandle open(MinecraftServer server, ResourceKey<Level> key) {
        var existing = server.getLevel(key);
        if (existing != null && !(existing instanceof LobbyLevel)) throw new IllegalStateException("大厅／工坊维度已被其他世界占用：" + key.identifier());
        return Fantasy.get(server).getOrOpenPersistentLevel(key.identifier(), config(server));
    }

    /**
     * A throwaway dimension with the same configuration, under a random key no lobby or draft uses; deleted with its
     * handle. For building a pack without a draft (“建筑清单与大厅包构建” 2.3).
     */
    public static RuntimeLevelHandle openTemporary(MinecraftServer server) {
        return Fantasy.get(server).openTemporaryLevel(config(server));
    }

    private static RuntimeLevelConfig config(MinecraftServer server) {
        return new RuntimeLevelConfig().setSeed(0).setGenerator(new VoidChunkGenerator(server, Biomes.PLAINS)).setFlat(true)
                .setDimensionType(BuiltinDimensionTypes.OVERWORLD).setDifficulty(Difficulty.PEACEFUL)
                .setMirrorOverworldGameRules(false).setShouldTickTime(false)
                .setGameRule(GameRules.SPAWN_MOBS, false).setGameRule(GameRules.ADVANCE_WEATHER, false)
                .setGameRule(GameRules.RANDOM_TICK_SPEED, 0)
                .setGameRule(GameRules.ALLOW_ENTERING_NETHER_USING_PORTALS, false)
                .setLevelConstructor(LobbyLevel::new);
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
