package dev.hardrush.gametest.mixin;

import dev.hardrush.gametest.P1TenRoundGameTest;
import dev.hardrush.p1.combat.P1CombatRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Test artifact only: native lethal observation must share the accelerated Session clock. */
@Mixin(value = P1CombatRuntime.class, remap = false)
public abstract class RegressionCombatClockMixin {
    @Redirect(method = "observed", at = @At(value = "INVOKE", target = "Ljava/lang/System;nanoTime()J"))
    private static long hardrush$regressionClock() { return P1TenRoundGameTest.observationTime(); }
}
