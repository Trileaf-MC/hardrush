package dev.hardrush.lobby.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hardrush.core.lobby.plan.*;
import java.util.*;

/**
 * {@code pack.json} and its {@code plan} field, and the installed lobby's {@code current.json}. Output is stable (fixed key order, sorted floor cells) and indented
 * two spaces with number arrays kept on one line, so a pack committed to the repository diffs well. Reading caps
 * every array so a hostile file cannot make us allocate much more than its own size.
 */
public final class LobbyPlanJson {
    /** Cells in one floor zone. */
    static final int MAX_CELLS = 4096 * 4;
    /** Entries in any other list: triggers, models, points, zones, lights, texts, authors. */
    static final int MAX_ITEMS = 4096;
    private static final int MAX_STRING = 256;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private LobbyPlanJson() {}

    // ---- pack.json ----

    public static JsonObject packToJson(PackManifest manifest, LobbyPlan plan) {
        if (!plan.bounds().equals(manifest.bounds())) throw new IllegalArgumentException("plan and manifest bounds differ");
        var json = new JsonObject();
        json.addProperty("format", manifest.format());
        json.addProperty("id", manifest.id());
        json.addProperty("name", manifest.name());
        var authors = new JsonArray();
        manifest.authors().forEach(authors::add);
        json.add("authors", authors);
        json.addProperty("version", manifest.version());
        json.addProperty("created", manifest.created());
        json.addProperty("mod_version", manifest.modVersion());
        json.addProperty("data_version", manifest.dataVersion());
        json.add("bounds", box(manifest.bounds()));
        json.add("plan", planToJson(plan));
        return json;
    }

    /** The header only; callers check {@link PackManifest#problems} before reading the plan. */
    public static PackManifest manifestFromJson(JsonObject json) throws PackException {
        try {
            var authors = new ArrayList<String>();
            if (json.has("authors")) for (var a : array(json.get("authors"), "authors", MAX_ITEMS)) authors.add(string(a, "authors"));
            return new PackManifest(json.has("format") ? integer(json.get("format"), "format") : 0,
                    optionalString(json, "id"), optionalString(json, "name"), authors,
                    json.has("version") ? integer(json.get("version"), "version") : 0,
                    optionalString(json, "created"), optionalString(json, "mod_version"),
                    json.has("data_version") ? integer(json.get("data_version"), "data_version") : 0,
                    json.has("bounds") ? box(json.get("bounds"), "bounds") : null);
        } catch (Malformed | RuntimeException e) {
            throw broken(e);
        }
    }

    /** The plan of an already accepted manifest; {@code bounds} lives in the header, not in {@code plan}. */
    public static LobbyPlan planFromPack(JsonObject json, Box bounds) throws PackException {
        if (!json.has("plan") || !json.get("plan").isJsonObject()) throw new PackException("pack.json 缺少 plan");
        return planFromJson(json.getAsJsonObject("plan"), bounds);
    }

    // ---- current.json ----

    public static JsonObject installationToJson(LobbyInstallation installation) {
        var json = new JsonObject();
        json.addProperty("seq", installation.seq());
        json.addProperty("dimension", installation.dimension());
        var pack = new JsonObject();
        pack.addProperty("id", installation.pack().id());
        pack.addProperty("name", installation.pack().name());
        pack.addProperty("version", installation.pack().version());
        var authors = new JsonArray();
        installation.pack().authors().forEach(authors::add);
        pack.add("authors", authors);
        pack.addProperty("sha256", installation.pack().sha256());
        json.add("pack", pack);
        json.add("bounds", box(installation.plan().bounds()));
        json.add("plan", planToJson(installation.plan()));
        json.addProperty("state", installation.state().name());
        return json;
    }

