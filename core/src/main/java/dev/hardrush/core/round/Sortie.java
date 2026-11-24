package dev.hardrush.core.round;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A monster's current personal hunt (docs/plan/悬赏分档与追猎强度.md §3–§5).
 *
 * @param quarry the human it was sent onto from the camp, while it is out; never kept in the camp
 * @param tier   1–5, the stars that sortie's kit and skills were issued for; kept while a deliberate return pauses
 *               the sortie, so a resumed one never climbs ({@link HuntPressure#resumedTier}); 0 when none
 */
public record Sortie(Optional<UUID> quarry, int tier) {
    public static final Sortie NONE = new Sortie(Optional.empty(), 0);

    public Sortie {
        Objects.requireNonNull(quarry);
        if (tier < 0 || tier > Bounty.MAX_STARS || quarry.isPresent() && tier == 0) throw new IllegalArgumentException("Invalid sortie");
    }

    public static Sortie onto(UUID quarry, int tier) { return new Sortie(Optional.of(Objects.requireNonNull(quarry)), HuntPressure.tier(tier)); }

    /** The same sortie paused in the camp: whom it hunted is forgotten, its tier is not. */
    Sortie paused() { return tier == 0 ? NONE : new Sortie(Optional.empty(), tier); }
}
