package dev.hardrush.lobby.pack.imports;

/** What a building file may be: the configured horizontal size limit and this server's data version. */
public record ImportLimits(int maxHorizontalSize, int serverDataVersion) {
    /** World height; a building taller than this cannot be placed. */
    public static final int MAX_HEIGHT = 384;
    /** Non-air blocks in one file. Well below 4096 full tiles; stops a crafted file from exhausting the heap. */
    public static final long MAX_BLOCKS = 16_000_000L;
    public static final long MAX_FILE_BYTES = 64L << 20;
    public static final long MAX_NBT_BYTES = 256L << 20;
}
