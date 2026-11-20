package dev.hardrush.core.lobby;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleBookTextTest {
    private static List<String> pages(GameMode mode) { return RuleBookText.pages(RulesConfig.defaults().withMode(mode)); }

    private static String page(List<String> pages, String title) {
        return pages.stream().filter(page -> page.startsWith(title + "\n")).findFirst().orElse(null);
    }

    @Test void hardcorePactPageAnnouncesTheTotemDropAndNormalDoesNot() {
        assertTrue(page(pages(GameMode.HARDCORE), "黄昏契约").endsWith("日落后，人类被人类击杀时会掉落 1 个复生图腾。"));
        assertFalse(page(pages(GameMode.NORMAL), "黄昏契约").contains("复生图腾"));
    }

    @Test void hardcoreSplitsTheTotemAndRevivalPages() {
        var hardcore = pages(GameMode.HARDCORE);
        var totem = page(hardcore, "复生图腾");
        assertTrue(totem.contains("人类被人类击杀会掉落 1 个，人类捡起即得；怪物击杀人类直接获得 1 个。"));
        assertTrue(totem.contains("阵亡时自己的图腾不会掉落"));
        var revival = page(hardcore, "复生");
        assertTrue(revival.contains("拿在手上阵亡时原地复生，消耗 1 个"));
        assertTrue(revival.contains("营地北侧的祭坛站上金块 4 秒复生，空手出发"));
        assertEquals(hardcore.indexOf(totem) + 1, hardcore.indexOf(revival));
        assertFalse(hardcore.stream().anyMatch(page -> page.contains("击杀一名人类获得")), "The old kill reward is gone");
        var normal = pages(GameMode.NORMAL);
        assertNull(page(normal, "复生图腾"));
        assertNull(page(normal, "复生"));
    }

    @Test void theRewrittenPagesStayWithinTheLongestPageTheBookAlreadyHad() {
        // A written book never scrolls: the pages this plan touched are no longer than any untouched page.
        var hardcore = pages(GameMode.HARDCORE);
        var touched = List.of(page(hardcore, "黄昏契约"), page(hardcore, "复生图腾"), page(hardcore, "复生"));
        int limit = hardcore.stream().filter(page -> !touched.contains(page)).mapToInt(String::length).max().orElseThrow();
        for (var page : touched) assertTrue(page.length() <= limit, page);
    }
}
