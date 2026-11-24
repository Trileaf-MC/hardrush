package dev.hardrush.core.hunt;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * A rung bell reveals monster players the way vanilla reveals raiders (docs/plan/钟声显形.md): it resonates when one
 * stands within {@link #RESONATE_RADIUS}, then every one within {@link #REVEAL_RADIUS} glows for {@link #GLOW_SECONDS}.
 * The radii and duration are vanilla's; this class adds only the rest: a bell that revealed a monster player stays
 * silent toward them for {@link #REST_TICKS}. Bells are held weakly, so a broken bell's rest goes with it.
 * Server thread only.
 */
public final class BellAlarm<B> {
    public static final int RESONATE_RADIUS = 32;
    public static final int REVEAL_RADIUS = 48;
    public static final int GLOW_SECONDS = 3;
    public static final long REST_TICKS = 30 * 20;
    private final Map<B, Long> revealedAt = new WeakHashMap<>();

    /** Whether {@code bell} may resonate for and reveal monster players at game tick {@code tick}. */
    public boolean ready(B bell, long tick) {
        Long at = revealedAt.get(bell);
        // A clock that went backwards (a reset world time) ends the rest rather than prolonging it.
        return at == null || tick < at || tick - at >= REST_TICKS;
    }

    /** {@code bell} just revealed at least one monster player. */
    public void revealed(B bell, long tick) { revealedAt.put(bell, tick); }

    public static int restSeconds() { return (int) (REST_TICKS / 20); }
}
