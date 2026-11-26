package dev.hardrush.monster;

import dev.hardrush.gametest.MonsterRoleGameTest;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;

/** Monster players see in the dark all sortie long (docs/plan/怪物夜视.md); in-process bodies without a client. */
public final class MonsterNightVisionGameTest {
    @GameTest(maxTicks=200)
    public void everyBoundBodySeesInTheDarkWithoutParticles(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        try {
            for (var role : new String[] {"zombie", "skeleton", "creeper"}) {
                MonsterRoles.select(p, role);
                assertNightVision(test, p, role + " sortie");
            }
            MonsterRoles.select(p, "skeleton");
            MonsterRoles.convert(p, MonsterForm.STRAY);
            assertNightVision(test, p, "Environmental conversion");

            MonsterRoles.onDisconnect(p);
            test.assertFalse(p.hasEffect(MobEffects.NIGHT_VISION), "A disconnected body is saved without the monster's night vision");
            MonsterRoles.onJoin(p);
            assertNightVision(test, p, "Reconnecting");

            p.removeEffect(MobEffects.NIGHT_VISION);
            MonsterRoles.tick(p.level().getServer());
            test.assertFalse(p.hasEffect(MobEffects.NIGHT_VISION), "Night vision cleared by native means is not topped up every tick");

            MonsterRoles.select(p, "zombie");
            p.addEffect(MonsterRoles.nightVision());
            test.assertValueEqual(p.getActiveEffects().stream().filter(effect -> effect.is(MobEffects.NIGHT_VISION)).count(), 1L,
                    "Binding again keeps a single night vision");
            MonsterRoles.clear(p);
            test.assertFalse(p.hasEffect(MobEffects.NIGHT_VISION), "Leaving the monster body takes night vision away");
        } finally { MonsterRoleGameTest.remove(p); }
        test.succeed();
    }

    private static void assertNightVision(GameTestHelper test, ServerPlayer p, String when) {
        var effect = p.getEffect(MobEffects.NIGHT_VISION);
        test.assertTrue(effect != null, when + " grants night vision");
        test.assertTrue(effect.isInfiniteDuration() && effect.getAmplifier() == 0, when + " grants infinite night vision I");
        test.assertFalse(effect.isVisible() || effect.isAmbient(), when + " shows no particles that would expose the player");
        test.assertTrue(effect.showIcon(), when + " shows the effect icon to the monster itself");
    }
}
