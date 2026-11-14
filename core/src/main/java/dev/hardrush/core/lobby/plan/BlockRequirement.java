package dev.hardrush.core.lobby.plan;

import java.util.Map;

/** The block type, and the state properties that must match, a block binding requires. */
public record BlockRequirement(String id, Map<String, String> properties) {
    public BlockRequirement { properties = Map.copyOf(properties); }
    public boolean matches(String blockId, Map<String, String> state) {
        if (!id.equals(blockId)) return false;
        for (var e : properties.entrySet()) if (!e.getValue().equals(state.get(e.getKey()))) return false;
        return true;
    }
}
