package dev.hardrush.monster;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.round.MonsterSpoils;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MonsterWorthRewardsTest {
    @BeforeAll static void bootstrap() {
        // Items reach the registries.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

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

    @Test void everyBrewingMaterialIsItsOwnNativeItemAndNoneIsOnTheForbiddenList() {
        var forbidden = List.of(Items.BLAZE_POWDER, Items.BLAZE_ROD, Items.ENDER_PEARL, Items.ENDER_EYE, Items.GOLD_INGOT,
                Items.GOLD_NUGGET, Items.GOLD_BLOCK, Items.OBSIDIAN, Items.CRYING_OBSIDIAN, Items.FLINT_AND_STEEL,
                Items.FIRE_CHARGE, Items.STRING, Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION,
                Items.SUGAR, Items.SPIDER_EYE, Items.SLIME_BALL, Items.COBWEB, Items.STONE, Items.GUNPOWDER, Items.TURTLE_HELMET);
        var items = new HashSet<>();
        for (var material : MonsterSpoils.Material.values()) {
            var item = MonsterWorthRewards.item(material);
            String path = BuiltInRegistries.ITEM.getKey(item).getPath();
            assertEquals(material.name().toLowerCase(Locale.ROOT), path);
            assertFalse(forbidden.contains(item), path);
            assertFalse(path.endsWith("_bed") || path.endsWith("_wool"), path);
            assertTrue(items.add(item), "one item per material: " + path);
        }
    }
}
