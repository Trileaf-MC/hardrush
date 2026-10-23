package dev.hardrush.p0.monster.mixin.ai;

import dev.hardrush.p0.monster.MonsterAiIdentity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(WitherBoss.class)
public abstract class SpeciesWitherMixin {
    @ModifyVariable(method="lambda$static$0", at=@At("HEAD"), argsOnly=true)
    private static LivingEntity species(LivingEntity target) { return MonsterAiIdentity.species(target); }
}
