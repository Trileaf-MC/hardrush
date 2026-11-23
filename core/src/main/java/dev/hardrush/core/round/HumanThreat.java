package dev.hardrush.core.round;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What one human life has shown of its strength: monster players it killed, hunts launched at it from the
 * camp, and the progress milestones it reached. Bound to {@code lifeSequence}: a record of another life reads
 * as an empty one, so no death or revival path has to clear it.
 *
 * @param huntedAt challenge time at which each hunter's hunt last counted; one hunter scores again only after
 *                 {@link Bounty#HUNTED_REPEAT_NANOS}, so going out and straight back cannot pump a human's stars
 */
public record HumanThreat(int lifeSequence, int monsterKills, int hunted, Set<String> milestones, Map<UUID, Long> huntedAt) {
    public static final HumanThreat NONE = new HumanThreat(0, 0, 0, Set.of(), Map.of());

    public HumanThreat(int lifeSequence, int monsterKills, int hunted, Set<String> milestones) {
        this(lifeSequence, monsterKills, hunted, milestones, Map.of());
    }

    public HumanThreat {
        milestones = Set.copyOf(milestones);
        huntedAt = Map.copyOf(huntedAt);
        if (lifeSequence < 0 || monsterKills < 0 || hunted < 0 || huntedAt.values().stream().anyMatch(at -> at < 0))
            throw new IllegalArgumentException("Invalid human threat");
        if (!Bounty.MILESTONES.keySet().containsAll(milestones)) throw new IllegalArgumentException("Unknown threat milestone");
    }

    /** This life's record, or an empty one when the stored record belongs to another life. */
    public HumanThreat forLife(int life) {
        return lifeSequence == life ? this : new HumanThreat(life, 0, 0, Set.of(), Map.of());
    }

    HumanThreat killedMonster(int life) {
        var current = forLife(life);
        return new HumanThreat(life, saturated(current.monsterKills), current.hunted, current.milestones, current.huntedAt);
    }

    /** Unchanged when this hunter's previous hunt of this life counted less than the repeat interval ago. */
    HumanThreat huntedFromCamp(int life, UUID hunter, long challengeNanos) {
        Objects.requireNonNull(hunter);
        var current = forLife(life);
        Long last = current.huntedAt.get(hunter);
        if (last != null && challengeNanos - last < Bounty.HUNTED_REPEAT_NANOS) return current;
        var at = new HashMap<>(current.huntedAt);
        at.put(hunter, challengeNanos);
        return new HumanThreat(life, current.monsterKills, saturated(current.hunted), current.milestones, at);
    }

    /** Unchanged when the milestone is not scored or already counted for this life. */
    HumanThreat reached(int life, String advancement) {
        Objects.requireNonNull(advancement);
        var current = forLife(life);
        if (!Bounty.MILESTONES.containsKey(advancement) || current.milestones.contains(advancement)) return current;
        var reached = new HashSet<>(current.milestones);
        reached.add(advancement);
        return new HumanThreat(life, current.monsterKills, current.hunted, reached, current.huntedAt);
    }

    private static int saturated(int count) { return count == Integer.MAX_VALUE ? count : count + 1; }
}
