package dev.hardrush.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;

/** Exact owned paths, never a recursive cleanup API. All ancestors are checked before IO. */
public final class RoundWorldPaths {
    private RoundWorldPaths() {}

    public static Path manifest(Path root, UUID round) {
        return root.toAbsolutePath().normalize().resolve("hardrush/p1-worlds/" + round + ".json");
    }

    public static String identifier(UUID round, String dimension) {
        requireDimension(dimension);
        return "hardrush:p1_" + round.toString().replace("-", "") + "/" + dimension;
    }

    public static Path world(Path root, UUID round, String dimension) {
        requireDimension(dimension);
        return root.toAbsolutePath().normalize().resolve("dimensions/hardrush/p1_"
                + round.toString().replace("-", "") + "/" + dimension);
    }

    public static void validate(Path root, UUID round, String dimension, Path claimed) throws IOException {
        Path expected = world(root, round, dimension);
        if (!claimed.toAbsolutePath().normalize().equals(expected)) {
            throw new IOException("拒绝未登记世界路径: " + claimed);
        }
        checkAncestors(expected, true);
        if (Files.exists(expected, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.walk(expected)) {
                var iterator = entries.iterator();
                while (iterator.hasNext()) {
                    Path entry = iterator.next();
                    if (Files.isSymbolicLink(entry) || !(Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                            || Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))) {
                        throw new IOException("拒绝世界目录内的链接或特殊文件: " + entry);
                    }
                }
            }
        }
    }

    public static void checkAncestors(Path target, boolean directory) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        for (Path parent = absolute; parent != null; parent = parent.getParent()) {
            if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) continue;
            if (Files.isSymbolicLink(parent)) throw new IOException("拒绝符号链接世界路径: " + parent);
            if ((directory || !parent.equals(absolute)) && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("世界路径父级不是目录: " + parent);
            }
            if (!directory && parent.equals(absolute) && !Files.isRegularFile(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("清单不是普通文件: " + parent);
            }
        }
    }

    public static void requireDimension(String dimension) {
        if (!dimension.equals("overworld") && !dimension.equals("nether") && !dimension.equals("end")) {
            throw new IllegalArgumentException("非法本轮维度: " + dimension);
        }
    }
}
