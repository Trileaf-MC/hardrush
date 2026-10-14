package dev.hardrush.p1.book;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

/** Opens an existing generated book even when lobby inventory clicks are sealed. */
public final class P1BookReader {
    private P1BookReader() {}
    public static boolean open(ServerPlayer player, int slot) {
        if (!player.level().getServer().isSameThread()) throw new IllegalStateException("Book reading requires the server thread");
        var inventory = player.getInventory();
        if (slot == -1) {
            // The newest appended manual volume normally occupies the highest used slot.
            for (int candidate = 35; candidate >= 0; candidate--) {
                if (P1BookPort.isHistoryBook(inventory.getItem(candidate))) { slot = candidate; break; }
            }
        }
        if (slot < 0 || slot >= 36 || !P1BookPort.isHistoryBook(inventory.getItem(slot))) return false;
        int selected = inventory.getSelectedSlot();
        if (slot != selected) {
            var book = inventory.getItem(slot);
            var held = inventory.getItem(selected);
            inventory.setItem(slot, held);
            inventory.setItem(selected, book);
        }
        player.inventoryMenu.sendAllDataToRemote();
        player.openItemGui(inventory.getItem(selected), InteractionHand.MAIN_HAND);
        return true;
    }
}
