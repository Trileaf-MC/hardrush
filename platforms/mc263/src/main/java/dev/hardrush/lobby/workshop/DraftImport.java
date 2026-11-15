package dev.hardrush.lobby.workshop;

import dev.hardrush.core.lobby.plan.Box;
import dev.hardrush.core.lobby.plan.Cell;
import dev.hardrush.lobby.pack.ChunkWarmup;
import dev.hardrush.lobby.pack.LobbyInstall;
import dev.hardrush.lobby.pack.LobbyStorage;
import dev.hardrush.lobby.pack.PackException;
import dev.hardrush.lobby.pack.PackLimits;
import dev.hardrush.lobby.pack.PackPlacer;
import dev.hardrush.lobby.pack.PreparedPack;
import dev.hardrush.lobby.pack.SanitizeMode;
import dev.hardrush.lobby.pack.TileKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Fills a new draft's dimension, one step per {@link #tick} (design 11.1): on an IO thread, clear any leftover region
 * files and load the content; then open the dimension, load the content's chunks, place one step per tick and wait for
 * the entity sections. The caller owns the dimension; {@link #cancel} only drops this job's chunk tickets.
 */
final class DraftImport {
    /** What goes into a new draft. M3b adds pack-library files, saves and structure files. */
    interface Content {
        /** Chunks under this box are loaded before placing. */
        Box bounds();
        int steps();
        /** Places step {@code index}; chunks are loaded. */
        void place(ServerLevel level, int index);
    }

    /** Produces the content on the IO thread. */
    @FunctionalInterface
    interface Loader { Content load() throws IOException, PackException; }

    /** A pack's tiles, sanitized for drafts: marker armor stands stay so builders keep editing them. */
    static Loader pack(LobbyInstall.Source source, int maxSize) {
        var limits = PackLimits.forServer(maxSize);
        return () -> {
            var pack = PreparedPack.read(source, limits, SanitizeMode.DRAFT);
            List<Map.Entry<TileKey, CompoundTag>> tiles = new ArrayList<>(pack.tiles().entrySet());
            var bounds = pack.manifest().bounds();
            return new Content() {
                public Box bounds() { return bounds; }
                public int steps() { return tiles.size(); }
                public void place(ServerLevel level, int index) {
                    var tile = tiles.get(index);
                    PackPlacer.place(level, tile.getKey(), tile.getValue(), bounds);
                }
            };
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

    private enum Stage { READING, LOADING, PLACING, SETTLING, DONE }

    private final Supplier<ServerLevel> open;
    private final long started = System.nanoTime();
    private final CompletableFuture<Content> reading;
    private Stage stage = Stage.READING;
    private Content content;
    private ServerLevel level;
    private ChunkWarmup chunks;
    private int placed;

    /** {@code folder} is the draft dimension's directory, deleted first; {@code open} opens the dimension. */
    DraftImport(Path folder, Loader loader, Supplier<ServerLevel> open) {
        this.open = open;
        reading = CompletableFuture.supplyAsync(() -> {
            try {
                LobbyStorage.deleteTree(folder);
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
        if (stage != Stage.DONE && System.nanoTime() - started > LobbyInstall.TIMEOUT_NANOS)
            throw new IllegalStateException("草稿导入超时（" + progress() + "）");
        switch (stage) {
            case READING -> {
                if (!reading.isDone()) return false;
                content = join(reading);
                level = open.get();
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
