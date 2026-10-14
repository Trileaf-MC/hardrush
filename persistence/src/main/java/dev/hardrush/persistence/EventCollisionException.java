package dev.hardrush.persistence;

import java.sql.SQLException;
import java.util.UUID;

public final class EventCollisionException extends SQLException {
    public EventCollisionException(UUID eventId) {
        super("Event ID already belongs to a different write: " + eventId);
    }
}
