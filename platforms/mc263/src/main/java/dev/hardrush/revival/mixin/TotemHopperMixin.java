package dev.hardrush.revival.mixin;

import dev.hardrush.revival.TotemDrops;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.3 {@code HopperBlockEntity.addItem(Container, ItemEntity)} is how hoppers ({@code suckInItems}, {@code entityInside})
 * and hopper minecarts ({@code MinecartHopper.suckInItems}) take a floor stack; none of them takes a revival totem.
 */
@Mixin(HopperBlockEntity.class)
public abstract class TotemHopperMixin {
    @Inject(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/entity/item/ItemEntity;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void hardrush$keepFromHopper(Container container, ItemEntity entity, CallbackInfoReturnable<Boolean> ci) {
        if (TotemDrops.hopperRefuses(entity)) ci.setReturnValue(false);
    }
}
