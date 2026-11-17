package dev.hardrush.lobby.workshop;

import dev.hardrush.core.lobby.plan.Box;
import dev.hardrush.core.lobby.plan.PackManifest;
import dev.hardrush.core.lobby.plan.Problem;
import dev.hardrush.core.lobby.plan.Severity;
import dev.hardrush.lobby.pack.ChunkWarmup;
import dev.hardrush.lobby.pack.MarkerScan;
import dev.hardrush.lobby.pack.PackCapture;
import dev.hardrush.lobby.pack.PackFile;
import dev.hardrush.lobby.pack.PackTiles;
import dev.hardrush.lobby.pack.SanitizeMode;
import dev.hardrush.lobby.pack.SanitizeReport;
import dev.hardrush.lobby.pack.TileKey;
import dev.hardrush.lobby.pack.TileSanitizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.BossEvent;

/**
 * Publishing a draft (design 11.5), spread over ticks: load every chunk of the build range with its entities, scan
 * its blocks a few milliseconds per tick, resolve the markers into a plan and check it, capture one tile per tick, list
 * what installing would strip, wait for the publisher to confirm the authors, then write
 * {@code hardrush/lobby/packs/<id>-v<version>.hrlobby} on the IO thread.
 * Any failure, or the draft being deleted, ends the job and drops its chunk tickets.
 */
final class PublishJob {
    enum Stage { LOADING, SCANNING, CAPTURING, CONFIRM, WRITING, DONE, FAILED }
    static final long LOAD_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(5);
    static final long CONFIRM_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(10);
    /** Server-thread time the block scan may take per tick; a terrain-heavy 256×256 draft then spreads over seconds. */
    static final long SCAN_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
    /** Problems listed at most, then "还有 N 条". */
    static final int SHOWN_PROBLEMS = 10;

