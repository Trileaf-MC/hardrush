package dev.hardrush.core.hunt;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ParticipantRole;

/**
 * 初入荒野 (docs/plan/复生庇护与初入荒野.md): a mid-round entrant's first life cannot be chosen as a monster's hunt
 * target until it has been an active human for a while. Time is the life's own human time, so it stands still while
 * the player is away or the match is paused, and survives a restart with the snapshot. Pass a projected participant
 * (the live view), not the last checkpoint, so display and refusal agree at the boundary.
 */
public final class LateShelter {
    public static final String KEY = "entry.lateShelterMillis";
    public static final long DEFAULT_MILLIS = 300_000;
    public static final String NAME = "初入荒野";

    private LateShelter() {}

    /** Only the courtyard's personal hunt lets a monster choose its target, and only hardcore rounds have monsters. */
    public static boolean enabled(RulesConfig rules) {
        return rules.mode() == GameMode.HARDCORE && rules.huntRule() == RulesConfig.HuntRule.PERSONAL_TARGET;
    }

    public static long durationNanos(RulesConfig rules) {
        return Math.multiplyExact(rules.combatValues().getOrDefault(KEY, (double) DEFAULT_MILLIS).longValue(), 1_000_000L);
    }

    /** Whole minutes for player-facing text, never below one. */
    public static long minutes(RulesConfig rules) {
        return Math.max(1, Math.round(durationNanos(rules) / 60_000_000_000.0));
    }

    /** Human time this life still has to spend before it can be hunted; zero when it is not sheltered at all. */
    public static long remainingNanos(RulesConfig rules, Participant participant) {
        if (participant == null || !enabled(rules) || participant.role() != ParticipantRole.HUMAN_ACTIVE
                || !participant.lateEntrant() || participant.lifeSequence() != 1) return 0;
        return Math.max(0, durationNanos(rules) - participant.lifeHumanNanos());
    }

    public static boolean sheltered(RulesConfig rules, Participant participant) {
        return remainingNanos(rules, participant) > 0;
    }

    /** "4:32", rounded up so the display reaches 0:00 exactly when the shelter ends. */
    public static String clock(long remainingNanos) {
        long seconds = (remainingNanos + 999_999_999L) / 1_000_000_000L;
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }
}
