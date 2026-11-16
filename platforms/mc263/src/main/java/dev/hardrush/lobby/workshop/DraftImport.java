package dev.hardrush.lobby.workshop;

import dev.hardrush.core.lobby.plan.Box;
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
import dev.hardrush.lobby.pack.imports.ImportLimits;
import dev.hardrush.lobby.pack.imports.ImportedStructure;
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
        /** Places step {@code index}; chunks are loaded. */
        void place(ServerLevel level, int index);
        /** Where builders should arrive, if the content knows. */
        default Point entry() { return null; }
        /** The build range's new centre (x, z), if the content is not around the origin. */
        default Cell center() { return null; }
        /** What the operator should know about the conversion. */
        default List<String> notes() { return List.of(); }
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
            return tiles(new ArrayList<>(pack.tiles().entrySet()), bounds, central.isEmpty() ? null : central.getFirst(), horizontalCenter(bounds), List.of());
        };
    }

    /**
     * A building file (design 19.2): converted, upgraded from its own data version and sanitized like a pack.
     * {@code origin} picks where the building's minimum corner goes, knowing its size.
     */
    static Loader structure(Path file, int maxSize, Function<ImportedStructure, Cell> origin) {
        return () -> {
            var structure = StructureImports.read(file, new ImportLimits(maxSize, PackTiles.currentDataVersion()));
            var at = origin.apply(structure);
            var placed = structure.placedAt(at.x(), at.y(), at.z());
            var tiles = new ArrayList<Map.Entry<TileKey, CompoundTag>>();
            for (var entry : placed.entrySet()) {
                var tile = PackTiles.upgrade(entry.getValue(), structure.dataVersion());
                TileSanitizer.sanitize(tile, SanitizeMode.DRAFT);
                tiles.add(Map.entry(entry.getKey(), tile));
            }
            TileSanitizer.checkTotals(tiles.stream().map(Map.Entry::getValue).toList());
            var bounds = new Box(at, at.offset(structure.sizeX() - 1, structure.sizeY() - 1, structure.sizeZ() - 1));
            return tiles(tiles, bounds, null, null, structure.notes());
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

    private static Content tiles(List<Map.Entry<TileKey, CompoundTag>> tiles, Box bounds, Point entry, Cell center, List<String> notes) {
        return new Content() {
            public Box bounds() { return bounds; }
            public int steps() { return tiles.size(); }
            public void place(ServerLevel level, int index) {
                var tile = tiles.get(index);
                PackPlacer.place(level, tile.getKey(), tile.getValue(), bounds);
            }
            public Point entry() { return entry; }
            public Cell center() { return center; }
            public List<String> notes() { return notes; }
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
                public List<String> notes() { return copied.notes(); }
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

    /** Advances one step; true once the draft's world is complete. Throws when the import failed. */
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
                if (placed < content.steps()) content.place(level, placed++);
                else stage = Stage.SETTLING;
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
            throw new IllegalStateException(cause.getMessage() == null ? cause.toString() : cause.getMessage(), cause);
        }
    }
}
