package dev.hardrush.lobby.workshop;

import dev.hardrush.core.lobby.plan.Cell;
import dev.hardrush.lobby.pack.PackException;
import dev.hardrush.world.RoundWorldPaths;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Copies a save's overworld chunks into a draft dimension's directory (design 19.1). Vanilla reads and upgrades them
 * when the dimension opens, so nothing here parses a chunk. The save is the server owner's own file, so it is not
 * sanitized; installing a pack published from it still is. IO thread only.
 */
final class SaveImport {
    /** Larger saves are refused before anything is copied. */
    static final long MAX_BYTES = 4L << 30;
    private static final List<String> FOLDERS = List.of("region", "entities", "poi");
    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    record Copied(Cell spawn, List<String> notes) {}

    private SaveImport() {}

    /** Copies into {@code target} (which must not exist yet or be empty) and finds the range centre. */
    static Copied copy(Path save, Path target) throws IOException, PackException {
        RoundWorldPaths.checkAncestors(save, true);
        var base = overworld(save).orElseThrow(() -> new PackException("「" + save.getFileName()
                + "」里没有区块文件：需要直接含 region 目录，或是含 dimensions/minecraft/overworld/region 的完整存档"));
        long total = 0;
        for (var folder : FOLDERS) total += size(base.resolve(folder));
        if (total > MAX_BYTES) throw new PackException("存档区块文件共 " + (total >> 20) + " MB，超过上限 " + (MAX_BYTES >> 30) + " GB");
        for (var folder : FOLDERS) copyTree(base.resolve(folder), target.resolve(folder));
        var notes = new ArrayList<String>();
        var spawn = levelSpawn(save).orElse(null);
        if (spawn == null) {
            spawn = firstChunk(base.resolve("region")).orElse(new Cell(0, 64, 0));
            notes.add("存档没有可读的出生点，范围中心取了第一个区块；进入后用工具箱「把范围中心设到我这里」调整");
        }
        notes.add("存档不清洗，发布后安装时才会清洗；验收会提醒将被移除的内容");
        return new Copied(spawn, notes);
    }

    /** A folder holding {@code region/}, or a full save: new ({@code dimensions/minecraft/overworld}) or old layout. */
    static Optional<Path> overworld(Path save) {
        for (var candidate : List.of(save, save.resolve("dimensions/minecraft/overworld")))
            if (Files.isDirectory(candidate.resolve("region"), LinkOption.NOFOLLOW_LINKS)) return Optional.of(candidate);
        return Optional.empty();
    }

    /** The world spawn in {@code level.dat}: {@code Data.spawn.pos} since 1.21.9, {@code SpawnX/Y/Z} before. */
    static Optional<Cell> levelSpawn(Path save) {
        var file = save.resolve("level.dat");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            if (Files.size(file) > 16 << 20) return Optional.empty();
            CompoundTag data = NbtIo.readCompressed(file, NbtAccounter.create(64L << 20)).getCompoundOrEmpty("Data");
            var spawn = data.getCompound("spawn");
            if (spawn.isPresent()) {
                var pos = spawn.get().getIntArray("pos").orElse(null);
                if (pos != null && pos.length == 3) return Optional.of(new Cell(pos[0], pos[1], pos[2]));
            }
            if (data.contains("SpawnX") && data.contains("SpawnZ"))
                return Optional.of(new Cell(data.getIntOr("SpawnX", 0), data.getIntOr("SpawnY", 64), data.getIntOr("SpawnZ", 0)));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** The centre of the first chunk the first region file (by name) holds, at Y 64. */
    static Optional<Cell> firstChunk(Path region) throws IOException {
        List<Path> files;
        try (var list = Files.list(region)) {
            files = list.filter(p -> REGION.matcher(p.getFileName().toString()).matches()).sorted().toList();
        }
        for (var file : files) {
            var m = REGION.matcher(file.getFileName().toString());
            if (!m.matches()) continue;
            int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2));
            try (var in = new RandomAccessFile(file.toFile(), "r")) {
                if (in.length() < 4096) continue;
                for (int i = 0; i < 1024; i++) {
                    if (in.readInt() == 0) continue;
                    int cx = rx * 32 + i % 32, cz = rz * 32 + i / 32;
                    return Optional.of(new Cell(cx * 16 + 8, 64, cz * 16 + 8));
                }
            }
        }
        return Optional.empty();
    }

    private static long size(Path folder) throws IOException {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return 0;
        long total = 0;
        try (var walk = Files.walk(folder)) {
            for (var p : walk.toList()) if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) total += Files.size(p);
        }
        return total;
    }

    /** Regular files only; links and anything else are skipped, so the copy never leaves the save. */
    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(from)) {
            for (var p : walk.toList()) {
                var dest = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(dest);
                else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
