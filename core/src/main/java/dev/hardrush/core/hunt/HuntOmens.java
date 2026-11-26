package dev.hardrush.core.hunt;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import dev.hardrush.core.round.MonsterRole;

/**
 * When a sortie from the camp lands, its target hears one low cave sound: "something is coming",
 * never where, which one or how many. A target hears it at most once per {@link #INTERVAL_MILLIS},
 * so a second landing inside that window stays silent and the count of sounds never gives away the
 * count of hunters. A creeper lands unheard: it has no fight of its own and lives on surprise, so its
 * landing neither sounds nor opens a window that would silence the next hunter. Server thread only.
 */
public final class HuntOmens {
    public static final long INTERVAL_MILLIS = 10_000;
    private final Map<UUID, Long> sounded = new HashMap<>();

    /** A {@code hunter} sortie onto {@code target} landed at {@code nowMillis}; true when the target should hear it now. */
    public boolean landed(UUID target, MonsterRole hunter, long nowMillis) {
        if (hunter == MonsterRole.CREEPER) return false;
        // A clock that went backwards (a replaced test clock) closes every window rather than silencing it forever.
        sounded.values().removeIf(at -> nowMillis < at || nowMillis - at >= INTERVAL_MILLIS);
        if (sounded.containsKey(target)) return false;
        sounded.put(target, nowMillis);
        return true;
    }
}
