package dev.hardrush.lobby.pack;

import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * Makes a stored text component inert. Since 1.21.5 components are stored as NBT through
 * {@code ComponentSerialization.CODEC}: a {@link StringTag} is literal text (not JSON), a {@link ListTag} is a
 * component followed by siblings, and a {@link CompoundTag} is a full component. Older JSON strings are turned into
 * NBT by the DataFixer, which runs before us, so a string is never parsed here.
 * <p>
 * Removes click and hover events and insertion text (signs run click commands with operator permission), and
 * replaces selector, score and nbt components, which read server state when resolved, with empty text.
 */
final class TextSanitizer {
    private static final Set<String> EVENTS = Set.of("click_event", "hover_event", "insertion", "clickEvent", "hoverEvent");
    private static final Set<String> RESOLVED = Set.of("selector", "score", "nbt");

    private TextSanitizer() {}

    /** A safe copy of {@code component}; anything that is not a component becomes empty text. */
    static Tag sanitize(Tag component) {
        return switch (component) {
            case StringTag s -> s;
            case ListTag list -> {
                if (list.isEmpty()) yield empty();
                var out = new ListTag();
                for (var element : list) out.add(sanitize(element));
                yield out;
            }
            case CompoundTag compound -> compound(compound);
            case null, default -> empty();
        };
    }

    private static Tag compound(CompoundTag in) {
        var type = in.getString("type").orElse("");
        if (RESOLVED.contains(type)) return empty();
        for (var key : RESOLVED) if (in.contains(key)) return empty();
        var out = in.copy();
        EVENTS.forEach(out::remove);
        if (out.get("extra") instanceof ListTag extra) {
            var clean = new ListTag();
            for (var e : extra) clean.add(sanitize(e));
            out.put("extra", clean);
        } else {
            out.remove("extra");
        }
        if (out.get("with") instanceof ListTag with) {
            // Translation arguments may also be plain numbers or booleans (stored as numeric tags).
            var clean = new ListTag();
            for (var e : with) clean.add(e instanceof NumericTag ? e : sanitize(e));
            out.put("with", clean);
        } else {
            out.remove("with");
        }
        return out;
    }

    static StringTag empty() { return StringTag.valueOf(""); }
}
