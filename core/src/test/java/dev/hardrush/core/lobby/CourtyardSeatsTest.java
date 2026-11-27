package dev.hardrush.core.lobby;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CourtyardSeatsTest {
    final UUID a = id(1), b = id(2), c = id(3), d = id(4), e = id(5);

    @Test void newHumansFillTheLowestVacantSeatBeforeAppending() {
        var seats = CourtyardSeats.seat(List.of(a, b, c), List.of(a, c, d, e), Set.of(b)::contains);
        assertEquals(List.of(a, d, c, e), seats);
    }

    @Test void seatedHumansNeverMove() {
        var seats = CourtyardSeats.seat(List.of(a, b, c), List.of(c, a, b), ignored -> false);
        assertEquals(List.of(a, b, c), seats);
    }

    @Test void aRevivedHumanStillHoldingItsSeatKeepsIt() {
        // B fell but its seat was not taken yet; once human again it keeps it, even against a stale vacancy and a newcomer.
        var seats = CourtyardSeats.seat(List.of(a, b, c), List.of(a, b, c, d), Set.of(b)::contains);
        assertEquals(List.of(a, b, c, d), seats);
    }

    @Test void aRevivedHumanWhoseSeatWasTakenSitsLikeANewcomer() {
        assertEquals(List.of(a, d, b), CourtyardSeats.seat(List.of(a, d, c), List.of(a, d, b), Set.of(c)::contains));
        assertEquals(List.of(a, d, b), CourtyardSeats.seat(List.of(a, d), List.of(a, d, b), ignored -> false));
    }

    @Test void vacantSeatsFillFromTheLowest() {
        var seats = CourtyardSeats.seat(List.of(a, b, c), List.of(b, d, e), Set.of(a, c)::contains);
        assertEquals(List.of(d, b, e), seats);
    }

    @Test void theFirstHumansSitInOrder() {
        assertEquals(List.of(c, a, b), CourtyardSeats.seat(List.of(), List.of(c, a, b), ignored -> false));
    }

    @Test void aRepeatedHumanSitsOnce() {
        assertEquals(List.of(a, b), CourtyardSeats.seat(List.of(), List.of(a, b, a), ignored -> false));
    }

    @Test void aSeatIsFilledOnlyOnce() {
        var seats = CourtyardSeats.seat(List.of(a, b), List.of(c, d, e), Set.of(a)::contains);
        assertEquals(List.of(c, b, d, e), seats);
    }

    @Test void vacantSeatsAtTheEndAreDroppedButInnerHolesStay() {
        assertEquals(List.of(a, b), CourtyardSeats.seat(List.of(a, b, c, d), List.of(a, b), Set.of(c, d)::contains));
        assertEquals(List.of(a, b, c), CourtyardSeats.seat(List.of(a, b, c), List.of(a, c), Set.of(b)::contains));
        assertEquals(List.of(), CourtyardSeats.seat(List.of(a, b), List.of(), Set.of(a, b)::contains));
    }

    @Test void aNewcomerAtTheEndIsNeverDropped() {
        assertEquals(List.of(a, b), CourtyardSeats.seat(List.of(a), List.of(b), Set.of(c)::contains));
        assertEquals(List.of(c), CourtyardSeats.seat(List.of(a, b), List.of(c), Set.of(a, b)::contains));
    }

    @Test void duplicateHoldersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CourtyardSeats.seat(List.of(a, a), List.of(), ignored -> false));
    }

    private static UUID id(long n) { return new UUID(0, n); }
}
