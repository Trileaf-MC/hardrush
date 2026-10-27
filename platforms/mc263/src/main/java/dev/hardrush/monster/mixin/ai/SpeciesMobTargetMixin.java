package dev.hardrush.monster.mixin.ai;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.hardrush.monster.MonsterAiIdentity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Mob.class)
public abstract class SpeciesMobTargetMixin {
    @ModifyVariable(method="asValidTarget", at=@At("HEAD"), argsOnly=true)
    private LivingEntity realTarget(LivingEntity target) { return MonsterAiIdentity.real(target); }

    @WrapOperation(method="asValidTarget", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/Mob;canAttack(Lnet/minecraft/world/entity/LivingEntity;)Z"))
    private boolean speciesAttack(Mob mob, LivingEntity target, Operation<Boolean> original) {
        return MonsterAiIdentity.canAttack(mob, target);
    }
}
