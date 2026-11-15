package dev.hardrush.lobby.pack;

import dev.hardrush.core.lobby.plan.*;
import dev.hardrush.world.LobbyDimension;
import dev.hardrush.world.RoundWorldPaths;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;

/**
 * Installs a lobby pack into a fresh void dimension (design 10.2), driven one step per {@link #tick}: read, upgrade
 * and sanitize on an IO thread; open the dimension and load the pack's chunks; place one tile per tick; wait for the
 * entity sections; check the recorded plan against the placed world; copy the pack to {@code installed/} and write
 * {@code current.json}. Switching the live lobby over is the caller's ({@code LobbyWorld}). Any failure deletes the new
 * dimension and leaves the current lobby alone.
 * <p>TODO: [Refactor] M4 places stand-in facilities (10.4) for missing required functions; until then a missing
 * function fails the install like any other error.
 */
public final class LobbyInstall {
    private static final Logger LOGGER = LoggerFactory.getLogger("hardrush-lobby");
    public static final long TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(2);
    public static final String DEFAULT_PACK = "/hardrush/lobby/default.hrlobby";

    /** Where the pack bytes come from; opened twice, to read and to copy into {@code installed/}. */
    public interface Source {
        String name();
        InputStream open() throws IOException;

        static Source file(Path file) {
            return new Source() {
                public String name() { return file.getFileName().toString(); }
                public InputStream open() throws IOException {
                    RoundWorldPaths.checkAncestors(file, false);
                    return Files.newInputStream(file);
                }
            };
        }
        /** The pack shipped in the mod jar. */
        static Source builtIn() {
            return new Source() {
                public String name() { return "内置默认大厅"; }
                public InputStream open() throws IOException {
                    var in = LobbyInstall.class.getResourceAsStream(DEFAULT_PACK);
                    if (in == null) throw new IOException("模组里缺少 " + DEFAULT_PACK);
                    return in;
                }
            };
        }
    }

    /** What a finished install hands over. The caller owns {@code handle} and {@code chunks} from then on. */
    public sealed interface Outcome {
        record Installed(LobbyInstallation installation, RuntimeLevelHandle handle, ChunkWarmup chunks,
                         List<Problem> warnings, SanitizeReport sanitized) implements Outcome {}
        record Failed(String reason) implements Outcome {}
    }

    private record Prepared(PackManifest manifest, LobbyPlan plan, Map<TileKey, CompoundTag> tiles, SanitizeReport report) {}
    private enum Stage { READING, LOADING, PLACING, SETTLING, COMMITTING, DONE }

    private final MinecraftServer server;
    private final LobbyStorage storage;
    private final Source source;
    private final int seq;
    private final ResourceKey<Level> key;
    private final int maxSize;
    private final OptionalInt previousSeq;
    private final long started = System.nanoTime();
    private Stage stage = Stage.READING;
    private CompletableFuture<Prepared> reading;
    private CompletableFuture<LobbyInstallation> committing;
    private Prepared prepared;
    private RuntimeLevelHandle handle;
    private ChunkWarmup chunks;
    private Iterator<Map.Entry<TileKey, CompoundTag>> placing;
    private int placed;
    private List<Problem> warnings = List.of();

    /**
     * {@code key} must not be loaded; its directory, if an earlier run left one, is deleted before opening.
     * {@code previousSeq} is the install whose copy is kept for rolling back.
     */
    public LobbyInstall(MinecraftServer server, LobbyStorage storage, Source source, int seq, ResourceKey<Level> key,
                        int maxSize, OptionalInt previousSeq) {
        if (!server.isSameThread()) throw new IllegalStateException("Lobby install starts on the server thread");
        if (server.getLevel(key) != null) throw new IllegalStateException("大厅维度已被加载：" + key.identifier());
        this.server = server; this.storage = storage; this.source = source; this.seq = seq; this.key = key;
        this.maxSize = maxSize; this.previousSeq = previousSeq;
        var folder = LobbyDimension.folder(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT), key);
        var limits = PackLimits.forServer(maxSize);
        reading = CompletableFuture.supplyAsync(() -> {
            try {
                LobbyStorage.deleteTree(folder);
                return prepare(source, limits);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (PackException e) {
                throw new CompletionException(e);
            }
        });
    }

    /** Read, upgrade and sanitize; IO thread. */
    private static Prepared prepare(Source source, PackLimits limits) throws IOException, PackException {
        PackContents contents;
        try (var in = source.open()) { contents = PackFile.read(in, limits); }
        var tiles = new LinkedHashMap<TileKey, CompoundTag>();
        var report = SanitizeReport.EMPTY;
        for (var entry : contents.tiles().entrySet()) {
            var tile = PackTiles.upgrade(entry.getValue(), NbtUtils.getDataVersion(entry.getValue(), contents.manifest().dataVersion()));
            report = report.plus(TileSanitizer.sanitize(tile, SanitizeMode.INSTALL));
            tiles.put(entry.getKey(), tile);
        }
        TileSanitizer.checkTotals(tiles.values());
        return new Prepared(contents.manifest(), contents.plan(), tiles, report);
    }

