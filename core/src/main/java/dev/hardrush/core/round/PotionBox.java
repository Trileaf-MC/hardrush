package dev.hardrush.core.round;

import dev.hardrush.core.config.RulesConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * The witch's blind boxes (docs/plan/女巫药水与药水盲盒.md): a bottle without effects whose contents are drawn the
 * moment it is drunk, for the form drinking it. A common box draws once from the common pool, a rare one adds the
 * rare pool and an epic one adds the epic pool; each bottle may trade its common draw for a negative one, at the same
 * chance and strength whatever the box.
 */
public enum PotionBox {
    COMMON("common", PotionBrew.Grade.COMMON),
    RARE("rare", PotionBrew.Grade.COMMON, PotionBrew.Grade.RARE),
    EPIC("epic", PotionBrew.Grade.COMMON, PotionBrew.Grade.RARE, PotionBrew.Grade.EPIC);

    public static final String NEGATIVE_PERMILLE = "coin.potionBox.negativePermille";
    public static final String NEGATIVE_MIN_SECONDS = "coin.potionBox.negativeMinSeconds";
    public static final String NEGATIVE_MAX_SECONDS = "coin.potionBox.negativeMaxSeconds";
    /** No drawn effect with a duration lasts longer. */
    public static final int MAX_SECONDS = 300;
    /** A dose's {@code seconds} for an effect that lasts until the monster dies ({@link PotionBrew#untilDeath()}). */
    public static final int UNTIL_DEATH = -1;

    private final String id;
    private final List<PotionBrew.Grade> pools;

    PotionBox(String id, PotionBrew.Grade... pools) { this.id = id; this.pools = List.of(pools); }

    public List<PotionBrew.Grade> pools() { return pools; }
    public String levelTwoKey() { return "coin.potionBox." + id + "LevelTwoPermille"; }
    public String minSecondsKey() { return "coin.potionBox." + id + "MinSeconds"; }
    public String maxSecondsKey() { return "coin.potionBox." + id + "MaxSeconds"; }

    /**
     * One drawn effect: {@code level} 0 is I and 1 is II; {@code seconds} is 0 for an instant effect and
     * {@link #UNTIL_DEATH} for a parting gift.
     */
    public record Dose(PotionBrew brew, PotionBrew.Grade grade, int level, int seconds) {
        public Dose {
            Objects.requireNonNull(brew); Objects.requireNonNull(grade);
            if (level < 0 || level > 1 || seconds < UNTIL_DEATH || seconds > MAX_SECONDS) throw new IllegalArgumentException("Invalid dose");
            if (brew.instant() != (seconds == 0)) throw new IllegalArgumentException("Instant effects have no duration");
            if (brew.untilDeath() != (seconds == UNTIL_DEATH)) throw new IllegalArgumentException("Parting gifts last until death, nothing else does");
        }
    }

    /** Rounds saved before the boxes existed carry none of their values; the witch never stocks one then. */
    public static boolean configured(RulesConfig rules) {
        for (var box : values()) {
            for (var key : List.of(box.levelTwoKey(), box.minSecondsKey(), box.maxSecondsKey())) {
                if (!rules.combatValues().containsKey(key)) return false;
            }
        }
        return rules.combatValues().containsKey(NEGATIVE_PERMILLE) && rules.combatValues().containsKey(NEGATIVE_MIN_SECONDS)
                && rules.combatValues().containsKey(NEGATIVE_MAX_SECONDS);
    }

    /** Best first: epic, rare, then the common draw or the negative that replaced it. */
    public List<Dose> draw(RulesConfig rules, MonsterRole role, RandomGenerator random) {
        Objects.requireNonNull(role); Objects.requireNonNull(random);
        var doses = new ArrayList<Dose>();
        for (int i = pools.size() - 1; i >= 0; i--) {
            var grade = pools.get(i);
            if (grade == PotionBrew.Grade.COMMON && random.nextInt(1000) < MonsterCoins.amount(rules, NEGATIVE_PERMILLE)) {
                var brew = pick(PotionBrew.Grade.NEGATIVE, role, random);
                doses.add(new Dose(brew, PotionBrew.Grade.NEGATIVE, 0, seconds(brew, random,
                        MonsterCoins.amount(rules, NEGATIVE_MIN_SECONDS), MonsterCoins.amount(rules, NEGATIVE_MAX_SECONDS))));
                continue;
            }
            var brew = pick(grade, role, random);
            int level = brew.leveled() && random.nextInt(1000) < MonsterCoins.amount(rules, levelTwoKey()) ? 1 : 0;
            doses.add(new Dose(brew, grade, level, seconds(brew, random,
                    MonsterCoins.amount(rules, minSecondsKey()), MonsterCoins.amount(rules, maxSecondsKey()))));
        }
        return List.copyOf(doses);
    }

    private static PotionBrew pick(PotionBrew.Grade grade, MonsterRole role, RandomGenerator random) {
        var pool = PotionBrew.pool(grade, role);
        int roll = random.nextInt(pool.stream().mapToInt(PotionBrew::weight).sum());
        for (var brew : pool) {
            roll -= brew.weight();
            if (roll < 0) return brew;
        }
        throw new IllegalStateException("Weighted pick ran past its pool");
    }

    /** Instant effects have none and parting gifts last until death; both skip the random source. */
    private static int seconds(PotionBrew brew, RandomGenerator random, int min, int max) {
        if (brew.instant()) return 0;
        if (brew.untilDeath()) return UNTIL_DEATH;
        if (brew.fixedSeconds() > 0) return brew.fixedSeconds();
        int seconds = min + random.nextInt(max - min + 1);
        return Math.min(Math.min(seconds, MAX_SECONDS), brew.maxSeconds());
    }
}
