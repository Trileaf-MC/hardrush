package dev.hardrush.core.lobby.plan;

import java.util.*;
import java.util.regex.Pattern;

/** The header of a {@code .hrlobby} pack ({@code pack.json} without its plan). Nullable fields are reported by {@link #problems}. */
public record PackManifest(int format, String id, String name, List<String> authors, int version, String created,
                           String modVersion, int dataVersion, Box bounds) {
    public static final int FORMAT = 1;
    public static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    public static final int MAX_NAME = 64, MAX_AUTHORS = 32;
    public static final String EXTENSION = ".hrlobby";
    /** Vanilla's world limits: {@code Level.MAX_LEVEL_SIZE} horizontally, {@code DimensionType.MIN_Y..MAX_Y} vertically. */
    public static final int MAX_HORIZONTAL_COORDINATE = 30_000_000, MIN_Y = -2032, MAX_Y = 2031;

    public PackManifest { authors = authors == null ? List.of() : List.copyOf(authors); }

    /** Reasons to refuse this pack, in Chinese; empty when acceptable. */
    public List<String> problems(int maxHorizontalSize, int serverDataVersion) {
        var problems = new ArrayList<String>();
        if (format != FORMAT) problems.add("无法识别的大厅包格式 " + format + "，本模组只认识格式 " + FORMAT);
        if (id == null || !ID.matcher(id).matches())
            problems.add("大厅包 id 不合法：" + id + "，只能用 1–32 个小写字母、数字、下划线或连字符");
        if (name == null || name.isBlank()) problems.add("大厅包缺少名称");
        else if (name.codePointCount(0, name.length()) > MAX_NAME) problems.add("大厅包名称太长，最多 " + MAX_NAME + " 个字");
        if (authors.size() > MAX_AUTHORS) problems.add("作者太多：最多 " + MAX_AUTHORS + " 个，现在 " + authors.size() + " 个");
        if (version < 1) problems.add("大厅包版本号必须从 1 开始，现在是 " + version);
        if (dataVersion > serverDataVersion)
            problems.add("大厅包来自更新的游戏版本（数据版本 " + dataVersion + "），本服务端是 " + serverDataVersion + "，请先升级服务端");
        if (bounds == null) problems.add("大厅包缺少范围");
        else if (!inWorld(bounds))
            problems.add("大厅范围 " + shown(bounds.min()) + " 到 " + shown(bounds.max()) + " 超出了世界范围（水平 ±" + MAX_HORIZONTAL_COORDINATE
                    + "，高度 " + MIN_Y + "–" + MAX_Y + "）");
        else if (bounds.sizeX() > maxHorizontalSize || bounds.sizeZ() > maxHorizontalSize)
            problems.add("大厅范围 " + bounds.sizeX() + "×" + bounds.sizeZ() + " 超过了本服务端的水平上限 " + maxHorizontalSize + "×" + maxHorizontalSize);
        return List.copyOf(problems);
    }

    /** Whether every block of {@code box} is a block a world can hold. */
    public static boolean inWorld(Box box) {
        return Math.abs((long) box.min().x()) <= MAX_HORIZONTAL_COORDINATE && Math.abs((long) box.max().x()) <= MAX_HORIZONTAL_COORDINATE
                && Math.abs((long) box.min().z()) <= MAX_HORIZONTAL_COORDINATE && Math.abs((long) box.max().z()) <= MAX_HORIZONTAL_COORDINATE
                && box.min().y() >= MIN_Y && box.max().y() <= MAX_Y;
    }

    private static String shown(Cell c) { return c.x() + " " + c.y() + " " + c.z(); }

    public String fileName() { return id + "-v" + version + EXTENSION; }
}
