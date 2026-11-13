package dev.hardrush.core.lobby;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LobbyHintsTest {
    @Test void ongoingLinesGoOutAtOnceThenOnlyOnRefreshTicks() {
        var hints = new LobbyHints();
        hints.offer("facility", LobbyHints.Priority.FACILITY, "讲台", 1_000);
        assertEquals(Optional.of("讲台"), hints.render(0, false), "A new source is not held back to the refresh cadence");
        assertEquals(Optional.empty(), hints.render(1, false));
        assertEquals(Optional.of("讲台"), hints.render(2, true), "Refresh keeps the client's 3-second line lit");
        hints.offer("facility", LobbyHints.Priority.FACILITY, "讲台 2", 1_000);
        assertEquals(Optional.empty(), hints.render(3, false), "A countdown's changing text keeps the refresh cadence");
        assertEquals(Optional.of("讲台 2"), hints.render(4, true));
    }

    @Test void itemFlashOutranksAmbientGuidanceButNotProgressAndIsSentOnce() {
        var hints = new LobbyHints();
        hints.offer("facility", LobbyHints.Priority.FACILITY, "讲台", 10_000);
        hints.render(0, true);
        hints.flash("item", LobbyHints.Priority.ITEM, "按住右键引爆", 2_000);
        assertEquals(Optional.of("按住右键引爆"), hints.render(1, false));
        assertEquals(Optional.empty(), hints.render(2, true), "A flash is never refreshed; the client fades it");
        assertEquals(Optional.of("讲台"), hints.render(2_000, false), "Ambient guidance returns when the lease ends");

        hints.offer("return", LobbyHints.Priority.PROGRESS, "正在返回营地", 10_000);
        hints.flash("item", LobbyHints.Priority.ITEM, "按住右键引爆", 4_000);
        assertEquals(Optional.of("正在返回营地"), hints.render(2_001, true), "Progress keeps the bar");
        hints.clear("return");
        assertEquals(Optional.of("按住右键引爆"), hints.render(2_002, false), "The flash still within its lease takes over");
    }

    @Test void changedFlashTextIsSentAtOnce() {
        var hints = new LobbyHints();
        hints.flash("item", LobbyHints.Priority.ITEM, "三连射冷却中", 2_000);
        assertEquals(Optional.of("三连射冷却中"), hints.render(0, false));
        hints.flash("item", LobbyHints.Priority.ITEM, "潜行时拉满弓松手，触发三连射", 3_000);
        assertEquals(Optional.of("潜行时拉满弓松手，触发三连射"), hints.render(1, false));
    }

    @Test void withdrawingAShownFlashBlanksTheBarOnce() {
        var hints = new LobbyHints();
        hints.flash("item", LobbyHints.Priority.ITEM, "按住右键引爆", 2_000);
        hints.render(0, false);
        hints.clear("item");
        assertEquals(Optional.of(""), hints.render(1, false), "Switching to a plain item clears the old usage at once");
        assertEquals(Optional.empty(), hints.render(2, true));

        hints.flash("item", LobbyHints.Priority.ITEM, "按住右键引爆", 2_000);
        hints.render(3, false);
        assertEquals(Optional.empty(), hints.render(2_000, true), "A flash that ran its lease just fades on the client");
        hints.clear("item");
        assertEquals(Optional.empty(), hints.render(2_001, true));
    }

    @Test void withdrawingAFlashHandsTheBarToTheNextLineAndNeverBlanksSomeoneElsesLine() {
        var hints = new LobbyHints();
        hints.offer("facility", LobbyHints.Priority.FACILITY, "讲台", 10_000);
        hints.flash("item", LobbyHints.Priority.ITEM, "按住右键引爆", 2_000);
        hints.render(0, false);
        hints.clear("item");
        assertEquals(Optional.of("讲台"), hints.render(1, false));

        hints.flash("item", LobbyHints.Priority.ITEM, "按住右键引爆", 2_000);
        hints.offer("notice", LobbyHints.Priority.NOTICE, "现在不能返回营地", 3_000);
        assertEquals(Optional.of("现在不能返回营地"), hints.render(2, false));
        hints.clear("item");
        assertEquals(Optional.empty(), hints.render(3, false), "The notice on screen stays");
    }
}
