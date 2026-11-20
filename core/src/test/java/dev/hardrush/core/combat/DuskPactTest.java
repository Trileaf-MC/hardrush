package dev.hardrush.core.combat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DuskPactTest {
    private DuskPact.Display display(long ticks) { return DuskPact.display(ticks, 0, 1, 20, false, false); }

    @Test void changesAt180And60RealSeconds() {
        assertEquals(DuskPact.Color.GREEN, display(9_399).color());
        assertFalse(display(9_399).bar());
        assertEquals("夜幕将至 · 距猎杀开始还有 03:00", display(9_400).text());
        assertTrue(display(9_400).bar());
        assertEquals(1, display(9_400).progress());
        assertEquals(DuskPact.Color.YELLOW, display(11_799).color());
        assertEquals(DuskPact.Color.RED, display(11_800).color());
        assertEquals("准备作战", display(11_800).text());
        assertEquals("准备作战", display(12_779).text());
        assertEquals("准备作战 · 10", display(12_800).text());
        assertEquals("准备作战 · 1", display(12_999).text());
        assertEquals(1, display(12_999).seconds());
        assertEquals(0, display(13_000).progress());
    }
    @Test void tabAndBlockedHintCarryTheCountdownBeforeTheBarAppears() {
        assertEquals("黄昏契约 · 日落前暂息纷争 · 10:50", display(0).tab());
        assertEquals("黄昏契约生效中，不能攻击其他玩家 · 10:50", display(0).blocked());
        assertEquals("黄昏契约 · 日落前暂息纷争 · 00:45", display(12_100).tab());
    }
    @Test void convertsRealSecondsUsingBothClockAndServerRate() {
        assertEquals(180, DuskPact.display(5_800, 0, 2, 20, false, false).seconds());
        assertEquals(180, DuskPact.display(5_800, 0, 1, 40, false, false).seconds());
        assertEquals(180, DuskPact.display(11_200, 0, 1, 10, false, false).seconds());
        assertEquals(1, DuskPact.display(12_980, .5F, 1, 20, false, false).seconds());
    }
    @Test void pausedDayHasNoCountdownBarOrFinalWarning() {
        for (var display : new DuskPact.Display[]{DuskPact.display(12_990, 0, 1, 20, true, false),
                DuskPact.display(12_990, 0, 0, 20, false, false)}) {
            assertFalse(display.advancing());
            assertEquals(DuskPact.Color.GREEN, display.color());
            assertFalse(display.bar());
            assertTrue(display.tab().endsWith("昼夜暂停"));
            assertEquals(DuskPact.BLOCKED, display.blocked());
        }
    }
    @Test void releaseSubtitleNamesTheTotemDropOnlyInHardcore() {
        assertEquals("夜幕降临 · 击杀人类可夺得复生图腾", DuskPact.releasedSubtitle(true));
        assertEquals("夜幕降临 · 祝你好运", DuskPact.releasedSubtitle(false));
    }
    @Test void hardcoreWarningNamesTheTotemDropWhileOtherSegmentsStayAlike() {
        var hardcore = DuskPact.display(9_400, 0, 1, 20, false, true);
        assertEquals("夜幕将至，击杀人类可夺得复生图腾 · 03:00", hardcore.text());
        assertEquals(DuskPact.Color.YELLOW, hardcore.color());
        assertEquals("夜幕将至 · 距猎杀开始还有 03:00", display(9_400).text(), "Normal rounds keep the plain warning");
        for (long ticks : new long[]{0, 9_399, 9_400, 11_800, 12_800}) {
            var normal = display(ticks);
            var hard = DuskPact.display(ticks, 0, 1, 20, false, true);
            if (normal.color() != DuskPact.Color.YELLOW) assertEquals(normal.text(), hard.text());
            assertEquals(normal.tab(), hard.tab());
            assertEquals(normal.blocked(), hard.blocked());
        }
    }
}
