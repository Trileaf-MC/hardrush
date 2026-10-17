package dev.hardrush.core.config;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immutable, versioned rules for one round. Changing the pending configuration
 * never changes a snapshot already attached to a round. The adapter must use
 * the matching Minecraft version for native combat and generation semantics.
 * The constructor validates the declared version; it does not resolve native
 * combat numbers or certify an installed adapter. Callers changing a version
 * must supply that version's verified numeric snapshot rather than reusing
 * the 26.3 numbers from defaults().
 */
public record RulesConfig(
        int schemaVersion,
        GameMode mode,
        String minecraftVersion,
        Difficulty difficulty,
        String generationRule,
        Map<String, Double> combatValues,
        double protectionRadius,
        long startCountdownMillis,
        long successCelebrationMillis,
        long failureDisplayMillis) {
    public static final int CURRENT_SCHEMA_VERSION = 2;
    private static final Set<String> SUPPORTED_VERSIONS = Set.of("26.1.2", "26.2", "26.3");
    private static final Set<String> FIXED_KEYS = Set.of("schemaVersion", "mode", "minecraftVersion",
            "difficulty", "generationRule", "protectionRadius", "startCountdownMillis",
            "successCelebrationMillis", "failureDisplayMillis");
    private static final int MAX_CONFIG_LENGTH = 131_072;

    public RulesConfig {
        if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("不支持的规则配置版本: " + schemaVersion);
        }
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(difficulty, "difficulty");
        minecraftVersion = requireText(minecraftVersion, "minecraftVersion");
        if (!SUPPORTED_VERSIONS.contains(minecraftVersion)) {
            throw new IllegalArgumentException("不支持的 Minecraft 版本: " + minecraftVersion);
        }
        generationRule = requireText(generationRule, "generationRule");
        if (generationRule.length() > 4096) throw new IllegalArgumentException("生成规则描述过长");
        Objects.requireNonNull(combatValues, "combatValues");
        if (combatValues.isEmpty() || combatValues.size() > 256) {
            throw new IllegalArgumentException("战斗参数必须有 1 至 256 项");
        }
        var copy = new TreeMap<String, Double>();
        combatValues.forEach((key, value) -> {
            if (key == null || !key.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,127}")) {
                throw new IllegalArgumentException("非法战斗参数名: " + key);
            }
            if (value == null || !Double.isFinite(value) || value < 0) {
                throw new IllegalArgumentException("战斗参数必须是有限非负数: " + key);
            }
            validateCombatValue(key, value);
            // Numeric equality must not create different leaderboard groups.
            copy.put(key, value == 0 ? 0.0 : value);
        });
        Double summonMin = copy.get("zombie.summonMinDistance");
        Double summonMax = copy.get("zombie.summonMaxDistance");
        if (summonMin != null && summonMax != null && summonMin > summonMax) {
            throw new IllegalArgumentException("召唤最小距离不能大于最大距离");
        }
        combatValues = Collections.unmodifiableSortedMap(copy);
        if (!Double.isFinite(protectionRadius) || protectionRadius <= 0 || protectionRadius > 30_000_000) {
            throw new IllegalArgumentException("保护半径必须为有效的正数");
        }
        requireDuration(startCountdownMillis, "startCountdownMillis");
        requireDuration(successCelebrationMillis, "successCelebrationMillis");
        requireDuration(failureDisplayMillis, "failureDisplayMillis");
    }

    /** Current defaults follow the 2026-10-14 native-role rules, not the retired custom HP/damage table. */
    public static RulesConfig defaults() {
        return new RulesConfig(CURRENT_SCHEMA_VERSION, GameMode.HARDCORE, "26.3", Difficulty.HARD,
                "normal", Map.ofEntries(
                Map.entry("zombie.maxHealth", 20.0),
                Map.entry("zombie.movementAttribute", 0.23),
                Map.entry("zombie.armor", 2.0),
                Map.entry("zombie.baseMeleeDamage", 3.0),
                Map.entry("zombie.meleeIntervalTicks", 20.0),
                Map.entry("zombie.summonCount", 2.0),
                Map.entry("zombie.summonCooldownMillis", 90_000.0),
                Map.entry("zombie.summonMinDistance", 2.0),
                Map.entry("zombie.summonMaxDistance", 4.0),
                Map.entry("skeleton.maxHealth", 20.0),
                Map.entry("skeleton.movementAttribute", 0.25),
                Map.entry("skeleton.armor", 0.0),
                Map.entry("skeleton.bowChargeTicks", 20.0),
                Map.entry("skeleton.burstCount", 3.0),
                Map.entry("skeleton.burstIntervalMillis", 600.0),
                Map.entry("skeleton.burstCooldownMillis", 20_000.0),
                Map.entry("creeper.maxHealth", 20.0),
                Map.entry("creeper.movementAttribute", 0.25),
                Map.entry("creeper.armor", 0.0),
                Map.entry("creeper.fuseTicks", 30.0),
                Map.entry("creeper.explosionStrength", 3.0),
                Map.entry("monster.deployCooldownMillis", 30_000.0),
                Map.entry("monster.returnChannelMillis", 5_000.0),
                Map.entry("round.rescueMillis", 60_000.0)),
                32.0, 30_000, 180_000, 30_000);
    }

    /** Schema 1 is the byte-for-byte historical voting rule; schema 2 is personal direct hunt. */
    public enum HuntRule { LEGACY_VOTE, PERSONAL_TARGET }

    public HuntRule huntRule() {
        return schemaVersion == 1 ? HuntRule.LEGACY_VOTE : HuntRule.PERSONAL_TARGET;
    }

    /** Only for pending/new rounds. Never apply this to an existing round snapshot. */
    public RulesConfig forNewRound() {
        return withHuntRule(HuntRule.PERSONAL_TARGET);
    }

    public RulesConfig withHuntRule(HuntRule rule) {
        return new RulesConfig(Objects.requireNonNull(rule) == HuntRule.LEGACY_VOTE ? 1 : 2,
                mode, minecraftVersion, difficulty, generationRule, combatValues, protectionRadius,
                startCountdownMillis, successCelebrationMillis, failureDisplayMillis);
    }

    public RulesConfig withMode(GameMode nextMode) {
        return new RulesConfig(schemaVersion, nextMode, minecraftVersion, difficulty, generationRule,
                combatValues, protectionRadius, startCountdownMillis, successCelebrationMillis, failureDisplayMillis);
    }

    /** Stable UTF-8 Java-properties representation with no timestamp or iteration-order dependence. */
    public String encode() {
        var values = new TreeMap<String, String>();
        values.put("schemaVersion", Integer.toString(schemaVersion));
        values.put("mode", mode.name());
        values.put("minecraftVersion", minecraftVersion);
        values.put("difficulty", difficulty.name());
        values.put("generationRule", generationRule);
        values.put("protectionRadius", Double.toString(protectionRadius));
        values.put("startCountdownMillis", Long.toString(startCountdownMillis));
        values.put("successCelebrationMillis", Long.toString(successCelebrationMillis));
        values.put("failureDisplayMillis", Long.toString(failureDisplayMillis));
        combatValues.forEach((key, value) -> values.put("combat." + key, Double.toString(value)));
        var result = new StringBuilder();
        values.forEach((key, value) -> result.append(key).append('=').append(escape(value)).append('\n'));
        return result.toString();
    }

    public static RulesConfig decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length() > MAX_CONFIG_LENGTH) throw new IllegalArgumentException("规则配置文件过大");
        var values = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("重复的规则配置字段: " + key);
                return super.put(key, value);
            }
        };
        try {
            values.load(new StringReader(encoded));
        } catch (IOException impossible) {
            throw new IllegalArgumentException("规则配置读取失败", impossible);
        }
        var combat = new TreeMap<String, Double>();
        for (String key : values.stringPropertyNames()) {
            if (key.startsWith("combat.")) {
                combat.put(key.substring("combat.".length()), parseDouble(values.getProperty(key), key));
            } else if (!FIXED_KEYS.contains(key)) {
                throw new IllegalArgumentException("未知规则配置字段: " + key);
            }
        }
        for (String key : FIXED_KEYS) {
            if (!values.containsKey(key)) throw new IllegalArgumentException("缺少规则配置字段: " + key);
        }
        try {
            return new RulesConfig(Integer.parseInt(values.getProperty("schemaVersion")),
                    GameMode.valueOf(values.getProperty("mode")), values.getProperty("minecraftVersion"),
                    Difficulty.valueOf(values.getProperty("difficulty")), values.getProperty("generationRule"),
                    combat, parseDouble(values.getProperty("protectionRadius"), "protectionRadius"),
                    Long.parseLong(values.getProperty("startCountdownMillis")),
                    Long.parseLong(values.getProperty("successCelebrationMillis")),
                    Long.parseLong(values.getProperty("failureDisplayMillis")));
        } catch (NumberFormatException badNumber) {
            throw new IllegalArgumentException("规则配置数值格式错误", badNumber);
        }
    }

    public static RulesConfig read(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (Files.size(path) > MAX_CONFIG_LENGTH) throw new IOException("规则配置文件过大: " + path);
        try {
            return decode(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("非法规则配置，未应用: " + path, invalid);
        }
    }

    public String fingerprint() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(encode().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index == value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new IllegalArgumentException(name + " 含非法 Unicode 字符");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException(name + " 含非法 Unicode 字符");
            }
        }
        return value;
    }

    private static void requireDuration(long millis, String name) {
        // Timers are converted to nanoseconds. Reject configurations that would overflow.
        if (millis <= 0 || millis > Long.MAX_VALUE / 1_000_000) {
            throw new IllegalArgumentException(name + " 必须是可计时的正毫秒数");
        }
    }

    private static double parseDouble(String value, String key) {
        try { return Double.parseDouble(value); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("非法数值: " + key, invalid); }
    }

    private static void validateCombatValue(String key, double value) {
        if (value == 0 && !key.endsWith("armor") && !key.endsWith("Resistance")) {
            throw new IllegalArgumentException("战斗参数必须为正数（护甲／抗性可为零）: " + key);
        }
        if ((key.endsWith("Health") || key.endsWith("Damage") || key.endsWith("Attribute")
                || key.endsWith("Distance") || key.endsWith("Strength")) && value <= 0) {
            throw new IllegalArgumentException("战斗属性必须为正数: " + key);
        }
        if (key.endsWith("Count") || key.endsWith("Ticks") || key.endsWith("Millis")) {
            long maximum = key.endsWith("Millis") ? Long.MAX_VALUE / 1_000_000 : Integer.MAX_VALUE;
            if (value <= 0 || value != Math.rint(value) || value > maximum) {
                throw new IllegalArgumentException("次数或时间必须是可用的正整数: " + key);
            }
        }
        if (key.equals("zombie.summonMaxDistance") && value > 30_000_000) {
            throw new IllegalArgumentException("召唤距离超出有效世界范围");
        }
    }

    private static String escape(String value) {
        var escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\f' -> escaped.append("\\f");
                case ' ' -> { if (i == 0) escaped.append('\\'); escaped.append(c); }
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
