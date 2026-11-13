package dev.hardrush.core.lobby;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** One arbiter per viewer; callers refresh leases, then render once per tick. Monotonic milliseconds. */
public final class LobbyHints {
    /**
     * {@code ITEM} is the usage line of the special item just taken into the hand; it briefly outranks ambient
     * guidance but never an ongoing countdown. {@code NOTICE} is a one-shot reaction to the player's own action.
     */
    public enum Priority { GUIDANCE, FACILITY, TARGET, READY, ITEM, PROGRESS, NOTICE, RECOVERY }
    /** {@code flash} lines are sent once, never refreshed, and blank the bar when withdrawn while shown. */
    private record Hint(Priority priority, String text, long expires, boolean flash) {}
    private final Map<String, Hint> hints = new HashMap<>();
    private String shownSource, shownText;
    private boolean blank;

    /** An ongoing line, resent on refresh ticks while its lease lasts. */
    public void offer(String source, Priority priority, String text, long expires) { put(source, priority, text, expires, false); }

    /**
     * A one-shot line the client lets fade on its own: sent the moment it tops the arbiter and never resent, so a
     * single send shows it for the vanilla action bar's 3 seconds. The lease only decides how long it outranks others.
     */
    public void flash(String source, Priority priority, String text, long expires) { put(source, priority, text, expires, true); }

    private void put(String source, Priority priority, String text, long expires, boolean flash) {
        hints.put(source, new Hint(java.util.Objects.requireNonNull(priority), java.util.Objects.requireNonNull(text), expires, flash));
    }

    public void clear(String source) {
        var removed = hints.remove(source);
        // A withdrawn flash must not linger over whatever replaced it in the player's hand.
        if (removed != null && removed.flash && source.equals(shownSource) && removed.text.equals(shownText)) blank = true;
    }

    public void clear() { hints.clear(); shownSource = null; shownText = null; blank = false; }

    public Optional<String> current(long now) { return top(now).map(e -> e.getValue().text); }

    /**
     * What the action bar should receive now. A line from a new source goes out at once; an ongoing line is resent
     * on {@code refresh} ticks so the client keeps it lit; a flash goes out once per text. An empty string blanks the bar.
     */
    public Optional<String> render(long now, boolean refresh) {
        var top = top(now).orElse(null);
        if (top == null) {
            boolean clearing = blank;
            blank = false; shownSource = null; shownText = null;
            return clearing ? Optional.of("") : Optional.empty();
        }
        blank = false;
        var hint = top.getValue();
        boolean sameSource = top.getKey().equals(shownSource), sameText = sameSource && hint.text.equals(shownText);
        shownSource = top.getKey(); shownText = hint.text;
        if (!sameSource || hint.flash && !sameText) return Optional.of(hint.text);
        return refresh && !hint.flash ? Optional.of(hint.text) : Optional.empty();
    }

    private Optional<Map.Entry<String, Hint>> top(long now) {
        hints.values().removeIf(h -> h.expires <= now);
        return hints.entrySet().stream().sorted(java.util.Comparator
                .<Map.Entry<String, Hint>, Priority>comparing(e -> e.getValue().priority).reversed()
                .thenComparing(Map.Entry::getKey)).findFirst();
    }
}
