package dev.hardrush.revival.mixin;

import dev.hardrush.revival.FallenCompass;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A thrown 遗落之地 compass is gone, not a floor stack: in anyone else's hand the vanilla needle would point at their
 * own death. Every player drop passes here (Q, Ctrl+Q, dragging out of a window, a cursor spilling on close).
 */
@Mixin(ServerPlayer.class)
public abstract class FallenCompassDropMixin {
    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"), cancellable = true)
    private void hardrush$vanish(ItemStack stack, boolean thrown, Prediction prediction, CallbackInfoReturnable<ItemEntity> ci) {
        if (FallenCompass.is(stack)) ci.setReturnValue(null);
    }
}
