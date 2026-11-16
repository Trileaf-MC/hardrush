package dev.hardrush.lobby.pack.imports;

import dev.hardrush.lobby.pack.PackException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;

/** Litematica ({@code .litematic}): any number of regions merged into one coordinate system. */
final class LitematicReader {
    private LitematicReader() {}

    /** One region: absolute minimum corner and extent. Negative sizes in the file extend backwards from Position. */
    private record Region(String name, CompoundTag tag, int minX, int minY, int minZ, int sx, int sy, int sz) {}

    static ImportedStructure read(CompoundTag root, ImportLimits limits) throws PackException {
        int dataVersion = root.getIntOr("MinecraftDataVersion", 0);
        StructureImports.checkDataVersion(dataVersion, limits);
        var regionsTag = root.getCompoundOrEmpty("Regions");
        if (regionsTag.isEmpty()) throw new PackException(".litematic 里没有任何区域");

        var regions = new ArrayList<Region>();
        long minX = Long.MAX_VALUE, minY = Long.MAX_VALUE, minZ = Long.MAX_VALUE;
        long maxX = Long.MIN_VALUE, maxY = Long.MIN_VALUE, maxZ = Long.MIN_VALUE;
        for (var name : regionsTag.keySet()) {
            var tag = regionsTag.getCompoundOrEmpty(name);
            var position = tag.getCompoundOrEmpty("Position");
            var size = tag.getCompoundOrEmpty("Size");
            long sx = size.getIntOr("x", 0), sy = size.getIntOr("y", 0), sz = size.getIntOr("z", 0);
            if (sx == 0 || sy == 0 || sz == 0) throw new PackException(".litematic 区域「" + name + "」的尺寸为 0");
            StructureImports.checkSize(limits, Math.abs(sx), Math.abs(sy), Math.abs(sz));
            long x = position.getIntOr("x", 0) + (sx < 0 ? sx + 1 : 0);
            long y = position.getIntOr("y", 0) + (sy < 0 ? sy + 1 : 0);
            long z = position.getIntOr("z", 0) + (sz < 0 ? sz + 1 : 0);
            regions.add(new Region(name, tag, (int) x, (int) y, (int) z, (int) Math.abs(sx), (int) Math.abs(sy), (int) Math.abs(sz)));
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x + Math.abs(sx)); maxY = Math.max(maxY, y + Math.abs(sy)); maxZ = Math.max(maxZ, z + Math.abs(sz));
        }
        StructureImports.checkSize(limits, maxX - minX, maxY - minY, maxZ - minZ);

        var assembler = new TileAssembler(dataVersion, ImportLimits.MAX_BLOCKS);
        var notes = new ArrayList<String>();
        long overwritten = 0;
        int entities = 0;
        for (var region : regions) {
            overwritten += readRegion(region, (int) (region.minX - minX), (int) (region.minY - minY), (int) (region.minZ - minZ), assembler);
            entities += region.tag.getListOrEmpty("Entities").size();
        }
        if (overwritten > 0) notes.add("区域有重叠：后面的区域覆盖了前面区域的 " + overwritten + " 个方块");
        if (entities > 0) notes.add(StructureImports.ignoredEntities(entities));
        return new ImportedStructure(dataVersion, (int) (maxX - minX), (int) (maxY - minY), (int) (maxZ - minZ), assembler.build(), notes);
    }

    /** Returns how many blocks replaced earlier ones. */
    private static long readRegion(Region region, int offX, int offY, int offZ, TileAssembler assembler) throws PackException {
        var paletteTag = region.tag.getListOrEmpty("BlockStatePalette");
        if (paletteTag.isEmpty()) throw new PackException(".litematic 区域「" + region.name + "」没有调色板");
        var palette = new StateEntry[paletteTag.size()];
        for (int i = 0; i < palette.length; i++) palette[i] = StateEntry.fromTag(paletteTag.getCompoundOrEmpty(i));

        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
        long volume = (long) region.sx * region.sy * region.sz;
        var longs = region.tag.getLongArray("BlockStates").orElseThrow(() -> new PackException(".litematic 区域「" + region.name + "」缺少方块数据"));
        long needed = (volume * bits + 63) / 64;
        if (longs.length < needed)
            throw new PackException(".litematic 区域「" + region.name + "」的方块数据太短：需要 " + needed + " 个长整数，只有 " + longs.length + " 个");
        long mask = (1L << bits) - 1;

        long overwritten = 0;
        for (long i = 0; i < volume; i++) {
            long bit = i * bits;
            int word = (int) (bit >>> 6), offset = (int) (bit & 63);
            long value = longs[word] >>> offset;
            // The value straddles two longs when it does not fit in the rest of the first one.
            if (offset + bits > 64) value |= longs[word + 1] << (64 - offset);
            int index = (int) (value & mask);
            if (index >= palette.length)
                throw new PackException(".litematic 区域「" + region.name + "」引用了不存在的调色板项：" + index);
            var state = palette[index];
            if (state.skipped()) continue;
            int x = (int) (i % region.sx), z = (int) (i / region.sx % region.sz), y = (int) (i / ((long) region.sx * region.sz));
            if (assembler.put(offX + x, offY + y, offZ + z, state)) overwritten++;
        }

        for (var tag : region.tag.getListOrEmpty("TileEntities")) {
            if (!(tag instanceof CompoundTag entity)) throw new PackException(".litematic 区域「" + region.name + "」的方块实体格式不对");
            int x = entity.getIntOr("x", -1), y = entity.getIntOr("y", -1), z = entity.getIntOr("z", -1);
            if (x < 0 || y < 0 || z < 0 || x >= region.sx || y >= region.sy || z >= region.sz)
                throw new PackException(".litematic 区域「" + region.name + "」的方块实体坐标超出区域：" + x + "," + y + "," + z);
            var id = entity.getStringOr("id", entity.getStringOr("Id", ""));
            if (id.isEmpty()) continue;
            var nbt = entity.copy();
            nbt.remove("x");
            nbt.remove("y");
            nbt.remove("z");
            nbt.remove("Id");
            nbt.putString("id", StructureImports.namespaced(id));
            assembler.setBlockEntity(offX + x, offY + y, offZ + z, nbt);
        }
        return overwritten;
    }
}
