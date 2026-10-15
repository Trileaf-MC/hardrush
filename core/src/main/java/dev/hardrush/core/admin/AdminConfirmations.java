package dev.hardrush.core.admin;

import dev.hardrush.core.api.MonotonicClock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded, single-thread confirmation gate. A successful consume removes the ticket before callers
 * perform IO; permission and execution gates must be rechecked by the command/session layer.
 * Tickets deliberately do not survive process restart or grant permissions of their own.
 */
public final class AdminConfirmations {
    public static final long VALID_FOR_NANOS = 120_000_000_000L;
    public static final int DEFAULT_CAPACITY = 128;
    private final MonotonicClock clock;
    private final int capacity;
    private final Map<UUID, AdminTicket> tickets = new LinkedHashMap<>();
    private long lastTime;

    public AdminConfirmations(MonotonicClock clock) { this(clock, DEFAULT_CAPACITY); }

    public AdminConfirmations(MonotonicClock clock, int capacity) {
        this.clock = Objects.requireNonNull(clock);
        if (capacity < 1 || capacity > 4096) throw new IllegalArgumentException("Invalid confirmation capacity");
        this.capacity = capacity;
        this.lastTime = clock.nanoTime();
    }

    public AdminTicket issue(AdminRequest request) {
        Objects.requireNonNull(request);
        long now = currentTime();
        expire(now);
        if (tickets.size() >= capacity) throw new IllegalStateException("Administrator confirmation capacity reached");
        UUID id;
        do { id = UUID.randomUUID(); } while (tickets.containsKey(id));
        AdminTicket ticket = new AdminTicket(id, request, now);
        tickets.put(id, ticket);
        return ticket;
    }

    /** Dispatch only the returned immutable request's action and values. */
    public AdminRequest consume(UUID id, AdminActor actor, UUID roundId, String contextTag) {
        return consumeChecked(id, actor, null, roundId, contextTag);
    }

    public AdminRequest consume(UUID id, AdminActor actor, AdminAction expectedAction, UUID roundId, String contextTag) {
        return consumeChecked(id, actor, Objects.requireNonNull(expectedAction), roundId, contextTag);
    }

    private AdminRequest consumeChecked(UUID id, AdminActor actor, AdminAction expectedAction, UUID roundId, String contextTag) {
        Objects.requireNonNull(id); Objects.requireNonNull(actor); Objects.requireNonNull(roundId);
        Objects.requireNonNull(contextTag);
        long now = currentTime();
        expire(now);
        AdminTicket ticket = tickets.get(id);
        if (ticket == null) throw new IllegalStateException("Unknown, expired, or consumed administrator confirmation");
        AdminRequest request = ticket.request();
        if (!request.actor().identity().equals(actor.identity())) {
            throw new IllegalStateException("Administrator confirmation belongs to another operator");
        }
        if (!request.roundId().equals(roundId) || !request.contextTag().equals(contextTag)
                || expectedAction != null && request.action() != expectedAction) {
            throw new IllegalStateException("Administrator confirmation no longer matches its action or round context");
        }
        tickets.remove(id);
        return request;
    }

    public int size() {
        expire(currentTime());
        return tickets.size();
    }

    private long currentTime() {
        long now = clock.nanoTime();
        if (now - lastTime < 0) {
            tickets.clear();
            throw new IllegalStateException("Monotonic clock moved backwards");
        }
        lastTime = now;
        return now;
    }

    private void expire(long now) {
        tickets.values().removeIf(ticket -> now - ticket.issuedNanos() >= VALID_FOR_NANOS);
    }
}
