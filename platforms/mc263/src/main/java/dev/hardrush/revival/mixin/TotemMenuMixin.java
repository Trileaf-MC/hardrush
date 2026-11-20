package dev.hardrush.revival.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.hardrush.revival.RevivalTotems;
import dev.hardrush.revival.TotemKeeping;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps revival totems out of every slot that is not the player's own, whatever the click (see {@link TotemKeeping}).
 * Not through {@code Slot.mayPlace}: {@code ShulkerBoxSlot} and others override it without calling super.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class TotemMenuMixin {
    @Shadow private int quickcraftStatus;
    @Shadow @Final private Set<Slot> quickcraftSlots;
    @Shadow public abstract ItemStack getCarried();
    /** A totem met a foreign slot during this click; the client predicted it would land there. */
    @Unique private ItemStack hardrush$refusedTotem = ItemStack.EMPTY;

    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void hardrush$keepTotem(int index, int button, ContainerInput input, Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer owner)) return;
        var menu = (AbstractContainerMenu) (Object) this;
        hardrush$refusedTotem = ItemStack.EMPTY;
        if (TotemKeeping.refuseClick(owner, menu, index, button, input)) { ci.cancel(); return; }
        // A drag is released: a carried totem is only spread over the player's own slots.
        if (input == ContainerInput.QUICK_CRAFT && AbstractContainerMenu.getQuickcraftHeader(button) == 2
                && quickcraftStatus == 1 && RevivalTotems.is(getCarried())
                && quickcraftSlots.removeIf(slot -> TotemKeeping.foreign(menu, slot))) {
            hardrush$refusedTotem = getCarried().copy();
        }
    }

    @Inject(method = "clicked", at = @At("RETURN"))
    private void hardrush$reclaimTotem(int index, int button, ContainerInput input, Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer owner)) return;
        var refused = hardrush$refusedTotem;
        hardrush$refusedTotem = ItemStack.EMPTY;
        if (!refused.isEmpty()) TotemKeeping.refuse(owner, refused);
        TotemKeeping.reclaim(owner, (AbstractContainerMenu) (Object) this);
    }

    /** Shift-click: a foreign slot looks occupied by something else, so a totem is never merged or placed there. */
    @WrapOperation(method = "moveItemStackTo", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack hardrush$hideForeignSlot(Slot slot, Operation<ItemStack> original, @Local(argsOnly = true) ItemStack moving) {
        var item = original.call(slot);
        if (!RevivalTotems.is(moving) || !TotemKeeping.foreign((AbstractContainerMenu) (Object) this, slot)) return item;
        if (item.isEmpty() || ItemStack.isSameItemSameComponents(item, moving)) hardrush$refusedTotem = moving.copy();
        return TotemKeeping.OCCUPIED;
    }
}
