package dev.hardrush.lobby.pack;

import dev.hardrush.core.lobby.plan.Box;
import dev.hardrush.core.lobby.plan.Cell;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A 48³ tile of a lobby pack, indexed by {@code floorDiv(world coordinate, 48)}. 48 is the vanilla structure
 * block's per-axis limit, so any tile also loads in a structure block.
 */
public record TileKey(int x, int y, int z) {
    public static final int SIZE = 48;
    /** Entry order inside a pack: bottom layer first, then rows, so equal content gives equal bytes. */
    public static final Comparator<TileKey> ORDER =
            Comparator.comparingInt(TileKey::y).thenComparingInt(TileKey::z).thenComparingInt(TileKey::x);
    private static final Pattern ENTRY = Pattern.compile("tiles/(-?\\d{1,10})_(-?\\d{1,10})_(-?\\d{1,10})\\.nbt");

    public static TileKey of(int worldX, int worldY, int worldZ) {
        return new TileKey(Math.floorDiv(worldX, SIZE), Math.floorDiv(worldY, SIZE), Math.floorDiv(worldZ, SIZE));
    }

    /**
     * Whether this tile touches {@code bounds}. Compares tile indices rather than {@link #box()}, whose origin
     * overflows for the absurd keys an entry name can spell.
     */
    public boolean touches(Box bounds) {
        var from = of(bounds.min().x(), bounds.min().y(), bounds.min().z());
        var to = of(bounds.max().x(), bounds.max().y(), bounds.max().z());
        return x >= from.x && x <= to.x && y >= from.y && y <= to.y && z >= from.z && z <= to.z;
    }

    public int originX() { return x * SIZE; }
    public int originY() { return y * SIZE; }
    public int originZ() { return z * SIZE; }
    /** The world blocks this tile covers. */
    public Box box() {
        return new Box(new Cell(originX(), originY(), originZ()),
                new Cell(originX() + SIZE - 1, originY() + SIZE - 1, originZ() + SIZE - 1));
    }

    public String entryName() { return "tiles/" + x + "_" + y + "_" + z + ".nbt"; }

    /** Only the canonical spelling is accepted, so two entry names can never denote the same tile. */
    public static Optional<TileKey> parseEntryName(String name) {
        var m = ENTRY.matcher(name);
        if (!m.matches()) return Optional.empty();
        try {
            var key = new TileKey(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            return key.entryName().equals(name) ? Optional.of(key) : Optional.empty();
        } catch (NumberFormatException overflow) {
            return Optional.empty();
        }
    }
}
