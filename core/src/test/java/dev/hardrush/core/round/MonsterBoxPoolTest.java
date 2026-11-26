package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.round.MonsterBoxPool.Group;
import dev.hardrush.core.round.MonsterBoxPool.Result;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MonsterBoxPoolTest {
    private static Map<Group, Integer> groups(int tier) {
        var shares = new EnumMap<Group, Integer>(Group.class);
        MonsterBoxPool.weights(tier).forEach((result, weight) -> shares.merge(result.group(), weight, Integer::sum));
        return shares;
    }

    @Test void everyTierWeighsTheWholeTotal() {
        for (int tier = 0; tier < MonsterBoxPool.TIERS; tier++) {
            assertEquals(MonsterBoxPool.TOTAL, MonsterBoxPool.weights(tier).values().stream().mapToInt(Integer::intValue).sum());
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
            assertFalse(MonsterBoxPool.weights(tier).containsKey(Result.WITHER));
            assertFalse(MonsterBoxPool.weights(tier).containsKey(Result.WARDEN));
        }
        assertEquals(MonsterBoxPool.TOTAL / 500, MonsterBoxPool.weights(3).get(Result.WITHER));
        assertEquals(MonsterBoxPool.TOTAL / 300, MonsterBoxPool.weights(3).get(Result.WARDEN));
        assertEquals(MonsterBoxPool.weights(3), MonsterBoxPool.weights(7), "Higher tiers count as epic");
    }

    @Test void resultsSplitTheirGroupByPercent() {
        assertEquals(9_975, MonsterBoxPool.weights(0).get(Result.ZOMBIE));
        assertEquals(1_425, MonsterBoxPool.weights(0).get(Result.CHICKEN_JOCKEY));
        assertEquals(600, MonsterBoxPool.weights(0).get(Result.CHICKEN));
        assertEquals(300, MonsterBoxPool.weights(0).get(Result.VILLAGER));
        assertEquals(1_980, MonsterBoxPool.weights(2).get(Result.CREEPER));
        assertEquals(786, MonsterBoxPool.weights(3).get(Result.RAVAGER));
    }

    @Test void drawsWalkTheWeightsInOrder() {
        int tier = 3;
        int seen = 0;
        for (var entry : MonsterBoxPool.weights(tier).entrySet()) {
            assertEquals(entry.getKey(), MonsterBoxPool.draw(tier, seen));
            assertEquals(entry.getKey(), MonsterBoxPool.draw(tier, seen + entry.getValue() - 1));
            seen += entry.getValue();
        }
        assertEquals(MonsterBoxPool.TOTAL, seen);
        assertThrows(IllegalArgumentException.class, () -> MonsterBoxPool.draw(0, MonsterBoxPool.TOTAL));
        assertThrows(IllegalArgumentException.class, () -> MonsterBoxPool.draw(0, -1));
        assertThrows(IllegalArgumentException.class, () -> MonsterBoxPool.draw(-1, 0));
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
