package dev.hardrush.core.lobby.plan;

import java.util.*;

/**
 * The runtime layout of an installed lobby, in absolute world coordinates. Immutable; every query is by
 * {@link LobbyFunction}. {@link LobbyFunction#ALTAR_LIGHT} lives only in {@code altarLights}; vault blocks are
 * {@code triggers} whose {@code blockId} is {@code minecraft:vault}.
 */
public record LobbyPlan(Box bounds, List<Trigger> triggers, List<Model> models,
                        Map<LobbyFunction, List<Point>> points, Map<LobbyFunction, List<Zone>> zones,
                        List<Cell> altarLights, List<TextSlot> texts) {
    private static final Comparator<Point> DISPLAY_ORDER =
            Comparator.comparingDouble(Point::y).thenComparingDouble(Point::z).thenComparingDouble(Point::x);

    public LobbyPlan {
        Objects.requireNonNull(bounds);
        triggers = List.copyOf(triggers); models = List.copyOf(models);
        altarLights = List.copyOf(altarLights); texts = List.copyOf(texts);
        points = byFunction(points, Binding.POINT, "points");
        zones = byFunction(zones, Binding.ZONE, "zones");
        var slots = points.get(LobbyFunction.DISPLAY_SLOT);
        if (slots != null) {
            var sorted = new EnumMap<>(points);
            sorted.put(LobbyFunction.DISPLAY_SLOT, slots.stream().sorted(DISPLAY_ORDER).toList());
            points = Collections.unmodifiableMap(sorted);
        }
        for (var t : triggers) {
            requireBinding(t.function(), "trigger", Binding.CLICK, Binding.BLOCK);
            if (t.function() == LobbyFunction.ALTAR_LIGHT) throw new IllegalArgumentException("altar lights belong in altarLights");
        }
        for (var m : models) requireBinding(m.function(), "model", Binding.MODEL);
        for (var t : texts) requireBinding(t.function(), "text", Binding.TEXT);
    }

    public List<Point> points(LobbyFunction f) { return points.getOrDefault(f, List.of()); }
    public List<Zone> zones(LobbyFunction f) { return zones.getOrDefault(f, List.of()); }
    public List<Trigger> triggers(LobbyFunction f) { return triggers.stream().filter(t -> t.function() == f).toList(); }
    public List<Model> models(LobbyFunction f) { return models.stream().filter(m -> m.function() == f).toList(); }
    public List<TextSlot> texts(LobbyFunction f) { return texts.stream().filter(t -> t.function() == f).toList(); }

    public boolean inZone(LobbyFunction f, double x, double y, double z, boolean staying) {
        return zoneAt(f, x, y, z, staying).isPresent();
    }
    public Optional<Zone> zoneAt(LobbyFunction f, double x, double y, double z, boolean staying) {
        for (var zone : zones(f)) if (zone.contains(x, y, z, staying)) return Optional.of(zone);
        return Optional.empty();
    }
    /** All bindings of {@code f}, whatever their form. */
    public int count(LobbyFunction f) {
        return triggers(f).size() + models(f).size() + points(f).size() + zones(f).size() + texts(f).size()
                + (f == LobbyFunction.ALTAR_LIGHT ? altarLights.size() : 0);
    }
    public int displayCapacity() { return points(LobbyFunction.DISPLAY_SLOT).size(); }
    /** This plan plus stand-in facilities. */
    public LobbyPlan withAdditions(List<Trigger> moreTriggers, List<Model> moreModels) {
        var t = new ArrayList<>(triggers); t.addAll(moreTriggers);
        var m = new ArrayList<>(models); m.addAll(moreModels);
        return new LobbyPlan(bounds, t, m, points, zones, altarLights, texts);
    }

    private static <T> Map<LobbyFunction, List<T>> byFunction(Map<LobbyFunction, List<T>> source, Binding binding, String what) {
        var copy = new EnumMap<LobbyFunction, List<T>>(LobbyFunction.class);
        source.forEach((f, list) -> {
            requireBinding(f, what, binding);
            if (!list.isEmpty()) copy.put(f, List.copyOf(list));
        });
        return Collections.unmodifiableMap(copy);
    }
    private static void requireBinding(LobbyFunction f, String what, Binding... allowed) {
        for (var b : allowed) if (f.bindings().contains(b)) return;
        throw new IllegalArgumentException(f.id() + " cannot appear in " + what);
    }
}
