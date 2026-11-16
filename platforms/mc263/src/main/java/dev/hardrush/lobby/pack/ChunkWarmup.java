package dev.hardrush.lobby.pack;

import dev.hardrush.HardRushProperties;
import dev.hardrush.core.lobby.plan.Box;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * Loads every chunk under a box, a few new tickets per tick, and reports ready only once each chunk is loaded and its
 * entity section too. Entity sections load asynchronously after the chunk: reading early silently misses markers and
 * decorations. Timeouts are the caller's; {@link #close} drops every ticket.
 */
public final class ChunkWarmup implements AutoCloseable {
    public static final int LOAD_BUDGET = 4;
    /**
     * The most chunks one warmup may hold: the chunks a box as wide as the largest configurable lobby can touch per
     * axis (it may straddle a chunk edge at both ends). A last line behind the size checks upstream, so a box that
     * slipped past them fails here instead of queueing millions of chunks on the server thread.
     */
    public static final int MAX_CHUNKS_PER_AXIS = (HardRushProperties.MAX_LOBBY_MAX_SIZE + 30) / 16;
    private static final TicketType TICKET = new TicketType(TicketType.NO_TIMEOUT,
            TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);

    private final ServerLevel level;
    private final ArrayDeque<ChunkPos> queued = new ArrayDeque<>();
    private final Map<ChunkPos, CompletableFuture<?>> loading = new LinkedHashMap<>();
    private boolean closed;

    public ChunkWarmup(ServerLevel level, Box box) {
        long spanX = (long) (box.max().x() >> 4) - (box.min().x() >> 4) + 1, spanZ = (long) (box.max().z() >> 4) - (box.min().z() >> 4) + 1;
        if (spanX > MAX_CHUNKS_PER_AXIS || spanZ > MAX_CHUNKS_PER_AXIS)
            throw new IllegalArgumentException("区域横跨 " + spanX + "×" + spanZ + " 个区块，超过上限 " + MAX_CHUNKS_PER_AXIS + "×" + MAX_CHUNKS_PER_AXIS);
        this.level = level;
        for (int x = box.min().x() >> 4; x <= box.max().x() >> 4; x++)
            for (int z = box.min().z() >> 4; z <= box.max().z() >> 4; z++) queued.add(new ChunkPos(x, z));
    }

    /** Issues up to {@link #LOAD_BUDGET} new tickets; true once everything is loaded. Throws if a chunk failed to load. */
    public boolean tick() {
        if (closed) throw new IllegalStateException("ChunkWarmup is closed");
        for (int issued = 0; issued < LOAD_BUDGET && !queued.isEmpty(); issued++) {
            var chunk = queued.remove();
            loading.put(chunk, level.getChunkSource().addTicketAndLoadWithRadius(TICKET, chunk, 0));
        }
        if (!queued.isEmpty()) return false;
        for (var entry : loading.entrySet()) {
            var future = entry.getValue();
            if (!future.isDone()) return false;
            if (future.join() instanceof ChunkResult<?> result && !result.isSuccess())
                throw new IllegalStateException("区块 " + entry.getKey() + " 加载失败：" + result.getError());
            var chunk = entry.getKey();
            if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null) return false;
            if (!level.areEntitiesLoaded(chunk.pack())) return false;
        }
        return true;
    }

    public int chunks() { return queued.size() + loading.size(); }

    /** What is still missing, for timeout messages. */
    public String pending() {
        int futures = 0, chunks = 0, entities = 0;
        for (var entry : loading.entrySet()) {
            var c = entry.getKey();
            if (!entry.getValue().isDone()) futures++;
            else if (level.getChunkSource().getChunkNow(c.x(), c.z()) == null) chunks++;
            else if (!level.areEntitiesLoaded(c.pack())) entities++;
        }
        return "未发票据 " + queued.size() + "，加载中 " + futures + "，未就绪区块 " + chunks + "，实体区段未加载 " + entities;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (var chunk : loading.keySet()) level.getChunkSource().removeTicketWithRadius(TICKET, chunk, 0);
        loading.clear();
        queued.clear();
    }
}
