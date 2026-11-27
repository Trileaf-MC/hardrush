package dev.hardrush.core.lobby;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.lobby.WitchGuideText.*;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class WitchGuideTextTest {
    private static Stream<Words> words() {
        return WitchGuideText.PAGES.stream().flatMap(page -> page.blocks().stream())
                .flatMap(block -> block instanceof Paragraph paragraph ? paragraph.lines().stream() : Stream.empty())
                .flatMap(line -> line.spans().stream()).flatMap(span -> span instanceof Words w ? Stream.of(w) : Stream.empty());
    }

    @Test void theGuideHasFourPagesAndOnlyTheFirstGiggles() {
        assertEquals(4, WitchGuideText.PAGES.size());
        assertEquals(List.of(Optional.of(Cue.GIGGLE), Optional.empty(), Optional.empty(), Optional.empty()),
                WitchGuideText.PAGES.stream().map(Page::cue).toList());
    }

    @Test void theKeyPromisesStandOut() {
        assertEquals(List.of("你还能变回人类！", "而怪物币可以通过对人类造成伤害，或者被人类杀死获得。", "我可以给你卖点人类需要用的装备"),
                words().filter(w -> w.look() == Look.KEY).map(Words::text).toList());
    }

    @Test void theSecondPageShowsTheMonstersBesideTheWitch() {
        var heads = WitchGuideText.PAGES.get(1).blocks().stream()
                .flatMap(block -> block instanceof Paragraph paragraph ? paragraph.lines().stream() : Stream.empty())
                .flatMap(line -> line.spans().stream()).flatMap(span -> span instanceof Face f ? Stream.of(f.head()) : Stream.empty()).toList();
        assertEquals(List.of(Head.ZOMBIE, Head.SKELETON, Head.CREEPER), heads);
        assertTrue(words().anyMatch(w -> w.text().equals("你可以右键我旁边的几个家伙，")));
    }

    @Test void theLastPageIsJustTheFarewell() {
        assertEquals(List.of(new Paragraph(Optional.empty(), List.of(new Line(List.of(new Words("购物愉快 o.O", Look.PLAIN)))))),
                WitchGuideText.PAGES.getLast().blocks());
    }
}
