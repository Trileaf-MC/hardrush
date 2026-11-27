package dev.hardrush.core.hunt;

/**
 * A rung bell reveals monster players the way vanilla reveals raiders (docs/plan/钟声显形.md): it resonates when one
 * stands within {@link #RESONATE_RADIUS}, then every one within {@link #REVEAL_RADIUS} glows for {@link #GLOW_SECONDS}.
 * Like vanilla, a bell never rests: every ring may reveal again.
 */
public final class BellAlarm {
    // The radii and the glow time are vanilla BellBlockEntity's own and only word the rule book: changing them here
    // changes no behaviour. Keep them equal to vanilla's.
    public static final int RESONATE_RADIUS = 32;
    public static final int REVEAL_RADIUS = 48;
    public static final int GLOW_SECONDS = 3;

    private BellAlarm() {}
}
