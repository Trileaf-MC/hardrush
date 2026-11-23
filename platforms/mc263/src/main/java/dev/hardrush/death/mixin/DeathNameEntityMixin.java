package dev.hardrush.death.mixin;

import dev.hardrush.death.DeathNarration;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Monster bodies, companions and bag creatures, named while a death message is built ({@link DeathNarration#displayName}). */
@Mixin(Entity.class)
public abstract class DeathNameEntityMixin {
    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
    private void hardrush$deathName(CallbackInfoReturnable<Component> callback) {
        var name = DeathNarration.displayName((Entity) (Object) this);
        if (name != null) callback.setReturnValue(name);
    }
}
