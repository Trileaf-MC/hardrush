package dev.hardrush.revival;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A revival totem never leaves its holder (design 2.7): it cannot be thrown, put into any slot that is not the
 * player's own, a bundle, a shelf, an item frame, an armor stand, a decorated pot, or handed to an allay. Applies to
 * every player; the camp's own locks come first and stay stricter. A missed path only costs presentation: the
 * projection restores the holder's count and takes surplus copies back from anyone else.
 *
 * <p>Every refusal tells the player why and resends the open menu, since the vanilla client has already predicted
 * the move.
 */
public final class TotemKeeping {
    /**
     * What a foreign slot looks like to {@code moveItemStackTo} while a totem is being shift-clicked: neither empty nor
     * the same item, so both its merge and its placement pass skip it. Never handed out or mutated.
     */
    public static final ItemStack OCCUPIED = new ItemStack(Items.BARRIER);

    private TotemKeeping() {}

    /** Anything but the player's own {@link Inventory} and the 2x2 grid of its inventory menu (the projection counts both). */
    public static boolean foreign(AbstractContainerMenu menu, Slot slot) {
        if (slot.container instanceof Inventory) return false;
        return !(menu instanceof InventoryMenu inventory && slot.container == inventory.getCraftSlots());
    }

    /** Tells the player and corrects the client's prediction. */
    public static void refuse(ServerPlayer player, ItemStack stack) {
        RevivalTotems.refuse(player, stack);
        player.containerMenu.sendAllDataToRemote();
    }

    /**
     * The direct click paths that would put a totem where it may not go; quick-move is filtered in
     * {@code moveItemStackTo} and drags at their release. True when the click must not run (already refused).
     */
    public static boolean refuseClick(ServerPlayer player, AbstractContainerMenu menu, int index, int button, ContainerInput input) {
        var slot = index >= 0 && index < menu.slots.size() ? menu.slots.get(index) : null;
        var carried = menu.getCarried();
        var moving = switch (input) {
            // Placing or swapping the carried stack into a foreign slot, or dropping it outside the window.
            case PICKUP -> (button == 0 || button == 1) && (index == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE
                    || slot != null && foreign(menu, slot)) ? carried : ItemStack.EMPTY;
            // Number keys and the off-hand key swap an inventory slot into the clicked one.
            case SWAP -> slot != null && foreign(menu, slot) && (button >= 0 && button < 9 || button == Inventory.SLOT_OFFHAND)
                    ? player.getInventory().getItem(button) : ItemStack.EMPTY;
            // Creative middle click would only mint a surplus copy; Q over a slot throws it.
            case CLONE, THROW -> slot != null ? slot.getItem() : ItemStack.EMPTY;
            default -> ItemStack.EMPTY;
        };
        if (!RevivalTotems.is(moving)) return false;
        refuse(player, moving);
        return true;
    }

    /**
     * After any click: a totem that still reached a foreign slot through a path not covered above (a menu whose
     * quick-move does not use {@code moveItemStackTo}) goes straight back to the clicker.
     */
    public static void reclaim(ServerPlayer player, AbstractContainerMenu menu) {
        ItemStack sample = null;
        for (var slot : menu.slots) {
            var stack = slot.getItem();
            if (!RevivalTotems.is(stack) || !foreign(menu, slot)) continue;
            sample = stack.copy();
            var back = stack.copy();
            slot.set(ItemStack.EMPTY);
            player.getInventory().add(back);
            // A full inventory: leave it where it was rather than destroy a copy the holder may still need.
            if (!back.isEmpty()) slot.set(back);
        }
        if (sample != null) refuse(player, sample);
    }

    /**
     * A thrown or spilled totem never becomes a floor stack; it goes back into the inventory (a full inventory leaves
     * it to the projection). {@code stack} is emptied, as a dropped stack would be.
     */
    public static void keep(ServerPlayer player, ItemStack stack, boolean thrown) {
        var sample = stack.copy();
        var back = stack.copy();
        stack.setCount(0);
        player.getInventory().add(back);
        if (thrown) refuse(player, sample);
        else player.containerMenu.sendAllDataToRemote();
    }

    /** A dying player's own totems are neither dropped nor lost: the balance lives on in the round snapshot. */
    public static void stripForDeath(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) if (RevivalTotems.is(inventory.getItem(i))) inventory.setItem(i, ItemStack.EMPTY);
        var craft = player.inventoryMenu.getCraftSlots();
        for (int i = 0; i < craft.getContainerSize(); i++) if (RevivalTotems.is(craft.getItem(i))) craft.setItem(i, ItemStack.EMPTY);
        if (RevivalTotems.is(player.containerMenu.getCarried())) player.containerMenu.setCarried(ItemStack.EMPTY);
    }
}
