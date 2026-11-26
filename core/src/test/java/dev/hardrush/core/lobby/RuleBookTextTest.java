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
        assertTrue(totem.contains("人类每阵亡一次、每用掉一个图腾，都会放出 1 个：怪物击杀或逼出的直接归怪物，其余掉在原地，"));
        assertTrue(totem.contains("人类和出击中的怪物都能捡，但放出它的那条命捡不回来。"));
        assertTrue(totem.contains("没有被人击杀或逼出时，掉落会公布坐标"));
        assertFalse(totem.contains("转赠"), "Giving a totem away through a spent life is possible, so the book promises nothing");
        assertTrue(totem.contains("阵亡时自己的图腾不会掉落"));
        var revival = page(hardcore, "复生");
        assertTrue(revival.contains("拿在手上阵亡时原地复生，消耗 1 个"));
        assertTrue(revival.contains("营地北侧的祭坛站上金块 4 秒复生，可先用怪物币在女巫处买复生补给"));
        assertTrue(revival.contains("持续 90 秒"), "The blessing is 90 seconds before any longer one is bought");
        var coins = page(hardcore, "怪物币");
        assertTrue(coins.contains("药水出击时自己喝，药水盲盒喝下才揭晓；亡灵喝伤害药水会回血"), "The witch's potions are drunk");
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

    @Test void leavingMidFightCountsAsDeathOnlyInHardcore() {
        assertTrue(page(pages(GameMode.HARDCORE), "离线与救场").contains("人类离线时保留资格，但被玩家打中后 5 秒内断线算作阵亡。"));
        assertTrue(page(pages(GameMode.NORMAL), "离线与救场").contains("人类离线时保留资格。所有人类都离线时"));
    }

    @Test void deathByAMonsterKeepsBelongingsAndTheStarsSetLocksAndSlots() {
        var hardcore = pages(GameMode.HARDCORE);
        assertTrue(page(hardcore, "阵亡").contains("被怪物玩家杀死时，身上的东西和经验都不掉落"));
        var wary = page(hardcore, "提防与满员");
        assertTrue(wary.contains("一星 120 秒，五星 30 秒") && wary.contains("返回营地不算") && wary.contains("满员时排队"), wary);
        assertFalse(page(hardcore, "悬赏").contains("被怪物从营地追猎"), "Being hunted no longer scores");
        assertFalse(page(pages(GameMode.NORMAL), "阵亡").contains("怪物玩家"));
    }

    @Test void theHuntPageTellsBothSidesAboutTheLandingOmen() {
        assertTrue(page(pages(GameMode.HARDCORE), "追猎").contains("僵尸和骷髅落地时，目标会听到一声洞穴音；苦力怕悄无声息"));
    }

    @Test void theCompassHasItsOwnPageWithTheRoundsChannelAndRest() {
        var compass = page(pages(GameMode.HARDCORE), "追迹罗盘");
        assertTrue(compass.contains("边走边引导 5 秒") && compass.contains("冷却 10 秒"), compass);
        assertNull(page(pages(GameMode.NORMAL), "追迹罗盘"));
    }

    @Test void paidCreepersGetTheirOwnPageWithTheRoundsPrice() {
        var encoded = RulesConfig.defaults().encode();
        var custom = RulesConfig.decode(encoded.replace("combat.coin.creeperUnlock=40.0", "combat.coin.creeperUnlock=" + Integer.MAX_VALUE));
        var paidPage = page(RuleBookText.pages(custom), "苦力怕解锁");
        assertTrue(paidPage.contains("付 " + Integer.MAX_VALUE + " 怪物币解锁"));
        assertTrue(paidPage.contains("只追三星以上的人类"));
        assertTrue(wrappedLines(paidPage) <= 14);
        assertTrue(page(RuleBookText.pages(custom), "角色").endsWith("\n\n苦力怕按住引爆器（打火石）引爆，也能用它点燃原版苦力怕。"));

        var legacy = RulesConfig.decode(encoded.replace("combat.coin.creeperUnlock=40.0\n", ""));
        assertTrue(RuleBookText.pages(legacy).stream().noneMatch(text -> text.startsWith("苦力怕解锁")));
    }

    @Test void everyPageFitsTheFourteenLinesABookShows() {
        // A written book never scrolls: BookViewScreen shows 14 lines of 114 pixels and clips the rest.
        for (var mode : GameMode.values())
            for (var page : pages(mode)) assertTrue(wrappedLines(page) <= 14, wrappedLines(page) + " lines: " + page);
    }

    @Test void theWrapModelBreaksAtTheLastSpaceLikeTheVanillaSplitter() {
        // 12 wide glyphs fill a line; a space earlier on the line takes the break, so " 5 " costs this paragraph a line.
        assertEquals(1, wrappedLines("一二三四五六七八九十一二"));
        assertEquals(2, wrappedLines("一二三四五六七八九十一二三"));
        assertEquals(4, wrappedLines("出击后右键回响碎片，原地不动 5 秒返回营地，物资暂存到下次。"));
    }

    /**
     * An approximation of the vanilla book layout: CJK glyphs and full-width punctuation advance 9 pixels, ASCII
     * uses the default font's advances, and an overflowing line breaks after its last space when it has one.
     * Key placeholders are measured as a typical key name.
     */
    private static int wrappedLines(String page) {
        int lines = 0;
        for (var paragraph : page.replaceAll("\\{key:[^}]*}", "Shift").split("\n", -1)) {
            lines++;
            int width = 0, sinceSpace = -1;
            for (int i = 0; i < paragraph.length(); i++) {
                char c = paragraph.charAt(i);
                int advance = advance(c);
                if (width + advance > 114) {
                    lines++;
                    width = sinceSpace >= 0 ? sinceSpace : 0;
                    sinceSpace = -1;
                }
                width += advance;
                if (c == ' ') sinceSpace = 0;
                else if (sinceSpace >= 0) sinceSpace += advance;
            }
        }
        return lines;
    }

    private static int advance(char c) {
        if (c >= 128) return 9;
        return switch (c) {
            case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 2;
            case 'l', '`' -> 3;
            case ' ', 't', 'I', '[', ']', '(', ')', '{', '}', '*', '"' -> 4;
            case 'f', 'k', '<', '>' -> 5;
            default -> 6;
        };
    }
}
