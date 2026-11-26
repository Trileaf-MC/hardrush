package dev.hardrush.storage;

import static dev.hardrush.storage.FullCheckpointRuntime.Decision.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.storage.FullCheckpointRuntime.Schedule;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FullCheckpointTimingTest {
    private static final UUID ROUND = UUID.randomUUID();
    private static final long DUE = 1_000_000_000L;
    private static final long LIMIT = FullCheckpointRuntime.SETTLE_LIMIT_NANOS;

    private static Schedule settlingFrom(long now) {
        return FullCheckpointRuntime.advance(Schedule.due(ROUND, DUE), now, true);
    }

    @Test void pointNotYetDueNeitherSettlesNorProbesTheWorlds() {
        var schedule = FullCheckpointRuntime.advance(Schedule.due(ROUND, DUE), DUE - 1, true);
        assertFalse(schedule.settling());
        var probes = new AtomicInteger();
        assertEquals(WAIT, FullCheckpointRuntime.decide(schedule, DUE - 1, () -> { probes.incrementAndGet(); return true; }));
        assertEquals(0, probes.get());
    }

    @Test void duePointWaitsWhileArrivalsAreGenerating() {
        var schedule = settlingFrom(DUE);
        assertEquals(WAIT, FullCheckpointRuntime.decide(schedule, DUE, () -> false));
        assertEquals(WAIT, FullCheckpointRuntime.decide(schedule, DUE + LIMIT - 1, () -> false));
    }

    @Test void duePointSavesOnceSettled() {
        assertEquals(SETTLED, FullCheckpointRuntime.decide(settlingFrom(DUE), DUE, () -> true));
    }

    @Test void endlessGenerationDelaysButNeverSkipsThePoint() {
        var probes = new AtomicInteger();
        assertEquals(FORCED, FullCheckpointRuntime.decide(settlingFrom(DUE), DUE + LIMIT, () -> { probes.incrementAndGet(); return false; }));
        assertEquals(0, probes.get());
    }

    @Test void timeNobodyCouldBeSavedDoesNotCountTowardsTheLimit() {
        // A player sat on the death screen long past the due time; respawning makes the point writable just as they arrive.
        long respawned = DUE + 2 * LIMIT;
        var schedule = FullCheckpointRuntime.advance(Schedule.due(ROUND, DUE), DUE + 1, false);
        assertFalse(schedule.settling());
        schedule = FullCheckpointRuntime.advance(schedule, respawned, true);
        assertEquals(WAIT, FullCheckpointRuntime.decide(schedule, respawned, () -> false));
        assertEquals(FORCED, FullCheckpointRuntime.decide(schedule, respawned + LIMIT, () -> false));
    }

    @Test void losingWritabilityRestartsTheSettleLimit() {
        var schedule = settlingFrom(DUE);
        schedule = FullCheckpointRuntime.advance(schedule, DUE + LIMIT / 2, false);
        assertFalse(schedule.settling());
        long back = DUE + LIMIT + 1;
        schedule = FullCheckpointRuntime.advance(schedule, back, true);
        assertEquals(back, schedule.settlingSinceNanos());
        assertEquals(WAIT, FullCheckpointRuntime.decide(schedule, back, () -> false));
    }

    @Test void stillWritablePointKeepsItsSettlingStart() {
        var schedule = settlingFrom(DUE);
        assertSame(schedule, FullCheckpointRuntime.advance(schedule, DUE + 5, true));
    }

    @Test void nanoTimeOverflowKeepsOrder() {
        long due = Long.MAX_VALUE - 10;
        var schedule = FullCheckpointRuntime.advance(Schedule.due(ROUND, due), due - 5, true);
        assertFalse(schedule.settling());
        schedule = FullCheckpointRuntime.advance(schedule, due + 1, true);
        assertTrue(schedule.settling());
        assertEquals(FORCED, FullCheckpointRuntime.decide(schedule, due + 1 + LIMIT, () -> false));
    }
}
