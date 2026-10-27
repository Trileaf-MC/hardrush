package dev.hardrush.monster.mixin.ai;

import dev.hardrush.monster.MonsterAiIdentity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.VillagerHostilesSensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(VillagerHostilesSensor.class)
public abstract class SpeciesVillagerSensorMixin {
    @ModifyVariable(method="isMatchingEntity", at=@At("HEAD"), argsOnly=true, ordinal=1)
    private LivingEntity species(LivingEntity target) { return MonsterAiIdentity.species(target); }
}
