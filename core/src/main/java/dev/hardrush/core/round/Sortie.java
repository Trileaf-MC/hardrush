package dev.hardrush.core.round;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A monster's current personal hunt (docs/plan/悬赏分档与追猎强度.md §3–§5).
 *
 * @param quarry  the human it was sent onto from the camp, while it is out; never kept in the camp
 * @param tier    1–5, the stars that sortie's kit and skills were issued for; kept while a deliberate return pauses
 *                the sortie, so a resumed one never climbs ({@link HuntPressure#resumedTier}); 0 when none
 * @param landing committed from the camp, but the platform has not yet confirmed that the body reached its drop
 *                point. Ending a sortie that never landed cancels it: nothing it froze was used, so everything goes
 *                back to the camp with no wait. Only an outing with a quarry can be landing
 */
public record Sortie(Optional<UUID> quarry, int tier, boolean landing) {
    public static final Sortie NONE = new Sortie(Optional.empty(), 0, false);

    public Sortie {
        Objects.requireNonNull(quarry);
        if (tier < 0 || tier > Bounty.MAX_STARS || quarry.isPresent() && tier == 0 || landing && quarry.isEmpty()) {
            throw new IllegalArgumentException("Invalid sortie");
        }
    }

    /** A sortie already out on its quarry, or a paused one. */
    public Sortie(Optional<UUID> quarry, int tier) { this(quarry, tier, false); }

    /** Sent onto {@code quarry} from the camp; it is {@linkplain #landing landing} until the platform puts it down. */
    public static Sortie onto(UUID quarry, int tier) {
        return new Sortie(Optional.of(Objects.requireNonNull(quarry)), HuntPressure.tier(tier), true);
    }

    /** The body reached its drop point. */
    Sortie landed() { return new Sortie(quarry, tier, false); }

    /** The same sortie paused in the camp: whom it hunted is forgotten, its tier is not. */
    Sortie paused() { return tier == 0 ? NONE : new Sortie(Optional.empty(), tier); }
}
