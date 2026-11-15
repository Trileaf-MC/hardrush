package dev.hardrush.core.lobby;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class LobbyCreditsTest {
    @Test void aShortListFitsOnTheBuildersLine() {
        assertEquals(List.of("大厅：默认营地", "v1", "建设者：A、B、C", ""), LobbyCredits.signLines("默认营地", 1, List.of("A", "B", "C")));
    }

    @Test void longerListsContinueOnTheLastLine() {
        var lines = LobbyCredits.signLines("营地", 3, List.of("Alice", "Bob", "Carol"));
        assertEquals("建设者：Alice", lines.get(2));
        assertEquals("Bob、Carol", lines.get(3));
    }

    @Test void whatDoesNotFitIsCutWithDeng() {
        var authors = List.of("Alice", "Bob", "Carol", "Dave", "Eve", "Frank", "Grace", "Heidi", "Ivan", "Judy");
        var lines = LobbyCredits.signLines("营地", 1, authors);
        assertTrue(lines.get(3).endsWith("等"), lines.toString());
        assertFalse(lines.get(3).endsWith("、等"), "no dangling separator: " + lines);
        for (var line : lines) assertTrue(LobbyCredits.width(line) <= LobbyCredits.SIGN_LINE_WIDTH, line);
        // A single overlong name is cut too.
        var one = LobbyCredits.signLines("营地", 1, List.of("一个特别特别特别特别长的名字"));
        assertTrue(one.get(2).startsWith("建设者：一") && one.get(2).endsWith("等"), one.toString());
        assertTrue(LobbyCredits.width(one.get(2)) <= LobbyCredits.SIGN_LINE_WIDTH);
    }

    @Test void longNamesAreCutWithAnEllipsis() {
        var lines = LobbyCredits.signLines("一个名字很长很长很长很长的大厅", 12, List.of());
        assertTrue(lines.get(0).endsWith("…") && LobbyCredits.width(lines.get(0)) <= LobbyCredits.SIGN_LINE_WIDTH, lines.get(0));
        assertEquals(List.of("v12", "", ""), lines.subList(1, 4));
    }

    @Test void theDisplayShowsEverything() {
        assertEquals("大厅：默认营地 v1\n建设者：A、B、C", LobbyCredits.display("默认营地", 1, List.of("A", "B", "C")));
        assertEquals("大厅：营地 v2", LobbyCredits.display("营地", 2, List.of()));
    }
}
