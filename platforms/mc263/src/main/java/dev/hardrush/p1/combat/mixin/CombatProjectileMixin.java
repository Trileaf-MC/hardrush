package dev.hardrush.p1.combat.mixin;

import dev.hardrush.p1.combat.P1CombatRuntime;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Projectile.class)
public abstract class CombatProjectileMixin {
    @Inject(method="setOwner(Lnet/minecraft/world/entity/Entity;)V", at=@At("TAIL"))
    private void created(Entity owner, CallbackInfo ci) { P1CombatRuntime.projectileCreated((Projectile)(Object)this, owner); }
}
