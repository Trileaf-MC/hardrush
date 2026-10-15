package dev.hardrush.core.admin;

import java.util.Objects;
import java.util.UUID;

/** Process-local, immutable confirmation preview. An instance alone is never authority to execute. */
public record AdminTicket(UUID id, AdminRequest request, long issuedNanos) {
    public AdminTicket { Objects.requireNonNull(id); Objects.requireNonNull(request); }
}