    final String draftId;
    final UUID publisher;
    final ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("发布"), BossEvent.BossBarColor.BLUE,
            BossEvent.BossBarOverlay.PROGRESS);
    private final ServerLevel level;
    private final Box range;
    private final OptionalInt bottomY;
    private final int maxSize;
    private final ChunkWarmup chunks;
    private long stageStarted = System.nanoTime();
    private Stage stage = Stage.LOADING;
    private MarkerScan.Scan scanning;
    private MarkerScan.ScanResult scan;
    private List<TileKey> keys;
    private final Map<TileKey, CompoundTag> tiles = new LinkedHashMap<>();
    private int captured;
    private final List<String> warnings = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    /** Participants the publisher struck from the authors. */
    final Set<UUID> excluded = new HashSet<>();
    private CompletableFuture<Path> writing;
    private Path written;

    PublishJob(Draft draft, UUID publisher, ServerLevel level, int maxSize) {
        this.draftId = draft.id();
        this.publisher = publisher;
        this.level = level;
        this.range = draft.range(level.getMinY(), level.getMinY() + level.getHeight() - 1);
        this.bottomY = draft.bottomY() == null ? OptionalInt.empty() : OptionalInt.of(draft.bottomY());
        this.maxSize = maxSize;
        this.chunks = new ChunkWarmup(level, range);
    }

    Stage stage() { return stage; }
    List<String> warnings() { return List.copyOf(warnings); }
    List<String> errors() { return List.copyOf(errors); }
    Path written() { return written; }
    MarkerScan.ScanResult scan() { return scan; }

    /** Advances one step. */
    void tick() {
        switch (stage) {
            case LOADING -> {
                if (System.nanoTime() - stageStarted > LOAD_TIMEOUT_NANOS) { fail("加载草稿区块超时（" + chunks.pending() + "）"); return; }
                if (chunks.tick()) {
                    scanning = MarkerScan.begin(level, range, bottomY, maxSize);
                    next(Stage.SCANNING);
                }
            }
            case SCANNING -> {
                if (!scanning.step(SCAN_BUDGET_NANOS)) return;
                scan = scanning.result();
                var problems = scan.problems();
                for (var p : problems) if (p.severity() == Severity.WARNING) warnings.add(p.message());
                if (scan.hasErrors()) {
                    problems.stream().filter(p -> p.severity() == Severity.ERROR).map(PublishJob::describe).forEach(errors::add);
                    fail(null);
                    return;
                }
                keys = PackCapture.tiles(scan.contentBounds(), bottomY);
                next(Stage.CAPTURING);
            }
            case CAPTURING -> {
                if (captured < keys.size()) {
                    var key = keys.get(captured++);
                    PackCapture.capture(level, key, scan.contentBounds(), bottomY).ifPresent(tile -> tiles.put(key, tile));
                    return;
                }
                chunks.close();
                // Installing sanitizes; publishing does not, but says what installing will take out (design 8).
                var report = SanitizeReport.EMPTY;
                for (var tile : tiles.values()) report = report.plus(TileSanitizer.sanitize(tile.copy(), SanitizeMode.INSTALL));
                if (!report.removedIds().isEmpty())
                    warnings.add("安装时将被移除：" + String.join("、", report.removedIds()));
                if (report.clearedContainers() > 0) warnings.add("安装时将清空 " + report.clearedContainers() + " 个容器的物品");
                if (report.strippedTexts() > 0) warnings.add("安装时将去掉 " + report.strippedTexts() + " 处文字里的点击命令或选择器");
                try { TileSanitizer.checkTotals(tiles.values()); }
                catch (dev.hardrush.lobby.pack.PackException e) { errors.add(e.getMessage()); fail(null); return; }
                next(Stage.CONFIRM);
            }
            case CONFIRM -> { if (System.nanoTime() - stageStarted > CONFIRM_TIMEOUT_NANOS) fail("等待确认超过 10 分钟，发布已取消"); }
            case WRITING -> {
                if (!writing.isDone()) return;
                try {
                    written = writing.join();
                    next(Stage.DONE);
                } catch (RuntimeException e) {
                    var cause = e.getCause() == null ? e : e.getCause();
                    fail("写入大厅包失败：" + cause.getMessage());
                }
            }
            case DONE, FAILED -> { }
        }
    }

    /** Writes the pack with the participants not {@link #excluded} as authors. */
    void confirm(Draft draft, Path packs, Executor io) {
        if (stage != Stage.CONFIRM) throw new IllegalStateException("not waiting for confirmation");
        var authors = draft.participants().stream().filter(p -> !excluded.contains(p.id())).map(Draft.Participant::name)
                .limit(PackManifest.MAX_AUTHORS).toList();
        var name = draft.name().codePointCount(0, draft.name().length()) > PackManifest.MAX_NAME
                ? draft.name().substring(0, draft.name().offsetByCodePoints(0, PackManifest.MAX_NAME)) : draft.name();
        var plan = scan.plan();
        var bounds = scan.contentBounds();
        var snapshot = new LinkedHashMap<>(tiles);
        int firstVersion = draft.publishedVersion() + 1;
        var created = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        next(Stage.WRITING);
        writing = CompletableFuture.supplyAsync(() -> {
            try {
                // A pack of this id may be in the library from an earlier draft with the same id; never overwrite it.
                for (int version = firstVersion; ; version++) {
                    var manifest = new PackManifest(PackManifest.FORMAT, draft.id(), name, authors, version, created, modVersion(),
                            PackTiles.currentDataVersion(), bounds);
                    var file = packs.resolve(manifest.fileName());
                    if (Files.exists(file)) continue;
                    PackFile.write(file, manifest, plan, snapshot);
                    return file;
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, io);
    }

    void cancel() {
        chunks.close();
        if (stage != Stage.DONE) stage = Stage.FAILED;
    }

    /** For the bar and the menu. */
    String progress() {
        return switch (stage) {
            case LOADING -> "加载区块 · " + chunks.pending();
            case SCANNING -> "检查标记 " + scanning.scanned() + " / " + scanning.total();
            case CAPTURING -> "打包 " + captured + " / " + keys.size();
            case CONFIRM -> "等待确认作者";
            case WRITING -> "写入文件";
            case DONE -> "完成";
            case FAILED -> "失败";
        };
    }

    float fraction() {
        return switch (stage) {
            case LOADING -> .05f;
            case SCANNING -> .05f + .1f * scanning.scanned() / Math.max(1, scanning.total());
            case CAPTURING -> .15f + .75f * captured / Math.max(1, keys.size());
            case CONFIRM, WRITING -> .95f;
            case DONE, FAILED -> 1f;
        };
    }

    /** At most {@link #SHOWN_PROBLEMS} lines, then how many more. */
    static List<String> shown(List<String> lines) {
        if (lines.size() <= SHOWN_PROBLEMS) return lines;
        var out = new ArrayList<>(lines.subList(0, SHOWN_PROBLEMS));
        out.add("还有 " + (lines.size() - SHOWN_PROBLEMS) + " 条");
        return out;
    }

    private static String describe(Problem p) {
        return p.message() + (p.where() == null || p.message().contains(p.where().x() + " " + p.where().y() + " " + p.where().z())
                ? "" : "（" + p.where().x() + " " + p.where().y() + " " + p.where().z() + "）");
    }

    private void fail(String reason) {
        if (reason != null) errors.add(reason);
        chunks.close();
        next(Stage.FAILED);
    }

    private void next(Stage stage) {
        this.stage = stage;
        stageStarted = System.nanoTime();
    }

    static String modVersion() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("hardrush")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }
}
