package dev.hardrush.lobby.pack.imports;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hardrush.core.lobby.plan.Binding;
import dev.hardrush.core.lobby.plan.BlockStateString;
import dev.hardrush.core.lobby.plan.BuildManifest;
import dev.hardrush.core.lobby.plan.Cell;
import dev.hardrush.core.lobby.plan.LobbyFunction;
import dev.hardrush.core.lobby.plan.MarkerSpec;
import dev.hardrush.core.lobby.plan.MarkerTags;
import dev.hardrush.core.lobby.plan.ZoneShape;
import dev.hardrush.lobby.pack.PackException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * Reads a building manifest ({@code <name>.hardrush.json}, design “建筑清单与大厅包构建” 1) with Minecraft's Gson. The
 * file is untrusted like the building beside it: size and counts are capped before anything is built from them, and
 * any field that breaks a rule fails the whole import with the entry's number, function and problem. Unknown fields are
 * ignored, so later formats can add some without breaking older servers.
 */
public final class BuildManifestJson {
    private BuildManifestJson() {}

    /** The manifest path that goes with {@code building}, whether or not it exists. */
    public static Path besides(Path building) {
        return building.resolveSibling(BuildManifest.fileNameFor(building.getFileName().toString()));
    }

    /** The manifest beside {@code building}; empty when there is none, so the import behaves as it always did. */
    public static Optional<BuildManifest> readBesides(Path building) throws PackException {
        var file = besides(building);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        var name = file.getFileName().toString();
        // The import folder ignores symbolic links; a linked manifest would read a file the owner did not put there.
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new PackException("建筑清单 " + name + " 不是普通文件（不能是目录或符号链接）");
        byte[] bytes;
        try {
            if (Files.size(file) > BuildManifest.MAX_FILE_BYTES) throw new PackException("建筑清单 " + name + " 太大：最多 " + (BuildManifest.MAX_FILE_BYTES >> 20) + " MB");
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new PackException("读不了建筑清单 " + name + "：" + e.getMessage(), e);
        }
        return Optional.of(parse(bytes, name));
    }

