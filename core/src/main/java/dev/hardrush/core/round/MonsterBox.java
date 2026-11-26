package dev.hardrush.core.round;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What a monster box lets out (docs/plan/怪物盲盒.md §4). The pool grows with the soulcatcher's tier: each tier
 * shares its draws among groups, and a group shares its part among its results. Weights are whole units of
 * {@link #TOTAL} per tier, so the bosses' 1/500 and 1/300 are exact.
 */
public final class MonsterBox {
    /** Soulcatcher tiers: common, uncommon, rare, epic. */
    public static final int TIERS = 4;
    /** Every tier's weights add up to this; 1/500 and 1/300 of it are whole numbers. */
    public static final int TOTAL = 30_000;

    /**
     * Result groups, with each tier's share in units of {@link #TOTAL}. The epic additions take what the other
     * groups leave, so the bosses' exact odds never shift the rest.
     */
    public enum Group {
        ZOMBIES(28_500, 19_500, 13_500, 9_900),
        SKELETONS_AND_SPIDERS(0, 9_000, 8_400, 6_600),
        RARE(0, 0, 6_600, 6_600),
        EPIC(0, 0, 0, -1),
        BOSS_WITHER(0, 0, 0, 60),
        BOSS_WARDEN(0, 0, 0, 100),
        NOVELTY(1_500, 1_500, 1_500, 1_500);

        private final int[] shares;
        Group(int... shares) { this.shares = shares; }
    }

    /** One draw: the creature, or for a jockey the rider and its mount, as the platform builds it. */
    public enum Result {
        ZOMBIE(Group.ZOMBIES, 35, "僵尸"), BABY_ZOMBIE(Group.ZOMBIES, 15, "幼年僵尸"), HUSK(Group.ZOMBIES, 15, "尸壳"),
        DROWNED(Group.ZOMBIES, 12, "溺尸"), ZOMBIE_VILLAGER(Group.ZOMBIES, 12, "僵尸村民"),
        ZOMBIE_HORSEMAN(Group.ZOMBIES, 6, "僵尸骑兵"), CHICKEN_JOCKEY(Group.ZOMBIES, 5, "鸡骑士"),
        SKELETON(Group.SKELETONS_AND_SPIDERS, 22, "骷髅"), SPIDER(Group.SKELETONS_AND_SPIDERS, 20, "蜘蛛"),
        STRAY(Group.SKELETONS_AND_SPIDERS, 10, "流浪者"), BOGGED(Group.SKELETONS_AND_SPIDERS, 10, "沼骸"),
        PARCHED(Group.SKELETONS_AND_SPIDERS, 10, "焦骸"), CAVE_SPIDER(Group.SKELETONS_AND_SPIDERS, 10, "洞穴蜘蛛"),
        SLIME(Group.SKELETONS_AND_SPIDERS, 6, "史莱姆"), MAGMA_CUBE(Group.SKELETONS_AND_SPIDERS, 6, "岩浆怪"),
        SPIDER_JOCKEY(Group.SKELETONS_AND_SPIDERS, 6, "蜘蛛骑士"),
        CREEPER(Group.RARE, 30, "苦力怕"), WITCH(Group.RARE, 15, "女巫"), PILLAGER(Group.RARE, 15, "掠夺者"),
        VINDICATOR(Group.RARE, 15, "卫道士"), WITHER_SKELETON(Group.RARE, 15, "凋灵骷髅"), BLAZE(Group.RARE, 10, "烈焰人"),
        EVOKER(Group.EPIC, 30, "唤魔者"), BREEZE(Group.EPIC, 30, "旋风人"), PIGLIN_BRUTE(Group.EPIC, 25, "猪灵蛮兵"),
        RAVAGER(Group.EPIC, 15, "劫掠兽"),
        WITHER(Group.BOSS_WITHER, 100, "凋灵"), WARDEN(Group.BOSS_WARDEN, 100, "监守者"),
        CHICKEN(Group.NOVELTY, 40, "鸡"), PIG(Group.NOVELTY, 40, "猪"), VILLAGER(Group.NOVELTY, 20, "村民");

        private final Group group;
        /** Percent of its group's share. */
        private final int percent;
        private final String title;
        Result(Group group, int percent, String title) { this.group = group; this.percent = percent; this.title = title; }

        public Group group() { return group; }
        /** The name the owner is told when it comes out. */
        public String title() { return title; }
        /** Worth a fanfare when it comes out: anything from the rare additions up. */
        public boolean notable() {
            return group == Group.RARE || group == Group.EPIC || group == Group.BOSS_WITHER || group == Group.BOSS_WARDEN;
        }
        public boolean boss() { return group == Group.BOSS_WITHER || group == Group.BOSS_WARDEN; }
    }

    private static final List<Map<Result, Integer>> WEIGHTS = weights();

    private MonsterBox() {}

    private static List<Map<Result, Integer>> weights() {
        var tiers = new ArrayList<Map<Result, Integer>>(TIERS);
        for (int tier = 0; tier < TIERS; tier++) {
            int fixed = 0;
            for (var group : Group.values()) if (group.shares[tier] > 0) fixed += group.shares[tier];
            var weights = new EnumMap<Result, Integer>(Result.class);
            for (var result : Result.values()) {
                int share = result.group.shares[tier] < 0 ? TOTAL - fixed : result.group.shares[tier];
                if (share * result.percent % 100 != 0) throw new IllegalStateException("Inexact box weight: " + result);
                if (share > 0) weights.put(result, share * result.percent / 100);
            }
            int sum = weights.values().stream().mapToInt(Integer::intValue).sum();
            if (sum != TOTAL) throw new IllegalStateException("Box tier " + tier + " weighs " + sum);
            tiers.add(Collections.unmodifiableMap(weights));
        }
        return List.copyOf(tiers);
    }

    /** The weight of every result {@code tier} can draw, in units of {@link #TOTAL}. */
    public static Map<Result, Integer> weights(int tier) { return WEIGHTS.get(clamp(tier)); }

    /**
     * The result of {@code roll}, a uniform draw in {@code [0, TOTAL)}, for a soulcatcher of {@code tier} (higher
     * tiers count as epic). Results are walked in declaration order.
     */
    public static Result draw(int tier, int roll) {
        if (roll < 0 || roll >= TOTAL) throw new IllegalArgumentException("Roll out of range: " + roll);
        int left = roll;
        for (var entry : WEIGHTS.get(clamp(tier)).entrySet()) {
            if (left < entry.getValue()) return entry.getKey();
            left -= entry.getValue();
        }
        throw new IllegalStateException("Box weights do not cover " + roll);
    }

    private static int clamp(int tier) {
        if (tier < 0) throw new IllegalArgumentException("Negative soulcatcher tier");
        return Math.min(tier, TIERS - 1);
    }
}
