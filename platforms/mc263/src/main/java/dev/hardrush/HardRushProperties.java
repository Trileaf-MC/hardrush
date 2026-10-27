package dev.hardrush;

/** Production system properties, read at the point of use. */
public final class HardRushProperties {
    private HardRushProperties() {}

    public static final String COMPETITION_ENABLED = "hardrush.competition.enabled";
    public static final String ROUND_INITIAL_MODE = "hardrush.round.initialMode";
    public static final String STORAGE_FULL_CHECKPOINT_INTERVAL_SECONDS = "hardrush.storage.fullCheckpointIntervalSeconds";
    public static final String STORAGE_MINIMUM_FREE_BYTES = "hardrush.storage.minimumFreeBytes";

    public static boolean competitionEnabled() { return Boolean.getBoolean(COMPETITION_ENABLED); }
    public static String initialMode() { return System.getProperty(ROUND_INITIAL_MODE, "hardcore"); }
    public static long fullCheckpointIntervalSeconds() { return Long.getLong(STORAGE_FULL_CHECKPOINT_INTERVAL_SECONDS, 60L); }
    public static String minimumFreeBytes() { return System.getProperty(STORAGE_MINIMUM_FREE_BYTES); }
}
