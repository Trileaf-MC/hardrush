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
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;

/**
 * Installs a lobby pack into a fresh void dimension (design 10.2), driven one step per {@link #tick}: read, upgrade
 * and sanitize on an IO thread; open the dimension and load the pack's chunks; place one tile per tick; wait for the
 * entity sections; check the recorded plan against the placed world and place stand-ins for missing functions
 * ({@link FallbackFacilities}); wait until the caller may switch; copy the pack to {@code installed/} and write
 * {@code current.json}. Switching the live lobby over is the caller's ({@code LobbyWorld}), right after the outcome:
 * the record is only written once a switch is allowed, so it never names a lobby that does not go live. Any failure,
 * waiting too long for the switch included, deletes the new dimension and leaves the current lobby alone.
 */
public final class LobbyInstall {
    private static final Logger LOGGER = LoggerFactory.getLogger("hardrush-lobby");
    public static final long TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(2);
    /** How long a built lobby waits for the round to allow the switch before it is given up (design 10.2). */
    public static final long SWITCH_WAIT_NANOS = TimeUnit.MINUTES.toNanos(10);
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
                         List<Problem> warnings, SanitizeReport sanitized, FallbackFacilities.Placed fallbacks) implements Outcome {}
        record Failed(String reason) implements Outcome {}
    }

    /** {@link #READY}: built and checked, waiting for the caller to allow the switch. */
    public enum Stage { READING, LOADING, PLACING, SETTLING, READY, COMMITTING, DONE }

    private final MinecraftServer server;
    private final LobbyStorage storage;
    private final Source source;
    private final int seq;
    private final ResourceKey<Level> key;
    private final int maxSize;
    private final OptionalInt previousSeq;
    private final long started = System.nanoTime();
    private Stage stage = Stage.READING;
    private CompletableFuture<PreparedPack> reading;
    private CompletableFuture<LobbyInstallation> committing;
    private PreparedPack prepared;
    private RuntimeLevelHandle handle;
    private ChunkWarmup chunks;
    private Iterator<Map.Entry<TileKey, CompoundTag>> placing;
    private int placed;
    private List<Problem> warnings = List.of();
    /** The pack's plan plus its stand-ins: what is recorded and switched to. */
    private LobbyPlan effective;
    private FallbackFacilities.Placed fallbacks = FallbackFacilities.Placed.NONE;
    private long readySince;

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
                return PreparedPack.read(source, limits, SanitizeMode.INSTALL);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (PackException e) {
                throw new CompletionException(e);
            }
        });
    }

    public int seq() { return seq; }
    public ResourceKey<Level> key() { return key; }
    public String sourceName() { return source.name(); }
    public Stage stage() { return stage; }
    /** The pack's header, once read. */
    public Optional<PackManifest> manifest() { return prepared == null ? Optional.empty() : Optional.of(prepared.manifest()); }
    /** Tiles placed so far. */
    public int placedTiles() { return placed; }
    /** Tiles to place; 0 until the pack is read. */
    public int totalTiles() { return prepared == null ? 0 : prepared.tiles().size(); }
    /** How long the built lobby has waited for the switch, in nanoseconds; 0 outside {@link Stage#READY}. */
    public long waitingNanos() { return stage == Stage.READY ? System.nanoTime() - readySince : 0; }

    /**
     * Advances one step; returns the outcome exactly once, then stays empty. {@code switchAllowed} is consulted once
     * the lobby is built: only then are the copy and {@code current.json} written, and the caller switches as soon as
     * the outcome arrives. A commit that has started finishes whatever the round does meanwhile.
     */
    public Optional<Outcome> tick(boolean switchAllowed) {
        if (stage == Stage.DONE) return Optional.empty();
        try {
            // Never time out a running commit: it may still write current.json after the dimension is gone. Waiting
            // for the switch has its own, longer limit.
            if (stage.compareTo(Stage.READY) < 0 && System.nanoTime() - started > TIMEOUT_NANOS)
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
                    readySince = System.nanoTime();
                    stage = Stage.READY;
                }
                case READY -> {
                    if (!switchAllowed) {
                        if (waitingNanos() > SWITCH_WAIT_NANOS)
                            throw new IllegalStateException("等待本轮回到准备阶段超过 " + TimeUnit.NANOSECONDS.toMinutes(SWITCH_WAIT_NANOS) + " 分钟，安装已取消");
                        return Optional.empty();
                    }
                    // Region files first: current.json must never name a dimension whose blocks are not on disk yet.
                    handle.asLevel().save(null, true, false);
                    var installation = new LobbyInstallation(seq, key.identifier().toString(),
                            LobbyInstallation.Pack.of(prepared.manifest(), ""), effective, LobbyInstallation.State.COMPLETE);
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
                    return Optional.of(new Outcome.Installed(installation, handle, chunks, warnings, prepared.report(), fallbacks));
                }
                case DONE -> throw new IllegalStateException();
            }
            return Optional.empty();
        } catch (RuntimeException error) {
            return Optional.of(fail(error));
        }
    }

    /**
     * Design 9 on the placed world, with the plan recorded in {@code pack.json}: the markers were stripped on install.
     * Required functions the pack lacks get stand-ins (10.4); the plan with them is checked again, and it is what gets
     * recorded and switched to.
     */
    private void verify() {
        var problems = check(prepared.plan());
        var missing = LobbyPlanValidator.missingWithFallback(problems);
        refuse(problems.stream().filter(p -> !(p.kind() == ProblemKind.MISSING && missing.contains(p.function()))).toList());
        fallbacks = FallbackFacilities.place(handle.asLevel(), prepared.plan(), missing);
        effective = prepared.plan().withAdditions(fallbacks.triggers(), fallbacks.models());
        if (!missing.isEmpty()) {
            problems = check(effective);
            refuse(problems);
            LOGGER.info("大厅包 {} 缺少 {}，已放置临时设施", source.name(), missing.stream().map(LobbyFunction::id).toList());
        }
        warnings = problems.stream().filter(p -> p.severity() == Severity.WARNING).toList();
        if (!warnings.isEmpty()) LOGGER.warn("大厅包 {} 验收警告：{}", source.name(), warnings.stream().map(Problem::message).toList());
    }
    private List<Problem> check(LobbyPlan plan) {
        var problems = new ArrayList<Problem>(LobbyPlanValidator.validate(plan, maxSize));
        problems.addAll(PlanWorldChecks.check(handle.asLevel(), plan));
        return problems;
    }
    private static void refuse(List<Problem> problems) {
        var errors = problems.stream().filter(p -> p.severity() == Severity.ERROR).map(Problem::message).toList();
        if (!errors.isEmpty()) throw new IllegalStateException("大厅包验收未通过：" + String.join("；", errors));
    }

    /** Whether {@link #cancel} can still keep the new lobby from being recorded: not once the commit is writing. */
    public boolean cancellable() { return stage != Stage.COMMITTING && stage != Stage.DONE; }

    /** Abandons the install: drops its tickets and deletes the new dimension. Only while {@link #cancellable}. */
    public void cancel() {
        if (stage == Stage.DONE) return;
        stage = Stage.DONE;
        if (chunks != null) chunks.close();
        if (handle != null) handle.delete();
    }

    /**
     * Server stop: drops the tickets only. The dimension stays registered for vanilla's save and close; if the commit
     * got as far as {@code current.json} the next startup reopens it, otherwise its cleanup deletes the directory.
     */
    public void release() {
        if (stage == Stage.DONE) return;
        stage = Stage.DONE;
        if (chunks != null) chunks.close();
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
