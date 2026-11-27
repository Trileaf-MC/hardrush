package dev.hardrush.core.lobby;

import java.util.*;
import java.util.function.Predicate;

/**
 * Who stands on which hunt-target display slot in the courtyard, in display-slot order.
 *
 * <p>Seated humans never move, so the models around a change stay put. A human without a seat (new, late, or
 * revived after its old seat went to someone else) takes the lowest vacant seat, else a new one at the end.
 * Vacant seats left at the end are dropped; holes between seated humans stay holes.
 * Whether a seat is vacant (its holder left the round, or fell and its model has faded) is the caller's call.
 */
public final class CourtyardSeats {
    private CourtyardSeats() {}

    /**
     * Returns the seats after seating {@code humans} in their given order. {@code vacant} is asked only about
     * holders in {@code seats} that are not among {@code humans}, so a seated human is never displaced; a seat it
     * frees is filled at most once.
     */
    public static List<UUID> seat(List<UUID> seats, Collection<UUID> humans, Predicate<UUID> vacant) {
        var next = new ArrayList<>(seats);
        var seated = new HashSet<>(seats);
        if (seated.size() != seats.size()) throw new IllegalArgumentException("Duplicate seat holder");
        var stillHuman = Set.copyOf(humans);
        int from = 0;
        for (var human : humans) {
            if (!seated.add(human)) continue;
            while (from < seats.size() && (stillHuman.contains(seats.get(from)) || !vacant.test(seats.get(from)))) from++;
            if (from < seats.size()) next.set(from++, human);
            else next.add(human);
        }
        // Vacant seats at the end hold nobody worth a slot, and would otherwise leave a page with no models to turn to.
        while (!next.isEmpty() && !stillHuman.contains(next.getLast()) && vacant.test(next.getLast())) next.removeLast();
        return List.copyOf(next);
    }
}
