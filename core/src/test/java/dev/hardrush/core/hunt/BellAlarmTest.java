package dev.hardrush.core.hunt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BellAlarmTest {
    @Test
    void aBellRestsAfterARevealAndOthersStayReady() {
        var alarm = new BellAlarm<Object>();
        Object bell = new Object(), other = new Object();
        assertTrue(alarm.ready(bell, 100), "A bell that never revealed anyone is ready");
        alarm.revealed(bell, 100);
        assertFalse(alarm.ready(bell, 101), "The bell rests right after a reveal");
        assertFalse(alarm.ready(bell, 100 + BellAlarm.REST_TICKS - 1), "It rests for the whole period");
        assertTrue(alarm.ready(bell, 100 + BellAlarm.REST_TICKS), "Then it is ready again");
        assertTrue(alarm.ready(other, 101), "Each bell rests on its own");
    }

    @Test
    void aClockThatWentBackwardsEndsTheRest() {
        var alarm = new BellAlarm<Object>();
        Object bell = new Object();
        alarm.revealed(bell, 1_000);
        assertTrue(alarm.ready(bell, 10), "A reset game time does not prolong the rest");
    }
}
