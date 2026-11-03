package dev.hardrush.world;

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
}
