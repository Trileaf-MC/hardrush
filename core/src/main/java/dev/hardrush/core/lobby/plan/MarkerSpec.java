package dev.hardrush.core.lobby.plan;

import java.util.Set;

/**
 * A parsed marker or entity trigger. {@code shape} is set only for zones; {@code materials} (block ids such as
 * {@code minecraft:lime_wool}) only for flood zones; {@code boxX/Y/Z} only for box zones, otherwise zero.
 */
public record MarkerSpec(LobbyFunction function, Binding binding, ZoneShape shape, Set<String> materials,
                         int boxX, int boxY, int boxZ) {
    public MarkerSpec {
        materials = materials == null ? Set.of() : Set.copyOf(materials);
        if (!function.bindings().contains(binding))
            throw new IllegalArgumentException(function.id() + " cannot be bound as " + binding);
        if ((binding == Binding.ZONE) != (shape != null)) throw new IllegalArgumentException("shape is for zones only");
        if (shape != ZoneShape.FLOOD && !materials.isEmpty()) throw new IllegalArgumentException("materials are for flood zones only");
        if (shape != ZoneShape.BOX && (boxX | boxY | boxZ) != 0) throw new IllegalArgumentException("box size is for box zones only");
        if (Math.min(boxX, Math.min(boxY, boxZ)) < 0 || Math.max(boxX, Math.max(boxY, boxZ)) > MarkerTags.MAX_BOX)
            throw new IllegalArgumentException("box size out of range");
    }
    public static MarkerSpec simple(LobbyFunction function, Binding binding) {
        return new MarkerSpec(function, binding, null, Set.of(), 0, 0, 0);
    }
    public static MarkerSpec flood(LobbyFunction function, Set<String> materials) {
        return new MarkerSpec(function, Binding.ZONE, ZoneShape.FLOOD, materials, 0, 0, 0);
    }
    public static MarkerSpec box(LobbyFunction function, int x, int y, int z) {
        return new MarkerSpec(function, Binding.ZONE, ZoneShape.BOX, Set.of(), x, y, z);
    }
}
