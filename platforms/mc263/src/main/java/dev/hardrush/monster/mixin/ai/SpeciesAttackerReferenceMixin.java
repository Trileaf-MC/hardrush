package dev.hardrush.monster.mixin.ai;

import dev.hardrush.monster.MonsterAiIdentity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LivingEntity.class)
public abstract class SpeciesAttackerReferenceMixin {
    @ModifyVariable(method="setLastHurtByMob", at=@At("HEAD"), argsOnly=true)
    private LivingEntity realAttacker(LivingEntity attacker) { return MonsterAiIdentity.real(attacker); }
}