    public int seq() { return seq; }
    public ResourceKey<Level> key() { return key; }
    /** Tiles placed so far. */
    public int placedTiles() { return placed; }

    /** Advances one step; returns the outcome exactly once, then stays empty. */
    public Optional<Outcome> tick() {
        if (stage == Stage.DONE) return Optional.empty();
        try {
            // Never time out a running commit: it may still write current.json after the dimension is gone.
            if (stage != Stage.COMMITTING && System.nanoTime() - started > TIMEOUT_NANOS)
                throw new IllegalStateException("大厅加载／安装超时（" + stage + (chunks == null ? "" : "，" + chunks.pending()) + "）");
            switch (stage) {
                case READING -> {
                    if (!reading.isDone()) return Optional.empty();
                    prepared = reading.join();
                    handle = LobbyDimension.open(server, key);
                    chunks = new ChunkWarmup(handle.asLevel(), prepared.manifest().bounds());
                    stage = Stage.LOADING;
                }
                case LOADING -> {
                    if (!chunks.tick()) return Optional.empty();
                    placing = prepared.tiles().entrySet().iterator();
                    stage = Stage.PLACING;
                }
                case PLACING -> {
                    if (placing.hasNext()) {
                        var tile = placing.next();
                        PackPlacer.place(handle.asLevel(), tile.getKey(), tile.getValue(), prepared.manifest().bounds());
                        placed++;
                    } else stage = Stage.SETTLING;
                }
                case SETTLING -> {
                    // Placed entities join loaded sections at once; checked anyway, as the scan in verify reads them.
                    if (!chunks.tick()) return Optional.empty();
                    verify();
                    // Region files first: current.json must never name a dimension whose blocks are not on disk yet.
                    handle.asLevel().save(null, true, false);
                    var installation = new LobbyInstallation(seq, key.identifier().toString(),
                            LobbyInstallation.Pack.of(prepared.manifest(), ""), prepared.plan(), LobbyInstallation.State.COMPLETE);
                    committing = CompletableFuture.supplyAsync(() -> {
                        try { return storage.commit(source, installation, previousSeq); }
                        catch (IOException e) { throw new UncheckedIOException(e); }
                    });
                    stage = Stage.COMMITTING;
                }
                case COMMITTING -> {
                    if (!committing.isDone()) return Optional.empty();
                    var installation = committing.join();
                    stage = Stage.DONE;
                    LOGGER.info("大厅包 {}（{} v{}）已安装到 {}：{} 块瓦片，清洗 {}", source.name(), installation.pack().name(),
                            installation.pack().version(), key.identifier(), placed, prepared.report());
                    return Optional.of(new Outcome.Installed(installation, handle, chunks, warnings, prepared.report()));
                }
                case DONE -> throw new IllegalStateException();
            }
            return Optional.empty();
        } catch (RuntimeException error) {
            return Optional.of(fail(error));
        }
    }

    /** Design 9 on the placed world, with the plan recorded in {@code pack.json}: the markers were stripped on install. */
    private void verify() {
        var problems = new ArrayList<Problem>(LobbyPlanValidator.validate(prepared.plan(), maxSize));
        problems.addAll(PlanWorldChecks.check(handle.asLevel(), prepared.plan()));
        var errors = problems.stream().filter(p -> p.severity() == Severity.ERROR).map(Problem::message).toList();
        if (!errors.isEmpty()) throw new IllegalStateException("大厅包验收未通过：" + String.join("；", errors));
        warnings = problems.stream().filter(p -> p.severity() == Severity.WARNING).toList();
        if (!warnings.isEmpty()) LOGGER.warn("大厅包 {} 验收警告：{}", source.name(), warnings.stream().map(Problem::message).toList());
    }

    /** Abandons the install: drops its tickets and deletes the new dimension. Safe to call at any stage. */
    public void cancel() {
        if (stage == Stage.DONE) return;
        stage = Stage.DONE;
        if (chunks != null) chunks.close();
        if (handle != null) handle.delete();
    }

    private Outcome fail(Throwable error) {
        cancel();
        var cause = error;
        while ((cause instanceof CompletionException || cause instanceof UncheckedIOException) && cause.getCause() != null) cause = cause.getCause();
        String reason = cause instanceof PackException || cause instanceof IllegalStateException ? cause.getMessage() : cause.toString();
        LOGGER.warn("大厅包 {} 安装失败", source.name(), error);
        return new Outcome.Failed(source.name() + "：" + reason);
    }
}
