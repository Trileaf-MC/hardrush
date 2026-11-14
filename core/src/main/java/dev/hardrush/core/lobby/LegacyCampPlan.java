package dev.hardrush.core.lobby;

import dev.hardrush.core.lobby.LobbyLayout.Facility;
import dev.hardrush.core.lobby.plan.*;
import java.util.*;

/**
 * The built-in camp ({@link LobbyLayout} plus the platform's camp blueprint) as a runtime {@link LobbyPlan} in
 * absolute world coordinates. The anchor is the lobby spawn: the block the player's feet are in, floor below.
 * Every point stands at the bottom centre of its block, as a marker armour stand would.
 * <p>Transitional: M2 installs the camp from a lobby pack and deletes this class together with {@link LobbyLayout}.
 */
public final class LegacyCampPlan {
    public static final String LECTERN = "minecraft:lectern", VAULT = "minecraft:vault";

    public static LobbyPlan build(int anchorX, int anchorY, int anchorZ) {
        var at = new Anchor(anchorX, anchorY, anchorZ);
        var bounds = new Box(new Cell(anchorX + LobbyLayout.MIN_X, anchorY - 1, anchorZ + LobbyLayout.MIN_Z),
                new Cell(anchorX + LobbyLayout.MAX_X, anchorY + 4, anchorZ + LobbyLayout.MAX_Z));

        var triggers = new ArrayList<Trigger>();
        for (var entry : Map.of(Facility.RULES, LobbyFunction.RULES, Facility.HISTORY, LobbyFunction.HISTORY,
                Facility.PREVIOUS, LobbyFunction.PAGE_PREV, Facility.NEXT, LobbyFunction.PAGE_NEXT).entrySet())
            triggers.add(at.trigger(entry.getValue(), entry.getKey(), LECTERN));
        triggers.add(at.trigger(LobbyFunction.VAULT, Facility.VAULT, VAULT));
        triggers.add(at.trigger(LobbyFunction.OMINOUS_VAULT, Facility.OMINOUS_VAULT, VAULT));
        triggers.sort(Comparator.comparing(Trigger::function));

        var models = List.of(
                new Model(LobbyFunction.ROLE_ZOMBIE, at.point(LobbyLayout.facility(Facility.ZOMBIE))),
                new Model(LobbyFunction.ROLE_SKELETON, at.point(LobbyLayout.facility(Facility.SKELETON))),
                new Model(LobbyFunction.ROLE_CREEPER, at.point(LobbyLayout.facility(Facility.CREEPER))),
                new Model(LobbyFunction.MERCHANT, at.point(LobbyLayout.facility(Facility.MERCHANT))));

        var points = new EnumMap<LobbyFunction, List<Point>>(LobbyFunction.class);
        points.put(LobbyFunction.LANDING_CENTRAL, List.of(at.point(LobbyLayout.CENTRAL_LANDING)));
        points.put(LobbyFunction.LANDING_MONSTER, List.of(at.point(LobbyLayout.MONSTER_LANDING)));
        points.put(LobbyFunction.LANDING_ALTAR, List.of(at.point(LobbyLayout.ALTAR_LANDING)));
        points.put(LobbyFunction.DISPLAY_SLOT, LobbyLayout.displaySlots().stream().map(at::point).toList());

        var zones = new EnumMap<LobbyFunction, List<Zone>>(LobbyFunction.class);
        // The wool circle: the floor cells whose centre lies within the circle.
        var assembly = new HashSet<Cell>();
        var circle = LobbyLayout.ASSEMBLY;
        for (int x = LobbyLayout.MIN_X; x <= LobbyLayout.MAX_X; x++) for (int z = LobbyLayout.MIN_Z; z <= LobbyLayout.MAX_Z; z++)
            if (circle.contains(x, z)) assembly.add(at.cell(x, -1, z));
        zones.put(LobbyFunction.ASSEMBLY, List.of(new Zone.Floor(assembly)));
        var camp = LobbyLayout.MONSTER_CAMP;
        zones.put(LobbyFunction.COURTYARD, List.of(new Zone.Volume(new Box(
                at.cell((int) camp.minX(), 0, (int) camp.minZ()), at.cell((int) camp.maxX(), 3, (int) camp.maxZ())))));
        // The eight gold cells around the beacon; the gold blocks themselves are the floor.
        var altar = LobbyLayout.facility(Facility.ALTAR);
        var pad = new HashSet<Cell>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (LobbyLayout.onAltarPad(dx, dz)) pad.add(at.cell((int) altar.x() + dx, 0, (int) altar.z() + dz));
        zones.put(LobbyFunction.ALTAR_PAD, List.of(new Zone.Floor(pad)));

        // The beam glass above the beacon (which sits one block above the gold core).
        var lights = List.of(at.cell((int) altar.x(), 2, (int) altar.z()));
        var status = new TextSlot(LobbyFunction.STATUS, at.cell(4, 0, 1), at.point(new LobbyLayout.Point(4, 1, 0)));
        return new LobbyPlan(bounds, triggers, models, points, zones, lights, List.of(status));
    }

    private record Anchor(int x, int y, int z) {
        Cell cell(int dx, int dy, int dz) { return new Cell(x + dx, y + dy, z + dz); }
        Point point(LobbyLayout.Point p) { return new Point(x + p.x() + .5, y, z + p.z() + .5, p.yaw()); }
        Trigger trigger(LobbyFunction function, Facility facility, String blockId) {
            var p = LobbyLayout.facility(facility);
            return new Trigger(function, cell((int) p.x(), 0, (int) p.z()), blockId, point(p));
        }
    }
    private LegacyCampPlan() {}
}
