package dev.hardrush.p0.monster.mixin.ai;

import dev.hardrush.p0.monster.MonsterAiIdentity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(IronGolem.class)
public abstract class SpeciesGolemMixin {
    @ModifyVariable(method="canAttack", at=@At("HEAD"), argsOnly=true)
    private LivingEntity species(LivingEntity target) { return MonsterAiIdentity.species(target); }
}
