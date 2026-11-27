package dev.hardrush.player;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * The native right-click armour swap (26.3 {@code Item.use} → {@code Equippable.swapWithEquipmentSlot}) for players
 * whose ordinary item use the server otherwise refuses. The vanilla client predicts this swap on its own, so every
 * refusal of it must also resend the inventory, or the client keeps showing armour it is not wearing.
 */
public final class NativeArmorSwap {
    private NativeArmorSwap() {}

    /** Whether a vanilla client predicts a right click with {@code stack} as an armour swap. */
    public static boolean predicted(ItemStack stack) {
        var equippable = stack.get(DataComponents.EQUIPPABLE);
        return equippable != null && equippable.swappable() && !stack.has(DataComponents.CONSUMABLE)
                && equippable.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR;
    }

    /** Swaps natively, as {@code ServerPlayerGameMode.useItem} would; false when the held item is no such armour. */
    public static boolean swap(ServerPlayer player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (!predicted(stack)) return false;
        if (stack.get(DataComponents.EQUIPPABLE).swapWithEquipmentSlot(stack, player) instanceof InteractionResult.Success success
                && success.heldItemTransformedTo() != null && success.heldItemTransformedTo() != stack) {
            player.setItemInHand(hand, success.heldItemTransformedTo());
        }
        player.inventoryMenu.sendAllDataToRemote();
        return true;
    }

    /** Undoes the client's own prediction of a swap the server refused. */
    public static void refuse(ServerPlayer player, InteractionHand hand) {
        if (predicted(player.getItemInHand(hand))) player.inventoryMenu.sendAllDataToRemote();
    }
}
