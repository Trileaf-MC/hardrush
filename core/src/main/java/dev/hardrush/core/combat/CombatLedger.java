package dev.hardrush.core.combat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Single-thread event identity guard. Durable consumers still enforce a unique event ID. */
public final class CombatLedger {
    public enum Result { ACCEPTED, DUPLICATE, STALE_ROUND }
    private final UUID roundId;
    private final Map<UUID, CombatEvent> events = new LinkedHashMap<>();
    public CombatLedger(UUID roundId) { this.roundId = java.util.Objects.requireNonNull(roundId); }
    public Result accept(CombatEvent event) {
        if (!roundId.equals(event.roundId())) return Result.STALE_ROUND;
        var prior = events.putIfAbsent(event.eventId(), event);
        if (prior == null) return Result.ACCEPTED;
        if (!prior.equals(event)) throw new IllegalStateException("Combat event ID collision");
        return Result.DUPLICATE;
    }
    public int size() { return events.size(); }
}
