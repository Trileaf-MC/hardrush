package dev.hardrush.lobby.pack.imports;

import dev.hardrush.lobby.pack.PackException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtAccounterException;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ReportedNbtException;

/**
 * Converts building files ({@code .nbt}, {@code .schem}, {@code .litematic}) to structure tiles. The file is
 * untrusted: sizes are checked before anything is allocated from them, and every index is bounds-checked.
 */
public final class StructureImports {
    private StructureImports() {}

    public static ImportedStructure read(Path file, ImportLimits limits) throws PackException {
        var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".schematic"))
            throw new PackException("不支持旧版 .schematic，请先用 WorldEdit 载入后另存为 .schem");
        if (!name.endsWith(".nbt") && !name.endsWith(".schem") && !name.endsWith(".litematic"))
            throw new PackException("不支持的建筑文件格式：" + file.getFileName() + "（支持 .nbt、.schem、.litematic）");
        var root = readRoot(file);
        try {
            if (name.endsWith(".nbt")) return NbtStructureReader.read(root, limits);
            if (name.endsWith(".schem")) return SchemReader.read(root, limits);
            return LitematicReader.read(root, limits);
        } catch (RuntimeException malformed) {
            // Tag accessors are lenient, but a hostile file can still trip an unforeseen invariant.
            throw new PackException("建筑文件内容不合法：" + file.getFileName(), malformed);
        }
    }

    private static CompoundTag readRoot(Path file) throws PackException {
        try {
            if (Files.size(file) > ImportLimits.MAX_FILE_BYTES)
                throw new PackException("建筑文件太大：最多 " + (ImportLimits.MAX_FILE_BYTES >> 20) + " MB");
            return NbtIo.readCompressed(file, NbtAccounter.create(ImportLimits.MAX_NBT_BYTES));
        } catch (NbtAccounterException tooBig) {
            throw new PackException("建筑文件解压后太大（超过 " + (ImportLimits.MAX_NBT_BYTES >> 20) + " MB）", tooBig);
        } catch (IOException | ReportedNbtException damaged) {
            // NbtIo wraps a damaged stream (truncated gzip, bad tag) in the unchecked ReportedNbtException.
            throw new PackException("建筑文件已损坏或不是 gzip 压缩的 NBT：" + file.getFileName(), damaged);
        }
    }

    static void checkDataVersion(int dataVersion, ImportLimits limits) throws PackException {
        if (dataVersion <= 0) throw new PackException("建筑文件缺少数据版本号（DataVersion）");
        if (dataVersion > limits.serverDataVersion())
            throw new PackException("建筑文件来自更新的游戏版本（数据版本 " + dataVersion + "），本服务端只到 " + limits.serverDataVersion());
    }

    static void checkSize(ImportLimits limits, long x, long y, long z) throws PackException {
        if (x <= 0 || y <= 0 || z <= 0) throw new PackException("建筑文件的尺寸不合法：" + x + "×" + y + "×" + z);
        if (x > limits.maxHorizontalSize() || z > limits.maxHorizontalSize())
            throw new PackException("建筑水平尺寸 " + x + "×" + z + " 超过上限 " + limits.maxHorizontalSize());
        if (y > ImportLimits.MAX_HEIGHT)
            throw new PackException("建筑高度 " + y + " 超过上限 " + ImportLimits.MAX_HEIGHT);
    }

    /** Block entity data in a template carries its id; the Sponge and Litematica spellings differ in case. */
    static String namespaced(String id) { return id.indexOf(':') < 0 ? "minecraft:" + id : id; }

    static String ignoredEntities(int count) { return "已忽略 " + count + " 个实体"; }
}
