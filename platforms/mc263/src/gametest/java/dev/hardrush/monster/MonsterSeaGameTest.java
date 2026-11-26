package dev.hardrush.monster;

import dev.hardrush.gametest.MonsterRoleGameTest;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Every drowned holds the sea's trident, and a deliberate return parks the form it was turned into; in-process bodies without a client. */
public final class MonsterSeaGameTest {
    @GameTest(maxTicks=200)
    public void aSeaRisenDrownedParksAsADrownedWithItsTridentAndTimedEffects(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        try {
            MonsterRoles.select(p, "zombie");
            MonsterRoleGameTest.withEggs(p);
            MonsterRoles.riseFromSea(p);
            test.assertValueEqual(MonsterRoles.currentForm(p), MonsterForm.DROWNED, "A sea deployment is a drowned at once");
            test.assertTrue(MonsterItems.matches(p, p.getMainHandItem(), MonsterRoles.SEA_TRIDENT) && p.getMainHandItem().is(Items.TRIDENT),
                    "The risen drowned holds its bound trident");
            test.assertValueEqual(countPurpose(p, "return"), 1, "The rest of the kit is untouched");
            p.addEffect(new MobEffectInstance(MobEffects.SPEED, 1200, 1));
            p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 1200));
            var parked = MonsterParking.capture(p);
            var body = MonsterParking.decode(p, parked, dev.hardrush.core.round.MonsterRole.ZOMBIE).orElseThrow();
            test.assertValueEqual(body.form(), MonsterForm.DROWNED, "The form the sea gave is parked");
            test.assertValueEqual(MonsterParking.describe(parked).orElseThrow(), "溺尸", "The camp names the parked drowned");
            test.assertValueEqual(body.items().values().stream().filter(stack -> MonsterItems.purpose(stack).equals(MonsterRoles.SEA_TRIDENT)).count(), 1L,
                    "The drowned's trident parks with it");
            test.assertTrue(body.items().values().stream().anyMatch(stack -> MonsterItems.purpose(stack).equals("baby_egg")
                    && stack.is(Items.DROWNED_SPAWN_EGG)), "Remaining starting eggs park as drowned eggs");
            // Every timed effect parks, the bad with the good (docs/plan/女巫药水与药水盲盒.md §1); the body's own night vision never does.
            test.assertValueEqual(body.effects().size(), 2, "Every timed effect is parked");
            var speed = body.effects().stream().filter(effect -> effect.is(MobEffects.SPEED)).findFirst().orElseThrow();
            test.assertTrue(speed.getAmplifier() == 1 && speed.getDuration() > 1100, "A parked effect keeps its strength and remaining time");
            test.assertTrue(body.effects().stream().anyMatch(effect -> effect.is(MobEffects.SLOWNESS)), "A harmful effect parks too");
            MonsterRoles.riseFromSea(p);
            test.assertValueEqual(countPurpose(p, MonsterRoles.SEA_TRIDENT), 1, "A drowned rising from the sea again gets no second trident");
        } finally { MonsterRoleGameTest.remove(p); }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void aZombieTurnedByWaterGetsTheTridentWithoutLosingItsHand(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        try {
            MonsterRoles.select(p, "zombie");
            MonsterRoleGameTest.withEggs(p);
            p.getInventory().setSelectedSlot(2);
            MonsterRoles.convert(p, MonsterForm.DROWNED);
            test.assertValueEqual(MonsterRoles.currentForm(p), MonsterForm.DROWNED, "Water turns the zombie into a drowned");
            test.assertTrue(p.getMainHandItem().is(Items.DROWNED_SPAWN_EGG) && MonsterItems.matches(p, p.getMainHandItem(), "adult_egg"),
                    "The egg in hand stays in hand, now a drowned egg");
            int slot = slotOf(p, MonsterRoles.SEA_TRIDENT);
            test.assertTrue(slot >= 0 && slot < 9 && p.getInventory().getItem(slot).is(Items.TRIDENT),
                    "The trident lands in a free hotbar slot");
            test.assertValueEqual(slot, firstEmptyBefore(p, slot), "It takes the first free slot");
            test.assertValueEqual(countPurpose(p, MonsterRoles.SEA_TRIDENT), 1, "One trident");
        } finally { MonsterRoleGameTest.remove(p); }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void aZombieTurnedWithAnEmptyHandTakesTheTridentIntoIt(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        try {
            MonsterRoles.select(p, "zombie");
            MonsterRoleGameTest.emptyHanded(p);
            MonsterRoles.convert(p, MonsterForm.DROWNED);
            test.assertTrue(MonsterItems.matches(p, p.getMainHandItem(), MonsterRoles.SEA_TRIDENT), "An empty hand takes the trident");
        } finally { MonsterRoleGameTest.remove(p); }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void aFullZombieTurnedByWaterGetsNoTridentAndDropsNothing(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        try {
            MonsterRoles.select(p, "zombie");
            var inventory = p.getInventory();
            for (int i = 0; i < 36; i++) if (inventory.getItem(i).isEmpty()) inventory.setItem(i, new ItemStack(Items.COBBLESTONE));
            var before = inventory.getSelectedItem().copy();
            MonsterRoles.convert(p, MonsterForm.DROWNED);
            test.assertValueEqual(MonsterRoles.currentForm(p), MonsterForm.DROWNED, "A full zombie still turns");
            test.assertValueEqual(countPurpose(p, MonsterRoles.SEA_TRIDENT), 0, "A full inventory gets no trident this time");
            test.assertTrue(ItemStack.isSameItem(inventory.getSelectedItem(), before), "Nothing is pushed out of the hand");
            test.assertTrue(p.level().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(4)).isEmpty(), "Nothing is dropped");
        } finally { MonsterRoleGameTest.remove(p); }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void aStrayParksAsAStray(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        try {
            MonsterRoles.select(p, "skeleton");
            MonsterRoles.convert(p, MonsterForm.STRAY);
            var parked = MonsterParking.capture(p);
            var body = MonsterParking.decode(p, parked, dev.hardrush.core.round.MonsterRole.SKELETON).orElseThrow();
            test.assertValueEqual(body.form(), MonsterForm.STRAY, "The form the snow gave is parked");
            test.assertValueEqual(MonsterParking.describe(parked).orElseThrow(), "流浪者", "The camp names the parked stray");
            test.assertTrue(body.effects().isEmpty(), "A body without paid effects parks none");
        } finally { MonsterRoleGameTest.remove(p); }
        test.succeed();
    }

    private static int countPurpose(ServerPlayer p, String purpose) {
        int count = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++)
            if (MonsterItems.matches(p, p.getInventory().getItem(i), purpose)) count += p.getInventory().getItem(i).getCount();
        return count;
    }
    private static int slotOf(ServerPlayer p, String purpose) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (MonsterItems.matches(p, p.getInventory().getItem(i), purpose)) return i;
        return -1;
    }
    /** The first slot before {@code taken} that is empty, or {@code taken} itself when none is. */
    private static int firstEmptyBefore(ServerPlayer p, int taken) {
        for (int i = 0; i < taken; i++) if (p.getInventory().getItem(i).isEmpty()) return i;
        return taken;
    }
}
