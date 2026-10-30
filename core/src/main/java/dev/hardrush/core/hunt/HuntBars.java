package dev.hardrush.core.hunt;

import dev.hardrush.core.round.*;
import java.util.*;
import java.util.function.Function;

/**
 * Native boss bar rules for a directed hunt. The adapter supplies positions, health and the
 * monster body's vanilla visibility percent; this class only decides who sees which bar.
 */
public final class HuntBars {
    public static final int MAX_HUMAN_BARS = 3;
    public static final long LINGER_MILLIS = 3_000;
    public static final long KILLED_DISPLAY_MILLIS = 3_000;
    private HuntBars() {}

    /** Operator settings, fixed for the server's lifetime. Distances are blocks before the visibility scale. */
    public record Settings(double showDistance, double hideDistance, boolean darken, double darkenDistance) {
        public static final Settings DEFAULTS = new Settings(5, 8, false, 3);
        public Settings {
            if (!usable(showDistance) || !usable(hideDistance) || !usable(darkenDistance))
                throw new IllegalArgumentException("追猎血条距离必须是 0～64 之间的正数");
            if (hideDistance < showDistance) throw new IllegalArgumentException("追猎血条消失距离不能小于出现距离");
        }
        private static boolean usable(double value) { return Double.isFinite(value) && value > 0 && value <= 64; }

        /** Missing keys keep their default; any invalid value rejects the whole set so a typo cannot half-apply. */
        public static Settings parse(Function<String, String> property) {
            return new Settings(number(property, "showDistance", DEFAULTS.showDistance),
                    number(property, "hideDistance", DEFAULTS.hideDistance),
                    flag(property, "darken", DEFAULTS.darken),
                    number(property, "darkenDistance", DEFAULTS.darkenDistance));
        }
        private static double number(Function<String, String> property, String key, double fallback) {
            String value = property.apply(key);
            if (value == null || value.isBlank()) return fallback;
            try { return Double.parseDouble(value.trim()); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("非法追猎血条配置: " + key, invalid); }
        }
        private static boolean flag(Function<String, String> property, String key, boolean fallback) {
            String value = property.apply(key);
            if (value == null || value.isBlank()) return fallback;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new IllegalArgumentException("非法追猎血条配置: " + key);
            };
        }
    }

    /** Bars are a fight indicator: settlement, celebration and recovery never show them. */
    public static boolean active(RoundSnapshot round) {
        return round != null && (round.phase() == RoundPhase.RUNNING || round.phase() == RoundPhase.RESCUE);
    }

    public static String humanTitle(String monsterName) { return "来自 " + monsterName + " 的追猎"; }

    public static String targetTitle(TargetState state, String targetName) {
        return switch (state) {
            case HUNTING -> "追猎目标 · " + targetName;
            case OTHER_DIMENSION -> "目标已不在当前维度";
            case OFFLINE -> "目标离线";
            case KILLED -> "目标已被击毙";
            case GONE -> "";
        };
    }

    /**
     * One human's view of nearby monster players. Proximity only counts the monsters hunting this
     * human; damage from any monster player shows its bar for the linger window. Server thread only.
     */
    public static final class HumanView {
        /** {@code distance} is NaN when the monster is not in the viewer's dimension. */
        public record Candidate(UUID monster, double distance, double visibility, boolean huntsViewer) {}
        public record Frame(List<UUID> monsters, boolean darken) {}
        private static final class Track { boolean inRange; long until = Long.MIN_VALUE; }
        private final Map<UUID, Track> tracks = new HashMap<>();

        public void damaged(UUID monster, long nowMillis) {
            var track = tracks.computeIfAbsent(monster, ignored -> new Track());
            track.until = Math.max(track.until, nowMillis + LINGER_MILLIS);
        }

        public Frame update(Collection<Candidate> candidates, Settings settings, long nowMillis) {
            var present = new HashSet<UUID>();
            var shown = new ArrayList<Candidate>();
            boolean darken = false;
            for (var candidate : candidates) {
                present.add(candidate.monster());
                var track = tracks.get(candidate.monster());
                if (Double.isNaN(candidate.distance())) { tracks.remove(candidate.monster()); continue; }
                // Vanilla mob detection scales range the same way: sneaking 0.8, invisibility by armor cover.
                double scale = Math.clamp(candidate.visibility(), 0.0, 10.0);
                if (candidate.huntsViewer()) {
                    if (track == null) { track = new Track(); tracks.put(candidate.monster(), track); }
                    if (candidate.distance() <= settings.showDistance() * scale) track.inRange = true;
                    else if (candidate.distance() > settings.hideDistance() * scale) track.inRange = false;
                } else if (track != null) track.inRange = false;
                if (track == null) continue;
                if (track.inRange) track.until = Math.max(track.until, nowMillis + LINGER_MILLIS);
                else if (nowMillis >= track.until) { tracks.remove(candidate.monster()); continue; }
                shown.add(candidate);
                if (settings.darken() && candidate.distance() <= settings.darkenDistance() * scale) darken = true;
            }
            tracks.keySet().retainAll(present);
            shown.sort(Comparator.comparingDouble(Candidate::distance).thenComparing(Candidate::monster));
            return new Frame(shown.stream().limit(MAX_HUMAN_BARS).map(Candidate::monster).toList(), darken);
        }
    }

    public enum TargetState { HUNTING, OTHER_DIMENSION, OFFLINE, KILLED, GONE }

    /** The selected life only: a revived target is a new life and still counts as killed for this sortie. */
    public static TargetState targetState(Participant target, int targetLife, boolean sameDimension) {
        if (target == null) return TargetState.GONE;
        if (target.lifeSequence() != targetLife) return TargetState.KILLED;
        return switch (target.role()) {
            case HUMAN_ACTIVE -> !target.online() ? TargetState.OFFLINE
                    : sameDimension ? TargetState.HUNTING : TargetState.OTHER_DIMENSION;
            case HUMAN_RESPAWNING, MONSTER_LOBBY, MONSTER_DEPLOYED -> TargetState.KILLED;
            default -> TargetState.GONE;
        };
    }

    /** One deployed monster's target bar. A kill is announced once, then hidden for the rest of the sortie. */
    public static final class TargetView {
        private long killedAt = Long.MIN_VALUE;

        public boolean visible(TargetState state, long nowMillis) {
            if (state == TargetState.GONE) return false;
            if (state != TargetState.KILLED) return killedAt == Long.MIN_VALUE;
            if (killedAt == Long.MIN_VALUE) killedAt = nowMillis;
            return nowMillis - killedAt < KILLED_DISPLAY_MILLIS;
        }
    }
}
