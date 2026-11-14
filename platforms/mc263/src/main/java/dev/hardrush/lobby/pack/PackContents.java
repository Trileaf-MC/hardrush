package dev.hardrush.lobby.pack;

import dev.hardrush.core.lobby.plan.LobbyPlan;
import dev.hardrush.core.lobby.plan.PackManifest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

/**
 * A pack as read from disk: neither upgraded nor sanitized. Tiles are structure template NBT with block positions
 * relative to the tile origin, iterated in {@link TileKey#ORDER}.
 */
public record PackContents(PackManifest manifest, LobbyPlan plan, Map<TileKey, CompoundTag> tiles) {
    public PackContents {
        var sorted = new LinkedHashMap<TileKey, CompoundTag>();
        for (var k : tiles.keySet().stream().sorted(TileKey.ORDER).toList()) sorted.put(k, tiles.get(k));
        tiles = Collections.unmodifiableMap(sorted);
    }
}
