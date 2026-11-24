package dev.hardrush.core.round;

import java.util.List;
import java.util.Optional;

/**
 * How hard a human is pressed, by its bounty stars (docs/plan/悬赏分档与追猎强度.md). Monster strength itself stays
 * fixed; what scales is how many monsters may hunt the human at once, how long a fallen monster waits before
 * hunting it again, and what a sortie onto it carries. Every display, gate and kit reads these tables, so tuning
 * the balance happens here alone.
 *
 * <p>A sortie's <em>tier</em> is the stars of the human it was sent onto when it landed; a resumed pause takes the
 * lower of the paused tier and the new quarry's stars ({@link #resumedTier}).
 */
public final class HuntPressure {
    /** Minimum bounty stars a paid creeper may hunt. */
    public static final int CREEPER_MIN_STARS = 3;

    private static final long[] LOCK_MILLIS = {120_000, 90_000, 60_000, 45_000, 30_000};
    private static final int[] STARTER_ADULT_EGGS = {0, 1, 2, 3, 3};
    private static final long[] VOLLEY_COOLDOWN_MILLIS = {40_000, 30_000, 20_000, 10_000, 10_000};

    /**
     * A sortie's spawn armour, rolled like vanilla's {@code Mob.populateDefaultEquipmentSlots}: one chance to wear any,
     * one material for the whole set, pieces from the helmet down with a chance to stop after each.
     *
     * @param materialWeights weights over vanilla's {@code Mob.getEquipmentForSlot} materials (0 leather, 1 copper,
     *                        2 gold, 3 chainmail, 4 iron, 5 diamond); empty means vanilla's own roll, a random base of
     *                        0–2 upgraded three times at 10.87% each
     */
    public record ArmorRoll(float wearChance, float stopChance, Optional<List<Integer>> materialWeights) {
        public static final int MATERIALS = 6;

        public ArmorRoll {
            materialWeights = materialWeights.map(List::copyOf);
            if (wearChance < 0 || wearChance > 1 || stopChance < 0 || stopChance > 1) throw new IllegalArgumentException("Invalid armour chances");
            materialWeights.ifPresent(weights -> {
                if (weights.size() != MATERIALS || weights.stream().anyMatch(weight -> weight < 0) || weights.stream().mapToInt(Integer::intValue).sum() <= 0)
                    throw new IllegalArgumentException("Invalid armour material weights");
            });
        }
    }

    /** Vanilla on hard with local difficulty at its peak: what every sortie wore before tiers. */
    private static final ArmorRoll VANILLA = new ArmorRoll(0.15F, 0.1F, Optional.empty());
    private static final ArmorRoll[] ARMOR = {
            VANILLA,
            VANILLA,
            new ArmorRoll(0.4F, 0.1F, Optional.of(List.of(0, 20, 20, 40, 20, 0))),
            new ArmorRoll(0.75F, 0.05F, Optional.of(List.of(0, 0, 0, 40, 50, 10))),
            // A whole set, nine times in ten iron or diamond: at least a full iron set's armour.
            new ArmorRoll(1F, 0F, Optional.of(List.of(0, 0, 0, 10, 75, 15)))};

    private HuntPressure() {}

    /** Bounty stars, or a sortie tier, clamped to the table. */
    public static int tier(int stars) { return Math.max(1, Math.min(Bounty.MAX_STARS, stars)); }

    /** How many monsters may hunt the human at once, those still searching for a landing included. */
    public static int concurrentHunters(int stars) { return tier(stars); }

    /** How long a monster that fell on a sortie may not be sent onto this human again. */
    public static long lockMillis(int stars) { return LOCK_MILLIS[tier(stars) - 1]; }

    public static long lockNanos(int stars) { return lockMillis(stars) * 1_000_000L; }

    /** Adult zombie eggs a zombie sortie brings of its own; bought eggs come on top. */
    public static int starterAdultEggs(int tier) { return STARTER_ADULT_EGGS[tier(tier) - 1]; }

    /** A skeleton's rest between triple volleys. */
    public static long volleyCooldownMillis(int tier) { return VOLLEY_COOLDOWN_MILLIS[tier(tier) - 1]; }

    public static ArmorRoll armor(int tier) { return ARMOR[tier(tier) - 1]; }

    /** A paused sortie never climbs: resuming onto a weaker human drops to its stars, onto a stronger one stays. */
    public static int resumedTier(int pausedTier, int quarryStars) { return Math.min(tier(pausedTier), tier(quarryStars)); }
}
