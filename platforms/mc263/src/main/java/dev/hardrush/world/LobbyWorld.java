package dev.hardrush.world;

import java.util.IdentityHashMap;
import dev.hardrush.core.lobby.LegacyCampPlan;
import dev.hardrush.core.lobby.plan.*;
import java.util.EnumMap;
import java.util.List;
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
public final class LobbyWorld {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("hardrush-lobby");
    public static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION, Identifier.parse("hardrush:lobby"));
    private static final Map<MinecraftServer, LobbyWorld> SERVICES = new IdentityHashMap<>();
    private static final TicketType SPAWN = new TicketType(TicketType.NO_TIMEOUT,
            TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    private final MinecraftServer server;
    private final Map<ChunkPos, CompletableFuture<?>> loading = new LinkedHashMap<>();
    private ServerLevel level;
    private BlockPos spawn;
    /** The runtime layout; every lobby position query reads it. */
    private LobbyPlan plan;
    /** Per purpose, the candidate the next placement starts from, so several landings take turns. */
    private final Map<Landing, Integer> rotation = new EnumMap<>(Landing.class);
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
    public int layoutVersion() { return ready ? CampBlueprint.VERSION : 0; }
    private String failure;
    private long started;

    private LobbyWorld(MinecraftServer server) {
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
            plan = LegacyCampPlan.build(spawn.getX(), spawn.getY(), spawn.getZ());
            started = System.nanoTime();
            // Cover the complete actual footprint, including its positive/negative chunk edges.
            var bounds = plan.bounds();
            for (int x = bounds.min().x() >> 4; x <= bounds.max().x() >> 4; x++)
                for (int z = bounds.min().z() >> 4; z <= bounds.max().z() >> 4; z++) queued.add(new ChunkPos(x, z));
            blueprint = CampBlueprint.blocks(spawn);
            manifest = server.getWorldPath(LevelResource.ROOT).resolve("hardrush/lobby-layout.txt");
            manifestRead = CompletableFuture.supplyAsync(() -> {
                try {
                    RoundWorldPaths.checkAncestors(manifest, false);
                    return Files.exists(manifest) ? Files.readString(manifest) : "";
                } catch (IOException error) { throw new java.util.concurrent.CompletionException(error); }
            });
        } catch (RuntimeException error) { failure = "超平坦大厅准备失败：" + error.getMessage(); }
    }

    public static LobbyWorld open(MinecraftServer server) {
        return SERVICES.computeIfAbsent(server, LobbyWorld::new);
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
            String identity = "hardrush:lobby\nversion=" + CampBlueprint.VERSION + "\nanchor=" + spawn.asLong() + "\n";
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
                        if (!natural && !(saved.equals(identity + "PREPARED\n") && (actual.equals(entry.getValue())
                                || CampBlueprint.legacyMonsterLectern(spawn, entry.getKey(), actual)
                                || CampBlueprint.litAltarGlass(spawn, entry.getKey(), actual))))
                            throw new IllegalStateException("未拥有或施工期间被修改的营地方块：" + entry.getKey());
                    }
                    // Check the entire walking volume before claiming it, not just the blocks we place.
                    var bounds = plan.bounds();
                    for (int x = bounds.min().x(); x <= bounds.max().x(); x++) for (int z = bounds.min().z(); z <= bounds.max().z(); z++)
                        for (int y = spawn.getY(); y < spawn.getY() + 4; y++) {
                            var pos = new BlockPos(x, y, z); var actual = level.getBlockState(pos);
                            if (!actual.isAir() && !(saved.equals(identity + "PREPARED\n") && (actual.equals(blueprint.get(pos))
                                    || CampBlueprint.legacyMonsterLectern(spawn, pos, actual)
                                    || CampBlueprint.litAltarGlass(spawn, pos, actual))))
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
                CampBlueprint.labels(spawn).forEach(this::label);
                level.save(null, true, false);
                completed = true; manifestWrite = writeManifest(identity + "COMPLETE\n"); return;
            }
            for (var purpose : new Landing[]{Landing.CENTRAL, Landing.MONSTER})
                if (!safeLanding(purpose)) throw new IllegalStateException("营地落点不安全，保留地形等待修复：" + purpose);
            // Keep the v1 terrain manifest: only these three owned facilities change representation.
            // Completed camps and interrupted old builds upgrade without rebuilding other terrain.
            boolean replaced = false;
            for (var pos : CampBlueprint.monsterFacilities(spawn)) {
                if (CampBlueprint.legacyMonsterLectern(spawn, pos, level.getBlockState(pos))) {
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()); replaced = true;
                }
            }
            // The monster shop arrived after v1: claim only air, never an administrator's edit.
            var labels = CampBlueprint.labels(spawn);
            for (var entry : CampBlueprint.shopAdditions(spawn).entrySet()) {
                var actual = level.getBlockState(entry.getKey());
                if (actual.is(entry.getValue().getBlock())) continue;
                if (!actual.isAir()) { LOGGER.warn("营地商店设施位置已被占用，保留原方块：{}", entry.getKey()); continue; }
                level.setBlockAndUpdate(entry.getKey(), entry.getValue()); replaced = true;
                if (labels.containsKey(entry.getKey())) label(entry.getKey(), labels.get(entry.getKey()));
            }
            if (replaced) level.save(null, true, false);
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
    /** Writes every status text slot; the stage and mode lines change with the round. */
    public void statusLabel(String stage, String mode) {
        if (!ready) return;
        for (var slot : plan.texts(LobbyFunction.STATUS))
            if (slot.sign() != null) label(pos(slot.sign()), new String[]{"HardRush 营地", stage, mode, "前方：集结区"});
    }
    private CompletableFuture<Void> writeManifest(String value) {
        return CompletableFuture.runAsync(() -> {
            try {
                RoundWorldPaths.checkAncestors(manifest, false); Files.createDirectories(manifest.getParent());
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
    public LobbyPlan plan() {
        if (!ready) throw new IllegalStateException("大厅尚未就绪");
        return plan;
    }
    /** Plan coordinates are already absolute. */
    public static Vec3 position(Point point) { return new Vec3(point.x(), point.y(), point.z()); }
    public static BlockPos pos(Cell cell) { return new BlockPos(cell.x(), cell.y(), cell.z()); }
    /** Whether at least one of the purpose's landings can be stood on. */
    public boolean safeLanding(Landing purpose) {
        if (level == null || plan == null) return false;
        return LandingRules.candidates(plan, purpose).stream().anyMatch(this::safe);
    }
    private boolean safe(Point point) { return RoundWorldBundle.safeStanding(level, BlockPos.containing(position(point))); }
    /** The safe landing the next placement for {@code purpose} uses; candidates take turns, skipping unsafe ones. */
    public Optional<Point> landing(Landing purpose) {
        var candidates = LandingRules.candidates(plan(), purpose);
        int start = rotation.getOrDefault(purpose, 0);
        for (int i = 0; i < candidates.size(); i++) {
            var point = candidates.get((start + i) % candidates.size());
            if (safe(point)) return Optional.of(point);
        }
        return Optional.empty();
    }
    /** Where a placement lands, and the facing that goes with it. */
    public record LandingSpot(Vec3 position, float yaw) {}
    /**
     * Vanilla's respawn scatter around a purpose landing, so arrivals do not stack: the lobby's own
     * {@code respawn_radius} on the landing's layer, limited to the purpose's zone and to open floor. Keeps
     * the exact landing otherwise. Advances the purpose's rotation, so the next arrival tries another landing.
     */
    public LandingSpot scatteredLanding(Landing purpose) {
        var candidates = LandingRules.candidates(plan(), purpose);
        if (candidates.isEmpty()) throw new IllegalStateException("大厅缺少落点：" + purpose);
        // Without a safe landing, keep the first one: callers check safeLanding before teleporting.
        var chosen = landing(purpose).orElse(candidates.getFirst());
        rotation.put(purpose, (candidates.indexOf(chosen) + 1) % candidates.size());
        var exact = BlockPos.containing(position(chosen));
        var feet = NativeScatter.first(exact, NativeScatter.radius(level, exact), column -> {
            var standing = new BlockPos(column.getX(), exact.getY(), column.getZ());
            return LandingRules.scatterAllowed(plan, purpose, chosen, standing.getX(), standing.getY(), standing.getZ())
                    && RoundWorldBundle.safeStanding(level, standing)
                    && level.getBlockState(standing).isAir() && level.getBlockState(standing.above()).isAir() ? standing : null;
        });
        return new LandingSpot(feet == null ? position(chosen) : Vec3.atBottomCenterOf(feet), chosen.yaw());
    }
    public boolean inAssembly(net.minecraft.server.level.ServerPlayer player, boolean staying) {
        if (!ready || player.level() != level || !player.isAlive()) return false;
        return plan.inZone(LobbyFunction.ASSEMBLY, player.getX(), player.getY(), player.getZ(), staying);
    }
    /** Standing on an altar pad (jumping included). */
    public boolean onAltar(net.minecraft.server.level.ServerPlayer player) { return altarUnder(player).isPresent(); }
    /** The altar pad the player stands on, for effects at its centre. */
    public Optional<Zone> altarUnder(net.minecraft.server.level.ServerPlayer player) {
        if (!ready || player.level() != level || !player.isAlive()) return Optional.empty();
        return plan.zoneAt(LobbyFunction.ALTAR_PAD, player.getX(), player.getY(), player.getZ(), false);
    }
    /**
     * Light or darken every altar beam by swapping the owned glass above its beacon. Vanilla does the
     * rest: tinted glass stops a beam, and the beacon plays its own activate/deactivate sound.
     */
    public void altarLit(boolean lit) {
        if (!ready) return;
        var want = lit ? Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.YELLOW) : Blocks.TINTED_GLASS;
        for (var cell : plan.altarLights()) {
            var pos = pos(cell);
            var actual = level.getBlockState(pos);
            // Never overwrite an administrator's edit: only our two glass states are ours to swap.
            if (!actual.is(Blocks.TINTED_GLASS) && !actual.is(Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.YELLOW))) continue;
            if (!actual.is(want)) dev.hardrush.protection.ProtectionRuntime.ownedWrite(level, () -> level.setBlockAndUpdate(pos, want.defaultBlockState()));
        }
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
