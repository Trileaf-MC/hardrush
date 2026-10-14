package dev.hardrush.p1.combat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.hardrush.p1.combat.P1CombatRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;

/** Rejects the impact before native ignition, knockback, effects and embedded-arrow state. */
@Mixin(AbstractArrow.class)
public abstract class CombatArrowImpactMixin {
    @WrapMethod(method="onHitEntity")
    private void impact(EntityHitResult hit, Operation<Void> original) {
        var arrow = (AbstractArrow)(Object)this;
        if (!(arrow.level() instanceof ServerLevel level)) { original.call(hit); return; }
        if (P1CombatRuntime.rejectDamage(level)) return;
        try (var ignored = P1CombatRuntime.projectileAction(level, arrow)) { original.call(hit); }
    }
}
