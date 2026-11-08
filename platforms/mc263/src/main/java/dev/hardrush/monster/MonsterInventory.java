package dev.hardrush.monster;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

/**
 * A monster only ever handles its own backpack: like a native mob it opens no container, so born
 * items can leave their owner's body only by dying. World items still come and go through pickup.
 */
public final class MonsterInventory {
    private MonsterInventory() {}

    public static boolean mayClick(ServerPlayer player, AbstractContainerMenu menu, int index, int button, ContainerInput input) {
        if (!MonsterRoles.isMonster(player)) return true;
        if (!MonsterRoundRuntime.canAct(player)) return false;
        // Every slot from here on belongs to the player's own inventory; 0-4 are the personal crafting grid.
        if (menu != player.inventoryMenu) return false;
        if (input == ContainerInput.CLONE) return false;
        var carried = menu.getCarried();
        var slot = index >= 0 && index < menu.slots.size() ? menu.slots.get(index) : null;
        var clicked = slot == null ? ItemStack.EMPTY : slot.getItem();
        if (index >= 0 && index <= 4) return false;
        if (MonsterRoles.currentForm(player) == MonsterForm.CREEPER) {
            if (index >= 5 && index <= 8) return false;
            var equippable = clicked.get(DataComponents.EQUIPPABLE);
            if (input == ContainerInput.QUICK_MOVE && equippable != null
                    && equippable.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR) return false;
        }
        if (input == ContainerInput.THROW && MonsterItems.bound(clicked)) return false;
        if (input == ContainerInput.PICKUP && index == -999 && MonsterItems.bound(carried)) return false;
        if (input == ContainerInput.SWAP && (button < 0 || button >= player.getInventory().getContainerSize())) return false;
        // The soulcatcher moves like any bound item, but nothing goes in or comes out by hand:
        // native bundle clicks insert with a held stack and pull out with a right click.
        boolean bagClicked = MonsterSoulcatcher.isBag(clicked), bagCarried = MonsterSoulcatcher.isBag(carried);
        if (bagClicked && input == ContainerInput.PICKUP && (button != 0 || !carried.isEmpty())) return false;
        if (bagCarried && (input == ContainerInput.QUICK_CRAFT
                || input == ContainerInput.PICKUP && (button != 0 || !clicked.isEmpty()))) return false;
        // Bundle inventory clicks must not hide a bound item inside a transferable world item.
        if ((MonsterItems.bound(clicked) && carried.has(DataComponents.BUNDLE_CONTENTS))
                || (MonsterItems.bound(carried) && clicked.has(DataComponents.BUNDLE_CONTENTS))) return false;
        return true;
    }
    public static void repairArmor(ServerPlayer player) {
        if (MonsterRoles.currentForm(player) != MonsterForm.CREEPER) return;
        for (var slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            var item = player.getItemBySlot(slot);
            if (item.isEmpty()) continue;
            player.setItemSlot(slot, ItemStack.EMPTY);
            if (!player.getInventory().add(item) && !item.isEmpty()) player.spawnAtLocation(player.level(), item);
        }
    }
    /** Closing a full inventory must neither leak nor destroy a cursor-held bound control. */
    public static void recoverBoundDrop(ServerPlayer player, ItemStack stack) {
        if (!MonsterItems.owned(player, stack)) return;
        var returned = stack.copy();
        if (player.getInventory().add(returned)) return;
        for (int i = 0; i < 36 && !returned.isEmpty(); i++) {
            var world = player.getInventory().getItem(i);
            if (MonsterItems.bound(world)) continue;
            if (!world.isEmpty()) player.spawnAtLocation(player.level(), world.copy());
            player.getInventory().setItem(i, returned.copy()); returned.setCount(0);
        }
    }
}
