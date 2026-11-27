package dev.hardrush.lobby.pack;

import dev.hardrush.core.lobby.plan.Box;
import dev.hardrush.core.lobby.plan.LobbyPlan;
import dev.hardrush.core.lobby.plan.PackManifest;
import dev.hardrush.core.lobby.plan.Problem;
import dev.hardrush.core.lobby.plan.ProblemKind;
import dev.hardrush.core.lobby.plan.Severity;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * A world range turned into a pack's content (design 11.5 steps 1–4), spread over ticks and knowing nothing of drafts
 * or players: load every chunk of the range with its entities, scan its blocks a few milliseconds per tick, resolve
 * the markers into a plan and check it, capture one tile per tick, then list what installing would strip. Once
 * {@link Stage#CAPTURED}, {@link #captured} writes the file. Workshop publishing and the headless pack build
 * (“建筑清单与大厅包构建” 2.3) both run this; timeouts, authors and where the file goes are theirs.
 */
public final class PackBuild implements AutoCloseable {
    public enum Stage { LOADING, SCANNING, CAPTURING, CAPTURED, FAILED }
    /** Server-thread time the block scan may take per tick; a terrain-heavy 256×256 range then spreads over seconds. */
    public static final long SCAN_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(5);

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
    private int capturedTiles;
    private final List<Problem> problems = new ArrayList<>();
    private String limit;

    /** {@code bottomY}: blocks below it are left out (imported saves, design 19.1). Starts loading the range's chunks. */
    public PackBuild(ServerLevel level, Box range, OptionalInt bottomY, int maxSize) {
        this.level = level;
        this.range = range;
        this.bottomY = bottomY;
        this.maxSize = maxSize;
        this.chunks = new ChunkWarmup(level, range);
    }

    public Stage stage() { return stage; }
    /** Since the current stage began, for the caller's timeouts. */
    public long stageNanos() { return System.nanoTime() - stageStarted; }
    /** What the chunk loading still waits for, for timeout messages. */
    public String pendingChunks() { return chunks.pending(); }
    /** Null until the scan finished. */
    public MarkerScan.ScanResult scan() { return scan; }

    /**
     * The scan's acceptance problems once it finished, then one {@link ProblemKind#SANITIZED} warning per kind of
     * thing installing will strip once the tiles are captured.
     */
    public List<Problem> problems() { return List.copyOf(problems); }

    /** Why a scan without errors still failed: the captured tiles exceed the pack's totals. Null otherwise. */
    public String limit() { return limit; }

    /** Advances one step: load, a slice of the scan, or one tile. Throws when a chunk fails to load. */
    public void tick() {
        switch (stage) {
            case LOADING -> {
                if (chunks.tick()) {
                    scanning = MarkerScan.begin(level, range, bottomY, maxSize);
                    next(Stage.SCANNING);
                }
            }
            case SCANNING -> {
                if (!scanning.step(SCAN_BUDGET_NANOS)) return;
                scan = scanning.result();
                problems.addAll(scan.problems());
                if (scan.hasErrors()) { fail(); return; }
                keys = PackCapture.tiles(scan.contentBounds(), bottomY);
                next(Stage.CAPTURING);
            }
            case CAPTURING -> {
                if (capturedTiles < keys.size()) {
                    var key = keys.get(capturedTiles++);
                    PackCapture.capture(level, key, scan.contentBounds(), bottomY).ifPresent(tile -> tiles.put(key, tile));
                    return;
                }
                chunks.close();
                // Installing sanitizes; packing does not, but says what installing will take out (design 8).
                var report = SanitizeReport.EMPTY;
                for (var tile : tiles.values()) report = report.plus(TileSanitizer.sanitize(tile.copy(), SanitizeMode.INSTALL));
                if (!report.removedIds().isEmpty()) sanitized("安装时将被移除：" + String.join("、", report.removedIds()));
                if (report.clearedContainers() > 0) sanitized("安装时将清空 " + report.clearedContainers() + " 个容器的物品");
                if (report.strippedTexts() > 0) sanitized("安装时将去掉 " + report.strippedTexts() + " 处文字里的点击命令或选择器");
                try { TileSanitizer.checkTotals(tiles.values()); }
                catch (PackException e) { limit = e.getMessage(); fail(); return; }
                next(Stage.CAPTURED);
            }
            case CAPTURED, FAILED -> { }
        }
    }

    /** What {@link Stage#CAPTURED} holds, detached from this build so it can be written on another thread. */
    public Captured captured() {
        if (stage != Stage.CAPTURED) throw new IllegalStateException("pack not captured");
        return new Captured(scan.plan(), scan.contentBounds(), new LinkedHashMap<>(tiles));
    }

    /** A pack's content, ready to be written under any header. */
    public record Captured(LobbyPlan plan, Box bounds, Map<TileKey, CompoundTag> tiles) {
        /** The header for this content; {@code created} is an ISO-8601 instant. */
        public PackManifest manifest(String id, String name, List<String> authors, int version, String created) {
            return new PackManifest(PackManifest.FORMAT, id, name, authors, version, created, modVersion(), PackTiles.currentDataVersion(), bounds);
        }

        /** Writes {@code target} atomically, replacing it if present ({@link PackFile#write}); IO thread. */
        public void write(Path target, PackManifest manifest) throws IOException {
            PackFile.write(target, manifest, plan, tiles);
        }
    }

    /** For a progress bar: what this build is doing. */
    public String progress() {
        return switch (stage) {
            case LOADING -> "加载区块 · " + chunks.pending();
            case SCANNING -> "检查标记 " + scanning.scanned() + " / " + scanning.total();
            case CAPTURING -> "打包 " + capturedTiles + " / " + keys.size();
            case CAPTURED -> "打包完成";
            case FAILED -> "失败";
        };
    }

    /** Progress from 0 to 0.9; the caller's own steps after capturing fill the rest. */
    public float fraction() {
        return switch (stage) {
            case LOADING -> .05f;
            case SCANNING -> .05f + .1f * scanning.scanned() / Math.max(1, scanning.total());
            case CAPTURING -> .15f + .75f * capturedTiles / Math.max(1, keys.size());
            case CAPTURED, FAILED -> .9f;
        };
    }

    /** Drops the chunk tickets; a build not yet captured ends as failed. */
    @Override public void close() {
        chunks.close();
        if (stage != Stage.CAPTURED) stage = Stage.FAILED;
    }

    public static String modVersion() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("hardrush")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    /** The errors among {@link #problems}, in their order. */
    public List<Problem> errors() { return problems.stream().filter(p -> p.severity() == Severity.ERROR).toList(); }

    private void sanitized(String message) { problems.add(Problem.warning(ProblemKind.SANITIZED, null, null, message)); }

    private void fail() {
        chunks.close();
        next(Stage.FAILED);
    }

    private void next(Stage stage) {
        this.stage = stage;
        stageStarted = System.nanoTime();
    }
}