    /** Parses manifest bytes; {@code name} is used in messages. */
    public static BuildManifest parse(byte[] bytes, String name) throws PackException {
        if (bytes.length > BuildManifest.MAX_FILE_BYTES) throw new PackException("建筑清单 " + name + " 太大：最多 " + (BuildManifest.MAX_FILE_BYTES >> 20) + " MB");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new PackException("建筑清单 " + name + " 不是 UTF-8 编码");
        }
        // Windows editors like to start UTF-8 files with a byte order mark.
        if (text.startsWith("﻿")) text = text.substring(1);
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (RuntimeException e) {
            throw new PackException("建筑清单 " + name + " 不是合法的 JSON：" + e.getMessage(), e);
        }
        try {
            return manifest(root);
        } catch (Malformed e) {
            throw new PackException("建筑清单 " + name + " 有误：" + e.getMessage());
        }
    }

    // ---- reading; Malformed carries a Chinese reason ----

    private static final class Malformed extends Exception {
        Malformed(String message) { super(message, null, false, false); }
    }

    private static BuildManifest manifest(JsonElement root) throws Malformed {
        if (!root.isJsonObject()) throw new Malformed("顶层应当是 JSON 对象");
        var json = root.getAsJsonObject();
        if (!json.has("format")) throw new Malformed("缺少 format");
        int format = integer(json.get("format"), "format");
        if (format != BuildManifest.FORMAT) throw new Malformed("不认识的 format " + format + "，本服务端只认 " + BuildManifest.FORMAT);
        var origin = json.has("origin") ? cell(json.get("origin"), "origin") : new Cell(0, 0, 0);
        boolean updateShapes = false;
        if (json.has("update_shapes")) {
            var e = json.get("update_shapes");
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new Malformed("update_shapes 应当是 true 或 false");
            updateShapes = e.getAsBoolean();
        }
        var markers = new ArrayList<BuildManifest.Marker>();
        var markerList = list(json, "markers", BuildManifest.MAX_MARKERS);
        for (int i = 0; i < markerList.size(); i++) markers.add(marker(markerList.get(i), i));
        var signs = new ArrayList<BuildManifest.Sign>();
        var signList = list(json, "signs", BuildManifest.MAX_SIGNS);
        for (int i = 0; i < signList.size(); i++) signs.add(sign(signList.get(i), i));
        return new BuildManifest(origin, updateShapes, markers, signs);
    }

    private static BuildManifest.Marker marker(JsonElement element, int index) throws Malformed {
        if (!element.isJsonObject()) throw new Malformed(BuildManifest.describeMarker(index, null) + "应当是 JSON 对象");
        var o = element.getAsJsonObject();
        if (!o.has("function")) throw new Malformed(BuildManifest.describeMarker(index, null) + "：缺少 function");
        var id = string(o.get("function"), BuildManifest.describeMarker(index, null) + "的 function");
        var function = LobbyFunction.byId(id).orElse(null);
        if (function == null) throw new Malformed(BuildManifest.describeMarker(index, null) + "：未知功能 " + id);
        var where = BuildManifest.describeMarker(index, function);
        try {
            if (!o.has("as")) throw new Malformed("缺少 as");
            var as = string(o.get("as"), "as");
            var binding = Arrays.stream(Binding.values()).filter(b -> b.tagName().equals(as)).findFirst()
                    .orElseThrow(() -> new Malformed("未知的 as：" + as));
            if (!function.bindings().contains(binding))
                throw new Malformed("不能绑定为" + MarkerTags.bindingName(binding) + "（" + as + "），可用："
                        + String.join("、", function.bindings().stream().map(b -> MarkerTags.bindingName(b) + "（" + b.tagName() + "）").toList()));
            if (!o.has("at")) throw new Malformed("缺少 at");
            var at = cell(o.get("at"), "at");
            float yaw = 0;
            if (o.has("yaw")) {
                var e = o.get("yaw");
                if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || !Double.isFinite(e.getAsDouble()))
                    throw new Malformed("yaw 应当是数字");
                yaw = e.getAsFloat();
            }
            return new BuildManifest.Marker(spec(function, binding, o), at, yaw);
        } catch (Malformed e) {
            throw new Malformed(where + "：" + e.getMessage());
        } catch (IllegalArgumentException e) {
            // MarkerSpec's own rules, should the checks above ever miss one.
            throw new Malformed(where + "：" + e.getMessage());
        }
    }

    /** The checks {@link MarkerSpec}'s constructor makes, worded for manifest authors. */
    private static MarkerSpec spec(LobbyFunction function, Binding binding, JsonObject o) throws Malformed {
        if (binding != Binding.ZONE) {
            if (o.has("shape") || o.has("materials") || o.has("box")) throw new Malformed("只有区域（zone）标记才能带 shape、materials、box");
            return MarkerSpec.simple(function, binding);
        }
        if (!o.has("shape")) throw new Malformed("区域标记需要 shape：flood 或 box");
        var name = string(o.get("shape"), "shape");
        var shape = Arrays.stream(ZoneShape.values()).filter(s -> s.tagName().equals(name)).findFirst()
                .orElseThrow(() -> new Malformed("未知的 shape：" + name + "，只能是 flood 或 box"));
        return switch (shape) {
            case FLOOD -> {
                if (o.has("box")) throw new Malformed("连片区域（flood）不能带 box");
                var materials = new LinkedHashSet<String>();
                if (o.has("materials")) {
                    for (var e : array(o.get("materials"), "materials", 64)) {
                        var material = string(e, "materials 的元素");
                        if (!BlockStateString.ID.matcher(material).matches())
                            throw new Malformed("materials 里的 " + material + " 不是方块 id，应形如 minecraft:lime_wool");
                        if (!BuiltInRegistries.BLOCK.containsKey(Identifier.parse(material)))
                            throw new Malformed("materials 里的 " + material + " 不是已知的方块");
                        materials.add(material);
                    }
                }
                yield MarkerSpec.flood(function, materials);
            }
            case BOX -> {
                if (o.has("materials")) throw new Malformed("方框区域（box）不能带 materials");
                if (!o.has("box")) throw new Malformed("方框区域需要 box：[宽, 高, 深]");
                var size = array(o.get("box"), "box", 3);
                if (size.size() != 3) throw new Malformed("box 应当是 [宽, 高, 深] 三个整数");
                int[] xyz = new int[3];
                for (int i = 0; i < 3; i++) {
                    xyz[i] = integer(size.get(i), "box");
                    if (xyz[i] < 0 || xyz[i] > MarkerTags.MAX_BOX) throw new Malformed("box 的三项都应是 0–" + MarkerTags.MAX_BOX + " 的整数");
                }
                yield MarkerSpec.box(function, xyz[0], xyz[1], xyz[2]);
            }
        };
    }

    private static BuildManifest.Sign sign(JsonElement element, int index) throws Malformed {
        var where = BuildManifest.describeSign(index);
        try {
            if (!element.isJsonObject()) throw new Malformed("应当是 JSON 对象");
            var o = element.getAsJsonObject();
            if (!o.has("at")) throw new Malformed("缺少 at");
            return new BuildManifest.Sign(cell(o.get("at"), "at"), lines(o, "front"), lines(o, "back"));
        } catch (Malformed e) {
            throw new Malformed(where + "：" + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new Malformed(where + "：" + e.getMessage());
        }
    }

    /** A sign side's lines; null when the side is left out. Line count and length are the record's to check. */
    private static List<String> lines(JsonObject o, String side) throws Malformed {
        if (!o.has(side)) return null;
        var out = new ArrayList<String>();
        for (var e : array(o.get(side), side, BuildManifest.SIGN_LINES)) out.add(string(e, side + " 的每一行"));
        return out;
    }

    private static List<JsonElement> list(JsonObject json, String key, int max) throws Malformed {
        return json.has(key) ? array(json.get(key), key, max) : List.of();
    }
    private static List<JsonElement> array(JsonElement element, String what, int max) throws Malformed {
        if (element == null || !element.isJsonArray()) throw new Malformed(what + " 应当是数组");
        var array = element.getAsJsonArray();
        if (array.size() > max) throw new Malformed(what + " 有 " + array.size() + " 项，最多 " + max + " 项");
        return array.asList();
    }
    private static String string(JsonElement element, String what) throws Malformed {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
            throw new Malformed(what + " 应当是字符串");
        var s = element.getAsString();
        // Far above any function id or sign line; the record checks the real line limit.
        if (s.length() > 256) throw new Malformed(what + " 太长");
        return s;
    }
    private static int integer(JsonElement element, String what) throws Malformed {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber())
            throw new Malformed(what + " 应当是整数");
        try {
            return element.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new Malformed(what + " 应当是整数：" + element);
        }
    }
    private static Cell cell(JsonElement element, String what) throws Malformed {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 3)
            throw new Malformed(what + " 应当是 [x, y, z] 三个整数");
        var a = element.getAsJsonArray();
        return new Cell(integer(a.get(0), what), integer(a.get(1), what), integer(a.get(2), what));
    }
}
