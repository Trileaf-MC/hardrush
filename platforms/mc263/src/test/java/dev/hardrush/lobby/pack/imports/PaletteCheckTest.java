package dev.hardrush.lobby.pack.imports;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class PaletteCheckTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static CompoundTag entry(String id, Map<String, String> properties) {
        var entry = new CompoundTag();
        entry.putString("id", id);
        if (!properties.isEmpty()) {
            var p = new CompoundTag();
            properties.forEach(p::putString);
            entry.put("properties", p);
        }
        return entry;
    }

    /** A tile whose palette entry {@code i} is used by {@code uses[i]} blocks. */
    private static CompoundTag tile(List<CompoundTag> palette, int... uses) {
        var list = new ListTag();
        list.addAll(palette);
        var blocks = new ListTag();
        int n = 0;
        for (int i = 0; i < uses.length; i++)
            for (int k = 0; k < uses[i]; k++, n++) {
                var pos = new ListTag();
                pos.add(IntTag.valueOf(n % 48)); pos.add(IntTag.valueOf(n / 48)); pos.add(IntTag.valueOf(0));
                var block = new CompoundTag();
                block.put("pos", pos);
                block.putInt("state", i);
                blocks.add(block);
            }
        var tile = new CompoundTag();
        tile.put("palette", list);
        tile.put("blocks", blocks);
        return tile;
    }

    @Test void unknownIdsPropertiesAndValuesAreCountedPerKind() {
        var first = tile(List.of(
                entry("minecraft:stone", Map.of()),
                entry("minecraft:foo", Map.of("bar", "1")),
                entry("minecraft:oak_fence", Map.of("east", "true", "bar", "x")),
                entry("minecraft:oak_fence", Map.of("east", "maybe")),
                entry("Not An Id!", Map.of()),
                entry("minecraft:dirt", Map.of())), 5, 3, 2, 1, 1, 0);
        var second = tile(List.of(entry("minecraft:foo", Map.of())), 4);
        var issues = PaletteCheck.unknownStates(List.of(first, second));
        assertTrue(issues.stream().allMatch(i -> i.kind() == ImportIssue.Kind.UNKNOWN_BLOCK && i.world() == null));
        assertEquals(List.of(
                "未知方块 minecraft:foo（7 格），导入后是空气",
                "minecraft:oak_fence 没有属性 bar（2 格），导入时忽略这个属性",
                "minecraft:oak_fence 的属性 east 不能是 maybe（1 格），导入时忽略这个属性",
                "未知方块 Not An Id!（1 格），导入后是空气"), issues.stream().map(ImportIssue::message).toList());
    }

    @Test void readableTilesHaveNoIssues() {
        var tile = tile(List.of(entry("minecraft:oak_fence", Map.of("east", "true", "waterlogged", "false")), entry("minecraft:stone", Map.of())), 2, 3);
        assertEquals(List.of(), PaletteCheck.unknownStates(List.of(tile)));
    }

    @Test void atMostTwentyKindsAreListed() {
        var palette = new ArrayList<CompoundTag>();
        var uses = new int[25];
        for (int i = 0; i < 25; i++) { palette.add(entry("minecraft:nope_" + (char) ('a' + i), Map.of())); uses[i] = 1; }
        var issues = PaletteCheck.unknownStates(List.of(tile(palette, uses)));
        assertEquals(PaletteCheck.MAX_LISTED + 1, issues.size());
        assertEquals("还有 5 种无法识别的方块状态没有列出", issues.getLast().message());
    }
}