    public static LobbyInstallation installationFromJson(JsonObject json) throws PackException {
        LobbyInstallation.Pack pack;
        Box bounds;
        int seq;
        String dimension;
        LobbyInstallation.State state;
        try {
            seq = integer(json.get("seq"), "seq");
            dimension = string(json.get("dimension"), "dimension");
            var p = object(json.get("pack"), "pack");
            var authors = new ArrayList<String>();
            for (var a : list(p, "authors")) authors.add(string(a, "authors"));
            pack = new LobbyInstallation.Pack(string(p.get("id"), "pack.id"), string(p.get("name"), "pack.name"),
                    integer(p.get("version"), "pack.version"), authors, string(p.get("sha256"), "pack.sha256"));
            bounds = box(json.get("bounds"), "bounds");
            var stateName = string(json.get("state"), "state");
            state = Arrays.stream(LobbyInstallation.State.values()).filter(s -> s.name().equals(stateName)).findFirst()
                    .orElseThrow(() -> new Malformed("未知安装状态 " + stateName));
            if (!json.has("plan") || !json.get("plan").isJsonObject()) throw new Malformed("缺少 plan");
        } catch (Malformed | RuntimeException e) {
            throw broken(e);
        }
        return new LobbyInstallation(seq, dimension, pack, planFromJson(json.getAsJsonObject("plan"), bounds), state);
    }

    // ---- plan ----

    /** Everything but {@code bounds}, which {@code pack.json} keeps at the top level. */
    public static JsonObject planToJson(LobbyPlan plan) {
        var json = new JsonObject();
        var triggers = new JsonArray();
        for (var t : plan.triggers()) {
            var o = function(t.function());
            if (t.block() != null) { o.add("block", cell(t.block())); o.addProperty("block_id", t.blockId()); }
            o.add("anchor", point(t.anchor()));
            triggers.add(o);
        }
        json.add("triggers", triggers);
        var models = new JsonArray();
        for (var m : plan.models()) { var o = function(m.function()); o.add("point", point(m.point())); models.add(o); }
        json.add("models", models);
        var points = new JsonObject();
        plan.points().forEach((f, list) -> {
            var a = new JsonArray(); list.forEach(p -> a.add(point(p))); points.add(f.id(), a);
        });
        json.add("points", points);
        var zones = new JsonObject();
        plan.zones().forEach((f, list) -> {
            var a = new JsonArray(); list.forEach(z -> a.add(zone(z))); zones.add(f.id(), a);
        });
        json.add("zones", zones);
        var lights = new JsonArray();
        plan.altarLights().forEach(c -> lights.add(cell(c)));
        json.add("altar_lights", lights);
        var texts = new JsonArray();
        for (var t : plan.texts()) {
            var o = function(t.function());
            if (t.sign() != null) o.add("sign", cell(t.sign()));
            o.add("anchor", point(t.anchor()));
            texts.add(o);
        }
        json.add("texts", texts);
        return json;
    }

    public static LobbyPlan planFromJson(JsonObject json, Box bounds) throws PackException {
        try {
            var triggers = new ArrayList<Trigger>();
            for (var e : list(json, "triggers")) {
                var o = object(e, "triggers");
                Cell block = o.has("block") ? cell(o.get("block"), "block") : null;
                String blockId = o.has("block_id") ? string(o.get("block_id"), "block_id") : null;
                triggers.add(new Trigger(function(o), block, blockId, point(o.get("anchor"), "anchor")));
            }
            var models = new ArrayList<Model>();
            for (var e : list(json, "models")) {
                var o = object(e, "models");
                models.add(new Model(function(o), point(o.get("point"), "point")));
            }
            var points = new EnumMap<LobbyFunction, List<Point>>(LobbyFunction.class);
            for (var entry : byFunction(json, "points").entrySet()) {
                var list = new ArrayList<Point>();
                for (var p : array(entry.getValue(), "points", MAX_ITEMS)) list.add(point(p, "points"));
                points.put(entry.getKey(), list);
            }
            var zones = new EnumMap<LobbyFunction, List<Zone>>(LobbyFunction.class);
            for (var entry : byFunction(json, "zones").entrySet()) {
                var list = new ArrayList<Zone>();
                for (var z : array(entry.getValue(), "zones", MAX_ITEMS)) list.add(zone(z));
                zones.put(entry.getKey(), list);
            }
            var lights = new ArrayList<Cell>();
            for (var e : list(json, "altar_lights")) lights.add(cell(e, "altar_lights"));
            var texts = new ArrayList<TextSlot>();
            for (var e : list(json, "texts")) {
                var o = object(e, "texts");
                texts.add(new TextSlot(function(o), o.has("sign") ? cell(o.get("sign"), "sign") : null, point(o.get("anchor"), "anchor")));
            }
            return new LobbyPlan(bounds, triggers, models, points, zones, lights, texts);
        } catch (Malformed | RuntimeException e) {
            throw broken(e);
        }
    }

