package dev.hardrush.monster.mixin.ai;

import dev.hardrush.monster.MonsterAiIdentity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.wolf.Wolf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Wolf.class)
public abstract class SpeciesWolfMixin {
    @ModifyVariable(method="wantsToAttack", at=@At("HEAD"), argsOnly=true, ordinal=0)
    private LivingEntity species(LivingEntity target) { return MonsterAiIdentity.species(target); }
}
