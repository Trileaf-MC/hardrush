package dev.hardrush.combat.mixin;

import dev.hardrush.combat.CombatRuntime;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PrimedTnt.class)
public abstract class CombatTntMixin {
    @Inject(method="<init>(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/entity/LivingEntity;)V", at=@At("TAIL"))
    private void created(Level level, double x, double y, double z, LivingEntity owner, CallbackInfo ci) {
        CombatRuntime.tntCreated((PrimedTnt)(Object)this, owner);
    }
}
