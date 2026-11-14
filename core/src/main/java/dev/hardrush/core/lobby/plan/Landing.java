package dev.hardrush.core.lobby.plan;

/**
 * Why a player is being placed in the lobby. Not a {@link LobbyFunction}: the restore landing has no
 * marker of its own but is derived from the assembly zone (see {@link LandingRules#candidates}).
 */
public enum Landing {
    /** Arrivals, returns from the round and anything without a better place. */
    CENTRAL,
    /** Camp monsters, into the courtyard. */
    MONSTER,
    /** A revival-earning kill, summoned to the altar; falls back to the courtyard landings. */
    ALTAR,
    /** A player who was ready before a restart, put back inside the assembly zone. */
    RESTORE_READY
}
