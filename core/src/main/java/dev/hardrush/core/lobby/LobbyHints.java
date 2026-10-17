package dev.hardrush.core.lobby;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** One arbiter per viewer; callers refresh leases, then render once. Monotonic milliseconds. */
public final class LobbyHints {
    public enum Priority { FACILITY, TARGET, READY, PROGRESS, RECOVERY }
    private record Hint(Priority priority, String text, long expires) {}
    private final Map<String, Hint> hints = new HashMap<>();
    public void offer(String source, Priority priority, String text, long expires) {
        hints.put(source, new Hint(java.util.Objects.requireNonNull(priority), java.util.Objects.requireNonNull(text), expires));
    }
    public void clear(String source) { hints.remove(source); }
    public void clear() { hints.clear(); }
    public Optional<String> current(long now) {
        hints.values().removeIf(h -> h.expires <= now);
        return hints.entrySet().stream().sorted(java.util.Comparator
                .<Map.Entry<String, Hint>, Priority>comparing(e -> e.getValue().priority).reversed()
                .thenComparing(Map.Entry::getKey)).map(e -> e.getValue().text).findFirst();
    }
}
