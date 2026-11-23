package dev.hardrush.revival.mixin;

import dev.hardrush.revival.FallenCompass;
import dev.hardrush.revival.RevivalTotems;
import dev.hardrush.revival.TotemKeeping;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Neither bundle gesture picks up a totem or a 遗落之地 compass: the click counts as handled and nothing moves. */
@Mixin(BundleItem.class)
public abstract class TotemBundleMixin {
    /** A carried bundle clicked onto a totem's slot. */
    @Inject(method = "overrideStackedOnOther", at = @At("HEAD"), cancellable = true)
    private void hardrush$keepSlotTotem(ItemStack self, Slot slot, ClickAction action, Player player, CallbackInfoReturnable<Boolean> ci) {
        if (action == ClickAction.PRIMARY && self.has(DataComponents.BUNDLE_CONTENTS) && RevivalTotems.is(slot.getItem())
                && player instanceof ServerPlayer owner) {
            TotemKeeping.refuse(owner, slot.getItem());
            ci.setReturnValue(true);
        } else if (action == ClickAction.PRIMARY && self.has(DataComponents.BUNDLE_CONTENTS) && FallenCompass.is(slot.getItem())
                && player instanceof ServerPlayer owner) {
            // The compass lives in the inventory only, where its life's take-back can find it.
            owner.containerMenu.sendAllDataToRemote();
            ci.setReturnValue(true);
        }
    }

    /** A carried totem clicked onto a bundle's slot. */
    @Inject(method = "overrideOtherStackedOnMe", at = @At("HEAD"), cancellable = true)
    private void hardrush$keepCarriedTotem(ItemStack self, ItemStack other, Slot slot, ClickAction action, Player player,
                                           SlotAccess carried, CallbackInfoReturnable<Boolean> ci) {
        if (action == ClickAction.PRIMARY && self.has(DataComponents.BUNDLE_CONTENTS) && RevivalTotems.is(other)
                && player instanceof ServerPlayer owner) {
            TotemKeeping.refuse(owner, other);
            ci.setReturnValue(true);
        } else if (action == ClickAction.PRIMARY && self.has(DataComponents.BUNDLE_CONTENTS) && FallenCompass.is(other)
                && player instanceof ServerPlayer owner) {
            owner.containerMenu.sendAllDataToRemote();
            ci.setReturnValue(true);
        }
    }
}
