package dev.hardrush.core.combat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DawnPactTest {
    private DawnPact.Display display(long ticks) { return DawnPact.display(ticks, 0, 1, 20, false); }

    @Test void changesAt180And60RealSeconds() {
        assertEquals(DawnPact.Color.GREEN, display(20_399).color());
        assertEquals("黎明契约即将解除 · 距猎杀开始还有 03:00", display(20_400).text());
        assertEquals(DawnPact.Color.YELLOW, display(22_799).color());
        assertEquals(DawnPact.Color.RED, display(22_800).color());
        assertEquals("准备作战", display(22_800).text());
        assertEquals(1, display(23_999).seconds());
        assertEquals(0, display(24_000).progress());
        assertEquals(1, display(0).progress());
    }
    @Test void convertsRealSecondsUsingBothClockAndServerRate() {
        assertEquals(180, DawnPact.display(16_800, 0, 2, 20, false).seconds());
        assertEquals(180, DawnPact.display(16_800, 0, 1, 40, false).seconds());
        assertEquals(180, DawnPact.display(22_200, 0, 1, 10, false).seconds());
        assertEquals(1, DawnPact.display(23_980, .5F, 1, 20, false).seconds());
    }
    @Test void pausedDayHasNoCountdownOrFinalWarning() {
        for (var display : new DawnPact.Display[]{DawnPact.display(23_990, 0, 1, 20, true),
                DawnPact.display(23_990, 0, 0, 20, false)}) {
            assertFalse(display.advancing());
            assertEquals(DawnPact.Color.GREEN, display.color());
            assertTrue(display.text().endsWith("昼夜暂停"));
        }
    }
}
