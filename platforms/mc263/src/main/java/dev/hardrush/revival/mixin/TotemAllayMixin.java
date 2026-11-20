package dev.hardrush.revival.mixin;

import dev.hardrush.revival.RevivalTotems;
import dev.hardrush.revival.TotemKeeping;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 26.3 {@code Allay.mobInteract} takes one of the held stack into the allay's hand; a totem is refused. */
@Mixin(Allay.class)
public abstract class TotemAllayMixin {
    @Inject(method = "mobInteract", at = @At("HEAD"), cancellable = true)
    private void hardrush$keepFromAllay(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> ci) {
        var held = player.getItemInHand(hand);
        // Only an empty-handed allay takes the stack; otherwise vanilla does nothing with it either.
        if (player instanceof ServerPlayer owner && RevivalTotems.is(held)
                && ((Allay) (Object) this).getItemInHand(InteractionHand.MAIN_HAND).isEmpty()) {
            TotemKeeping.refuse(owner, held);
            ci.setReturnValue(InteractionResult.FAIL);
        }
    }
}
