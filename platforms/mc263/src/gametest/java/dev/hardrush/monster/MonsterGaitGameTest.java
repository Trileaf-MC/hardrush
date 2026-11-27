package dev.hardrush.monster;

import dev.hardrush.gametest.MonsterRoleGameTest;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** docs/plan/怪物移速换算.md: modifiers act on a monster player as on the native monster, before the speed is squared. */
public final class MonsterGaitGameTest {
    private static final Identifier SOUL_SPEED = Identifier.fromNamespaceAndPath("hardrush", "test_soul_speed");

    @GameTest(maxTicks = 300)
    public void modifiersActOnTheNativeSpeedBeforeItIsSquared(GameTestHelper test) {
        var player = MonsterRoleGameTest.actor(test);
        double human = player.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue();
        var base = new double[1];
        var kept = new AttributeModifier[1];
        Runnable cleanup = () -> MonsterRoleGameTest.remove(player);
        var sequence = test.startSequence().thenIdle(20);
        for (var kind : java.util.List.of("zombie", "skeleton")) {
            sequence.thenExecute(guarded(cleanup, () -> {
                        MonsterRoles.select(player, kind);
                        base[0] = MonsterRoles.nativeModel(player).getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue();
                    }))
                    .thenIdle(2)
                    .thenExecute(guarded(cleanup, () -> {
                        expect(test, player, base[0], kind + " without modifiers walks as the native monster");
                        test.assertTrue(player.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(MonsterGait.ID) == null,
                                "No compensation without modifiers");
                        player.addEffect(new MobEffectInstance(MobEffects.SPEED, 600, 0));
                    }))
                    .thenIdle(2)
                    .thenExecute(guarded(cleanup, () -> {
                        expect(test, player, base[0] * 1.2, kind + " on swiftness I");
                        kept[0] = player.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(MonsterGait.ID);
                    }))
                    .thenIdle(2)
                    .thenExecute(guarded(cleanup, () -> {
                        test.assertTrue(player.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(MonsterGait.ID) == kept[0],
                                "An unchanged compensation is not replaced every tick");
                        player.removeEffect(MobEffects.SPEED);
                        player.addEffect(new MobEffectInstance(MobEffects.SPEED, 600, 1));
                        player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 600, 0));
                    }))
                    .thenIdle(2)
                    .thenExecute(guarded(cleanup, () -> {
                        expect(test, player, base[0] * 1.4 * 0.85, kind + " on swiftness II and slowness I");
                        player.removeAllEffects();
                        // Soul speed I's own amount, as vanilla's enchantment adds it.
                        player.getAttribute(Attributes.MOVEMENT_SPEED).addTransientModifier(
                                new AttributeModifier(SOUL_SPEED, 0.0405, AttributeModifier.Operation.ADD_VALUE));
                    }))
                    .thenIdle(2)
                    .thenExecute(guarded(cleanup, () -> {
                        expect(test, player, base[0] + 0.0405, kind + " with an added amount");
                        player.getAttribute(Attributes.MOVEMENT_SPEED).removeModifier(SOUL_SPEED);
                    }))
                    .thenIdle(2)
                    .thenExecute(guarded(cleanup, () -> expect(test, player, base[0], kind + " back to the native walk")));
        }
        sequence.thenExecute(guarded(cleanup, () -> {
                    player.addEffect(new MobEffectInstance(MobEffects.SPEED, 600, 1));
                    MonsterRoles.clear(player);
                    var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
                    test.assertTrue(speed.getModifier(MonsterGait.ID) == null, "The human keeps no compensation");
                    test.assertValueEqual(speed.getBaseValue(), human, "The human walks as before");
                    cleanup.run();
                }))
                .thenSucceed();
    }

    private static void expect(GameTestHelper test, ServerPlayer player, double nativeSpeed, String message) {
        double actual = player.getAttributeValue(Attributes.MOVEMENT_SPEED), expected = nativeSpeed * nativeSpeed;
        test.assertTrue(Math.abs(actual - expected) < 1e-5, message + ": expected " + expected + ", was " + actual);
    }

    private static Runnable guarded(Runnable cleanup, Runnable step) {
        return () -> {
            try { step.run(); } catch (Throwable failure) { cleanup.run(); throw failure; }
        };
    }
}
