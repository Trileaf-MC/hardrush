package dev.hardrush.lobby.pack.imports;

import dev.hardrush.lobby.pack.PackException;
import java.util.ArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/** Vanilla structure template ({@code .nbt}). */
final class NbtStructureReader {
    private NbtStructureReader() {}

    static ImportedStructure read(CompoundTag root, ImportLimits limits) throws PackException {
        int dataVersion = root.getIntOr("DataVersion", 0);
        StructureImports.checkDataVersion(dataVersion, limits);
        var size = root.getListOrEmpty("size");
        if (size.size() != 3) throw new PackException("结构文件缺少 size");
        int sx = size.getIntOr(0, 0), sy = size.getIntOr(1, 0), sz = size.getIntOr(2, 0);
        StructureImports.checkSize(limits, sx, sy, sz);

        ListTag palette = root.getList("palette").orElse(null);
        if (palette == null) {
            // Several palettes (shipwreck variants) share block indices; the first is the base one.
            for (var candidate : root.getListOrEmpty("palettes")) {
                if (candidate instanceof ListTag list) { palette = list; break; }
            }
        }
        if (palette == null) throw new PackException("结构文件缺少调色板（palette）");
        var states = new StateEntry[palette.size()];
        for (int i = 0; i < states.length; i++) states[i] = StateEntry.fromTag(palette.getCompoundOrEmpty(i));

        var assembler = new TileAssembler(dataVersion, ImportLimits.MAX_BLOCKS);
        for (var tag : root.getListOrEmpty("blocks")) {
            if (!(tag instanceof CompoundTag block)) throw new PackException("结构文件的方块列表格式不对");
            var pos = block.getListOrEmpty("pos");
            int x = pos.getIntOr(0, -1), y = pos.getIntOr(1, -1), z = pos.getIntOr(2, -1);
            if (pos.size() != 3 || x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz)
                throw new PackException("结构文件里有方块坐标超出尺寸：" + x + "," + y + "," + z);
            int state = block.getIntOr("state", -1);
            if (state < 0 || state >= states.length) throw new PackException("结构文件的方块引用了不存在的调色板项：" + state);
            if (states[state].skipped()) continue;
            assembler.put(x, y, z, states[state]);
            block.getCompound("nbt").ifPresent(nbt -> assembler.setBlockEntity(x, y, z, nbt.copy()));
        }
        var notes = new ArrayList<String>();
        int entities = root.getListOrEmpty("entities").size();
        if (entities > 0) notes.add(StructureImports.ignoredEntities(entities));
        return new ImportedStructure(dataVersion, sx, sy, sz, assembler.build(), notes);
    }
}
