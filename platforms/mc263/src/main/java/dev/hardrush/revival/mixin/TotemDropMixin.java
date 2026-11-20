package dev.hardrush.revival.mixin;

import dev.hardrush.revival.RevivalTotems;
import dev.hardrush.revival.TotemKeeping;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The single exit of every player drop: Q and Ctrl+Q, dragging out of a window, a cursor stack spilling when a menu
 * closes on a full inventory. A revival totem never becomes a floor stack.
 */
@Mixin(ServerPlayer.class)
public abstract class TotemDropMixin {
    /** Q and Ctrl+Q with a totem selected: nothing leaves the slot in the first place. */
    @Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
    private void hardrush$keepSelected(boolean all, CallbackInfo ci) {
        var player = (ServerPlayer) (Object) this;
        var selected = player.getInventory().getSelectedItem();
        if (!RevivalTotems.is(selected)) return;
        TotemKeeping.refuse(player, selected);
        ci.cancel();
    }

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"), cancellable = true)
    private void hardrush$keep(ItemStack stack, boolean thrown, Prediction prediction, CallbackInfoReturnable<ItemEntity> ci) {
        if (!RevivalTotems.is(stack)) return;
        TotemKeeping.keep((ServerPlayer) (Object) this, stack, thrown);
        ci.setReturnValue(null);
    }
}
