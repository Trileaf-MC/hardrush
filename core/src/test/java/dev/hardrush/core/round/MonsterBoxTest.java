package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.round.MonsterBox.Group;
import dev.hardrush.core.round.MonsterBox.Result;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MonsterBoxTest {
    private static Map<Group, Integer> groups(int tier) {
        var shares = new EnumMap<Group, Integer>(Group.class);
        MonsterBox.weights(tier).forEach((result, weight) -> shares.merge(result.group(), weight, Integer::sum));
        return shares;
    }

    @Test void everyTierWeighsTheWholeTotal() {
        for (int tier = 0; tier < MonsterBox.TIERS; tier++) {
            assertEquals(MonsterBox.TOTAL, MonsterBox.weights(tier).values().stream().mapToInt(Integer::intValue).sum());
        }
    }

    @Test void groupSharesFollowThePlan() {
        assertEquals(Map.of(Group.ZOMBIES, 28_500, Group.NOVELTY, 1_500), groups(0));
        assertEquals(Map.of(Group.ZOMBIES, 19_500, Group.SKELETONS_AND_SPIDERS, 9_000, Group.NOVELTY, 1_500), groups(1));
        assertEquals(Map.of(Group.ZOMBIES, 13_500, Group.SKELETONS_AND_SPIDERS, 8_400, Group.RARE, 6_600,
                Group.NOVELTY, 1_500), groups(2));
        assertEquals(Map.of(Group.ZOMBIES, 9_900, Group.SKELETONS_AND_SPIDERS, 6_600, Group.RARE, 6_600, Group.EPIC, 5_240,
                Group.BOSS_WITHER, 60, Group.BOSS_WARDEN, 100, Group.NOVELTY, 1_500), groups(3));
    }

    @Test void bossesOnlyComeFromAnEpicSoulcatcherAtOneInFiveAndThreeHundred() {
        for (int tier = 0; tier < 3; tier++) {
            assertFalse(MonsterBox.weights(tier).containsKey(Result.WITHER));
            assertFalse(MonsterBox.weights(tier).containsKey(Result.WARDEN));
        }
        assertEquals(MonsterBox.TOTAL / 500, MonsterBox.weights(3).get(Result.WITHER));
        assertEquals(MonsterBox.TOTAL / 300, MonsterBox.weights(3).get(Result.WARDEN));
        assertEquals(MonsterBox.weights(3), MonsterBox.weights(7), "Higher tiers count as epic");
    }

    @Test void resultsSplitTheirGroupByPercent() {
        assertEquals(9_975, MonsterBox.weights(0).get(Result.ZOMBIE));
        assertEquals(1_425, MonsterBox.weights(0).get(Result.CHICKEN_JOCKEY));
        assertEquals(600, MonsterBox.weights(0).get(Result.CHICKEN));
        assertEquals(300, MonsterBox.weights(0).get(Result.VILLAGER));
        assertEquals(1_980, MonsterBox.weights(2).get(Result.CREEPER));
        assertEquals(786, MonsterBox.weights(3).get(Result.RAVAGER));
    }

    @Test void drawsWalkTheWeightsInOrder() {
        int tier = 3;
        int seen = 0;
        for (var entry : MonsterBox.weights(tier).entrySet()) {
            assertEquals(entry.getKey(), MonsterBox.draw(tier, seen));
            assertEquals(entry.getKey(), MonsterBox.draw(tier, seen + entry.getValue() - 1));
            seen += entry.getValue();
        }
        assertEquals(MonsterBox.TOTAL, seen);
        assertThrows(IllegalArgumentException.class, () -> MonsterBox.draw(0, MonsterBox.TOTAL));
        assertThrows(IllegalArgumentException.class, () -> MonsterBox.draw(0, -1));
        assertThrows(IllegalArgumentException.class, () -> MonsterBox.draw(-1, 0));
    }

    @Test void fanfareMarksRareAdditionsAndUp() {
        assertFalse(Result.ZOMBIE.notable());
        assertFalse(Result.SPIDER_JOCKEY.notable());
        assertFalse(Result.VILLAGER.notable());
        assertTrue(Result.CREEPER.notable());
        assertTrue(Result.RAVAGER.notable());
        assertTrue(Result.WITHER.notable() && Result.WITHER.boss());
        assertTrue(Result.WARDEN.boss());
    }
}
