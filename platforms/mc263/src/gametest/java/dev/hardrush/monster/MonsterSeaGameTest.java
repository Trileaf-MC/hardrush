package dev.hardrush.monster;

import dev.hardrush.gametest.MonsterRoleGameTest;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;

/** A zombie rising from the sea, and what a deliberate return parks of it; in-process bodies without a client. */
public final class MonsterSeaGameTest {
    @GameTest(maxTicks=200)
    public void aSeaRisenDrownedParksAsAZombieKeepingOnlyPaidEffects(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        try {
            MonsterRoles.select(p, "zombie");
            MonsterRoles.riseFromSea(p);
            test.assertValueEqual(MonsterRoles.currentForm(p), MonsterForm.DROWNED, "A sea deployment is a drowned at once");
            test.assertTrue(MonsterItems.matches(p, p.getMainHandItem(), MonsterRoles.SEA_TRIDENT) && p.getMainHandItem().is(Items.TRIDENT),
                    "The risen drowned holds its bound trident");
            test.assertValueEqual(countPurpose(p, "return"), 1, "The rest of the kit is untouched");
            p.addEffect(new MobEffectInstance(MobEffects.SPEED, 1200, 1));
            p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 1200));
            var parked = MonsterParking.capture(p);
            var body = MonsterParking.decode(p, parked, dev.hardrush.core.round.MonsterRole.ZOMBIE).orElseThrow();
            test.assertValueEqual(body.form(), MonsterForm.ZOMBIE, "An environmental form is never parked");
            test.assertValueEqual(MonsterParking.describe(parked).orElseThrow(), "僵尸", "The camp names the base form");
            test.assertFalse(body.items().values().stream().anyMatch(stack -> stack.is(Items.TRIDENT)), "The sea trident stays behind");
            test.assertTrue(body.items().values().stream().anyMatch(stack -> MonsterItems.purpose(stack).equals("baby_egg")),
                    "Remaining starting eggs are parked");
            test.assertValueEqual(body.effects().size(), 1, "Only the witch's paid effects are parked");
            var speed = body.effects().getFirst();
            test.assertTrue(speed.is(MobEffects.SPEED) && speed.getAmplifier() == 1 && speed.getDuration() > 1100,
                    "A paid effect keeps its strength and remaining time");
        } finally { MonsterRoleGameTest.remove(p); }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void anOldParkedDrownedResumesAsAZombie(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        try {
            MonsterRoles.select(p, "zombie");
            MonsterRoles.convert(p, MonsterForm.DROWNED);
            var parked = MonsterParking.capture(p).replace("\"form\":\"ZOMBIE\"", "\"form\":\"DROWNED\"");
            var body = MonsterParking.decode(p, parked, dev.hardrush.core.round.MonsterRole.ZOMBIE).orElseThrow();
            test.assertValueEqual(body.form(), MonsterForm.ZOMBIE, "A record parked before this rule still resumes in the base form");
            test.assertTrue(body.effects().isEmpty(), "A record without effects decodes with none");
        } finally { MonsterRoleGameTest.remove(p); }
        test.succeed();
    }

    private static int countPurpose(net.minecraft.server.level.ServerPlayer p, String purpose) {
        int count = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++)
            if (MonsterItems.matches(p, p.getInventory().getItem(i), purpose)) count += p.getInventory().getItem(i).getCount();
        return count;
    }
}
