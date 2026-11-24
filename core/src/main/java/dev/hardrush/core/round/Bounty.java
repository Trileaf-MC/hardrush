package dev.hardrush.core.round;

import dev.hardrush.core.config.RulesConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bounty stars: each human life's threat read against fixed thresholds that follow the speedrun's stages, so a
 * human is only five stars when actually far along, however the others fare. Stars set the damage weight monsters
 * earn coins at, and how hard the human is pressed ({@code HuntPressure}). Only the top bounty is relative.
 * See docs/plan/悬赏分档与追猎强度.md.
 */
public final class Bounty {
    public static final String KILL_POINTS = "bounty.killPoints";
    public static final String STAR2_THREAT = "bounty.star2Threat";
    public static final String STAR3_THREAT = "bounty.star3Threat";
    public static final String STAR4_THREAT = "bounty.star4Threat";
    public static final String STAR5_THREAT = "bounty.star5Threat";
    public static final String STAR2_MULTIPLIER = "bounty.star2Multiplier";
    public static final String STAR3_MULTIPLIER = "bounty.star3Multiplier";
    public static final String STAR4_MULTIPLIER = "bounty.star4Multiplier";
    public static final String STAR5_MULTIPLIER = "bounty.star5Multiplier";
    public static final int MAX_STARS = 5;
    /** The top bounty needs at least this many stars: a leader of humans still in the stone age is no prize. */
    public static final int LEADER_MIN_STARS = 3;
    /** Thresholds for rounds saved before absolute stars: they read with the current defaults. */
    private static final Map<String, Integer> DEFAULT_THRESHOLDS = Map.of(STAR2_THREAT, 8, STAR3_THREAT, 17,
            STAR4_THREAT, 30, STAR5_THREAT, 43);

    /**
     * Native advancements that mark real speedrun progress, weighted by how far along the run they put a
     * human. Every other advancement scores nothing.
     */
    public static final Map<String, Integer> MILESTONES = Map.ofEntries(
            Map.entry("minecraft:story/mine_stone", 1),
            Map.entry("minecraft:story/upgrade_tools", 1),
            Map.entry("minecraft:story/deflect_arrow", 1),
            Map.entry("minecraft:story/smelt_iron", 2),
            Map.entry("minecraft:story/obtain_armor", 2),
            Map.entry("minecraft:story/iron_tools", 2),
            Map.entry("minecraft:story/lava_bucket", 2),
            Map.entry("minecraft:story/form_obsidian", 2),
            Map.entry("minecraft:story/enchant_item", 3),
            Map.entry("minecraft:nether/brew_potion", 3),
            Map.entry("minecraft:story/mine_diamond", 4),
            Map.entry("minecraft:nether/find_fortress", 4),
            Map.entry("minecraft:story/enter_the_nether", 5),
            Map.entry("minecraft:story/shiny_gear", 6),
            Map.entry("minecraft:nether/obtain_blaze_rod", 6),
            Map.entry("minecraft:story/follow_ender_eye", 8),
            Map.entry("minecraft:story/enter_the_end", 10));

    /** A human's place on the bounty board: stars, and whether it is (one of) the top bounty. */
    public record Standing(int threat, int stars, boolean leader) {
        public static final Standing NONE = new Standing(0, 1, false);
    }

    private Bounty() {}

    /** Rounds saved before relative bounties keep paying every hit at the plain rate. */
    public static boolean enabled(RulesConfig rules) {
        return MonsterCoins.enabled(rules) && rules.combatValues().containsKey(STAR5_MULTIPLIER);
    }

    /** The current life's threat; zero for anyone who is not an active human. */
    public static int threat(RulesConfig rules, Participant player) {
        if (!enabled(rules) || player.role() != ParticipantRole.HUMAN_ACTIVE) return 0;
        var life = player.threat().forLife(player.lifeSequence());
        long points = (long) life.monsterKills() * amount(rules, KILL_POINTS);
        for (String milestone : life.milestones()) points += MILESTONES.get(milestone);
        return (int) Math.min(Integer.MAX_VALUE, points);
    }

    /**
     * Every active human's standing. Stars are absolute; the top bounty is the highest threat among online
     * humans, ties included, once that threat reaches {@link #LEADER_MIN_STARS}. An absent human can be
     * neither hunted nor shown, so it never takes the top.
     */
    public static Map<UUID, Standing> standings(RulesConfig rules, Map<UUID, Participant> participants) {
        var standings = new LinkedHashMap<UUID, Standing>();
        if (!enabled(rules)) return standings;
        int top = 0;
        for (var p : participants.values()) if (p.online() && p.role() == ParticipantRole.HUMAN_ACTIVE) top = Math.max(top, threat(rules, p));
        for (var p : participants.values()) {
            if (p.role() != ParticipantRole.HUMAN_ACTIVE) continue;
            int threat = threat(rules, p);
            int stars = stars(rules, threat);
            boolean leader = p.online() && threat == top && stars >= LEADER_MIN_STARS;
            standings.put(p.id(), new Standing(threat, stars, leader));
        }
        return standings;
    }

    public static Standing standing(RulesConfig rules, Map<UUID, Participant> participants, UUID player) {
        return standings(rules, participants).getOrDefault(player, Standing.NONE);
    }

    /** Stars for a threat value. */
    public static int stars(RulesConfig rules, long threat) {
        if (threat >= threshold(rules, STAR5_THREAT)) return 5;
        if (threat >= threshold(rules, STAR4_THREAT)) return 4;
        if (threat >= threshold(rules, STAR3_THREAT)) return 3;
        if (threat >= threshold(rules, STAR2_THREAT)) return 2;
        return 1;
    }

    private static int threshold(RulesConfig rules, String key) {
        Double value = rules.combatValues().get(key);
        return value == null ? DEFAULT_THRESHOLDS.get(key) : (int) Math.round(value);
    }

    /** Damage weight in permille for a star count; one star pays the plain rate. */
    public static int permille(RulesConfig rules, int stars) {
        if (stars < 1 || stars > MAX_STARS) throw new IllegalArgumentException("Invalid bounty stars");
        if (!enabled(rules) || stars == 1) return 1000;
        String key = switch (stars) {
            case 2 -> STAR2_MULTIPLIER; case 3 -> STAR3_MULTIPLIER; case 4 -> STAR4_MULTIPLIER; default -> STAR5_MULTIPLIER;
        };
        return (int) Math.round(value(rules, key) * 1000);
    }

    /** The highest weight any hit may carry in this round. */
    public static int maxPermille(RulesConfig rules) { return permille(rules, MAX_STARS); }

    /** Player-facing stars such as ★★★☆☆. */
    public static String starText(int stars) {
        if (stars < 1 || stars > MAX_STARS) throw new IllegalArgumentException("Invalid bounty stars");
        return "★".repeat(stars) + "☆".repeat(MAX_STARS - stars);
    }

    private static int amount(RulesConfig rules, String key) { return (int) Math.round(value(rules, key)); }

    private static double value(RulesConfig rules, String key) {
        Double value = rules.combatValues().get(key);
        if (value == null) throw new IllegalStateException("本轮规则缺少悬赏参数：" + key);
        return value;
    }
}
