package dev.hardrush.monster;

import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.core.round.MonsterWallet.GearSlot;
import dev.hardrush.core.round.ShopOffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.LegacyRandomSource;

public final class MonsterGachaGameTest {
    @GameTest
    public void normalVaultUsesNativeMaterialStepsAndAStrictEnchantBoundary(GameTestHelper test) {
        var random = new ScriptedRandom(List.of(Math.nextDown(0.1087F), 0.2F, 0.2F, 0.5F), List.of(0, 2));
        var draw = MonsterGacha.draw(random, MonsterRole.ZOMBIE, ShopOffer.TRIAL_KEY, (stack, rng, cost) -> {
            throw new AssertionError("The 50% boundary is not enchanted");
        });
        test.assertTrue(draw.slot() == GearSlot.HEAD && draw.stack().is(Items.CHAINMAIL_HELMET), "Gold plus one upgrade is chainmail");
        test.assertTrue(draw.roles().equals(Set.of(MonsterRole.ZOMBIE, MonsterRole.SKELETON)), "Armor serves both armored roles");
        random.assertExhausted();
        test.succeed();
    }

    @GameTest
    public void ominousVaultCapsAtDiamondAndAlwaysEnchants(GameTestHelper test) {
        var random = new ScriptedRandom(List.of(0F, 0F, 0F, Math.nextDown(1F)), List.of(1, 1, 15));
        var costs = new ArrayList<Integer>();
        var draw = MonsterGacha.draw(random, MonsterRole.SKELETON, ShopOffer.OMINOUS_KEY, (stack, rng, cost) -> costs.add(cost));
        test.assertTrue(draw.stack().is(Items.DIAMOND_CHESTPLATE), "Native mob armor never exceeds diamond");
        test.assertTrue(costs.equals(List.of(30)), "Ominous enchantment input spans 15–30");
        random.assertExhausted();
        test.succeed();
    }

    @GameTest
    public void weaponsAreOnlyWhatTheRoleNativelyHolds(GameTestHelper test) {
        var bow = MonsterGacha.draw(new ScriptedRandom(List.of(0.9F), List.of(4)), MonsterRole.SKELETON, ShopOffer.TRIAL_KEY,
                (stack, rng, cost) -> { throw new AssertionError(); });
        test.assertTrue(bow.slot() == GearSlot.MAINHAND && bow.stack().is(Items.BOW) && bow.roles().equals(Set.of(MonsterRole.SKELETON)),
                "Skeletons draw bows");
        for (int weapon = 0; weapon < 6; weapon++) {
            var draw = MonsterGacha.draw(new ScriptedRandom(List.of(0.9F), List.of(4, weapon)), MonsterRole.ZOMBIE, ShopOffer.TRIAL_KEY,
                    (stack, rng, cost) -> { throw new AssertionError(); });
            test.assertTrue(draw.stack().is(switch (weapon) {
                case 0 -> Items.IRON_SWORD;
                case 1 -> Items.IRON_SPEAR;
                default -> Items.IRON_SHOVEL;
            }) && draw.roles().equals(Set.of(MonsterRole.ZOMBIE)), "Zombie weapons keep native spawn weights");
        }
        var ominous = MonsterGacha.draw(new ScriptedRandom(List.of(0.9F), List.of(4, 1, 0)), MonsterRole.ZOMBIE, ShopOffer.OMINOUS_KEY,
                (stack, rng, cost) -> {});
        test.assertTrue(ominous.stack().is(Items.IRON_SPEAR), "Ominous vaults never give the shovel");
        test.assertTrue(isCreeperRejected(), "Creepers cannot draw equipment");
        test.succeed();
    }

    @GameTest
    public void drawnStacksSurviveTheWalletTextEncoding(GameTestHelper test) {
        var registries = test.getLevel().registryAccess();
        var draw = MonsterGacha.draw(test.getLevel(), test.getLevel().getRandom(), MonsterRole.ZOMBIE, ShopOffer.OMINOUS_KEY);
        var text = MonsterGacha.encode(registries, draw.stack());
        test.assertTrue(ItemStack.matches(draw.stack(), MonsterGacha.decode(registries, text)), "Encoded gear decodes unchanged");
        test.assertTrue(MonsterGacha.decode(registries, "{broken").isEmpty(), "Corrupt text decodes to nothing");
        test.succeed();
    }

    private static boolean isCreeperRejected() {
        try {
            MonsterGacha.draw(new ScriptedRandom(List.of(), List.of()), MonsterRole.CREEPER, ShopOffer.TRIAL_KEY, (stack, rng, cost) -> {});
            return false;
        } catch (IllegalArgumentException expected) { return true; }
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
