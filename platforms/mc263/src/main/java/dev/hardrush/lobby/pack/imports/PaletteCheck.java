package dev.hardrush.lobby.pack.imports;

import dev.hardrush.lobby.pack.PackTiles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;

/**
 * Finds the block states of upgraded tiles that this game cannot read (design “建筑清单与大厅包构建” 1.5). Vanilla's
 * {@code NbtUtils.readBlockState} turns an unknown block id into air and silently skips an unknown property or value, so
 * a building from a newer game or a typo would lose blocks without a word. Runs after {@link PackTiles#upgrade} (the
 * DataFixer has renamed what it knows), on any thread: it only reads {@link BuiltInRegistries#BLOCK} and the blocks'
 * state definitions.
 */
public final class PaletteCheck {
    /** Kinds listed one by one; the rest are counted in one more issue. */
    static final int MAX_LISTED = 20;

    private PaletteCheck() {}

    /** One {@link ImportIssue.Kind#UNKNOWN_BLOCK} issue per kind of problem, most cells first, at most {@link #MAX_LISTED} + 1. */
    public static List<ImportIssue> unknownStates(Collection<CompoundTag> tiles) {
        var cells = new LinkedHashMap<Problem, Long>();
        for (var tile : tiles) {
            tile.getList("palette").ifPresent(palette -> count(tile, palette, cells));
            tile.getList("palettes").ifPresent(all -> { for (var p : all) if (p instanceof ListTag palette) count(tile, palette, cells); });
        }
        var sorted = new ArrayList<>(cells.entrySet());
        sorted.sort(Map.Entry.<Problem, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(e -> e.getKey().text()));
        var out = new ArrayList<ImportIssue>();
        for (int i = 0; i < Math.min(MAX_LISTED, sorted.size()); i++) {
            var e = sorted.get(i);
            out.add(ImportIssue.of(ImportIssue.Kind.UNKNOWN_BLOCK, e.getKey().text() + "（" + e.getValue() + " 格）"
                    + (e.getKey().air() ? "，导入后是空气" : "，导入时忽略这个属性")));
        }
        if (sorted.size() > MAX_LISTED)
            out.add(ImportIssue.of(ImportIssue.Kind.UNKNOWN_BLOCK, "还有 " + (sorted.size() - MAX_LISTED) + " 种无法识别的方块状态没有列出"));
        return out;
    }

    /** Adds each problem of {@code palette}'s entries, weighted by how many of {@code tile}'s blocks use the entry. */
    private static void count(CompoundTag tile, ListTag palette, Map<Problem, Long> cells) {
        var uses = new long[palette.size()];
        for (var tag : tile.getListOrEmpty("blocks")) {
            if (!(tag instanceof CompoundTag block)) continue;
            int state = block.getIntOr("state", -1);
            if (state >= 0 && state < uses.length) uses[state]++;
        }
        for (int i = 0; i < uses.length; i++) {
            if (uses[i] == 0) continue;
            for (var problem : problems(palette.getCompoundOrEmpty(i))) cells.merge(problem, uses[i], Long::sum);
        }
    }

    /** One kind of unreadable state: {@code air} when the whole block is lost, not just a property. */
    record Problem(String text, boolean air) {}

    /** What this game cannot read in one palette entry. */
    static List<Problem> problems(CompoundTag entry) {
        var name = PackTiles.blockName(entry);
        var id = Identifier.tryParse(name);
        // containsKey, not getValue: the block registry is defaulted and answers air for unknown ids.
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return List.of(new Problem("未知方块 " + name, true));
        var definition = BuiltInRegistries.BLOCK.getValue(id).getStateDefinition();
        var out = new ArrayList<Problem>();
        var properties = PackTiles.blockProperties(entry);
        for (var key : properties.keySet()) {
            var property = definition.getProperty(key);
            if (property == null) {
                out.add(new Problem(name + " 没有属性 " + key, false));
                continue;
            }
            var value = properties.getString(key);
            if (value.isEmpty() || property.getValue(value.get()).isEmpty())
                out.add(new Problem(name + " 的属性 " + key + " 不能是 " + value.orElseGet(() -> String.valueOf(properties.get(key))), false));
        }
        return out;
    }
}
