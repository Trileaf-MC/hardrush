package dev.hardrush.core.lobby.plan;

import java.util.*;

/**
 * Marker data lives in plain scoreboard tags so OPs can read and edit it with {@code /tag}. Block ids use
 * {@code +} instead of {@code :} because {@code /tag} rejects colons.
 */
public final class MarkerTags {
    public static final String PREFIX = "hardrush.";
    public static final String MARKER = "hardrush.marker", FUNCTION = "hardrush.fn.", BINDING = "hardrush.as.",
            SHAPE = "hardrush.shape.", MATERIAL = "hardrush.mat.", BOX = "hardrush.box.";
    public static final int MAX_BOX = 64;

    public sealed interface Parsed permits Ok, Failed {}
    public record Ok(MarkerSpec spec) implements Parsed {}
    /** Problems in Chinese, for builders. */
    public record Failed(List<String> errors) implements Parsed {
        public Failed { errors = List.copyOf(errors); }
    }

    /** Parses a marker armor stand's tags; they must include {@link #MARKER}. */
    public static Parsed parseMarker(Set<String> tags) {
        var errors = new ArrayList<String>();
        if (!tags.contains(MARKER)) errors.add("缺少标签 " + MARKER);
        List<String> functions = new ArrayList<>(), bindings = new ArrayList<>(), shapes = new ArrayList<>(),
                materials = new ArrayList<>(), boxes = new ArrayList<>();
        for (var tag : sorted(tags)) {
            if (!tag.startsWith(PREFIX) || tag.equals(MARKER)) continue;
            if (tag.startsWith(FUNCTION)) functions.add(tag.substring(FUNCTION.length()));
            else if (tag.startsWith(BINDING)) bindings.add(tag.substring(BINDING.length()));
            else if (tag.startsWith(SHAPE)) shapes.add(tag.substring(SHAPE.length()));
            else if (tag.startsWith(MATERIAL)) materials.add(tag);
            else if (tag.startsWith(BOX)) boxes.add(tag.substring(BOX.length()));
            else errors.add("无法识别的标签：" + tag);
        }
        var function = function(functions, errors);
        Binding binding = null;
        if (bindings.size() != 1) errors.add("需要恰好一个绑定形态标签 " + BINDING + "<形态>，现在有 " + bindings.size() + " 个");
        else {
            binding = Arrays.stream(Binding.values()).filter(b -> b.tagName().equals(bindings.getFirst())).findFirst().orElse(null);
            if (binding == null) errors.add("未知的绑定形态：" + BINDING + bindings.getFirst());
            else if (function != null && !function.bindings().contains(binding)) {
                errors.add("【" + function.displayName() + "】不能绑定为" + bindingName(binding) + "，可用形态：" + allowed(function));
                binding = null;
            }
        }
        ZoneShape shape = null;
        var materialIds = new LinkedHashSet<String>();
        int[] box = new int[3];
        if (binding == Binding.ZONE) {
            if (shapes.size() != 1) errors.add("区域标记需要恰好一个形状标签 " + SHAPE + "flood 或 " + SHAPE + "box，现在有 " + shapes.size() + " 个");
            else {
                shape = Arrays.stream(ZoneShape.values()).filter(s -> s.tagName().equals(shapes.getFirst())).findFirst().orElse(null);
                if (shape == null) errors.add("未知的区域形状：" + SHAPE + shapes.getFirst());
            }
            if (shape == ZoneShape.FLOOD) {
                if (!boxes.isEmpty()) errors.add("连片区域不能带方框尺寸标签 " + BOX + "*");
                for (var tag : materials) materialId(tag).ifPresentOrElse(materialIds::add, () -> errors.add("材质标签写法不对：" + tag + "，应形如 " + MATERIAL + "minecraft+lime_wool"));
            } else if (shape == ZoneShape.BOX) {
                if (!materials.isEmpty()) errors.add("方框区域不能带材质标签 " + MATERIAL + "*");
                if (boxes.size() != 1) errors.add("方框区域需要恰好一个尺寸标签 " + BOX + "<宽>.<高>.<深>，现在有 " + boxes.size() + " 个");
                else if (!parseBox(boxes.getFirst(), box)) errors.add("方框尺寸写法不对：" + BOX + boxes.getFirst() + "，三项都应是 0–" + MAX_BOX + " 的整数");
            }
        } else if (binding != null && (!shapes.isEmpty() || !materials.isEmpty() || !boxes.isEmpty())) {
            errors.add("只有区域标记才能带形状、材质或方框尺寸标签");
        }
        if (!errors.isEmpty()) return new Failed(errors);
        return new Ok(new MarkerSpec(function, binding, shape, materialIds, box[0], box[1], box[2]));
    }

