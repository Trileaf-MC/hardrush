package dev.hardrush.p1.record;

import dev.hardrush.core.record.ProgressWrite;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** UUID-scoped, same-round observations; disconnect and replacement player objects do not reset them. */
public final class P1ProgressTracker {
    private record Observation(String name, Set<String> completed, String fingerprint, boolean enteredEnd) {}
    private final Map<UUID, Observation> players = new HashMap<>();
    private UUID round;

    public Optional<ProgressWrite> observe(UUID roundId, UUID playerId, String name,
            P1AdvancementCatalog catalog, Set<String> completed, boolean inEnd, long challengeNanos) {
        if (!roundId.equals(round)) {
            players.clear();
            round = roundId;
        }
        var previous = players.get(playerId);
        var collected = new HashSet<>(completed);
        if (previous != null) collected.addAll(previous.completed());
        // A reloaded data pack may turn a previously counted ID into a root, recipe or hidden technical advancement.
        collected.retainAll(catalog.ids());
        var observation = new Observation(name, Set.copyOf(collected), catalog.fingerprint(),
                inEnd || previous != null && previous.enteredEnd());
        if (observation.equals(previous)) return Optional.empty();
        var write = new ProgressWrite(roundId, UUID.randomUUID(), playerId, name, observation.completed(),
                observation.fingerprint(), observation.enteredEnd(), challengeNanos);
        players.put(playerId, observation);
        return Optional.of(write);
    }
}
