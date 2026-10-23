package dev.hardrush.p0.monster.mixin.ai;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.hardrush.p0.monster.MonsterAiIdentity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TargetGoal.class)
public abstract class SpeciesTargetGoalMixin {
    @WrapOperation(method="canContinueToUse", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/Mob;canAttack(Lnet/minecraft/world/entity/LivingEntity;)Z"))
    private boolean speciesAttack(Mob mob, LivingEntity target, Operation<Boolean> original) {
        return MonsterAiIdentity.canAttack(mob, target);
    }
}
