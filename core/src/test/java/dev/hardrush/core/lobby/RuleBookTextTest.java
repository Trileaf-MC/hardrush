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
        assertTrue(totem.contains("人类被人类击杀或逼出图腾时会掉落 1 个，人类捡起即得；怪物击杀人类或逼出图腾直接获得 1 个。"));
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

    @Test void diggingPageReadsTheRoundsCapAndOnlyHardcoreHuntsHaveIt() {
        var dig = page(pages(GameMode.HARDCORE), "挖掘与虫穴");
        assertTrue(dig.contains("最长 7.5 秒，不掉落。苦力怕挖不动。"), dig);
        assertTrue(dig.contains("破石而出"));
        var values = new java.util.TreeMap<>(RulesConfig.defaults().combatValues());
        values.put("monster.digMillis", 3_000.0);
        var rules = RulesConfig.defaults();
        rules = new RulesConfig(rules.schemaVersion(), rules.mode(), rules.minecraftVersion(), rules.difficulty(), rules.generationRule(),
                values, rules.startCountdownMillis(), rules.successCelebrationMillis(), rules.failureDisplayMillis());
        assertTrue(page(RuleBookText.pages(rules), "挖掘与虫穴").contains("最长 3 秒"));
        assertNull(page(pages(GameMode.NORMAL), "挖掘与虫穴"));
    }

    @Test void entryPageNamesTheLateShelterOnlyWhenMonstersHuntAndRevivalPageTheBlessing() {
        var hardcore = page(pages(GameMode.HARDCORE), "开局与加入");
        assertTrue(hardcore.contains("落地后 5 分钟内怪物无法追猎你，离线不计时。"), hardcore);
        assertFalse(hardcore.contains("出生保护"));
        var normal = page(pages(GameMode.NORMAL), "开局与加入");
        assertFalse(normal.contains("追猎") || normal.contains("出生保护"), normal);
        assertTrue(page(pages(GameMode.HARDCORE), "复生").contains("图腾之力庇护"));
    }

    @Test void theRewrittenPagesStayWithinTheLongestPageTheBookAlreadyHad() {
        // A written book never scrolls: the pages this plan touched are no longer than any untouched page.
        var hardcore = pages(GameMode.HARDCORE);
        var touched = List.of(page(hardcore, "开局与加入"), page(hardcore, "黄昏契约"), page(hardcore, "复生图腾"), page(hardcore, "复生"), page(hardcore, "挖掘与虫穴"));
        int limit = hardcore.stream().filter(page -> !touched.contains(page)).mapToInt(String::length).max().orElseThrow();
        for (var page : touched) assertTrue(page.length() <= limit, page);
    }
}
