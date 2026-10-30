package dev.hardrush.monster;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MonsterWorthRewardsTest {
    @Test void theFountainSpraysTheWholeWorthInEqualWavesWithTheRemainderLast() {
        for (int xp : new int[] {100, 160, 315}) {
            int[] waves = MonsterWorthRewards.waves(xp);
            assertEquals(MonsterWorthRewards.WAVES, waves.length);
            assertEquals(xp, Arrays.stream(waves).sum());
            for (int i = 0; i < waves.length - 1; i++) assertEquals(xp / waves.length, waves[i]);
        }
        assertArrayEquals(new int[] {16, 16, 16, 16, 16, 20}, MonsterWorthRewards.waves(100));
    }
}
