package dev.hardrush.p0.monster.mixin.ai;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.hardrush.p0.monster.MonsterAiIdentity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.ResetUniversalAngerTargetGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ResetUniversalAngerTargetGoal.class)
public abstract class SpeciesUniversalAngerMixin {
    @WrapOperation(method="wasHurtByPlayer", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/Mob;getLastHurtByMob()Lnet/minecraft/world/entity/LivingEntity;"))
    private LivingEntity species(Mob mob, Operation<LivingEntity> original) {
        return MonsterAiIdentity.species(original.call(mob));
    }
}
