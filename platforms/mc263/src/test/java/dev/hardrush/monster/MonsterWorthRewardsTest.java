package dev.hardrush.monster;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MonsterWorthRewardsTest {
    @Test void theFountainSpraysTheWholeExperienceWithAFifthOrMoreInTheLastWave() {
        for (int xp : new int[] {100, 150, 300}) {
            int[] waves = MonsterWorthRewards.waves(xp);
            assertEquals(MonsterWorthRewards.WAVES, waves.length);
            assertEquals(xp, Arrays.stream(waves).sum());
            for (int i = 0; i < waves.length - 1; i++) assertEquals(waves[0], waves[i]);
            assertTrue(waves[waves.length - 1] * 5 >= xp);
        }
        assertArrayEquals(new int[] {8, 8, 8, 8, 8, 8, 8, 8, 8, 28}, MonsterWorthRewards.waves(100));
        assertArrayEquals(new int[] {26, 26, 26, 26, 26, 26, 26, 26, 26, 66}, MonsterWorthRewards.waves(300));
    }
}
