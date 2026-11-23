package dev.hardrush.core.round;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * What one human life has shown of its strength: monster players it killed, hunts launched at it from the
 * camp, and the progress milestones it reached. Bound to {@code lifeSequence}: a record of another life reads
 * as an empty one, so no death or revival path has to clear it.
 */
public record HumanThreat(int lifeSequence, int monsterKills, int hunted, Set<String> milestones) {
    public static final HumanThreat NONE = new HumanThreat(0, 0, 0, Set.of());

    public HumanThreat {
        milestones = Set.copyOf(milestones);
        if (lifeSequence < 0 || monsterKills < 0 || hunted < 0) throw new IllegalArgumentException("Invalid human threat");
        if (!Bounty.MILESTONES.keySet().containsAll(milestones)) throw new IllegalArgumentException("Unknown threat milestone");
    }

    /** This life's record, or an empty one when the stored record belongs to another life. */
    public HumanThreat forLife(int life) {
        return lifeSequence == life ? this : new HumanThreat(life, 0, 0, Set.of());
    }

    HumanThreat killedMonster(int life) {
        var current = forLife(life);
        return new HumanThreat(life, saturated(current.monsterKills), current.hunted, current.milestones);
    }

    HumanThreat huntedFromCamp(int life) {
        var current = forLife(life);
        return new HumanThreat(life, current.monsterKills, saturated(current.hunted), current.milestones);
    }

    /** Unchanged when the milestone is not scored or already counted for this life. */
    HumanThreat reached(int life, String advancement) {
        Objects.requireNonNull(advancement);
        var current = forLife(life);
        if (!Bounty.MILESTONES.containsKey(advancement) || current.milestones.contains(advancement)) return current;
        var reached = new HashSet<>(current.milestones);
        reached.add(advancement);
        return new HumanThreat(life, current.monsterKills, current.hunted, reached);
    }

    private static int saturated(int count) { return count == Integer.MAX_VALUE ? count : count + 1; }
}
