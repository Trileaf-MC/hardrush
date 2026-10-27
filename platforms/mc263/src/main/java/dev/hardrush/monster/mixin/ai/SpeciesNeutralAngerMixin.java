package dev.hardrush.monster.mixin.ai;

import dev.hardrush.monster.MonsterAiIdentity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(NeutralMob.class)
public interface SpeciesNeutralAngerMixin {
    @ModifyVariable(method="isValidPlayerTarget", at=@At("HEAD"), argsOnly=true)
    private static LivingEntity species(LivingEntity target) { return MonsterAiIdentity.species(target); }
}
