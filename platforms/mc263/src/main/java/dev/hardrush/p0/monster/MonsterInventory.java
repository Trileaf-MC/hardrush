package dev.hardrush.p0.monster;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import dev.hardrush.p2.monster.P2MonsterRuntime;
import dev.hardrush.p2.protection.P2ProtectionRuntime;

/** Storage is open, but born items may never leave their owner's current body. */
public final class MonsterInventory {
    private MonsterInventory() {}

    public static boolean storageBlock(ServerPlayer player, BlockPos pos) {
        if (P2ProtectionRuntime.protectedBlock(player.level(), pos)) return false;
        var state = player.level().getBlockState(pos);
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.ENDER_CHEST)
                || state.is(Blocks.BARREL) || state.is(Blocks.HOPPER) || state.is(Blocks.DISPENSER)
                || state.is(Blocks.DROPPER) || state.getBlock() instanceof ShulkerBoxBlock;
    }
    public static boolean storageEntity(Entity entity) {
        return entity instanceof Container && !(entity instanceof Mob) && !P2ProtectionRuntime.protectedEntity(entity);
    }
    private static boolean storageMenu(AbstractContainerMenu menu) {
        return menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu || menu instanceof HopperMenu
                || menu instanceof DispenserMenu;
    }
    public static boolean mayClick(ServerPlayer player, AbstractContainerMenu menu, int index, int button, ContainerInput input) {
        if (!MonsterRoles.isMonster(player)) return true;
        if (!P2MonsterRuntime.canAct(player)) return false;
        if (menu != player.inventoryMenu && !storageMenu(menu)) return false;
        if (input == ContainerInput.CLONE) return false;
        var carried = menu.getCarried();
        var slot = index >= 0 && index < menu.slots.size() ? menu.slots.get(index) : null;
        var clicked = slot == null ? ItemStack.EMPTY : slot.getItem();
        boolean own = slot != null && slot.container == player.getInventory();
        if (menu == player.inventoryMenu && index >= 0 && index <= 4) return false;
        if (MonsterRoles.currentForm(player) == MonsterForm.CREEPER) {
            if (menu == player.inventoryMenu && index >= 5 && index <= 8) return false;
            var equippable = clicked.get(DataComponents.EQUIPPABLE);
            if (input == ContainerInput.QUICK_MOVE && menu == player.inventoryMenu && equippable != null
                    && equippable.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR) return false;
        }
        if (input == ContainerInput.THROW && MonsterItems.bound(clicked)) return false;
        if (input == ContainerInput.QUICK_MOVE && MonsterItems.bound(clicked) && menu != player.inventoryMenu) return false;
        if (input == ContainerInput.PICKUP && index == -999 && MonsterItems.bound(carried)) return false;
        if (slot != null && !own && MonsterItems.bound(carried)) return false;
        if (input == ContainerInput.SWAP) {
            if (button < 0 || button >= player.getInventory().getContainerSize()) return false;
            if (!own && MonsterItems.bound(player.getInventory().getItem(button))) return false;
        }
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
