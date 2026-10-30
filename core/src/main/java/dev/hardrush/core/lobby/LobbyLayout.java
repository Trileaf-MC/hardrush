package dev.hardrush.core.lobby;

import java.util.List;

/** Layout contract, relative to the persistent lobby spawn. No terrain construction or admission. */
public final class LobbyLayout {
    public static final int VERSION = 1;
    public static final int MIN_X = -32, MAX_X = 31, MIN_Z = -24, MAX_Z = 23;
    public static final int DISPLAY_CAPACITY = 20;
    public enum Landing { CENTRAL, MONSTER, WAIT_OUTSIDE, RESTORE_READY }
    public enum Facility { RULES, HISTORY, MIDROUND, ZOMBIE, SKELETON, CREEPER, PREVIOUS, NEXT, ALTAR, MERCHANT, VAULT, OMINOUS_VAULT }
    public static final List<Facility> MONSTER_FACILITIES = List.of(Facility.ZOMBIE, Facility.SKELETON, Facility.CREEPER);
    /** The witch is a packet model like the role models; the two vaults are owned blocks added to existing camps. */
    public static final List<Facility> SHOP_FACILITIES = List.of(Facility.MERCHANT, Facility.VAULT, Facility.OMINOUS_VAULT);
    public record Point(double x, double z, float yaw) {}
    public record Circle(double x, double z, double radius) {
        public boolean contains(double px, double pz) { return Math.hypot(px - x, pz - z) <= radius; }
    }
    public static final Circle ASSEMBLY = new Circle(0, -16, 7);
    public static Point landing(Landing purpose) {
        return switch (purpose) {
            case CENTRAL -> new Point(0, 0, 180);
            case MONSTER -> new Point(15, 2, -90);
            case WAIT_OUTSIDE -> new Point(-3, -6, 180);
            case RESTORE_READY -> new Point(0, -16, 0);
        };
    }
    public static Point facility(Facility facility) {
        return switch (facility) {
            case RULES -> new Point(-16, -2, 90);
            case HISTORY -> new Point(-22, 3, 90);
            case MIDROUND -> new Point(14, -16, 180);
            case ZOMBIE -> new Point(17, -4, 0);
            case SKELETON -> new Point(21, -4, 0);
            case CREEPER -> new Point(25, -4, 0);
            case PREVIOUS -> new Point(14, 8, 0);
            case NEXT -> new Point(29, 8, 0);
            case ALTAR -> new Point(27, 20, 0);
            case MERCHANT -> new Point(29, -4, 0);
            case VAULT -> new Point(27, 1, 90);
            case OMINOUS_VAULT -> new Point(29, 1, 90);
        };
    }
    public static List<Point> displaySlots() {
        // Face north toward the passage and monster role models (negative Z).
        return java.util.stream.IntStream.range(0, DISPLAY_CAPACITY)
                .mapToObj(i -> new Point(15 + i % 5 * 3, 8 + i / 5 * 3, 180)).toList();
    }
    private LobbyLayout() {}
}
