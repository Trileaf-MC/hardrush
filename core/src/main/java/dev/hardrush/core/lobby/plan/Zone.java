package dev.hardrush.core.lobby.plan;

import java.util.*;

/** A region players stand in. Floors come from resolved flood markers, volumes from box markers. */
public sealed interface Zone permits Zone.Floor, Zone.Volume {
    /** Half a player's hitbox width; staying players count while any foot corner is still inside. */
    double HALF_WIDTH = 0.3;
    /** How far above a floor block's bottom a player still counts as standing on it: slabs, carpets, jumps. */
    double FLOOR_REACH = 2.5;

    /** Entering ({@code staying == false}) tests the centre; staying also accepts any hitbox corner. */
    default boolean contains(double x, double y, double z, boolean staying) {
        if (containsPoint(x, y, z)) return true;
        if (!staying) return false;
        return containsPoint(x - HALF_WIDTH, y, z - HALF_WIDTH) || containsPoint(x + HALF_WIDTH, y, z - HALF_WIDTH)
                || containsPoint(x - HALF_WIDTH, y, z + HALF_WIDTH) || containsPoint(x + HALF_WIDTH, y, z + HALF_WIDTH);
    }
    boolean containsPoint(double x, double y, double z);
    /** The geometric centre at standing height; yaw is zero. */
    Point center();
    /** The enclosing block range. */
    Box extent();
    boolean overlaps(Zone other);

    /** A box whose bottom face is centred on {@code marker}: horizontally ±x/±z, vertically up {@code y}. */
    static Volume box(Cell marker, int x, int y, int z) {
        return new Volume(new Box(marker.offset(-x, 0, -z), marker.offset(x, y, z)));
    }

    /**
     * Floor blocks (the blocks stood on). A class rather than a record so it can keep a per-column index:
     * {@link #contains} runs several times per player per tick. Equality is by {@link #floor()} only.
     */
    final class Floor implements Zone {
        private final Set<Cell> floor;
        /** Column key → sorted floor Ys in that column; several layers allow multi-storey buildings. */
        private final Map<Long, int[]> columns;

        public Floor(Set<Cell> floor) {
            this.floor = Set.copyOf(floor);
            var byColumn = new HashMap<Long, List<Integer>>();
            for (var c : this.floor) byColumn.computeIfAbsent(column(c.x(), c.z()), k -> new ArrayList<>()).add(c.y());
            var index = new HashMap<Long, int[]>();
            byColumn.forEach((k, ys) -> index.put(k, ys.stream().mapToInt(Integer::intValue).sorted().toArray()));
            this.columns = index;
        }
        public Set<Cell> floor() { return floor; }
        public boolean isEmpty() { return floor.isEmpty(); }

        @Override public boolean containsPoint(double x, double y, double z) {
            var ys = columns.get(column((int) Math.floor(x), (int) Math.floor(z)));
            if (ys == null) return false;
            for (int cy : ys) if (cy <= y && y <= cy + FLOOR_REACH) return true;
            return false;
        }
        @Override public Point center() {
            if (floor.isEmpty()) throw new IllegalStateException("empty floor has no centre");
            double x = 0, y = 0, z = 0;
            for (var c : floor) { x += c.x() + .5; y += c.y() + 1; z += c.z() + .5; }
            return new Point(x / floor.size(), y / floor.size(), z / floor.size(), 0);
        }
        /** The floor cells plus the two blocks of headroom above them. */
        @Override public Box extent() {
            if (floor.isEmpty()) throw new IllegalStateException("empty floor has no extent");
            Box box = null;
            for (var c : floor) box = box == null ? new Box(c, c) : box.union(c);
            return new Box(box.min(), box.max().offset(0, 2, 0));
        }
        @Override public boolean overlaps(Zone other) {
            return switch (other) {
                case Floor f -> {
                    for (var e : columns.entrySet()) {
                        var theirs = f.columns.get(e.getKey());
                        if (theirs == null) continue;
                        // Reaches [a, a + FLOOR_REACH] and [b, b + FLOOR_REACH] meet iff |a - b| <= FLOOR_REACH: 2 for whole blocks.
                        for (int a : e.getValue()) for (int b : theirs) if (Math.abs(a - b) <= FLOOR_REACH) yield true;
                    }
                    yield false;
                }
                case Volume v -> v.overlaps(this);
            };
        }
        @Override public boolean equals(Object o) { return o instanceof Floor f && floor.equals(f.floor); }
        @Override public int hashCode() { return floor.hashCode(); }
        @Override public String toString() { return "Floor[" + floor.size() + " cells]"; }

        private static long column(int x, int z) { return (long) x << 32 | (z & 0xFFFFFFFFL); }
    }

    record Volume(Box box) implements Zone {
        @Override public boolean containsPoint(double x, double y, double z) { return box.contains(x, y, z); }
        @Override public Point center() {
            return new Point(((double) box.min().x() + box.max().x() + 1) / 2, box.min().y(), ((double) box.min().z() + box.max().z() + 1) / 2, 0);
        }
        @Override public Box extent() { return box; }
        @Override public boolean overlaps(Zone other) {
            return switch (other) {
                case Volume v -> box.intersects(v.box);
                // Same reach as containsPoint on both sides: the floor's feet range [cy, cy + FLOOR_REACH] against the
                // box's [min.y, max.y + 1) in the column, so no standing height counts for both zones.
                case Floor f -> {
                    for (var c : f.floor())
                        if (c.x() >= box.min().x() && c.x() <= box.max().x() && c.z() >= box.min().z() && c.z() <= box.max().z()
                                && box.min().y() <= c.y() + FLOOR_REACH && c.y() < box.max().y() + 1) yield true;
                    yield false;
                }
            };
        }
    }
}
