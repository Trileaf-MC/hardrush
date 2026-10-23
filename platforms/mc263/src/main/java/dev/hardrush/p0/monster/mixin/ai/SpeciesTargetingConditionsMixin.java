package dev.hardrush.p0.monster.mixin.ai;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.hardrush.p0.monster.MonsterAiIdentity;
import dev.hardrush.p0.monster.MonsterRoles;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TargetingConditions.class)
public abstract class SpeciesTargetingConditionsMixin {
    @WrapMethod(method="test")
    private boolean realTarget(ServerLevel level, LivingEntity attacker, LivingEntity target, Operation<Boolean> original) {
        var real = MonsterAiIdentity.real(target);
        return real != null && real.level() == level && original.call(level, attacker, real);
    }

    @WrapOperation(method="test", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/ai/targeting/TargetingConditions$Selector;test(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/server/level/ServerLevel;)Z"))
    private boolean speciesPredicate(TargetingConditions.Selector selector, LivingEntity target, ServerLevel level,
                                     Operation<Boolean> original, ServerLevel ignored, LivingEntity attacker, LivingEntity candidate) {
        return original.call(selector, MonsterRoles.summonOwner(attacker) == null ? MonsterAiIdentity.species(target) : target, level);
    }

    @WrapOperation(method="test", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/LivingEntity;canAttack(Lnet/minecraft/world/entity/LivingEntity;)Z"))
    private boolean speciesAttack(LivingEntity attacker, LivingEntity target, Operation<Boolean> original) {
        return MonsterAiIdentity.canAttack(attacker, target);
    }
}
