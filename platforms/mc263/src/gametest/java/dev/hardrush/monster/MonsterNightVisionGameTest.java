package dev.hardrush.monster;

import dev.hardrush.gametest.MonsterRoleGameTest;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import dev.hardrush.core.round.MonsterRole;
import java.util.Set;

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
            // Crossing dimensions rebinds the body, the same bind a respawn or reconnect goes through.
            var overworld = p.level(); var position = p.position();
            p.teleportTo(overworld.getServer().getLevel(Level.NETHER), .5, 150, .5, Set.of(), 0, 0, true);
            MonsterRoles.tick(overworld.getServer());
            assertNightVision(test, p, "Rebinding in another dimension");
            p.teleportTo(overworld, position.x, position.y, position.z, Set.of(), 0, 0, true);
            MonsterRoles.tick(overworld.getServer());

            MonsterRoles.clear(p);
            test.assertFalse(p.hasEffect(MobEffects.NIGHT_VISION), "Leaving the monster body takes night vision away");

            MonsterRoles.select(p, "zombie");
            p.addEffect(new MobEffectInstance(MobEffects.SPEED, 1200));
            var parked = MonsterParking.decode(p, MonsterParking.capture(p), MonsterRole.ZOMBIE).orElseThrow();
            test.assertTrue(parked.effects().stream().noneMatch(effect -> effect.is(MobEffects.NIGHT_VISION)),
                    "A parked body keeps paid effects only; the next bind gives night vision afresh");
            MonsterRoles.suspend(p);
            test.assertFalse(p.hasEffect(MobEffects.NIGHT_VISION), "A body parked in the camp leaves night vision behind");

            p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 1200));
            MonsterRoles.select(p, "skeleton"); MonsterRoles.clear(p);
            test.assertFalse(p.hasEffect(MobEffects.NIGHT_VISION), "Leaving the body also ends night vision it absorbed into its own");
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
