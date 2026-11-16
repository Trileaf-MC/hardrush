package dev.hardrush.core.lobby;

import dev.hardrush.core.lobby.plan.*;
import java.util.*;

/**
 * The built-in camp's plan, built in code, relative to an anchor (the block a player's feet are in at the central
 * landing; floor below). The default lobby pack ({@code default.hrlobby}, anchor 0 65 0) was exported from exactly
 * this layout, except that its altar pad also holds the gold cell under the beacon. Kept as a realistic fixture for
 * the core rules, which have no JSON reader for the pack itself.
 */
public final class CampPlanFixture {
    static final String LECTERN = "minecraft:lectern", VAULT = "minecraft:vault";
    static final int MIN_X = -32, MAX_X = 31, MIN_Z = -24, MAX_Z = 23;
    /** The 4 × 5 display slots, three blocks apart, facing north; anchor-relative x and z. */
    static final List<int[]> DISPLAY_SLOTS = java.util.stream.IntStream.range(0, 20)
            .mapToObj(i -> new int[]{15 + i % 5 * 3, 8 + i / 5 * 3}).toList();

    public static LobbyPlan build(int ax, int ay, int az) {
        var at = new Anchor(ax, ay, az);
        var bounds = new Box(new Cell(ax + MIN_X, ay - 1, az + MIN_Z), new Cell(ax + MAX_X, ay + 4, az + MAX_Z));

        var triggers = new ArrayList<Trigger>();
        triggers.add(at.trigger(LobbyFunction.RULES, -16, -2, 90, LECTERN));
        triggers.add(at.trigger(LobbyFunction.HISTORY, -22, 3, 90, LECTERN));
        triggers.add(at.trigger(LobbyFunction.PAGE_PREV, 14, 8, 0, LECTERN));
        triggers.add(at.trigger(LobbyFunction.PAGE_NEXT, 29, 8, 0, LECTERN));
        triggers.add(at.trigger(LobbyFunction.VAULT, 27, 1, 90, VAULT));
        triggers.add(at.trigger(LobbyFunction.OMINOUS_VAULT, 29, 1, 90, VAULT));

        var models = List.of(
                new Model(LobbyFunction.ROLE_ZOMBIE, at.point(17, -4, 0)),
                new Model(LobbyFunction.ROLE_SKELETON, at.point(21, -4, 0)),
                new Model(LobbyFunction.ROLE_CREEPER, at.point(25, -4, 0)),
                new Model(LobbyFunction.MERCHANT, at.point(29, -4, 0)));

        var points = new EnumMap<LobbyFunction, List<Point>>(LobbyFunction.class);
        points.put(LobbyFunction.LANDING_CENTRAL, List.of(at.point(0, 0, 180)));
        points.put(LobbyFunction.LANDING_MONSTER, List.of(at.point(15, 2, -90)));
        // Foot of the altar's south steps, facing the beacon; never on the pad itself.
        points.put(LobbyFunction.LANDING_ALTAR, List.of(at.point(21, -11, 180)));
        points.put(LobbyFunction.DISPLAY_SLOT, DISPLAY_SLOTS.stream().map(s -> at.point(s[0], s[1], 180)).toList());

        var zones = new EnumMap<LobbyFunction, List<Zone>>(LobbyFunction.class);
        // The wool circle of radius 7 around (0, -16): the floor cells whose centre lies within it.
        var assembly = new HashSet<Cell>();
        for (int x = MIN_X; x <= MAX_X; x++) for (int z = MIN_Z; z <= MAX_Z; z++)
            if (Math.hypot(x, z + 16) <= 7) assembly.add(at.cell(x, -1, z));
        zones.put(LobbyFunction.ASSEMBLY, List.of(new Zone.Floor(assembly)));
        zones.put(LobbyFunction.COURTYARD, List.of(new Zone.Volume(new Box(at.cell(11, 0, -9), at.cell(MAX_X, 3, MAX_Z)))));
        // The altar at (21, -15): a 3 × 3 gold core one step up; the eight cells around the beacon are the pad.
        var pad = new HashSet<Cell>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (dx != 0 || dz != 0) pad.add(at.cell(21 + dx, 0, -15 + dz));
        zones.put(LobbyFunction.ALTAR_PAD, List.of(new Zone.Floor(pad)));

        // The beam glass above the beacon (which sits one block above the gold core).
        var lights = List.of(at.cell(21, 2, -15));
        var status = new TextSlot(LobbyFunction.STATUS, at.cell(4, 0, 1), at.point(4, 1, 0));
        return new LobbyPlan(bounds, triggers, models, points, zones, lights, List.of(status));
    }

    private record Anchor(int x, int y, int z) {
        Cell cell(int dx, int dy, int dz) { return new Cell(x + dx, y + dy, z + dz); }
        Point point(int dx, int dz, float yaw) { return new Point(x + dx + .5, y, z + dz + .5, yaw); }
        Trigger trigger(LobbyFunction function, int dx, int dz, float yaw, String blockId) {
            return new Trigger(function, cell(dx, 0, dz), blockId, point(dx, dz, yaw));
        }
    }
    private CampPlanFixture() {}
}