    // ---- text ----

    public static JsonObject parse(String text) throws PackException {
        try {
            var element = JsonParser.parseString(text);
            if (!element.isJsonObject()) throw new PackException("pack.json 不是 JSON 对象");
            return element.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new PackException("pack.json 不是合法的 JSON：" + e.getMessage(), e);
        }
    }

    /** Two-space indentation; arrays of plain values stay on one line. Ends with a newline. */
    public static String write(JsonElement json) {
        var out = new StringBuilder();
        write(out, json, "");
        return out.append('\n').toString();
    }

    private static void write(StringBuilder out, JsonElement json, String indent) {
        String inner = indent + "  ";
        if (json.isJsonObject()) {
            var entries = json.getAsJsonObject().entrySet();
            if (entries.isEmpty()) { out.append("{}"); return; }
            out.append("{\n");
            var it = entries.iterator();
            while (it.hasNext()) {
                var e = it.next();
                out.append(inner).append(GSON.toJson(e.getKey())).append(": ");
                write(out, e.getValue(), inner);
                out.append(it.hasNext() ? ",\n" : "\n");
            }
            out.append(indent).append('}');
        } else if (json.isJsonArray()) {
            var array = json.getAsJsonArray();
            if (array.isEmpty()) { out.append("[]"); return; }
            boolean flat = array.asList().stream().allMatch(e -> e.isJsonPrimitive() || e.isJsonNull());
            if (flat) {
                out.append('[');
                for (int i = 0; i < array.size(); i++) out.append(i == 0 ? "" : ", ").append(GSON.toJson(array.get(i)));
                out.append(']');
                return;
            }
            out.append("[\n");
            for (int i = 0; i < array.size(); i++) {
                out.append(inner);
                write(out, array.get(i), inner);
                out.append(i + 1 < array.size() ? ",\n" : "\n");
            }
            out.append(indent).append(']');
        } else {
            out.append(GSON.toJson(json));
        }
    }

    // ---- writing helpers ----

    private static JsonObject function(LobbyFunction f) {
        var o = new JsonObject();
        o.addProperty("function", f.id());
        return o;
    }
    private static JsonArray cell(Cell c) {
        var a = new JsonArray(); a.add(c.x()); a.add(c.y()); a.add(c.z()); return a;
    }
    private static JsonArray point(Point p) {
        var a = new JsonArray(); a.add(p.x()); a.add(p.y()); a.add(p.z()); a.add(p.yaw()); return a;
    }
    private static JsonObject box(Box b) {
        var o = new JsonObject(); o.add("min", cell(b.min())); o.add("max", cell(b.max())); return o;
    }
    private static final Comparator<Cell> CELL_ORDER =
            Comparator.comparingInt(Cell::y).thenComparingInt(Cell::z).thenComparingInt(Cell::x);
    private static JsonObject zone(Zone zone) {
        var o = new JsonObject();
        switch (zone) {
            case Zone.Floor f -> {
                o.addProperty("type", "floor");
                var cells = new JsonArray();
                f.floor().stream().sorted(CELL_ORDER).forEach(c -> cells.add(cell(c)));
                o.add("cells", cells);
            }
            case Zone.Volume v -> {
                o.addProperty("type", "box");
                o.add("min", cell(v.box().min()));
                o.add("max", cell(v.box().max()));
            }
        }
        return o;
    }

    // ---- reading helpers; Malformed carries a Chinese reason ----

    private static final class Malformed extends Exception {
        Malformed(String message) { super(message, null, false, false); }
    }

