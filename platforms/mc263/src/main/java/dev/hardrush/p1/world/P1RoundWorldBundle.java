package dev.hardrush.p1.world;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.p1.storage.P1StorageGuard;
import dev.hardrush.p1.storage.P1StorageFailure;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;
import xyz.nucleoid.fantasy.mixin.MinecraftServerAccess;

/**
 * Main-thread world preparation with asynchronous native chunk generation and manifest IO.
 * No getChunk/getChunkFuture, worker-thread world edits, deletion, or P0 world reuse.
 */
public final class P1RoundWorldBundle implements AutoCloseable {
    public enum State { OPENING_MANIFEST, OPENING_DIMENSIONS, SEARCHING, LOADING_REGION, VALIDATING, BUILDING, SAVING_MANIFEST, READY, FAILED, CLOSING, CLOSE_FAILED, CLOSED }
    public record Metrics(long elapsedMillis, long longestChunkMillis, long maximumTickMicros, int candidates, int changedBlocks) {}
    private static final long TICK_BUDGET_NANOS = 2_000_000;
    private static final int CANDIDATES_PER_TICK = 16;
    private static final int BLOCKS_PER_TICK = 64;
    private static final int MAX_IN_FLIGHT = 2;
    private static final long CHUNK_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final TicketType PREPARATION = new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING);
    private static final Map<MinecraftServer, Map<UUID, P1RoundWorldBundle>> CLAIMS = new java.util.IdentityHashMap<>();

    private final MinecraftServer server;
    private final RoundSnapshot snapshot;
    private final Path root;
    private final Path manifestPath;
    private final ExecutorService io;
    private final Map<String, RuntimeLevelHandle> handles = new LinkedHashMap<>();
    private final Map<Request, CompletableFuture<?>> inFlight = new LinkedHashMap<>();
    private final Map<Request, Long> startedRequests = new LinkedHashMap<>();
    private final java.util.Set<Request> held = new java.util.LinkedHashSet<>();
    private final ArrayDeque<Request> requested = new ArrayDeque<>();
    private final ArrayDeque<Edit> edits = new ArrayDeque<>();
    private final List<ChunkPos> searchChunks = new ArrayList<>();
    private CompletableFuture<P1WorldManifest> manifestIo;
    private CompletableFuture<Void> capacityIo;
    private long lastCapacityCheck;
    private CompletableFuture<P1WorldManifest> closeValidation;
    private CompletableFuture<Void> closeCompletion;
    private final java.util.Set<ServerLevel> closedNativeLevels = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private boolean unloadQueued;
    private long closeStartedNanos;
    private P1WorldManifest manifest;
    private State state = State.OPENING_MANIFEST;
    private String failure = "";
    private BlockPos candidate;
    private List<BlockPos> exits = List.of();
    private int openedDimensions;
    private int searchIndex;
    private int columnIndex;
    private long startedNanos = System.nanoTime();
    private long finishedNanos;
    private long longestChunkNanos;
    private long maximumTickNanos;
    private int candidates;
    private int changedBlocks;
    private int validationIndex;

    private P1RoundWorldBundle(MinecraftServer server, RoundSnapshot snapshot) {
        requireMainThread(server);
        this.server = server;
        this.snapshot = snapshot;
        root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        manifestPath = P1WorldPaths.manifest(root, snapshot.roundId());
        io = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "hardrush-p1-world-manifest");
            thread.setDaemon(true);
            return thread;
        });
        if (!snapshot.rules().minecraftVersion().equals("26.3") || !snapshot.rules().generationRule().equals("normal")
                || snapshot.rules().protectionRadius() > 128) {
            fail(new IllegalArgumentException("26.3 世界适配只支持 normal 生成和最多 128 格出生保护半径"));
            return;
        }
        beginManifest();
    }

    public static P1RoundWorldBundle open(MinecraftServer server, RoundSnapshot snapshot) {
        requireMainThread(server);
        var claims = CLAIMS.computeIfAbsent(server, ignored -> new LinkedHashMap<>());
        if (claims.containsKey(snapshot.roundId())) {
            throw new IllegalStateException("本轮世界仍被准备或关闭会话持有；等待真实关闭完成后重开: " + snapshot.roundId());
        }
        var bundle = new P1RoundWorldBundle(server, snapshot);
        claims.put(snapshot.roundId(), bundle);
        return bundle;
    }

    private void beginManifest() {
        var worldDimensions = WorldPresets.createNormalWorldDimensions(server.registryAccess());
        var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        var generators = new LinkedHashMap<String, JsonElement>();
        for (String dimension : P1WorldManifest.DIMENSIONS) {
            ResourceKey<LevelStem> key = switch (dimension) {
                case "nether" -> LevelStem.NETHER;
                case "end" -> LevelStem.END;
                default -> LevelStem.OVERWORLD;
            };
            generators.put(dimension, ChunkGenerator.CODEC.encodeStart(ops, worldDimensions.get(key).orElseThrow().generator()).getOrThrow());
        }
        var initial = new P1WorldManifest(snapshot.roundId(), snapshot.seed(), snapshot.rules().encode(), generators, null, List.of());
        manifestIo = CompletableFuture.supplyAsync(() -> {
            try {
                P1StorageGuard.requireWorldPreparation(root);
                P1WorldPaths.checkAncestors(manifestPath, false);
                boolean existingManifest = Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS);
                if (!existingManifest) {
                    for (String dimension : P1WorldManifest.DIMENSIONS) {
                        if (Files.exists(P1WorldPaths.world(root, snapshot.roundId(), dimension), LinkOption.NOFOLLOW_LINKS)) {
                            throw new java.io.IOException("本轮世界目录已存在但所有权清单缺失；保留文件并停止，需恢复原清单");
                        }
                    }
                }
                var stored = existingManifest ? P1WorldManifest.read(root, manifestPath) : initial;
                stored.requireSnapshot(snapshot);
                // Existing ownership is checked without rewriting its recovery evidence.
                if (!existingManifest) stored.write(root, manifestPath);
                return stored;
            } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        }, io);
        state = State.OPENING_MANIFEST;
    }

    /** One dimension constructor per tick; terrain/search/edit work also has quantity and time budgets. */
    public void tick() {
        requireMainThread(server);
        if (state == State.CLOSING) { tickClose(); return; }
        if (state == State.CLOSE_FAILED) return;
        if (state == State.FAILED || state == State.CLOSED || state == State.READY) return;
        long tickStart = System.nanoTime();
        try {
            if (state == State.OPENING_MANIFEST) {
                if (!manifestIo.isDone()) return;
                manifest = manifestIo.join();
                lastCapacityCheck = System.nanoTime();
                state = State.OPENING_DIMENSIONS;
                return;
            }
            // All filesystem queries stay on IO. Hold further preparation while a check is pending.
            if (capacityIo != null) {
                if (!capacityIo.isDone()) return;
                capacityIo.join();
                capacityIo = null;
                lastCapacityCheck = System.nanoTime();
            }
            if (System.nanoTime() - lastCapacityCheck >= TimeUnit.SECONDS.toNanos(1)) {
                capacityIo = CompletableFuture.runAsync(() -> {
                    try { P1StorageGuard.requireCapacity(root); }
                    catch (java.io.IOException error) { throw new java.util.concurrent.CompletionException(error); }
                }, io);
                return;
            }
            if (state == State.OPENING_DIMENSIONS) { openOneDimension(); return; }
            if (state == State.SAVING_MANIFEST) {
                if (manifestIo.isDone()) {
                    manifest = manifestIo.join();
                    state = State.READY;
                    finishedNanos = System.nanoTime();
                }
                return;
            }
            pollChunks(tickStart);
            if (state == State.SEARCHING) search(tickStart);
            if (state == State.LOADING_REGION && requested.isEmpty() && inFlight.isEmpty()) prepareCandidate();
            if (state == State.VALIDATING) validateRecoveredRegion(tickStart);
            if (state == State.BUILDING) build(tickStart);
        } catch (RuntimeException error) { fail(error); }
        finally { maximumTickNanos = Math.max(maximumTickNanos, System.nanoTime() - tickStart); }
    }

    private void openOneDimension() {
        String name = P1WorldManifest.DIMENSIONS.get(openedDimensions);
        var key = key(name);
        var existing = server.getLevel(key);
        if (existing != null && !(existing instanceof P1RoundRuntimeLevel)) throw new IllegalStateException("维度已被其他实现占用: " + key);
        if (existing instanceof P1RoundRuntimeLevel opened && (opened.owner() != this || !opened.persistent())) {
            throw new IllegalStateException("本轮世界已被另一准备会话持有或不是持久世界: " + key);
        }
        var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        var generator = ChunkGenerator.CODEC.parse(ops, manifest.generators.get(name)).getOrThrow();
        var config = new RuntimeLevelConfig().setSeed(manifest.seed).setGenerator(generator)
                .setDimensionType(switch (name) {
                    case "nether" -> BuiltinDimensionTypes.NETHER;
                    case "end" -> BuiltinDimensionTypes.END;
                    default -> BuiltinDimensionTypes.OVERWORLD;
                }).setDifficulty(Difficulty.valueOf(snapshot.rules().difficulty().name()))
                .setShouldTickTime(true).setMirrorOverworldGameRules(false)
                .setGameRule(GameRules.KEEP_INVENTORY, false).setLevelConstructor(P1RoundRuntimeLevel::new);
        var handle = Fantasy.get(server).getOrOpenPersistentLevel(key.identifier(), config);
        if (handle.asLevel().getSeed() != snapshot.seed()) throw new IllegalStateException("轮次种子不符: " + name);
        handles.put(name, handle);
        ((P1RoundRuntimeLevel) handle.asLevel()).bind(this, (P1RoundRuntimeLevel) rawLevel("overworld"));
        if (++openedDimensions != 3) return;
        var overworld = (P1RoundRuntimeLevel) rawLevel("overworld");
        for (var opened : handles.values()) ((P1RoundRuntimeLevel) opened.asLevel()).bind(this, overworld);
        if (rawLevel("end").getDragonFight() == null) throw new IllegalStateException("末地未初始化原版龙战");
        var origin = overworld.getChunkSource().getGenerator().getOrigin(overworld.getChunkSource().randomState());
        for (int radius = 0; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (radius == 0 || Math.abs(dx) == radius || Math.abs(dz) == radius) searchChunks.add(new ChunkPos(origin.x() + dx, origin.z() + dz));
            }
        }
        if (manifest.spawn != null) {
            candidate = manifest.spawn;
            exits = manifest.exits;
            queueRegion();
        } else {
            state = State.SEARCHING;
            queue("overworld", searchChunks.getFirst());
        }
    }

    private void pollChunks(long tickStart) {
        for (var entry : new ArrayList<>(inFlight.entrySet())) {
            Request request = entry.getKey();
            long elapsed = System.nanoTime() - startedRequests.get(request);
            if (elapsed > CHUNK_TIMEOUT_NANOS) throw new IllegalStateException("区块准备超时，可重试同一轮: " + request);
            if (!entry.getValue().isDone()) continue;
            Object result = entry.getValue().join();
            if (result instanceof ChunkResult<?> chunkResult && !chunkResult.isSuccess()) {
                throw new IllegalStateException("区块准备失败: " + chunkResult.getError());
            }
            if (rawLevel(request.dimension).getChunkSource().getChunkNow(request.chunk.x(), request.chunk.z()) == null) continue;
            longestChunkNanos = Math.max(longestChunkNanos, elapsed);
            inFlight.remove(request);
            startedRequests.remove(request);
        }
        while (inFlight.size() < MAX_IN_FLIGHT && !requested.isEmpty() && System.nanoTime() - tickStart < TICK_BUDGET_NANOS) {
            Request request = requested.remove();
            held.add(request);
            startedRequests.put(request, System.nanoTime());
            // Unlike getChunkFuture on the server thread, this native API does not managedBlock.
            var future = rawLevel(request.dimension).getChunkSource().addTicketAndLoadWithRadius(PREPARATION, request.chunk, 0);
            inFlight.put(request, future);
        }
    }

    private void search(long tickStart) {
        if (!requested.isEmpty() || !inFlight.isEmpty()) return;
        var level = rawLevel("overworld");
        ChunkPos pos = searchChunks.get(searchIndex);
        var chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
        if (chunk == null) throw new IllegalStateException("准备区块提前卸载");
        for (int examined = 0; examined < CANDIDATES_PER_TICK && columnIndex < 16
                && System.nanoTime() - tickStart < TICK_BUDGET_NANOS; examined++) {
            int localX = 2 + (columnIndex % 4) * 4, localZ = 2 + (columnIndex / 4) * 4;
            columnIndex++;
            candidates++;
            int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, localX, localZ) + 1;
            BlockPos position = new BlockPos(pos.getMinBlockX() + localX, y, pos.getMinBlockZ() + localZ);
            if (safeStanding(level, position) && y + 4 < level.getMaxY()) {
                candidate = position;
                exits = List.of();
                queueRegion();
                return;
            }
        }
        if (columnIndex == 16) {
            releaseTickets("overworld");
            if (++searchIndex == searchChunks.size()) throw new IllegalStateException("原版出生区域未找到多条安全出口；保持大厅，可重试");
            columnIndex = 0;
            queue("overworld", searchChunks.get(searchIndex));
        }
    }

    private void queueRegion() {
        state = State.LOADING_REGION;
        int extent = (int) Math.ceil(snapshot.rules().protectionRadius()) + 11;
        for (int x = (candidate.getX() - extent) >> 4; x <= (candidate.getX() + extent) >> 4; x++) {
            for (int z = (candidate.getZ() - extent) >> 4; z <= (candidate.getZ() + extent) >> 4; z++) queue("overworld", new ChunkPos(x, z));
        }
        // Necessary vanilla arrival regions; no eager whole-world generation.
        var end = ServerLevel.END_SPAWN_POINT;
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) queue("end", new ChunkPos((end.getX() >> 4) + x, (end.getZ() >> 4) + z));
        queue("nether", new ChunkPos(0, 0));
    }

    private void prepareCandidate() {
        var level = rawLevel("overworld");
        int distance = (int) Math.ceil(snapshot.rules().protectionRadius()) + 8;
        if (manifest.spawn != null) {
            // A ready world can contain player changes. Recovery only validates it; it never repairs
            // blocks, recreates the End platform, clears containers, or regenerates terrain.
            if (!safeStanding(level, manifest.spawn)) throw new IllegalStateException("已准备出生点受损，保持大厅等待人工恢复；未修改世界");
            validationIndex = 0;
            state = State.VALIDATING;
            return;
        }
        if (exits.isEmpty()) {
            var checked = new ArrayList<BlockPos>();
            for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
                BlockPos column = candidate.relative(direction, distance);
                var chunk = level.getChunkSource().getChunkNow(column.getX() >> 4, column.getZ() >> 4);
                int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX() & 15, column.getZ() & 15) + 1;
                BlockPos endpoint = new BlockPos(column.getX(), y, column.getZ());
                if (Math.abs(y - candidate.getY()) <= distance - 4 && safeStanding(level, endpoint)
                        && safeStanding(level, endpoint.relative(direction))) checked.add(endpoint);
            }
            if (checked.size() < 2) { state = State.SEARCHING; candidate = null; return; }
            exits = List.copyOf(checked);
        }
        // Idempotent spawn infrastructure; every world edit runs on the main thread under the budget.
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) walkway(candidate.offset(x, 0, z));
        for (BlockPos endpoint : exits) {
            Direction direction = exitDirection(endpoint);
            Direction side = direction.getClockWise();
            for (int step = 5; step <= distance; step++) {
                int y = candidate.getY() + Math.floorDiv((endpoint.getY() - candidate.getY()) * (step - 4), distance - 4);
                BlockPos center = candidate.relative(direction, step).atY(y);
                for (int offset = -1; offset <= 1; offset++) walkway(center.relative(side, offset));
                if (step < distance) for (int offset : new int[] {-2, 2}) {
                    BlockPos rail = center.relative(side, offset);
                    edits.add(new Edit("overworld", rail.below(), Blocks.STONE.defaultBlockState()));
                    edits.add(new Edit("overworld", rail, Blocks.STONE.defaultBlockState()));
                    edits.add(new Edit("overworld", rail.above(), Blocks.STONE.defaultBlockState()));
                    edits.add(new Edit("overworld", rail.above(2), Blocks.AIR.defaultBlockState()));
                }
            }
        }
        BlockPos endFeet = ServerLevel.END_SPAWN_POINT;
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            BlockPos column = endFeet.offset(x, 0, z);
            edits.add(new Edit("end", column.below(), Blocks.OBSIDIAN.defaultBlockState()));
            for (int y = 0; y <= 2; y++) edits.add(new Edit("end", column.above(y), Blocks.AIR.defaultBlockState()));
        }
        state = State.BUILDING;
    }

    private void validateRecoveredRegion(long tickStart) {
        int distance = (int) Math.ceil(snapshot.rules().protectionRadius()) + 8;
        int total = exits.size() * (distance - 3);
        for (int count = 0; count < CANDIDATES_PER_TICK && validationIndex < total
                && System.nanoTime() - tickStart < TICK_BUDGET_NANOS; count++) {
            int directionIndex = validationIndex / (distance - 3);
            int step = 4 + validationIndex % (distance - 3);
            BlockPos endpoint = exits.get(directionIndex);
            int y = candidate.getY() + Math.floorDiv((endpoint.getY() - candidate.getY()) * (step - 4), distance - 4);
            BlockPos feet = candidate.relative(exitDirection(endpoint), step).atY(y);
            if (!safeStanding(rawLevel("overworld"), feet)) {
                throw new IllegalStateException("已准备出生出口受损，保持大厅等待人工恢复；未修改世界: " + feet);
            }
            validationIndex++;
        }
        if (validationIndex == total) {
            state = State.READY;
            finishedNanos = System.nanoTime();
        }
    }

    private void walkway(BlockPos feet) {
        edits.add(new Edit("overworld", feet.below(), Blocks.STONE.defaultBlockState()));
        for (int y = 0; y <= 3; y++) edits.add(new Edit("overworld", feet.above(y), Blocks.AIR.defaultBlockState()));
    }

    private Direction exitDirection(BlockPos endpoint) {
        if (endpoint.getX() > candidate.getX()) return Direction.EAST;
        if (endpoint.getX() < candidate.getX()) return Direction.WEST;
        return endpoint.getZ() > candidate.getZ() ? Direction.SOUTH : Direction.NORTH;
    }

    private void build(long tickStart) {
        for (int count = 0; count < BLOCKS_PER_TICK && !edits.isEmpty()
                && System.nanoTime() - tickStart < TICK_BUDGET_NANOS; count++) {
            Edit edit = edits.remove();
            var level = rawLevel(edit.dimension);
            if (!level.getWorldBorder().isWithinBounds(edit.position) || !level.isInsideBuildHeight(edit.position)) {
                throw new IllegalStateException("出生设施越过世界边界或高度限制");
            }
            if (!level.getBlockState(edit.position).equals(edit.state)) {
                level.setBlock(edit.position, edit.state, 18);
                changedBlocks++;
            }
        }
        if (!edits.isEmpty()) return;
        if (!safeStanding(rawLevel("overworld"), candidate)) throw new IllegalStateException("出生区建造后安全校验失败");
        for (BlockPos exit : exits) if (!safeStanding(rawLevel("overworld"), exit)) throw new IllegalStateException("出生出口安全校验失败");
        var prepared = manifest.prepared(candidate, exits);
        manifestIo = CompletableFuture.supplyAsync(() -> {
            try { P1StorageGuard.requireCapacity(root); prepared.write(root, manifestPath); return prepared; }
            catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        }, io);
        state = State.SAVING_MANIFEST;
    }

    private void queue(String dimension, ChunkPos chunk) {
        var request = new Request(dimension, chunk);
        if (!held.contains(request) && !requested.contains(request)) requested.add(request);
    }

    public State state() { return state; }
    public boolean ready() { return state == State.READY; }
    public Optional<String> failure() { return failure.isEmpty() ? Optional.empty() : Optional.of(failure); }
    public java.util.UUID roundId() { return snapshot.roundId(); }
    public Metrics metrics() {
        return new Metrics(TimeUnit.NANOSECONDS.toMillis((finishedNanos == 0 ? System.nanoTime() : finishedNanos) - startedNanos),
                TimeUnit.NANOSECONDS.toMillis(longestChunkNanos), maximumTickNanos / 1_000, candidates, changedBlocks);
    }
    /** Read-only task detail distinguishes pending generation from a completed but inaccessible chunk. */
    public String diagnostics() {
        requireMainThread(server);
        var details = new ArrayList<String>();
        for (var entry : inFlight.entrySet()) {
            var request = entry.getKey();
            var source = rawLevel(request.dimension).getChunkSource();
            details.add(request + ":future=" + (entry.getValue().isDone() ? "done" : "pending")
                    + ",accessible=" + (source.getChunkNow(request.chunk.x(), request.chunk.z()) != null)
                    + ",status=" + source.chunkMap.getLatestStatus(request.chunk.pack())
                    + ",millis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedRequests.get(request)));
        }
        return state + ", queued=" + requested.size() + ", held=" + held.size() + ", requests=" + details + ", metrics=" + metrics();
    }
    public BlockPos spawn() { requireReady(); return manifest.spawn; }
    public List<BlockPos> exits() { requireReady(); return manifest.exits; }
    public ServerLevel level(ResourceKey<Level> semantic) { requireReady(); return rawLevel(name(semantic)); }
    public Path manifestPath() { return manifestPath; }

    /** Retains the exact round identity, generator and partial world files. No automatic fault clearing. */
    public void retry() {
        requireMainThread(server);
        if (state != State.FAILED) throw new IllegalStateException("只有失败准备任务可重试");
        if (!snapshot.rules().minecraftVersion().equals("26.3") || !snapshot.rules().generationRule().equals("normal")
                || snapshot.rules().protectionRadius() > 128) {
            throw new IllegalStateException("本轮规则不受当前世界适配支持，重试不能改变既存规则快照");
        }
        handles.values().forEach(handle -> {
            if (!handle.asLevel().players().isEmpty()) throw new IllegalStateException("半完成世界仍有玩家；先安全回大厅");
        });
        failure = "";
        capacityIo = null;
        openedDimensions = 0;
        searchIndex = 0;
        columnIndex = 0;
        candidate = null;
        exits = List.of();
        searchChunks.clear();
        requested.clear();
        inFlight.clear();
        startedRequests.clear();
        edits.clear();
        startedNanos = System.nanoTime();
        finishedNanos = 0;
        beginManifest();
    }

    public void save() {
        requireMainThread(server);
        if (state == State.CLOSED || unloadQueued) throw new IllegalStateException("本轮世界正在关闭或已经关闭，不能再次保存");
        requireOwnedHandles(true);
        for (var handle : handles.values()) handle.asLevel().save(null, true, false);
    }

    /**
     * Starts a verified save/unload on the main thread after evacuation. Call tick() until the
     * completion finishes: Fantasy unload is queued and does not close native region IO itself.
     * Success means every handle is unregistered and every native level is closed. No file is
     * deleted. A refused close leaves the bundle intact; CLOSE_FAILED can be retried after repair.
     */
    public CompletionStage<Void> saveAndClose() {
        requireMainThread(server);
        if (state == State.CLOSED) return CompletableFuture.completedFuture(null);
        if (state == State.CLOSING) return closeCompletion.minimalCompletionStage();
        requireOwnedHandles(!unloadQueued);
        requireEvacuated();
        closeCompletion = new CompletableFuture<>();
        closeStartedNanos = System.nanoTime();
        failure = "";
        state = State.CLOSING;
        // Join neither manifest writes nor directory scans on the server thread. This read checks
        // current persisted ownership rather than trusting the manifest cached when preparation began.
        var pending = manifestIo == null ? CompletableFuture.<P1WorldManifest>completedFuture(null) : manifestIo;
        closeValidation = pending.handle((stored, error) -> null).thenApplyAsync(ignored -> {
            try {
                if (handles.isEmpty()) {
                    // No native handle was acquired, so a failed/missing manifest is recovery evidence
                    // rather than permission to touch any world. There is nothing to unload or save.
                    P1WorldPaths.checkAncestors(manifestPath, false);
                    return null;
                }
                var stored = P1WorldManifest.read(root, manifestPath);
                stored.requireSnapshot(snapshot);
                if (manifest == null || !manifest.generators.equals(stored.generators)) {
                    throw new java.io.IOException("世界生成器清单在持有期间变化；拒绝保存或关闭，保留原始恢复证据");
                }
                return stored;
            } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        }, io);
        return closeCompletion.minimalCompletionStage();
    }

    /** AutoCloseable compatibility only initiates unloading; use saveAndClose() to await success. */
    @Override public void close() { saveAndClose(); }

    private void tickClose() {
        try {
            if (!closeValidation.isDone()) return;
            closeValidation.join();
            requireEvacuated();
            requireOwnedHandles(!unloadQueued);
            if (!unloadQueued) {
                releaseTickets(null);
                for (var handle : handles.values()) handle.asLevel().save(null, true, false);
                for (var handle : handles.values()) handle.unload();
                unloadQueued = true;
                return;
            }
            for (var handle : handles.values()) {
                var level = handle.asLevel();
                if (server.getLevel(level.dimension()) == level) {
                    if (System.nanoTime() - closeStartedNanos > CHUNK_TIMEOUT_NANOS) {
                        throw new IllegalStateException("Fantasy 世界注销超时；保留文件并等待人工恢复，可重试关闭");
                    }
                    return;
                }
            }
            for (var handle : handles.values()) {
                var level = handle.asLevel();
                if (!closedNativeLevels.contains(level)) {
                    level.close();
                    closedNativeLevels.add(level);
                }
            }
            io.shutdown();
            finishClosed();
            closeCompletion.complete(null);
        } catch (Exception error) {
            Throwable cause = error;
            while (cause.getCause() != null) cause = cause.getCause();
            failure = "CLOSING: " + cause.getMessage();
            state = State.CLOSE_FAILED;
            closeCompletion.completeExceptionally(error);
        }
    }

    private void requireEvacuated() {
        for (var handle : handles.values()) if (!handle.asLevel().players().isEmpty()) {
            throw new IllegalStateException("关闭本轮世界前必须将全部玩家送回大厅");
        }
        for (var player : server.getPlayerList().getPlayers()) if (handles.values().stream().anyMatch(handle -> handle.asLevel() == player.level())) {
            throw new IllegalStateException("在线玩家仍引用本轮世界；必须先安全回大厅");
        }
    }

    private void requireOwnedHandles(boolean registered) {
        for (var entry : handles.entrySet()) {
            var level = entry.getValue().asLevel();
            var expectedKey = key(entry.getKey());
            var active = server.getLevel(expectedKey);
            if (!(level instanceof P1RoundRuntimeLevel owned) || owned.owner() != this || !owned.persistent()
                    || level.getServer() != server || level == server.overworld()
                    || !level.dimension().equals(expectedKey) || level.getSeed() != snapshot.seed()
                    || (registered ? active != level : active != null && active != level)) {
                throw new IllegalStateException("拒绝保存或关闭未由本轮持有的活动世界: " + expectedKey);
            }
            Path actual = ((MinecraftServerAccess) server).getStorageSource().getDimensionPath(expectedKey).toAbsolutePath().normalize();
            if (!P1WorldPaths.world(root, snapshot.roundId(), entry.getKey()).equals(actual)) {
                throw new IllegalStateException("拒绝保存或关闭存储路径不符的世界: " + actual);
            }
            try { P1WorldPaths.checkAncestors(actual, true); }
            catch (java.io.IOException error) { throw new IllegalStateException("拒绝保存或关闭不安全的世界路径", error); }
        }
    }

    /**
     * Normal SERVER_STOPPING only. Keep levels registered so vanilla subsequently closes every
     * ServerLevel/region IO worker; Fantasy.unload must not remove them from vanilla's close loop.
     * The caller first suspends/evacuates managed player profiles and drains their durable writes.
     */
    public void shutdown() {
        requireMainThread(server);
        if (state == State.CLOSED) return;
        requireOwnedHandles(!unloadQueued);
        finishManifestIo();
        releaseTickets(null);
        if (!unloadQueued) for (var handle : handles.values()) handle.asLevel().save(null, true, false);
        else for (var handle : handles.values()) {
            var level = handle.asLevel();
            if (server.getLevel(level.dimension()) == null && !closedNativeLevels.contains(level)) {
                try { level.close(); }
                catch (java.io.IOException error) { throw new IllegalStateException(error); }
                closedNativeLevels.add(level);
            }
        }
        finishClosed();
        if (closeCompletion != null && !closeCompletion.isDone()) {
            closeCompletion.completeExceptionally(new IllegalStateException("服务器关闭中；注册世界交由原版关闭循环释放"));
        }
    }

    private void finishManifestIo() {
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("世界清单 IO 尚未完成，保留世界等待恢复");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        // A worker can fail after its last tick but before SERVER_STOPPING. Draining the
        // executor is not proof of successful IO; propagate failure so no clean marker is saved.
        RuntimeException failure = null;
        for (var task : java.util.Arrays.asList(manifestIo, capacityIo, closeValidation)) {
            if (task == null) continue;
            try { task.join(); }
            catch (RuntimeException error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure != null) throw new IllegalStateException("世界存储 IO 失败，不能标记正常停服；保留资料等待恢复", failure);
    }

    private void finishClosed() {
        state = State.CLOSED;
        inFlight.clear();
        startedRequests.clear();
        requested.clear();
        edits.clear();
        var claims = CLAIMS.get(server);
        if (claims != null) {
            claims.remove(snapshot.roundId(), this);
            if (claims.isEmpty()) CLAIMS.remove(server);
        }
    }

    private void fail(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) cause = cause.getCause();
        failure = state + ": " + cause.getMessage() + "。" + P1StorageFailure.advice(error);
        state = State.FAILED;
        releaseTickets(null);
    }

    private void releaseTickets(String dimension) {
        for (Request request : new ArrayList<>(held)) if (dimension == null || request.dimension.equals(dimension)) {
            rawLevel(request.dimension).getChunkSource().removeTicketWithRadius(PREPARATION, request.chunk, 0);
            held.remove(request);
        }
    }

    private ServerLevel rawLevel(String name) {
        var handle = handles.get(name);
        if (handle == null) throw new IllegalStateException("本轮维度尚未打开: " + name);
        return handle.asLevel();
    }
    private ResourceKey<Level> key(String name) { return ResourceKey.create(Registries.DIMENSION, Identifier.parse(P1WorldPaths.identifier(snapshot.roundId(), name))); }
    private void requireReady() { if (!ready()) throw new IllegalStateException("本轮三维度尚未就绪，留在大厅: " + state + " " + failure); }
    private static void requireMainThread(MinecraftServer server) { if (!server.isSameThread()) throw new IllegalStateException("世界操作必须在服务端主线程"); }
    private static String name(ResourceKey<Level> key) {
        if (key.equals(Level.OVERWORLD)) return "overworld";
        if (key.equals(Level.NETHER)) return "nether";
        if (key.equals(Level.END)) return "end";
        throw new IllegalArgumentException("不是原版语义维度: " + key);
    }

    public LevelData.RespawnData respawnData() {
        BlockPos position = manifest == null || manifest.spawn == null ? new BlockPos(0, 64, 0) : manifest.spawn;
        return LevelData.RespawnData.of(key("overworld"), position, 0, 0);
    }

    public static boolean owns(ServerLevel level) {
        return level instanceof P1RoundRuntimeLevel round && round.owner() != null
                && round.owner().state != State.CLOSED && round.owner().handles.values().stream().anyMatch(handle -> handle.asLevel() == level);
    }
    public static ResourceKey<Level> semanticDimension(ServerLevel level) {
        if (!owns(level)) return level.dimension();
        var owner = ((P1RoundRuntimeLevel) level).owner();
        if (level.dimension().equals(owner.key("nether"))) return Level.NETHER;
        if (level.dimension().equals(owner.key("end"))) return Level.END;
        return Level.OVERWORLD;
    }
    public static ServerLevel portalDestination(ServerLevel source, ResourceKey<Level> requested) {
        if (!owns(source)) return source.getServer().getLevel(requested);
        var owner = ((P1RoundRuntimeLevel) source).owner();
        if (!owner.ready()) return null;
        if (requested.equals(Level.OVERWORLD) || requested.equals(Level.NETHER) || requested.equals(Level.END)) return owner.rawLevel(name(requested));
        ServerLevel destination = source.getServer().getLevel(requested);
        return destination != null && sameRound(source, destination) ? destination : null;
    }
    public static boolean sameRound(ServerLevel source, ServerLevel destination) {
        return owns(source) && owns(destination) && ((P1RoundRuntimeLevel) source).owner() == ((P1RoundRuntimeLevel) destination).owner();
    }
    public static TeleportTransition boundRespawn(ServerPlayer player, TeleportTransition original) {
        if (!owns(player.level())) return original;
        var owner = ((P1RoundRuntimeLevel) player.level()).owner();
        owner.requireReady();
        if (sameRound(player.level(), original.newLevel())) return original;
        ServerLevel destination = owner.rawLevel("overworld");
        BlockPos spawn = owner.spawn();
        if (!safeStanding(destination, spawn)) throw new IllegalStateException("本轮出生点不安全；拒绝跨轮回退，需大厅恢复");
        return new TeleportTransition(destination, Vec3.atBottomCenterOf(spawn), Vec3.ZERO, 0, 0,
                original.missingRespawnBlock(), false, java.util.Set.of(), original.postTeleportTransition());
    }
    public static boolean safeStanding(ServerLevel level, BlockPos feet) {
        if (level.getChunkSource().getChunkNow(feet.getX() >> 4, feet.getZ() >> 4) == null
                || !level.isInsideBuildHeight(feet.below()) || !level.isInsideBuildHeight(feet.above())
                || !level.getWorldBorder().isWithinBounds(feet)) return false;
        var floor = level.getBlockState(feet.below());
        return floor.isFaceSturdy(level, feet.below(), Direction.UP) && !floor.is(Blocks.MAGMA_BLOCK)
                && !floor.is(Blocks.CACTUS) && !floor.is(Blocks.CAMPFIRE) && !floor.is(Blocks.SOUL_CAMPFIRE)
                && clearStandingBlock(level, feet) && clearStandingBlock(level, feet.above())
                && level.getFluidState(feet).isEmpty() && level.getFluidState(feet.below()).isEmpty();
    }
    private static boolean clearStandingBlock(ServerLevel level, BlockPos position) {
        var state = level.getBlockState(position);
        return state.getCollisionShape(level, position).isEmpty() && level.getFluidState(position).isEmpty()
                && !state.is(Blocks.FIRE) && !state.is(Blocks.SOUL_FIRE) && !state.is(Blocks.NETHER_PORTAL)
                && !state.is(Blocks.END_PORTAL) && !state.is(Blocks.SWEET_BERRY_BUSH) && !state.is(Blocks.WITHER_ROSE);
    }
    private record Request(String dimension, ChunkPos chunk) {}
    private record Edit(String dimension, BlockPos position, BlockState state) {}
}
