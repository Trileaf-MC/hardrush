package dev.hardrush.core.lobby.plan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A building file's companion {@code <name>.hardrush.json} (建筑清单, design “建筑清单与大厅包构建” 1): markers to spawn
 * and sign texts to write once the building is placed. Every cell is in the author's own frame; {@code origin} is where
 * the building file's minimum corner sits in that frame, so a manifest cell {@code p} lands on the world cell
 * {@code placedMin + (p - origin)}. Parsing lives in the platform (the core has no JSON library); this record holds the
 * limits that do not depend on the format.
 */
public record BuildManifest(Cell origin, boolean updateShapes, List<Marker> markers, List<Sign> signs) {
    public static final int FORMAT = 1;
    public static final int MAX_MARKERS = 512, MAX_SIGNS = 256;
    /** Lines per sign side, as vanilla signs have. */
    public static final int SIGN_LINES = 4;
    /** Characters (code points) per sign line. */
    public static final int MAX_LINE_LENGTH = 64;
    public static final long MAX_FILE_BYTES = 1L << 20;
    /** Replaces the building file's extension: {@code camp.schem} goes with {@code camp.hardrush.json}. */
    public static final String SUFFIX = ".hardrush.json";

    /** A marker as {@code MarkerStands} spawns it; {@code at} is the cell the binding names (design 1.2). */
    public record Marker(MarkerSpec spec, Cell at, float yaw) {
        public Marker {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(at, "at");
            if (!Float.isFinite(yaw)) throw new IllegalArgumentException("yaw 应当是有限数字");
        }
    }

    /** Plain text for an existing sign; a null side is left as the building file has it. */
    public record Sign(Cell at, List<String> front, List<String> back) {
        public Sign {
            Objects.requireNonNull(at, "at");
            front = lines(front, "front");
            back = lines(back, "back");
        }
        private static List<String> lines(List<String> lines, String side) {
            if (lines == null) return null;
            if (lines.size() > SIGN_LINES) throw new IllegalArgumentException(side + " 最多 " + SIGN_LINES + " 行，现在有 " + lines.size() + " 行");
            for (int i = 0; i < lines.size(); i++) {
                var line = Objects.requireNonNull(lines.get(i), side);
                if (line.codePointCount(0, line.length()) > MAX_LINE_LENGTH)
                    throw new IllegalArgumentException(side + " 第 " + (i + 1) + " 行超过 " + MAX_LINE_LENGTH + " 个字符");
            }
            return List.copyOf(lines);
        }
    }

    public BuildManifest {
        Objects.requireNonNull(origin, "origin");
        markers = List.copyOf(markers);
        signs = List.copyOf(signs);
        if (markers.size() > MAX_MARKERS) throw new IllegalArgumentException("markers 最多 " + MAX_MARKERS + " 条，现在有 " + markers.size() + " 条");
        if (signs.size() > MAX_SIGNS) throw new IllegalArgumentException("signs 最多 " + MAX_SIGNS + " 条，现在有 " + signs.size() + " 条");
    }

    /** The manifest's file name for a building file name: its extension replaced by {@link #SUFFIX}. */
    public static String fileNameFor(String buildingFileName) {
        int dot = buildingFileName.lastIndexOf('.');
        return (dot <= 0 ? buildingFileName : buildingFileName.substring(0, dot)) + SUFFIX;
    }

    /** The world cell of manifest cell {@code p}, with the building's minimum corner placed at {@code placedMin}. */
    public Cell toWorld(Cell p, Cell placedMin) {
        return new Cell(Math.addExact(placedMin.x(), Math.subtractExact(p.x(), origin.x())),
                Math.addExact(placedMin.y(), Math.subtractExact(p.y(), origin.y())),
                Math.addExact(placedMin.z(), Math.subtractExact(p.z(), origin.z())));
    }

    /**
     * The first marker or sign whose column lies outside a building {@code sizeX × sizeZ} wide (design 1.5), so that
     * everything the manifest touches stands in chunks the import loads. Height is checked against the world later.
     */
    public Optional<String> horizontalProblem(int sizeX, int sizeZ) {
        return firstProblem(p -> {
            long dx = (long) p.x() - origin.x(), dz = (long) p.z() - origin.z();
            return dx < 0 || dx >= sizeX || dz < 0 || dz >= sizeZ;
        }, "超出了建筑的水平范围（x " + origin.x() + "～" + ((long) origin.x() + sizeX - 1)
                + "，z " + origin.z() + "～" + ((long) origin.z() + sizeZ - 1) + "）");
    }

    /** The first marker or sign that would land outside the world's build height {@code minY..maxY} (inclusive). */
    public Optional<String> heightProblem(Cell placedMin, int minY, int maxY) {
        return firstProblem(p -> {
            long y = (long) placedMin.y() + p.y() - origin.y();
            return y < minY || y > maxY;
        }, "放置后超出了世界高度（Y " + minY + "～" + maxY + "）");
    }

    private interface CellTest { boolean outside(Cell p); }

    private Optional<String> firstProblem(CellTest test, String why) {
        for (int i = 0; i < markers.size(); i++) {
            var m = markers.get(i);
            if (test.outside(m.at())) return Optional.of(describeMarker(i, m.spec().function()) + "的位置 " + cell(m.at()) + " " + why);
        }
        for (int i = 0; i < signs.size(); i++) {
            var s = signs.get(i);
            if (test.outside(s.at())) return Optional.of(describeSign(i) + "的位置 " + cell(s.at()) + " " + why);
        }
        return Optional.empty();
    }

    /** “第 3 条标记（【中央落点】）”; {@code index} is zero-based. */
    public static String describeMarker(int index, LobbyFunction function) {
        return "第 " + (index + 1) + " 条标记" + (function == null ? "" : "（【" + function.displayName() + "】）");
    }
    public static String describeSign(int index) { return "第 " + (index + 1) + " 条告示牌"; }
    public static String cell(Cell c) { return "[" + c.x() + ", " + c.y() + ", " + c.z() + "]"; }
}
