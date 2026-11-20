package dev.hardrush.revival.mixin;

import dev.hardrush.revival.RevivalTotems;
import dev.hardrush.revival.TotemKeeping;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every hand-to-stand exchange of 26.3 {@code ArmorStand.interact} goes through {@code swapItem} (server side only). */
@Mixin(ArmorStand.class)
public abstract class TotemArmorStandMixin {
    @Inject(method = "swapItem", at = @At("HEAD"), cancellable = true)
    private void hardrush$keepOffStand(Player player, EquipmentSlot slot, ItemStack held, InteractionHand hand,
                                       CallbackInfoReturnable<Boolean> ci) {
        if (player instanceof ServerPlayer owner && RevivalTotems.is(held)) {
            TotemKeeping.refuse(owner, held);
            ci.setReturnValue(false);
        }
    }
}
