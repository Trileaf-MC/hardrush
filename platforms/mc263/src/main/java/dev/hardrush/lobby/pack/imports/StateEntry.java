package dev.hardrush.lobby.pack.imports;

import dev.hardrush.core.lobby.plan.BlockStateString;
import dev.hardrush.lobby.pack.PackException;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.nbt.CompoundTag;

/** A block state as a template palette entry ({@code {Name, Properties}}), with a canonical key for deduplication. */
final class StateEntry {
    private static final Set<String> SKIPPED = Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void");

    final String key;
    /** Never mutated; copied into every tile palette that uses it. */
    final CompoundTag tag;

    private StateEntry(String key, CompoundTag tag) {
        this.key = key;
        this.tag = tag;
    }

    /** Air and structure voids are not written into tiles, as with captured tiles. */
    boolean skipped() { return SKIPPED.contains(tag.getStringOr("Name", "")); }

    static StateEntry fromTag(CompoundTag palette) {
        var name = palette.getStringOr("Name", "minecraft:air");
        var properties = new TreeMap<String, String>();
        var source = palette.getCompoundOrEmpty("Properties");
        for (var property : source.keySet()) properties.put(property, source.getStringOr(property, ""));
        return of(name, properties);
    }

    static StateEntry of(String name, java.util.Map<String, String> properties) {
        var sorted = new TreeMap<>(properties);
        var key = new StringBuilder(name);
        var tag = new CompoundTag();
        tag.putString("Name", name);
        if (!sorted.isEmpty()) {
            var props = new CompoundTag();
            key.append('[');
            sorted.forEach((k, v) -> {
                props.putString(k, v);
                if (key.charAt(key.length() - 1) != '[') key.append(',');
                key.append(k).append('=').append(v);
            });
            key.append(']');
            tag.put("Properties", props);
        }
        return new StateEntry(key.toString(), tag);
    }

    /** Parses the Sponge spelling {@code minecraft:oak_stairs[facing=north]}. */
    static StateEntry parse(String text) throws PackException {
        try {
            var parsed = BlockStateString.parse(text);
            return of(parsed.name(), parsed.properties());
        } catch (IllegalArgumentException e) {
            throw new PackException("建筑文件里的方块状态无法识别：" + e.getMessage(), e);
        }
    }
}
