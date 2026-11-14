package dev.hardrush.lobby.pack;

/** What this server accepts: the configured horizontal size limit and its own game data version. */
public record PackLimits(int maxHorizontalSize, int serverDataVersion) {
    /** Limits for the running game; needs {@code SharedConstants} to have detected the version. */
    public static PackLimits forServer(int maxHorizontalSize) {
        return new PackLimits(maxHorizontalSize, PackTiles.currentDataVersion());
    }
}
