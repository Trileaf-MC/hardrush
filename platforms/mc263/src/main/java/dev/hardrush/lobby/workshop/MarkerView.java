package dev.hardrush.lobby.workshop;

import dev.hardrush.core.lobby.plan.Binding;
import dev.hardrush.core.lobby.plan.Box;
import dev.hardrush.core.lobby.plan.Cell;
import dev.hardrush.core.lobby.plan.MarkerSpec;
import dev.hardrush.core.lobby.plan.MarkerTags;
import dev.hardrush.core.lobby.plan.Zone;
import dev.hardrush.core.lobby.plan.ZoneShape;
import dev.hardrush.lobby.pack.MarkerScan;
import dev.hardrush.lobby.pack.MarkerStands;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

/**
 * Lets builders see what markers bind (design 4.5): every {@link #PERIOD} ticks, dust particles sent to each builder
 * alone, for the markers within {@link #RANGE} blocks. Flood zones light their floor, box zones their edges, block
 * markers the outline of their block; markers the light check flagged ({@link WorkshopChecks}) glow red. At most
 * {@link #BUDGET} particles per builder per round, nearest markers first.
 */
final class MarkerView {
    static final int PERIOD = 10, RANGE = 32, BUDGET = 400;
    /** Flood floors are searched again after this many ticks. */
    static final int FLOOD_REFRESH = 20;
    private static final int ZONE = 0x55FF55, BLOCK = 0x55CCFF, POINT = 0xFFDD55, PROBLEM = 0xFF3333;

    private record Flood(long tick, Cell marker, Set<String> materials, List<Cell> cells) {}
    private final Map<UUID, Flood> floods = new HashMap<>();

    void tick(Workshop workshop, long tick) {
        if (tick % PERIOD != 0) return;
        var seen = new java.util.HashSet<UUID>();
        for (var player : workshop.server().getPlayerList().getPlayers()) {
            var draft = workshop.buildingIn(player).orElse(null);
            if (draft == null || !dev.hardrush.player.PlayerProfiles.building(player)) continue;
            var flagged = workshop.checks().result(draft.id()).map(WorkshopChecks.Result::flagged).orElse(Set.of());
            show(player, draft, flagged, tick, seen);
        }
        floods.keySet().retainAll(seen);
    }

    private void show(ServerPlayer player, Draft draft, Set<Cell> flagged, long tick, Set<UUID> seen) {
        var level = (ServerLevel) player.level();
        var eye = player.getEyePosition();
        var markers = level.getEntitiesOfClass(ArmorStand.class, player.getBoundingBox().inflate(RANGE), MarkerStands::is);
        markers.sort(Comparator.comparingDouble(m -> m.distanceToSqr(eye)));
        int budget = BUDGET;
        var area = draft.range(level.getMinY(), level.getMinY() + level.getHeight() - 1);
        for (var marker : markers) {
            if (budget <= 0) break;
            seen.add(marker.getUUID());
            var cell = new Cell(marker.getBlockX(), marker.getBlockY(), marker.getBlockZ());
            var parsed = MarkerTags.parseMarker(marker.entityTags());
            if (!(parsed instanceof MarkerTags.Ok ok)) {
                budget -= send(player, PROBLEM, List.of(marker.position().add(0, .2, 0)), budget);
                continue;
            }
            var spec = ok.spec();
            var points = new ArrayList<Vec3>();
            int color;
            Cell target = cell;
            switch (spec.binding()) {
                case ZONE -> {
                    color = ZONE;
                    if (spec.shape() == ZoneShape.FLOOD) {
                        for (var c : flood(level, area, marker.getUUID(), cell, spec, tick)) points.add(new Vec3(c.x() + .5, c.y() + 1.05, c.z() + .5));
                    } else edges(Zone.box(cell, spec.boxX(), spec.boxY(), spec.boxZ()).box(), 1, points);
                }
                case CLICK, BLOCK, TEXT -> {
                    color = BLOCK;
                    var pos = MarkerStands.target(level, marker.blockPosition());
                    target = new Cell(pos.getX(), pos.getY(), pos.getZ());
                    edges(new Box(target, target), .25, points);
                }
                default -> {
                    color = POINT;
                    points.add(marker.position().add(0, .1, 0));
                }
            }
            if (flagged.contains(cell) || flagged.contains(target)) color = PROBLEM;
            points.sort(Comparator.comparingDouble(p -> p.distanceToSqr(eye)));
            budget -= send(player, color, points, budget);
        }
    }

    private List<Cell> flood(ServerLevel level, Box area, UUID id, Cell marker, MarkerSpec spec, long tick) {
        var cached = floods.get(id);
        if (cached != null && tick - cached.tick < FLOOD_REFRESH && cached.marker.equals(marker) && cached.materials.equals(spec.materials()))
            return cached.cells;
        var cells = List.copyOf(MarkerScan.previewFlood(level, area, marker, spec.materials()).cells());
        floods.put(id, new Flood(tick, marker, spec.materials(), cells));
        return cells;
    }

    /** Points along the twelve edges of a block box, {@code step} apart. */
    static void edges(Box box, double step, List<Vec3> out) {
        double x0 = box.min().x(), y0 = box.min().y(), z0 = box.min().z();
        double x1 = box.max().x() + 1, y1 = box.max().y() + 1, z1 = box.max().z() + 1;
        for (double x = x0; x <= x1 + 1e-6; x += step)
            for (double y : new double[] {y0, y1}) for (double z : new double[] {z0, z1}) out.add(new Vec3(x, y, z));
        for (double y = y0 + step; y < y1 - 1e-6; y += step)
            for (double x : new double[] {x0, x1}) for (double z : new double[] {z0, z1}) out.add(new Vec3(x, y, z));
        for (double z = z0 + step; z < z1 - 1e-6; z += step)
            for (double x : new double[] {x0, x1}) for (double y : new double[] {y0, y1}) out.add(new Vec3(x, y, z));
    }

    private static int send(ServerPlayer player, int color, List<Vec3> points, int budget) {
        var level = (ServerLevel) player.level();
        var dust = new DustParticleOptions(color, 1f);
        int sent = 0;
        for (var p : points) {
            if (sent >= budget) break;
            if (p.distanceToSqr(player.getEyePosition()) > RANGE * RANGE * 4) continue;
            level.sendParticles(player, dust, true, false, p.x, p.y, p.z, 1, 0, 0, 0, 0);
            sent++;
        }
        return sent;
    }
}
