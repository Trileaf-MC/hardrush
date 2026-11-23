package dev.hardrush.core.round;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * What the camp witch remembers of one player for the whole round, across lives (docs/plan/复生补给与女巫说明.md):
 * whether her guide was already shown, and the revival supplies bought for the human life an altar started.
 *
 * @param guideSeen    the guide is shown once per player and round, closing it unread included
 * @param supplyLife   the human life the supplies belong to; 0 when none was ever bought
 * @param supply       revival supplies of that life, by offer and count
 * @param landed       the landing supplies (bonus chest, longer blessing) have been delivered; the carried ones go
 *                     into the body when the adapter builds that life
 */
public record WitchRecord(boolean guideSeen, int supplyLife, Map<ShopOffer, Integer> supply, boolean landed) {
    public static final WitchRecord NONE = new WitchRecord(false, 0, Map.of(), false);

    /** The altar's blessing before any longer blessing was bought, and its cap. */
    public static final long BLESSING_MILLIS = 90_000;
    public static final long BLESSING_STEP_MILLIS = 70_000;
    public static final long MAX_BLESSING_MILLIS = 300_000;

    public WitchRecord {
        var copy = new EnumMap<ShopOffer, Integer>(ShopOffer.class);
        Objects.requireNonNull(supply).forEach((offer, count) -> {
            if (!Objects.requireNonNull(offer).revival() || count == null || count < 0) throw new IllegalArgumentException("Invalid revival supply");
            if (count > 0) copy.put(offer, count);
        });
        supply = Map.copyOf(copy);
        if (supplyLife < 0 || supplyLife == 0 && (!supply.isEmpty() || landed)) throw new IllegalArgumentException("Invalid witch record");
    }

    public WitchRecord withGuideSeen() { return new WitchRecord(true, supplyLife, supply, landed); }

    /** Supplies bought for {@code life}; a life with none still replaces an older life's record. */
    public WitchRecord supplied(int life, Map<ShopOffer, Integer> goods) {
        if (life <= 0) throw new IllegalArgumentException("Invalid supply life");
        return new WitchRecord(guideSeen, life, goods, false);
    }

    public WitchRecord withLanded() { return new WitchRecord(guideSeen, supplyLife, supply, true); }

    /** Supplies of {@code life}, or none when they belong to another life. */
    public Map<ShopOffer, Integer> supplyOf(int life) { return life == supplyLife ? supply : Map.of(); }

    public int count(int life, ShopOffer offer) { return supplyOf(life).getOrDefault(offer, 0); }

    /** Whether anything of {@code life}'s supplies still waits for its landing. */
    public boolean landingPending(int life) {
        return !landed && supplyOf(life).keySet().stream().anyMatch(ShopOffer::landing);
    }

    /** The altar's blessing for {@code life}: 90 s, 70 s longer per longer blessing bought, at most five minutes. */
    public long blessingMillis(int life) {
        return Math.min(MAX_BLESSING_MILLIS, BLESSING_MILLIS + BLESSING_STEP_MILLIS * count(life, ShopOffer.LONGER_BLESSING));
    }
}
