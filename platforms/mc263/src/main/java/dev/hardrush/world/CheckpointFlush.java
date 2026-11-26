package dev.hardrush.world;

import dev.hardrush.world.mixin.ChunkMapAccessor;
import dev.hardrush.world.mixin.ServerChunkCacheInvoker;
import net.minecraft.server.level.ServerLevel;

/**
 * A complete save point only needs every native write to have finished, not reached the platter: its own copy
 * is synced off the server thread before it is published. While a checkpoint save runs on the server thread,
 * vanilla's flush therefore waits for chunk, POI and entity writes without forcing every open region file.
 */
public final class CheckpointFlush {
    private static boolean active;
    private CheckpointFlush() {}

    public static boolean active() { return active; }

    static void save(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Checkpoint saves require the server thread");
        active = true;
        try { level.save(null, true, false); }
        finally { active = false; }
    }

    /**
     * Chunks a flushing save would stop the server thread for right now, counted up to {@code limit + 1}. Vanilla's
     * {@code ServerChunkCache.save} first promotes pending tickets, then {@code ChunkMap.saveAllChunks(true)}
     * {@code managedBlock}s on every holder accessible since its last save until its generation/promotion futures
     * finish ({@code ChunkHolder.isReadyForSaving}). The same promotion runs here so a teleport earlier this tick
     * is already visible; freshly arrived players' view distance can otherwise hold the save for seconds.
     */
    static int waiting(ServerLevel level, int limit) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Checkpoint probes require the server thread");
        ((ServerChunkCacheInvoker) level.getChunkSource()).hardrush$runDistanceManagerUpdates();
        int waiting = 0;
        for (var holder : ((ChunkMapAccessor) level.getChunkSource().chunkMap).hardrush$visibleChunks().values())
            if (holder.wasAccessibleSinceLastSave() && !holder.isReadyForSaving() && ++waiting > limit) break;
        return waiting;
    }

    /** Loaded chunks with changes not yet handed to storage: what the flushing save still has to serialize. */
    static int unsaved(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Checkpoint probes require the server thread");
        int unsaved = 0;
        for (var holder : ((ChunkMapAccessor) level.getChunkSource().chunkMap).hardrush$visibleChunks().values()) {
            var chunk = holder.getLatestChunk();
            if (chunk != null && chunk.isUnsaved()) unsaved++;
        }
        return unsaved;
    }
}
