package dev.hardrush.monster;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.LegacyRandomSource;

public final class MonsterSpawnEquipmentGameTest {
    /** docs/plan/悬赏分档与追猎强度.md §5.4: five stars always wear a whole set of chainmail, iron or diamond. */
    @GameTest
    public void fiveStarSortiesWearAWholeSetOfAtLeastChainmail(GameTestHelper test) {
        var random = new LegacyRandomSource(74);
        int ironOrBetter = 0, rolls = 400;
        for (int i = 0; i < rolls; i++) {
            var gear = MonsterSpawnEquipment.generate(random, false, dev.hardrush.core.round.HuntPressure.armor(5), (stack, rng, cost) -> {});
            for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET))
                test.assertFalse(gear.get(slot).isEmpty(), "A five-star sortie wears every piece");
            var chest = gear.get(EquipmentSlot.CHEST);
            test.assertTrue(chest.is(Items.CHAINMAIL_CHESTPLATE) || chest.is(Items.IRON_CHESTPLATE) || chest.is(Items.DIAMOND_CHESTPLATE),
                    "Never weaker than chainmail: " + chest);
            if (!chest.is(Items.CHAINMAIL_CHESTPLATE)) ironOrBetter++;
        }
        test.assertTrue(ironOrBetter > rolls * 0.8 && ironOrBetter < rolls, "About nine in ten are iron or diamond: " + ironOrBetter);
        test.succeed();
    }


    @GameTest
    public void spawnChancesUseStrictNativeThresholds(GameTestHelper test) {
        var empty = new ScriptedRandom(List.of(0.15F, 0.05F), List.of());
        var gear = MonsterSpawnEquipment.generate(empty, false, (stack, random, cost) -> {
            throw new AssertionError("Empty equipment must not be enchanted");
        });
        test.assertTrue(gear.values().stream().allMatch(stack -> stack.isEmpty()), "Thresholds exclude equality");
        empty.assertExhausted();
        for (int weapon = 0; weapon < 6; weapon++) {
            var random = new ScriptedRandom(List.of(0.15F, Math.nextDown(0.05F), 0.25F), List.of(weapon));
            var result = MonsterSpawnEquipment.generate(random, false, (stack, rng, cost) -> {
                throw new AssertionError("The 25% boundary is not enchanted");
            });
            test.assertTrue(result.get(EquipmentSlot.MAINHAND).is(switch (weapon) {
                case 0 -> Items.IRON_SWORD;
                case 1 -> Items.IRON_SPEAR;
                default -> Items.IRON_SHOVEL;
            }), "All six native weapon outcomes retain their weights");
            random.assertExhausted();
        }
        test.succeed();
    }

    @GameTest
    public void armorIncludesCopperAndUsesHardPartialChance(GameTestHelper test) {
        for (int material = 0; material < 6; material++) {
            var draws = new ArrayList<Float>();
            draws.add(Math.nextDown(0.15F));
            int initial = Math.min(material, 2);
            for (int i = 0; i < 3; i++) draws.add(i < material - initial ? Math.nextDown(0.1087F) : 0.1087F);
            draws.addAll(List.of(0.1F, 0.1F, 0.1F, 0.05F, 0.5F, 0.5F, 0.5F, 0.5F));
            var random = new ScriptedRandom(draws, List.of(initial));
            var gear = MonsterSpawnEquipment.generate(random, false, (stack, rng, cost) -> {
                throw new AssertionError("The 50% armor boundary is not enchanted");
            });
            for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
                test.assertTrue(gear.get(slot).is(Mob.getEquipmentForSlot(slot, material)), "All six native armor materials are reachable");
            }
            random.assertExhausted();
        }
        var partial = new ScriptedRandom(List.of(0F, 0.1087F, 0.1087F, 0.1087F,
                Math.nextDown(0.1F), 0.05F, Math.nextDown(0.5F)), List.of(0, 25));
        var costs = new ArrayList<Integer>();
        var gear = MonsterSpawnEquipment.generate(partial, false, (stack, rng, cost) -> costs.add(cost));
        test.assertTrue(gear.get(EquipmentSlot.HEAD).is(Items.LEATHER_HELMET)
                && gear.get(EquipmentSlot.CHEST).isEmpty(), "Partial armor starts at the head and stops before the chest");
        test.assertTrue(costs.equals(List.of(30)), "Armor below the enchantment threshold can use level 30");
        partial.assertExhausted();
        test.succeed();
    }

    @GameTest
    public void skeletonBowEnchantmentInputIncludesFiveAndThirty(GameTestHelper test) {
        for (int endpoint : List.of(0, 25)) {
            var random = new ScriptedRandom(List.of(0.15F, Math.nextDown(0.25F)), List.of(endpoint));
            var costs = new ArrayList<Integer>();
            var gear = MonsterSpawnEquipment.generate(random, true, (stack, rng, cost) -> costs.add(cost));
            test.assertTrue(gear.get(EquipmentSlot.MAINHAND).is(Items.BOW), "Skeletons always receive a native bow");
            test.assertTrue(costs.equals(List.of(5 + endpoint)), "Both inclusive enchantment endpoints are reachable");
            random.assertExhausted();
        }
        test.succeed();
    }

    private static final class ScriptedRandom extends LegacyRandomSource {
        private final ArrayDeque<Float> floats;
        private final ArrayDeque<Integer> integers;
        ScriptedRandom(List<Float> floats, List<Integer> integers) {
            super(0);
            this.floats = new ArrayDeque<>(floats);
            this.integers = new ArrayDeque<>(integers);
        }
        @Override public float nextFloat() { return floats.removeFirst(); }
        @Override public int nextInt(int bound) {
            int value = integers.removeFirst();
            if (value < 0 || value >= bound) throw new AssertionError("Unexpected random bound: " + bound);
            return value;
        }
        void assertExhausted() {
            if (!floats.isEmpty() || !integers.isEmpty()) throw new AssertionError("Unused random draws");
        }
    }
}
