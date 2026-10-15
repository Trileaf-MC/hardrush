package dev.hardrush.core.record;

import java.util.Objects;
import java.util.UUID;

/** An administrator's durable UTC audit record. A null round ID denotes a global operation. */
public record AdminAudit(UUID operationId, UUID roundId, String actor, String action, String reason,
                         String beforeValue, String afterValue, long occurredAtEpochMillis) {
    public AdminAudit {
        Objects.requireNonNull(operationId);
        actor = text(actor, "actor", 256, false);
        action = text(action, "action", 64, false);
        reason = text(reason, "reason", 1024, false);
        beforeValue = text(beforeValue, "beforeValue", 16_384, true);
        afterValue = text(afterValue, "afterValue", 16_384, true);
        if (occurredAtEpochMillis < 0) throw new IllegalArgumentException("Invalid UTC audit timestamp");
    }

    private static String text(String value, String name, int maximum, boolean blankAllowed) {
        Objects.requireNonNull(value, name);
        if (value.length() > maximum || !blankAllowed && value.isBlank()) throw new IllegalArgumentException("Invalid " + name);
        return value;
    }
}
