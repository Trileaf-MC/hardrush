package dev.hardrush.combat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.hardrush.combat.CombatRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EnderDragon.class)
public abstract class CombatDragonMixin {
    @WrapMethod(method="hurt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/boss/enderdragon/EnderDragonPart;Lnet/minecraft/world/damagesource/DamageSource;F)Z")
    private boolean damage(ServerLevel level, EnderDragonPart part, DamageSource source, float amount, Operation<Boolean> original) {
        var dragon = (EnderDragon)(Object)this;
        float before = dragon.getHealth();
        boolean wasDying = dragon.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.DYING;
        try (var ignored = CombatRuntime.damage(level, source)) {
            if (CombatRuntime.rejectDragonAttack(level, source)) return false;
            boolean result = original.call(level, part, source, amount);
            boolean lethal = !wasDying && before > 0 && (dragon.getHealth() <= 0
                    || dragon.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.DYING);
            CombatRuntime.observed(level, dragon, source, Math.max(0, before - dragon.getHealth()), lethal);
            return result;
        }
    }
    @ModifyExpressionValue(method="hurt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/boss/enderdragon/EnderDragonPart;Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at=@At(value="INVOKE", target="Lnet/minecraft/world/damagesource/DamageSource;is(Lnet/minecraft/tags/TagKey;)Z"))
    private boolean offlineHumanArrow(boolean vanilla, ServerLevel level, EnderDragonPart part, DamageSource source, float amount) {
        return vanilla || CombatRuntime.trustedHumanArrow(level, source);
    }
}
