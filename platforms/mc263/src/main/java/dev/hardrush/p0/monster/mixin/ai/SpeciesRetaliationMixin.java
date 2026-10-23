package dev.hardrush.p0.monster.mixin.ai;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.hardrush.p0.monster.MonsterAiIdentity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(HurtByTargetGoal.class)
public abstract class SpeciesRetaliationMixin {
    @WrapOperation(method="canUse", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/Mob;getLastHurtByMob()Lnet/minecraft/world/entity/LivingEntity;"))
    private LivingEntity attackerSpecies(Mob mob, Operation<LivingEntity> original) {
        return MonsterAiIdentity.species(original.call(mob));
    }
}
