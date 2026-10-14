package dev.hardrush.core.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MonotonicClockTest {
    @Test void injectedClockCanMeasureSlowTicksWithoutAssumingTwentyTicksPerSecond() {
        var clock = new FakeClock(-1_000_000_000L);
        MonotonicClock elapsedSource = clock;
        long started = elapsedSource.nanoTime();
        clock.advanceMillis(120);
        clock.advanceMillis(180);
        assertEquals(300_000_000, elapsedSource.nanoTime() - started);
        assertNotEquals(2 * 50_000_000, elapsedSource.nanoTime() - started);
        assertThrows(IllegalArgumentException.class, () -> clock.advanceMillis(-1));
    }

    @Test void systemClockIsAnElapsedTimeSource() {
        MonotonicClock clock = MonotonicClock.system();
        long first = clock.nanoTime();
        assertTrue(clock.nanoTime() - first >= 0);
    }

    private static final class FakeClock implements MonotonicClock {
        private long now;
        FakeClock(long now) { this.now = now; }
        @Override public long nanoTime() { return now; }
        void advanceMillis(long millis) {
            if (millis < 0) throw new IllegalArgumentException("Time cannot run backwards");
            now = Math.addExact(now, Math.multiplyExact(millis, 1_000_000));
        }
    }
}
