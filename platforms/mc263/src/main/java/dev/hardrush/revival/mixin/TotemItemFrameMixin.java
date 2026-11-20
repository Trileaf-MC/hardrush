package dev.hardrush.revival.mixin;

import dev.hardrush.revival.RevivalTotems;
import dev.hardrush.revival.TotemKeeping;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** An empty (glow) item frame takes the held stack in 26.3 {@code ItemFrame.interact}; a totem is refused. */
@Mixin(ItemFrame.class)
public abstract class TotemItemFrameMixin {
    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void hardrush$keepOutOfFrame(Player player, InteractionHand hand, Vec3 location, CallbackInfoReturnable<InteractionResult> ci) {
        var frame = (ItemFrame) (Object) this;
        var held = player.getItemInHand(hand);
        if (player instanceof ServerPlayer owner && frame.getItem().isEmpty() && RevivalTotems.is(held)) {
            TotemKeeping.refuse(owner, held);
            ci.setReturnValue(InteractionResult.FAIL);
        }
    }
}
