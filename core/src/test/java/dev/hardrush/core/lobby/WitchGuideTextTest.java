package dev.hardrush.core.lobby;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.RulesConfig;
import org.junit.jupiter.api.Test;

class WitchGuideTextTest {
    private static boolean mentionsCreeperPrice(RulesConfig rules, String price) {
        return WitchGuideText.pages(rules).get(1).stream().anyMatch(line -> line.text().contains("苦力怕要先付我 " + price + " 怪物币"));
    }

    @Test void paidCreepersAreAnnouncedWithTheRoundsPrice() {
        var encoded = RulesConfig.defaults().encode();
        assertTrue(mentionsCreeperPrice(RulesConfig.defaults(), "40"));
        assertTrue(mentionsCreeperPrice(RulesConfig.decode(encoded.replace("combat.coin.creeperUnlock=40.0", "combat.coin.creeperUnlock=55.0")), "55"));
        assertEquals(WitchGuideText.PAGES.get(1).size() + 2, WitchGuideText.pages(RulesConfig.defaults()).get(1).size());
    }

    @Test void roundsWithFreeCreepersReadTheGuideAsWritten() {
        var legacy = RulesConfig.decode(RulesConfig.defaults().encode().replace("combat.coin.creeperUnlock=40.0\n", ""));
        assertSame(WitchGuideText.PAGES, WitchGuideText.pages(legacy));
        assertTrue(WitchGuideText.PAGES.stream().flatMap(java.util.List::stream).noneMatch(line -> line.text().contains("苦力怕要先付")));
    }
}
