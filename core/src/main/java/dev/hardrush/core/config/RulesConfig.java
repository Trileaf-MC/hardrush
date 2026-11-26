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
        long startCountdownMillis,
        long successCelebrationMillis,
        long failureDisplayMillis) {
    public static final int CURRENT_SCHEMA_VERSION = 2;
    private static final Set<String> SUPPORTED_VERSIONS = Set.of("26.1.2", "26.2", "26.3");
    private static final Set<String> FIXED_KEYS = Set.of("schemaVersion", "mode", "minecraftVersion",
            "difficulty", "generationRule", "startCountdownMillis",
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
        for (var box : java.util.List.of("negative", "common", "rare", "epic")) {
            Double min = copy.get("coin.potionBox." + box + "MinSeconds"), max = copy.get("coin.potionBox." + box + "MaxSeconds");
            if (min != null && max != null && min > max) throw new IllegalArgumentException("盲盒最短时长不能大于最长时长: " + box);
        }
        Double summonMin = copy.get("zombie.summonMinDistance");
        Double summonMax = copy.get("zombie.summonMaxDistance");
        if (summonMin != null && summonMax != null && summonMin > summonMax) {
            throw new IllegalArgumentException("召唤最小距离不能大于最大距离");
        }
        copy.forEach((key, value) -> {
            if (!key.startsWith("coin.price.")) return;
            String limitKey = "coin.limit." + key.substring("coin.price.".length());
            Double limit = copy.get(limitKey);
            // The highest price must still fit the trade's two payment slots, one native emerald stack each.
            boolean flat = offerLimitedBy(limitKey).filter(dev.hardrush.core.round.ShopOffer::flatPrice).isPresent();
            if (limit == null || value * (flat ? 1 : Math.pow(2, limit - 1)) > 128) {
                throw new IllegalArgumentException("怪物币最高价不能超过 128，且需配置购买上限: " + key);
            }
        });
        if (copy.keySet().stream().anyMatch(key -> key.startsWith("worth."))) {
            if (!copy.keySet().containsAll(WORTH_DEFAULTS.keySet())) {
                throw new IllegalArgumentException("身价参数必须完整配置: " + WORTH_DEFAULTS.keySet());
            }
            if (copy.get("worth.baseLevel") > copy.get("worth.maxLevel")) {
                throw new IllegalArgumentException("身价基础等级不能超过身价上限");
            }
        }
        if (copy.keySet().stream().anyMatch(key -> key.startsWith("bounty.") && !retiredKey(key))) {
            if (!copy.keySet().containsAll(BOUNTY_CORE_KEYS)) {
                throw new IllegalArgumentException("悬赏参数必须完整配置: " + BOUNTY_CORE_KEYS);
            }
            var thresholds = java.util.List.of("bounty.star2Threat", "bounty.star3Threat", "bounty.star4Threat", "bounty.star5Threat");
            long present = thresholds.stream().filter(copy::containsKey).count();
            // Rounds saved before absolute stars carry none and read the current defaults.
            if (present != 0 && present != thresholds.size()) throw new IllegalArgumentException("悬赏星级阈值必须完整配置: " + thresholds);
            if (present != 0 && !(copy.get("bounty.star2Threat") < copy.get("bounty.star3Threat")
                    && copy.get("bounty.star3Threat") < copy.get("bounty.star4Threat")
                    && copy.get("bounty.star4Threat") < copy.get("bounty.star5Threat"))) {
                throw new IllegalArgumentException("悬赏星级阈值必须逐级递增");
            }
            if (!(copy.get("bounty.star2Multiplier") <= copy.get("bounty.star3Multiplier")
                    && copy.get("bounty.star3Multiplier") <= copy.get("bounty.star4Multiplier")
                    && copy.get("bounty.star4Multiplier") <= copy.get("bounty.star5Multiplier"))) {
                throw new IllegalArgumentException("悬赏倍率必须逐级不减");
            }
        }
        combatValues = Collections.unmodifiableSortedMap(copy);
        requireDuration(startCountdownMillis, "startCountdownMillis");
        requireDuration(successCelebrationMillis, "successCelebrationMillis");
        requireDuration(failureDisplayMillis, "failureDisplayMillis");
    }

    /** New rounds use the current native roles; retired summon keys remain valid when reading older rounds. */
    public static RulesConfig defaults() {
        return new RulesConfig(CURRENT_SCHEMA_VERSION, GameMode.HARDCORE, "26.3", Difficulty.HARD,
                "normal", Map.ofEntries(
                Map.entry("zombie.maxHealth", 20.0),
                Map.entry("zombie.movementAttribute", 0.23),
                Map.entry("zombie.armor", 2.0),
                Map.entry("zombie.baseMeleeDamage", 3.0),
                Map.entry("zombie.meleeIntervalTicks", 20.0),
                Map.entry("skeleton.maxHealth", 20.0),
                Map.entry("skeleton.movementAttribute", 0.25),
                Map.entry("skeleton.armor", 0.0),
                Map.entry("skeleton.bowChargeTicks", 20.0),
                Map.entry("skeleton.burstCount", 3.0),
                Map.entry("skeleton.burstIntervalMillis", 200.0),
                Map.entry("creeper.maxHealth", 20.0),
                Map.entry("creeper.movementAttribute", 0.25),
                Map.entry("creeper.armor", 0.0),
                Map.entry("creeper.fuseTicks", 30.0),
                Map.entry("creeper.explosionStrength", 3.0),
                Map.entry("monster.deployCooldownMillis", 45_000.0),
                Map.entry("monster.returnChannelMillis", 4_000.0),
                Map.entry("round.rescueMillis", 60_000.0)),
                30_000, 90_000, 30_000).withEconomyDefaults();
    }

    /** Longer blessings that still lengthen the altar's blessing before it reaches its five-minute cap. */
    private static final int LONGER_BLESSING_STEPS = (int) Math.ceil(
            (double) (dev.hardrush.core.round.WitchRecord.MAX_BLESSING_MILLIS - dev.hardrush.core.round.WitchRecord.BLESSING_MILLIS)
                    / dev.hardrush.core.round.WitchRecord.BLESSING_STEP_MILLIS);

    /** Monster coin economy; each offer's price doubles per purchase within one sortie. */
    private static final Map<String, Double> COIN_DEFAULTS = Map.ofEntries(
            Map.entry("coin.starter", 6.0),
            // One creeper sortie ticket, see docs/plan/苦力怕付费解锁.md.
            Map.entry("coin.creeperUnlock", 40.0),
            // Coins for a monster a human killed, by the killer's stars; bought back as sortie upgrades.
            Map.entry("coin.killedByHuman.star1", 8.0), Map.entry("coin.killedByHuman.star2", 10.0),
            Map.entry("coin.killedByHuman.star3", 13.0), Map.entry("coin.killedByHuman.star4", 16.0),
            Map.entry("coin.killedByHuman.star5", 20.0),
            // A human's head, by its stars, for the monster that killed it or forced its totem. That monster
            // holds a revival totem next, so the witch only sells it revival supplies: priced against those.
            Map.entry("coin.headBounty.star2", 40.0), Map.entry("coin.headBounty.star3", 80.0),
            Map.entry("coin.headBounty.star4", 150.0), Map.entry("coin.headBounty.star5", 250.0),
            Map.entry("coin.damageCapPerHuman", 20.0),
            Map.entry("coin.arrowsPerBundle", 4.0),
            // Witch potions and blind boxes (docs/plan/女巫药水与药水盲盒.md); the turtle master keeps its native 20 seconds.
            Map.entry("coin.potion.swiftnessSeconds", 180.0),
            Map.entry("coin.potion.fireResistanceSeconds", 180.0),
            Map.entry("coin.potion.absorptionSeconds", 120.0),
            Map.entry("coin.potion.resistanceSeconds", 90.0),
            Map.entry("coin.potion.poisonSeconds", 45.0),
            Map.entry("coin.potionBox.negativePermille", 150.0),
            Map.entry("coin.potionBox.negativeMinSeconds", 20.0), Map.entry("coin.potionBox.negativeMaxSeconds", 45.0),
            Map.entry("coin.potionBox.commonLevelTwoPermille", 100.0),
            Map.entry("coin.potionBox.commonMinSeconds", 45.0), Map.entry("coin.potionBox.commonMaxSeconds", 90.0),
            Map.entry("coin.potionBox.rareLevelTwoPermille", 300.0),
            Map.entry("coin.potionBox.rareMinSeconds", 60.0), Map.entry("coin.potionBox.rareMaxSeconds", 120.0),
            Map.entry("coin.potionBox.epicLevelTwoPermille", 500.0),
            Map.entry("coin.potionBox.epicMinSeconds", 90.0), Map.entry("coin.potionBox.epicMaxSeconds", 180.0),
            Map.entry("coin.price.adultEgg", 8.0), Map.entry("coin.limit.adultEgg", 3.0),
            Map.entry("coin.price.babyEgg", 20.0), Map.entry("coin.limit.babyEgg", 2.0),
            // 25 doubles to 100 on the third box; a fourth would pass the trade's 128-coin ceiling.
            Map.entry("coin.price.monsterBox", 25.0), Map.entry("coin.limit.monsterBox", 3.0),
            Map.entry("coin.price.swiftness", 10.0), Map.entry("coin.limit.swiftness", 1.0),
            Map.entry("coin.price.fireResistance", 5.0), Map.entry("coin.limit.fireResistance", 1.0),
            // Every bottle at the base price: the doubling ladder skips it (ShopOffer.flatPrice).
            Map.entry("coin.price.instantHealth", 4.0), Map.entry("coin.limit.instantHealth", 2.0),
            Map.entry("coin.price.absorption", 12.0), Map.entry("coin.limit.absorption", 1.0),
            Map.entry("coin.price.resistance", 12.0), Map.entry("coin.limit.resistance", 1.0),
            Map.entry("coin.price.turtleMaster", 12.0), Map.entry("coin.limit.turtleMaster", 1.0),
            Map.entry("coin.price.poison", 8.0), Map.entry("coin.limit.poison", 1.0),
            Map.entry("coin.price.commonPotionBox", 8.0), Map.entry("coin.limit.commonPotionBox", 3.0),
            Map.entry("coin.price.rarePotionBox", 18.0), Map.entry("coin.limit.rarePotionBox", 2.0),
            Map.entry("coin.price.epicPotionBox", 35.0), Map.entry("coin.limit.epicPotionBox", 1.0),
            Map.entry("coin.price.slownessArrows", 8.0), Map.entry("coin.limit.slownessArrows", 2.0),
            Map.entry("coin.price.poisonArrows", 10.0), Map.entry("coin.limit.poisonArrows", 2.0),
            Map.entry("coin.price.charged", 40.0), Map.entry("coin.limit.charged", 1.0),
            // Keys keep one price; the limit matches the camp's gear cap (MonsterWallet.MAX_GEAR), so coins bound them.
            Map.entry("coin.price.trialKey", 3.0), Map.entry("coin.limit.trialKey", 16.0),
            Map.entry("coin.price.ominousKey", 8.0), Map.entry("coin.limit.ominousKey", 16.0),
            // 32 is the highest base whose third step (128) still fits the trade's two payment slots.
            Map.entry("coin.price.soulcatcherTier", 32.0), Map.entry("coin.limit.soulcatcherTier", 3.0),
            Map.entry("coin.price.soulcatcherCapacity", 20.0), Map.entry("coin.limit.soulcatcherCapacity", 3.0),
            // Revival supplies (docs/plan/复生补给与女巫说明.md); the doubling ladder applies to repeat purchases.
            Map.entry("coin.price.bonusChest", 30.0), Map.entry("coin.limit.bonusChest", 1.0),
            Map.entry("coin.price.longerBlessing", 30.0), Map.entry("coin.limit.longerBlessing", 3.0),
            Map.entry("coin.price.ironBucket", 15.0), Map.entry("coin.limit.ironBucket", 1.0),
            Map.entry("coin.price.oakLogs", 10.0), Map.entry("coin.limit.oakLogs", 3.0),
            Map.entry("coin.price.ironPickaxe", 35.0), Map.entry("coin.limit.ironPickaxe", 1.0),
            Map.entry("coin.price.shield", 35.0), Map.entry("coin.limit.shield", 1.0),
            Map.entry("coin.price.bell", 45.0), Map.entry("coin.limit.bell", 1.0),
            Map.entry("coin.price.bread", 30.0), Map.entry("coin.limit.bread", 3.0));

    /**
     * Absolute bounty (docs/plan/悬赏分档与追猎强度.md): killing a monster player scores 2, milestones dominate.
     * Stars step at fixed threats that follow the speedrun's stages, and five stars weigh damage at ×2.5.
     */
    private static final Map<String, Double> BOUNTY_DEFAULTS = Map.ofEntries(
            Map.entry("bounty.killPoints", 2.0),
            Map.entry("bounty.star2Threat", 8.0),
            Map.entry("bounty.star3Threat", 17.0),
            Map.entry("bounty.star4Threat", 30.0),
            Map.entry("bounty.star5Threat", 43.0),
            Map.entry("bounty.star2Multiplier", 1.25),
            Map.entry("bounty.star3Multiplier", 1.5),
            Map.entry("bounty.star4Multiplier", 2.0),
            Map.entry("bounty.star5Multiplier", 2.5));
    /** Keys every round with bounties carries, before and after absolute stars. */
    private static final Set<String> BOUNTY_CORE_KEYS = Set.of("bounty.killPoints", "bounty.star2Multiplier",
            "bounty.star3Multiplier", "bounty.star4Multiplier", "bounty.star5Multiplier");

    /**
     * Retired keys: rounds saved with them still decode, pending rules drop them. Coin bounty and survival keys
     * predate relative bounties; the floor, ratios and hunted points predate absolute stars; the flat coins for a
     * slain monster and the single volley cooldown predate stars deciding them.
     */
    private static boolean retiredKey(String key) {
        return key.startsWith("coin.bounty.") || key.startsWith("coin.survival.") || key.equals("bounty.floor")
                || key.startsWith("bounty.") && key.endsWith("Ratio") || key.equals("bounty.huntedPoints")
                || key.equals("coin.killedByHuman") || key.equals("skeleton.burstCooldownMillis")
                || key.equals("coin.price.regeneration") || key.equals("coin.limit.regeneration")
                || key.equals("coin.potion.regenerationSeconds");
    }

    /** Worth runs from level 10 to 30, one level per damage point; a slain body sprays 10 experience per level and leaves a book from level 15. */
    private static final Map<String, Double> WORTH_DEFAULTS = Map.ofEntries(
            Map.entry("worth.baseLevel", 10.0),
            Map.entry("worth.levelsPerDamage", 1.0),
            Map.entry("worth.maxLevel", 30.0),
            Map.entry("worth.xpPerLevel", 10.0),
            Map.entry("worth.bookMinLevel", 15.0));

    /**
     * A deliberate return pauses the sortie, so hunting again may follow at once; a defeat only locks the human
     * who won it (docs/plan/击败锁定与怪物平衡.md). The tracking compass channels five seconds and rests ten after each landing. Natural ground is dug no slower than
     * bare-handed stone (7.5 seconds); a block whose bare-handed time exceeds 30 seconds is too hard to dig.
     * A zombie's soulcatcher holds three creatures before the witch enlarges it. A round whose humans
     * have all left waits three minutes for one of them: long enough to restart a client and rejoin.
     * A mid-round entrant's first life cannot be chosen as a hunt target for five minutes of its own human time.
     */
    private static final Map<String, Double> SORTIE_DEFAULTS = Map.of("monster.returnCooldownMillis", 0.0,
            "monster.retrackCooldownMillis", 10_000.0, "monster.retrackChannelMillis", 5_000.0, "monster.digMillis", 7_500.0,
            "monster.digMaxMillis", 30_000.0, "soulcatcher.capacity", 3.0, "round.reconnectMillis", 180_000.0,
            "entry.lateShelterMillis", 300_000.0);

    /**
     * Revision of the defaults that stored pending rules have been migrated to; rules stored before revisions were
     * recorded read as revision 1. A migration runs once, when pending rules are first read under a newer revision:
     * after that the revision is stored with them, so an operator may set any value again, an earlier default included.
     */
    public static final String DEFAULTS_REVISION_KEY = "defaults.revision";

    /**
     * Each revision's retuned defaults, from revision 2 on: a value still on the previous default moves to the new
     * one. Rules stored at an older revision replay every later step in order, each step at most once.
     */
    private static final java.util.List<Map<String, Map.Entry<Double, Double>>> RETUNED_DEFAULTS = java.util.List.of(
            // Revision 2 (docs/plan/击败锁定与怪物平衡.md).
            Map.of("monster.deployCooldownMillis", Map.entry(30_000.0, 45_000.0),
                    "monster.returnChannelMillis", Map.entry(5_000.0, 4_000.0),
                    "coin.killedByHuman", Map.entry(5.0, 8.0)),
            // Revision 3: one random piece of gear priced against a spawn egg, not above it.
            Map.of("coin.price.trialKey", Map.entry(15.0, 5.0),
                    "coin.price.ominousKey", Map.entry(40.0, 12.0)),
            // Revision 4 (docs/plan/悬赏分档与追猎强度.md): kills score well below milestones; a tighter volley.
            Map.of("bounty.killPoints", Map.entry(3.0, 2.0),
                    "skeleton.burstIntervalMillis", Map.entry(300.0, 200.0)),
            // Revision 5 (issue #88): vault keys are cheaper, flat-priced and bought as often as the camp holds gear.
            Map.of("coin.price.trialKey", Map.entry(5.0, 3.0), "coin.limit.trialKey", Map.entry(2.0, 16.0),
                    "coin.price.ominousKey", Map.entry(12.0, 8.0), "coin.limit.ominousKey", Map.entry(1.0, 16.0)),
            // Revision 6 (docs/plan/怪物盲盒.md): the monster box arrives; eggs and soulcatcher tiers cost a little more.
            Map.of("coin.price.adultEgg", Map.entry(6.0, 8.0),
                    "coin.price.babyEgg", Map.entry(16.0, 20.0),
                    "coin.price.soulcatcherTier", Map.entry(30.0, 32.0)),
            // Revision 7 (docs/plan/女巫药水与药水盲盒.md): fire resistance becomes a cheap staple.
            Map.of("coin.price.fireResistance", Map.entry(6.0, 5.0)));
    private static final int DEFAULTS_REVISION = 1 + RETUNED_DEFAULTS.size();

    private int defaultsRevision() {
        return combatValues.getOrDefault(DEFAULTS_REVISION_KEY, 1.0).intValue();
    }

    /**
     * Adds missing coin, worth and sortie values to stored pending rules and migrates them to the current
     * {@link #DEFAULTS_REVISION} once; values an operator already set are otherwise kept.
     */
    public RulesConfig withEconomyDefaults() {
        boolean current = defaultsRevision() >= DEFAULTS_REVISION;
        if (current && COIN_DEFAULTS.keySet().stream().allMatch(combatValues::containsKey)
                && WORTH_DEFAULTS.keySet().stream().allMatch(combatValues::containsKey)
                && SORTIE_DEFAULTS.keySet().stream().allMatch(combatValues::containsKey)
                && BOUNTY_DEFAULTS.keySet().stream().allMatch(combatValues::containsKey)
                && combatValues.keySet().stream().noneMatch(RulesConfig::retiredKey)) return this;
        var values = new TreeMap<>(combatValues);
        values.keySet().removeIf(RulesConfig::retiredKey);
        if (!current) {
            for (int revision = Math.max(defaultsRevision(), 1) + 1; revision <= DEFAULTS_REVISION; revision++) {
                RETUNED_DEFAULTS.get(revision - 2).forEach((key, change) -> values.replace(key, change.getKey(), change.getValue()));
            }
            values.put(DEFAULTS_REVISION_KEY, (double) DEFAULTS_REVISION);
        }
        BOUNTY_DEFAULTS.forEach(values::putIfAbsent);
        COIN_DEFAULTS.forEach(values::putIfAbsent);
        WORTH_DEFAULTS.forEach(values::putIfAbsent);
        SORTIE_DEFAULTS.forEach(values::putIfAbsent);
        return new RulesConfig(schemaVersion, mode, minecraftVersion, difficulty, generationRule, values,
                startCountdownMillis, successCelebrationMillis, failureDisplayMillis);
    }

    /** Schema 1 is the byte-for-byte historical voting rule; schema 2 is personal direct hunt. */
    public enum HuntRule { LEGACY_VOTE, PERSONAL_TARGET }

    public HuntRule huntRule() {
        return schemaVersion == 1 ? HuntRule.LEGACY_VOTE : HuntRule.PERSONAL_TARGET;
    }

    /** Only for pending/new rounds. Never apply this to an existing round snapshot. */
    public RulesConfig forNewRound() {
        return withHuntRule(HuntRule.PERSONAL_TARGET).withEconomyDefaults();
    }

    public RulesConfig withHuntRule(HuntRule rule) {
        return new RulesConfig(Objects.requireNonNull(rule) == HuntRule.LEGACY_VOTE ? 1 : 2,
                mode, minecraftVersion, difficulty, generationRule, combatValues,
                startCountdownMillis, successCelebrationMillis, failureDisplayMillis);
    }

    public RulesConfig withDifficulty(Difficulty nextDifficulty) {
        return new RulesConfig(schemaVersion, mode, minecraftVersion, Objects.requireNonNull(nextDifficulty), generationRule,
                combatValues, startCountdownMillis, successCelebrationMillis, failureDisplayMillis);
    }

    public RulesConfig withMode(GameMode nextMode) {
        return new RulesConfig(schemaVersion, nextMode, minecraftVersion, difficulty, generationRule,
                combatValues, startCountdownMillis, successCelebrationMillis, failureDisplayMillis);
    }

    /** Stable UTF-8 Java-properties representation with no timestamp or iteration-order dependence. */
    public String encode() {
        var values = new TreeMap<String, String>();
        values.put("schemaVersion", Integer.toString(schemaVersion));
        values.put("mode", mode.name());
        values.put("minecraftVersion", minecraftVersion);
        values.put("difficulty", difficulty.name());
        values.put("generationRule", generationRule);
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
                    combat, Long.parseLong(values.getProperty("startCountdownMillis")),
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
        // An immediate re-hunt after a deliberate return is the default, not a misconfiguration.
        if (value == 0 && key.equals("monster.returnCooldownMillis")) return;
        // A blind box chance of zero turns that draw off (docs/plan/女巫药水与药水盲盒.md §3.3).
        boolean chance = key.startsWith("coin.potionBox.") && key.endsWith("Permille");
        if (value == 0 && !key.endsWith("armor") && !key.endsWith("Resistance") && !chance) {
            throw new IllegalArgumentException("战斗参数必须为正数（护甲／抗性／盲盒概率可为零）: " + key);
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
        if (key.startsWith("coin.") && !retiredKey(key)
                && (value != Math.rint(value) || value > Integer.MAX_VALUE)) {
            throw new IllegalArgumentException("怪物币参数必须是整数: " + key);
        }
        if (key.startsWith("worth.") && (value != Math.rint(value) || value > 1_000_000)) {
            throw new IllegalArgumentException("身价参数必须是不超过 1000000 的整数: " + key);
        }
        if ((key.endsWith("Points") || key.endsWith("Threat") || key.equals("bounty.floor")) && key.startsWith("bounty.")
                && (value != Math.rint(value) || value > 1_000_000)) {
            throw new IllegalArgumentException("悬赏分值必须是不超过 1000000 的正整数: " + key);
        }
        if (key.startsWith("bounty.") && key.endsWith("Ratio") && value >= 1) {
            throw new IllegalArgumentException("悬赏星级比例必须在 0 与 1 之间: " + key);
        }
        if (key.startsWith("bounty.") && key.endsWith("Multiplier") && (value < 1 || value > 100)) {
            throw new IllegalArgumentException("悬赏倍率必须在 1 与 100 之间: " + key);
        }
        // The doubling ladder caps how far a price may climb; flat-priced vault keys are bounded by the camp's gear instead.
        if (offerLimitedBy(key).filter(dev.hardrush.core.round.ShopOffer::key).isPresent()) {
            int gear = dev.hardrush.core.round.MonsterWallet.MAX_GEAR;
            if (value > gear) throw new IllegalArgumentException("钥匙限购不能超过营地装备上限 " + gear + ": " + key);
        } else if (key.startsWith("coin.limit.") && value > 8) throw new IllegalArgumentException("单次出场购买上限不能超过 8: " + key);
        // Revival supplies delivered once per life: a second purchase would be paid for and never handed out.
        if (java.util.Set.of("coin.limit.bonusChest", "coin.limit.ironBucket", "coin.limit.ironPickaxe", "coin.limit.shield").contains(key)
                && value > 1) throw new IllegalArgumentException("只交付一次的复生补给限购只能为 1: " + key);
        if (key.equals("coin.limit.longerBlessing") && value > LONGER_BLESSING_STEPS)
            throw new IllegalArgumentException("延长庇护的档数不能超过上限能容纳的 " + LONGER_BLESSING_STEPS + " 档: " + key);
        if (key.startsWith("coin.potionBox.") && key.endsWith("Permille") && value > 1000)
            throw new IllegalArgumentException("盲盒概率以千分比表示，不能超过 1000: " + key);
        if (key.startsWith("coin.potionBox.") && key.endsWith("Seconds") && value > dev.hardrush.core.round.PotionBox.MAX_SECONDS)
            throw new IllegalArgumentException("盲盒效果时长不能超过 " + dev.hardrush.core.round.PotionBox.MAX_SECONDS + " 秒: " + key);
        if (key.equals("zombie.summonMaxDistance") && value > 30_000_000) {
            throw new IllegalArgumentException("召唤距离超出有效世界范围");
        }
    }

    private static java.util.Optional<dev.hardrush.core.round.ShopOffer> offerLimitedBy(String key) {
        for (var offer : dev.hardrush.core.round.ShopOffer.values()) if (offer.limitKey().equals(key)) return java.util.Optional.of(offer);
        return java.util.Optional.empty();
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
