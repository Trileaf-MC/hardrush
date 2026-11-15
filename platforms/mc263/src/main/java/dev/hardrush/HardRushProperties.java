package dev.hardrush;

/** Production system properties, read at the point of use. */
public final class HardRushProperties {
    private HardRushProperties() {}

    public static final String COMPETITION_ENABLED = "hardrush.competition.enabled";
    public static final String ROUND_INITIAL_MODE = "hardrush.round.initialMode";
    public static final String STORAGE_FULL_CHECKPOINT_INTERVAL_SECONDS = "hardrush.storage.fullCheckpointIntervalSeconds";
    public static final String STORAGE_MINIMUM_FREE_BYTES = "hardrush.storage.minimumFreeBytes";
    /** showDistance, hideDistance, darken and darkenDistance; read once per server start. */
    public static final String HUNT_BOSS_BAR_PREFIX = "hardrush.hunt.bossBar.";

    public static boolean competitionEnabled() { return Boolean.getBoolean(COMPETITION_ENABLED); }
    public static String initialMode() { return System.getProperty(ROUND_INITIAL_MODE, "hardcore"); }
    /** Vanilla's own autosave period; each point still costs one native save on the server thread. */
    public static final long DEFAULT_FULL_CHECKPOINT_INTERVAL_SECONDS = 300L;
    public static long fullCheckpointIntervalSeconds() { return Long.getLong(STORAGE_FULL_CHECKPOINT_INTERVAL_SECONDS, DEFAULT_FULL_CHECKPOINT_INTERVAL_SECONDS); }
    public static String minimumFreeBytes() { return System.getProperty(STORAGE_MINIMUM_FREE_BYTES); }

    /** Horizontal size limit of a lobby pack, in blocks per axis. */
    public static final String LOBBY_MAX_SIZE = "hardrush.lobby.maxSize";
    public static final int DEFAULT_LOBBY_MAX_SIZE = 256, MIN_LOBBY_MAX_SIZE = 64, MAX_LOBBY_MAX_SIZE = 512;
    /** Clamped to 64–512, so a typo cannot lift the limit that keeps hostile packs small. */
    public static int lobbyMaxSize() {
        return Math.clamp(Integer.getInteger(LOBBY_MAX_SIZE, DEFAULT_LOBBY_MAX_SIZE), MIN_LOBBY_MAX_SIZE, MAX_LOBBY_MAX_SIZE);
    }
}
