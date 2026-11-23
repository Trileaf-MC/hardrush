package dev.hardrush.revival.mixin;

import dev.hardrush.revival.TotemDrops;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3 {@code Entity.onBelowWorld}, which {@code ItemEntity} does not override: a live floor revival totem is returned
 * instead of discarded (docs/plan/复生图腾守恒.md 1.3), so the void never destroys a released totem.
 */
@Mixin(Entity.class)
public abstract class TotemVoidMixin {
    @Inject(method = "onBelowWorld", at = @At("HEAD"), cancellable = true)
    private void hardrush$returnTotem(CallbackInfo ci) {
        if ((Object) this instanceof ItemEntity item && TotemDrops.returnFromVoid(item)) ci.cancel();
    }
}
