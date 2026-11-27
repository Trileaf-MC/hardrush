package dev.hardrush.lobby.workshop;

import dev.hardrush.core.lobby.plan.Box;
import dev.hardrush.core.lobby.plan.BuildManifest;
import dev.hardrush.core.lobby.plan.Cell;
import dev.hardrush.core.lobby.plan.LobbyFunction;
import dev.hardrush.core.lobby.plan.Point;
import dev.hardrush.lobby.pack.ChunkWarmup;
import dev.hardrush.lobby.pack.LobbyInstall;
import dev.hardrush.lobby.pack.LobbyStorage;
import dev.hardrush.lobby.pack.PackException;
import dev.hardrush.lobby.pack.PackLimits;
import dev.hardrush.lobby.pack.PackPlacer;
import dev.hardrush.lobby.pack.PackTiles;
import dev.hardrush.lobby.pack.PreparedPack;
import dev.hardrush.lobby.pack.SanitizeMode;
import dev.hardrush.lobby.pack.TileKey;
import dev.hardrush.lobby.pack.TileSanitizer;
import dev.hardrush.lobby.pack.imports.BuildManifestJson;
import dev.hardrush.lobby.pack.imports.BuildManifestPlacer;
import dev.hardrush.lobby.pack.imports.ImportIssue;
import dev.hardrush.lobby.pack.imports.ImportLimits;
import dev.hardrush.lobby.pack.imports.ImportedStructure;
import dev.hardrush.lobby.pack.imports.PaletteCheck;
import dev.hardrush.lobby.pack.imports.StructureImports;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Fills a draft's dimension, one step per {@link #tick} (design 11.1, 19): on an IO thread, clear any leftover region
 * files (new drafts only) and load the content; then open the dimension, load the content's chunks, place one step per
 * tick and wait for the entity sections. The same job pastes a building file into a draft that already exists. The
 * caller owns the dimension; {@link #cancel} only drops this job's chunk tickets.
 */
final class DraftImport {
    /** What goes into a draft. */
    interface Content {
        /** Chunks under this box are loaded before placing. */
        Box bounds();
        int steps();
        /** Places step {@code index}; chunks are loaded. A {@link PackException} rejects the content (see {@link Rejected}). */
        void place(ServerLevel level, int index) throws PackException;
        /** Where builders should arrive, if the content knows. */
        default Point entry() { return null; }
        /** The build range's new centre (x, z), if the content is not around the origin. */
        default Cell center() { return null; }
        /** What the operator should know about the conversion, other than {@link #issues}. */
        default List<String> remarks() { return List.of(); }
        /**
         * What the import found wrong without failing, for the headless build to sort by kind; complete once the last
         * step ran. Never classify by {@link #notes}' text.
         */
        default List<ImportIssue> issues() { return List.of(); }
        /** The completion notice's lines: {@link #remarks}, then each issue's message. */
        default List<String> notes() {
            var notes = new ArrayList<>(remarks());
            for (var issue : issues()) notes.add(issue.message());
            return notes;
        }
    }

    /**
     * The import failed because the content is wrong (a {@link PackException}: a damaged or oversized building file, a
     * mistake in its manifest), not because the import itself broke (timeouts, a dimension that would not open). The
     * headless build reports the former in the pack's report and treats anything else as a tooling failure.
     */
    static final class Rejected extends IllegalStateException {
        Rejected(PackException cause) { super(cause.getMessage(), cause); }
    }

    /** Produces the content on the IO thread. */
    @FunctionalInterface
    interface Loader { Content load() throws IOException, PackException; }

    /** A pack's tiles, sanitized for drafts: marker armor stands stay so builders keep editing them. */
    static Loader pack(LobbyInstall.Source source, int maxSize) {
        var limits = PackLimits.forServer(maxSize);
        return () -> {
            var pack = PreparedPack.read(source, limits, SanitizeMode.DRAFT);
            var central = pack.plan().points(LobbyFunction.LANDING_CENTRAL);
            var bounds = pack.manifest().bounds();
            // Packs keep absolute coordinates, so the range follows the lobby rather than the origin.
            return tiles(new ArrayList<>(pack.tiles().entrySet()), bounds, central.isEmpty() ? null : central.getFirst(), horizontalCenter(bounds), List.of(), List.of());
        };
    }

    /**
     * A building file (design 19.2): converted, upgraded from its own data version and sanitized like a pack.
     * {@code origin} picks where the building's minimum corner goes, knowing its size. A building manifest beside the
     * file (design “建筑清单与大厅包构建” 1) adds its steps after the tiles; without one nothing changes.
     */
    static Loader structure(Path file, int maxSize, Function<ImportedStructure, Cell> origin) {
        return () -> {
            // The manifest is small: a mistake in it fails before the building is read.
            var manifest = BuildManifestJson.readBesides(file).orElse(null);
            var mistake = "建筑清单 " + BuildManifestJson.besides(file).getFileName() + " 有误：";
            var structure = StructureImports.read(file, new ImportLimits(maxSize, PackTiles.currentDataVersion()));
            if (manifest != null) {
                var outside = manifest.horizontalProblem(structure.sizeX(), structure.sizeZ());
                if (outside.isPresent()) throw new PackException(mistake + outside.get());
            }
            var at = origin.apply(structure);
            var placed = structure.placedAt(at.x(), at.y(), at.z());
            var tiles = new ArrayList<Map.Entry<TileKey, CompoundTag>>();
            for (var entry : placed.entrySet()) tiles.add(Map.entry(entry.getKey(), PackTiles.upgrade(entry.getValue(), structure.dataVersion())));
            // After the DataFixer, before anything is placed: what vanilla would quietly turn into air.
            var unknown = PaletteCheck.unknownStates(tiles.stream().map(Map.Entry::getValue).toList());
            for (var tile : tiles) TileSanitizer.sanitize(tile.getValue(), SanitizeMode.DRAFT);
            TileSanitizer.checkTotals(tiles.stream().map(Map.Entry::getValue).toList());
            var bounds = new Box(at, at.offset(structure.sizeX() - 1, structure.sizeY() - 1, structure.sizeZ() - 1));
            var blocks = tiles(tiles, bounds, null, null, structure.notes(), unknown);
            return manifest == null ? blocks : manifested(blocks, tiles, manifest, at, mistake);
        };
    }

    /**
     * {@code blocks} followed by the manifest's steps (design 1.6): one shape update per tile when asked, over the cells
     * that tile placed, then one step that spawns the markers and writes the signs. The first step checks the manifest
     * against the world's height, so nothing is placed when that fails. Entry is the first central landing's cell.
     */
    private static Content manifested(Content blocks, List<Map.Entry<TileKey, CompoundTag>> placed, BuildManifest manifest, Cell placedMin, String mistake) {
        int tiles = blocks.steps(), shapes = manifest.updateShapes() ? placed.size() : 0;
        var central = manifest.markers().stream().filter(m -> m.spec().function() == LobbyFunction.LANDING_CENTRAL).findFirst().orElse(null);
        return new Content() {
            int removed;
            BuildManifestPlacer.Applied applied;

            public Box bounds() { return blocks.bounds(); }
            public int steps() { return tiles + shapes + 1; }
            public void place(ServerLevel level, int index) throws PackException {
                if (index == 0) {
                    var high = manifest.heightProblem(placedMin, level.getMinY(), level.getMaxY());
                    if (high.isPresent()) throw new PackException(mistake + high.get());
                }
                if (index < tiles) blocks.place(level, index);
                else if (index < tiles + shapes) {
                    var tile = placed.get(index - tiles);
                    removed += BuildManifestPlacer.updateShapes(level, tile.getKey(), tile.getValue(), blocks.bounds());
                } else applied = BuildManifestPlacer.apply(level, manifest, placedMin);
            }
            /** Read once placing finished; by then the height check has passed, so the cell is in the world. */
            public Point entry() {
                if (central == null) return null;
                var c = manifest.toWorld(central.at(), placedMin);
                return new Point(c.x() + .5, c.y(), c.z() + .5, central.yaw());
            }
            public Cell center() { return blocks.center(); }
            /** Complete once the last step ran, which is when the import reads them. */
            public List<String> remarks() {
                var remarks = new ArrayList<>(blocks.remarks());
                if (removed > 0) remarks.add("形状更新移除了 " + removed + " 个失去支撑的方块");
                if (applied != null) remarks.add("已按清单放置 " + applied.markers() + " 个标记、写了 " + applied.signs() + " 块告示牌");
                return remarks;
            }
            public List<ImportIssue> issues() {
                var issues = new ArrayList<>(blocks.issues());
                if (applied != null) issues.addAll(applied.issues());
                return issues;
            }
        };
    }

    /** A new structure-file draft: centred horizontally on the origin, bottom face at Y 64 (design 19.2). */
    static Cell centredOnOrigin(ImportedStructure s) { return new Cell(-s.sizeX() / 2, 64, -s.sizeZ() / 2); }

    /** Room left around imported content on each side, so its edges are not flush with the world border. */
    static final int MARGIN = 16;

    /** A diameter that holds {@code content} with {@link #MARGIN}, never below {@code current} nor above {@code maxSize}. */
    static int diameterFor(int current, Box content, int maxSize) {
        long span = Math.max(content.sizeX(), content.sizeZ()) + 2L * MARGIN;
        return (int) Math.min(maxSize, Math.max(current, span));
    }

    /** The column a build range centred on it covers {@code bounds} from, for {@link Draft#range}'s rounding. */
    static Cell horizontalCenter(Box bounds) {
        return new Cell((int) Math.floorDiv((long) bounds.min().x() + bounds.max().x() + 1, 2), bounds.min().y(),
                (int) Math.floorDiv((long) bounds.min().z() + bounds.max().z() + 1, 2));
    }

    private static Content tiles(List<Map.Entry<TileKey, CompoundTag>> tiles, Box bounds, Point entry, Cell center, List<String> remarks, List<ImportIssue> issues) {
        return new Content() {
            public Box bounds() { return bounds; }
            public int steps() { return tiles.size(); }
            public void place(ServerLevel level, int index) {
                var tile = tiles.get(index);
                PackPlacer.place(level, tile.getKey(), tile.getValue(), bounds);
            }
            public Point entry() { return entry; }
            public Cell center() { return center; }
            public List<String> remarks() { return remarks; }
            public List<ImportIssue> issues() { return issues; }
        };
    }

    /** A 5×5 stone pad at {@code (0, 64, 0)}, to stand on in an empty void. */
    static Loader blank() {
        var bounds = new Box(new Cell(-2, 64, -2), new Cell(2, 64, 2));
        return () -> new Content() {
            public Box bounds() { return bounds; }
            public int steps() { return 1; }
            public void place(ServerLevel level, int index) {
                for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
                    level.setBlock(new BlockPos(x, 64, z), Blocks.STONE.defaultBlockState(), PackPlacer.FLAGS);
            }
        };
    }

    /**
     * A save's overworld (design 19.1): its region files are copied into the draft dimension's directory before the
     * dimension first opens, so vanilla reads and upgrades them itself. Nothing is placed; loading the chunk at the
     * spawn only proves the copy is readable.
     */
    static Loader save(Path save, Path dimension) {
        return () -> {
            var copied = SaveImport.copy(save, dimension);
            var spawn = copied.spawn();
            var chunk = new Box(new Cell(spawn.x(), spawn.y(), spawn.z()), new Cell(spawn.x(), spawn.y(), spawn.z()));
            return new Content() {
                public Box bounds() { return chunk; }
                public int steps() { return 0; }
                public void place(ServerLevel level, int index) { throw new IllegalStateException("saves place nothing"); }
                public Point entry() { return new Point(spawn.x() + .5, spawn.y(), spawn.z() + .5, 0); }
                public Cell center() { return spawn; }
                public List<String> remarks() { return copied.notes(); }
            };
        };
    }

    private enum Stage { READING, LOADING, PLACING, SETTLING, DONE }

    private final Supplier<ServerLevel> open;
    static final long READ_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(30);
    private long started = System.nanoTime();
    private final CompletableFuture<Content> reading;
    private Stage stage = Stage.READING;
    private Content content;
    private ServerLevel level;
    private ChunkWarmup chunks;
    private int placed;

    /**
     * {@code folder} is the draft dimension's directory, deleted first for a new draft, or null to keep the dimension
     * (pasting); {@code open} opens the dimension.
     */
    DraftImport(Path folder, Loader loader, Supplier<ServerLevel> open) {
        this.open = open;
        reading = CompletableFuture.supplyAsync(() -> {
            try {
                if (folder != null) LobbyStorage.deleteTree(folder);
                return loader.load();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (PackException e) {
                throw new CompletionException(e);
            }
        });
    }

    /** Advances one step; true once the draft's world is complete. Throws when the import failed: {@link Rejected} when the content is wrong. */
    boolean tick() {
        // Reading may copy gigabytes of a save; the world steps after it get the install's limit.
        long limit = stage == Stage.READING ? READ_TIMEOUT_NANOS : LobbyInstall.TIMEOUT_NANOS;
        if (stage != Stage.DONE && System.nanoTime() - started > limit)
            throw new IllegalStateException("草稿导入超时（" + progress() + "）");
        switch (stage) {
            case READING -> {
                if (!reading.isDone()) return false;
                content = join(reading);
                started = System.nanoTime();
                level = open.get();
                if (level == null) throw new IllegalStateException("草稿维度没有打开");
                chunks = new ChunkWarmup(level, content.bounds());
                stage = Stage.LOADING;
            }
            case LOADING -> { if (chunks.tick()) stage = Stage.PLACING; }
            case PLACING -> {
                if (placed < content.steps()) {
                    try { content.place(level, placed++); }
                    catch (PackException e) { throw new Rejected(e); }
                } else stage = Stage.SETTLING;
            }
            case SETTLING -> {
                if (!chunks.tick()) return false;
                chunks.close();
                level.save(null, true, false);
                stage = Stage.DONE;
                return true;
            }
            case DONE -> { return true; }
        }
        return false;
    }

    /** The loaded content; null until reading finished. */
    Content content() { return content; }

    /** For the menu: what the import is doing. */
    String progress() {
        return switch (stage) {
            case READING -> "读取中";
            case LOADING -> "加载区块";
            case PLACING -> "放置 " + placed + " / " + content.steps();
            case SETTLING -> "收尾";
            case DONE -> "完成";
        };
    }

    void cancel() {
        stage = Stage.DONE;
        if (chunks != null) chunks.close();
    }

    private static <T> T join(CompletableFuture<T> future) {
        try { return future.join(); }
        catch (CompletionException e) {
            var cause = e.getCause();
            while ((cause instanceof CompletionException || cause instanceof UncheckedIOException) && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof PackException rejected) throw new Rejected(rejected);
            throw new IllegalStateException(cause.getMessage() == null ? cause.toString() : cause.getMessage(), cause);
        }
    }
}
