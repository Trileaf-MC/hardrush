package dev.hardrush.lobby.pack.imports;

import dev.hardrush.lobby.pack.PackException;
import java.util.ArrayList;
import net.minecraft.nbt.CompoundTag;

/** Sponge Schematic, versions 2 and 3. */
final class SchemReader {
    private static final int MAX_PALETTE_INDEX = 1 << 20;

    private SchemReader() {}

    static ImportedStructure read(CompoundTag file, ImportLimits limits) throws PackException {
        // v3 nests everything under "Schematic"; v2 keeps it at the root.
        var root = file.getCompound("Schematic").orElse(file);
        int version = root.getIntOr("Version", 0);
        if (version == 1) throw new PackException(".schem 版本 1 已过时，请用 WorldEdit 载入后另存为新版 .schem");
        if (version != 2 && version != 3) throw new PackException("不支持的 .schem 版本：" + version);
        boolean v3 = version == 3;

        int dataVersion = root.getIntOr("DataVersion", 0);
        StructureImports.checkDataVersion(dataVersion, limits);
        int w = root.getShortOr("Width", (short) 0) & 0xFFFF;
        int h = root.getShortOr("Height", (short) 0) & 0xFFFF;
        int l = root.getShortOr("Length", (short) 0) & 0xFFFF;
        StructureImports.checkSize(limits, w, h, l);

        var blocks = v3 ? root.getCompoundOrEmpty("Blocks") : root;
        var palette = palette(blocks.getCompoundOrEmpty("Palette"));
        var data = blocks.getByteArray(v3 ? "Data" : "BlockData").orElseThrow(() -> new PackException(".schem 缺少方块数据"));

        long volume = (long) w * h * l;
        var assembler = new TileAssembler(dataVersion, ImportLimits.MAX_BLOCKS);
        int at = 0;
        long index = 0;
        while (at < data.length) {
            if (index >= volume) throw new PackException(".schem 的方块数据比尺寸多");
            int value = 0, shift = 0, b;
            do {
                if (at >= data.length) throw new PackException(".schem 的方块数据在变长整数中间被截断");
                if (shift > 28) throw new PackException(".schem 的方块数据里有过长的变长整数");
                b = data[at++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            if (value < 0 || value >= palette.length || palette[value] == null)
                throw new PackException(".schem 的方块引用了不存在的调色板编号：" + value);
            var state = palette[value];
            if (!state.skipped()) {
                int x = (int) (index % w), z = (int) (index / w % l), y = (int) (index / ((long) w * l));
                assembler.put(x, y, z, state);
            }
            index++;
        }
        if (index != volume) throw new PackException(".schem 的方块数据比尺寸少：" + index + " / " + volume);

        for (var tag : blocks.getListOrEmpty("BlockEntities")) {
            if (!(tag instanceof CompoundTag entity)) throw new PackException(".schem 的方块实体格式不对");
            var pos = entity.getIntArray("Pos").orElse(null);
            if (pos == null || pos.length != 3) throw new PackException(".schem 的方块实体缺少坐标");
            if (pos[0] < 0 || pos[1] < 0 || pos[2] < 0 || pos[0] >= w || pos[1] >= h || pos[2] >= l)
                throw new PackException(".schem 的方块实体坐标超出尺寸：" + pos[0] + "," + pos[1] + "," + pos[2]);
            var id = entity.getStringOr("Id", "");
            if (id.isEmpty()) continue;
            // v2 flattens the entity's data next to Pos/Id; v3 nests it under Data.
            var nbt = (v3 ? entity.getCompoundOrEmpty("Data") : entity).copy();
            nbt.remove("Pos");
            nbt.remove("Id");
            nbt.remove("x");
            nbt.remove("y");
            nbt.remove("z");
            nbt.putString("id", StructureImports.namespaced(id));
            assembler.setBlockEntity(pos[0], pos[1], pos[2], nbt);
        }

        var notes = new ArrayList<String>();
        int entities = root.getListOrEmpty("Entities").size();
        if (entities > 0) notes.add(StructureImports.ignoredEntities(entities));
        return new ImportedStructure(dataVersion, w, h, l, assembler.build(), notes);
    }

    private static StateEntry[] palette(CompoundTag tag) throws PackException {
        int max = -1;
        for (var key : tag.keySet()) {
            int id = tag.getIntOr(key, -1);
            if (id < 0 || id > MAX_PALETTE_INDEX) throw new PackException(".schem 的调色板编号不合法：" + key + " → " + id);
            max = Math.max(max, id);
        }
        if (max < 0) throw new PackException(".schem 的调色板是空的");
        var palette = new StateEntry[max + 1];
        for (var key : tag.keySet()) palette[tag.getIntOr(key, -1)] = StateEntry.parse(key);
        return palette;
    }
}