    private static PackException broken(Exception e) {
        if (e instanceof Malformed) return new PackException("pack.json 内容不合法：" + e.getMessage());
        return new PackException("pack.json 内容不合法：" + e, e);
    }

    private static LobbyFunction function(JsonObject o) throws Malformed {
        var id = string(o.get("function"), "function");
        return LobbyFunction.byId(id).orElseThrow(() -> new Malformed("未知功能 " + id));
    }
    private static Map<LobbyFunction, JsonElement> byFunction(JsonObject json, String key) throws Malformed {
        var result = new EnumMap<LobbyFunction, JsonElement>(LobbyFunction.class);
        if (!json.has(key)) return result;
        var element = json.get(key);
        if (!element.isJsonObject()) throw new Malformed(key + " 应当是对象");
        for (var e : element.getAsJsonObject().entrySet()) {
            var f = LobbyFunction.byId(e.getKey()).orElseThrow(() -> new Malformed("未知功能 " + e.getKey()));
            result.put(f, e.getValue());
        }
        return result;
    }
    private static Zone zone(JsonElement element) throws Malformed {
        var o = object(element, "zones");
        var type = string(o.get("type"), "type");
        return switch (type) {
            case "floor" -> {
                var cells = new HashSet<Cell>();
                for (var c : array(o.get("cells"), "cells", MAX_CELLS)) cells.add(cell(c, "cells"));
                yield new Zone.Floor(cells);
            }
            case "box" -> new Zone.Volume(new Box(cell(o.get("min"), "min"), cell(o.get("max"), "max")));
            default -> throw new Malformed("未知区域类型 " + type);
        };
    }
    private static List<JsonElement> list(JsonObject json, String key) throws Malformed {
        return json.has(key) ? array(json.get(key), key, MAX_ITEMS) : List.of();
    }
    private static List<JsonElement> array(JsonElement element, String what, int max) throws Malformed {
        if (element == null || !element.isJsonArray()) throw new Malformed(what + " 应当是数组");
        var array = element.getAsJsonArray();
        if (array.size() > max) throw new Malformed(what + " 有 " + array.size() + " 项，超过上限 " + max);
        return array.asList();
    }
    private static JsonObject object(JsonElement element, String what) throws Malformed {
        if (element == null || !element.isJsonObject()) throw new Malformed(what + " 的元素应当是对象");
        return element.getAsJsonObject();
    }
    private static String string(JsonElement element, String what) throws Malformed {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
            throw new Malformed(what + " 应当是字符串");
        var s = element.getAsString();
        if (s.length() > MAX_STRING) throw new Malformed(what + " 太长");
        return s;
    }
    private static String optionalString(JsonObject json, String key) throws Malformed {
        return json.has(key) && !json.get(key).isJsonNull() ? string(json.get(key), key) : null;
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
    private static double real(JsonElement element, String what) throws Malformed {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber())
            throw new Malformed(what + " 应当是数字");
        double d = element.getAsDouble();
        if (!Double.isFinite(d)) throw new Malformed(what + " 不是有限数字");
        return d;
    }
    private static Cell cell(JsonElement element, String what) throws Malformed {
        var a = array(element, what, 3);
        if (a.size() != 3) throw new Malformed(what + " 应当是 [x, y, z]");
        return new Cell(integer(a.get(0), what), integer(a.get(1), what), integer(a.get(2), what));
    }
    private static Point point(JsonElement element, String what) throws Malformed {
        var a = array(element, what, 4);
        if (a.size() != 4) throw new Malformed(what + " 应当是 [x, y, z, 朝向]");
        return new Point(real(a.get(0), what), real(a.get(1), what), real(a.get(2), what), (float) real(a.get(3), what));
    }
    private static Box box(JsonElement element, String what) throws Malformed {
        var o = object(element, what);
        var min = cell(o.get("min"), what + ".min");
        var max = cell(o.get("max"), what + ".max");
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) throw new Malformed(what + " 的 min 大于 max");
        return new Box(min, max);
    }
}
