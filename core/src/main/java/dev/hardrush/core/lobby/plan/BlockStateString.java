package dev.hardrush.core.lobby.plan;

import java.util.*;
import java.util.regex.Pattern;

/** A block state written as {@code minecraft:oak_stairs[facing=north,half=bottom]}, as in Sponge schematics. */
public record BlockStateString(String name, Map<String, String> properties) {
    /** A namespaced block id: {@code namespace:path}. */
    public static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");
    private static final Pattern PROPERTY = Pattern.compile("[a-z0-9_]+");

    public BlockStateString {
        if (!ID.matcher(name).matches()) throw new IllegalArgumentException("方块 id 不合法：" + name);
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    /** Throws {@link IllegalArgumentException} with a Chinese message when {@code text} is malformed. */
    public static BlockStateString parse(String text) {
        var trimmed = text.strip();
        int open = trimmed.indexOf('[');
        var name = (open < 0 ? trimmed : trimmed.substring(0, open)).strip();
        if (name.isEmpty()) throw new IllegalArgumentException("方块状态缺少方块 id：" + text);
        if (name.indexOf(':') < 0 && PATH.matcher(name).matches()) name = "minecraft:" + name;
        if (!ID.matcher(name).matches()) throw new IllegalArgumentException("方块 id 含非法字符：" + text);
        var properties = new LinkedHashMap<String, String>();
        if (open >= 0) {
            if (!trimmed.endsWith("]")) throw new IllegalArgumentException(trimmed.indexOf(']') < 0
                    ? "方块状态缺少右括号：" + text : "方块状态的右括号后面还有多余内容：" + text);
            var body = trimmed.substring(open + 1, trimmed.length() - 1);
            if (body.indexOf('[') >= 0 || body.indexOf(']') >= 0) throw new IllegalArgumentException("方块状态的括号不配对：" + text);
            if (!body.isBlank()) for (var entry : body.split(",", -1)) {
                int eq = entry.indexOf('=');
                if (eq < 0) throw new IllegalArgumentException("方块属性缺少等号：" + entry.strip() + "（" + text + "）");
                var key = entry.substring(0, eq).strip();
                var value = entry.substring(eq + 1).strip();
                if (key.isEmpty() || value.isEmpty()) throw new IllegalArgumentException("方块属性的键或值为空：" + text);
                if (!PROPERTY.matcher(key).matches() || !PROPERTY.matcher(value).matches())
                    throw new IllegalArgumentException("方块属性含非法字符：" + entry.strip() + "（" + text + "）");
                if (properties.putIfAbsent(key, value) != null) throw new IllegalArgumentException("方块属性重复：" + key + "（" + text + "）");
            }
        }
        return new BlockStateString(name, properties);
    }

    public String format() {
        if (properties.isEmpty()) return name;
        var joiner = new StringJoiner(",", name + "[", "]");
        properties.forEach((k, v) -> joiner.add(k + "=" + v));
        return joiner.toString();
    }
}
