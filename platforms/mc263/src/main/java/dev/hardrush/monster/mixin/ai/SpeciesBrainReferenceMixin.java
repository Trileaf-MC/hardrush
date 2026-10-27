package dev.hardrush.monster.mixin.ai;

import dev.hardrush.monster.MonsterAiIdentity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Brain.class)
public abstract class SpeciesBrainReferenceMixin {
    @ModifyVariable(method={"setMemoryInternal(Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;Ljava/lang/Object;J)V",
            "setMemoryInternal(Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;Ljava/lang/Object;)V"},
            at=@At("HEAD"), argsOnly=true)
    private Object realReference(Object value) {
        return value instanceof LivingEntity living ? MonsterAiIdentity.real(living) : value;
    }
}