    /**
     * Parses a non-marker entity's tags. Empty when the entity carries no {@code hardrush.*} tag at all. Otherwise
     * it must carry exactly one function tag and nothing else, and the function must support click or text;
     * the binding follows from the function, click first.
     */
    public static Optional<Parsed> parseTrigger(Set<String> tags) {
        var ours = sorted(tags).stream().filter(t -> t.startsWith(PREFIX)).toList();
        if (ours.isEmpty()) return Optional.empty();
        var errors = new ArrayList<String>();
        var functions = new ArrayList<String>();
        for (var tag : ours) {
            if (tag.startsWith(FUNCTION)) functions.add(tag.substring(FUNCTION.length()));
            else if (tag.equals(MARKER)) errors.add("带 " + MARKER + " 的是标记盔甲架，不是实体触发器");
            else errors.add("实体触发器只能带 " + FUNCTION + "<功能> 标签，无法识别：" + tag);
        }
        var function = function(functions, errors);
        Binding binding = null;
        if (function != null) {
            if (function.bindings().contains(Binding.CLICK)) binding = Binding.CLICK;
            else if (function.bindings().contains(Binding.TEXT)) binding = Binding.TEXT;
            else errors.add("【" + function.displayName() + "】不能绑定到实体，只有点击类和文字类功能可以");
        }
        if (!errors.isEmpty()) return Optional.of(new Failed(errors));
        return Optional.of(new Ok(MarkerSpec.simple(function, binding)));
    }

    /** The marker tags for {@code spec}; {@link #parseMarker} reads them back to an equal spec. */
    public static Set<String> format(MarkerSpec spec) {
        var tags = new LinkedHashSet<String>();
        tags.add(MARKER);
        tags.add(FUNCTION + spec.function().id());
        tags.add(BINDING + spec.binding().tagName());
        if (spec.shape() != null) tags.add(SHAPE + spec.shape().tagName());
        for (var material : spec.materials()) tags.add(materialTag(material));
        if (spec.shape() == ZoneShape.BOX) tags.add(BOX + spec.boxX() + "." + spec.boxY() + "." + spec.boxZ());
        return Collections.unmodifiableSet(tags);
    }

    public static String materialTag(String blockId) {
        if (!BlockStateString.ID.matcher(blockId).matches()) throw new IllegalArgumentException("Not a block id: " + blockId);
        return MATERIAL + blockId.replace(':', '+');
    }
    public static Optional<String> materialId(String tag) {
        if (!tag.startsWith(MATERIAL)) return Optional.empty();
        var id = tag.substring(MATERIAL.length());
        if (id.indexOf(':') >= 0) return Optional.empty();
        id = id.replace('+', ':');
        return BlockStateString.ID.matcher(id).matches() ? Optional.of(id) : Optional.empty();
    }

    public static String bindingName(Binding binding) {
        return switch (binding) {
            case POINT -> "点"; case MODEL -> "模型"; case CLICK -> "点击"; case BLOCK -> "方块"; case ZONE -> "区域"; case TEXT -> "文字";
        };
    }

    private static LobbyFunction function(List<String> ids, List<String> errors) {
        if (ids.size() != 1) { errors.add("需要恰好一个功能标签 " + FUNCTION + "<功能>，现在有 " + ids.size() + " 个"); return null; }
        var function = LobbyFunction.byId(ids.getFirst());
        if (function.isEmpty()) errors.add("未知的功能：" + FUNCTION + ids.getFirst());
        return function.orElse(null);
    }
    private static String allowed(LobbyFunction function) {
        return String.join("、", function.bindings().stream().map(MarkerTags::bindingName).toList());
    }
    private static boolean parseBox(String text, int[] out) {
        var parts = text.split("\\.", -1);
        if (parts.length != 3) return false;
        for (int i = 0; i < 3; i++) {
            if (!parts[i].matches("\\d{1,2}")) return false;
            out[i] = Integer.parseInt(parts[i]);
            if (out[i] > MAX_BOX) return false;
        }
        return true;
    }
    /** Stable error order regardless of the entity's tag set iteration order. */
    private static List<String> sorted(Set<String> tags) { return tags.stream().sorted().toList(); }
    private MarkerTags() {}
}
