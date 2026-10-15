package dev.hardrush.p2.combat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.hardrush.p2.combat.P2CombatRuntime;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(AbstractArrow.class)
public abstract class MonsterCombatArrowMixin {
    @WrapMethod(method="onHitEntity")
    private void impact(EntityHitResult hit, Operation<Void> original) {
        var arrow = (AbstractArrow)(Object)this;
        if (P2CombatRuntime.reject(arrow, hit.getEntity())) { arrow.discard(); return; }
        try (var ignored = P2CombatRuntime.action(arrow)) { original.call(hit); }
    }
}
