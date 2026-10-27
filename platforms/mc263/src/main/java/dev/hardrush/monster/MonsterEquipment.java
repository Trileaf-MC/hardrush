package dev.hardrush.monster;

import java.util.EnumMap;
import java.util.Map;
import dev.hardrush.monster.mixin.MonsterLivingAccessor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/** Player inventory owns items; the unregistered mob only borrows an attack-time copy. */
final class MonsterEquipment {
    private final Map<EquipmentSlot, ItemStack> previous = new EnumMap<>(EquipmentSlot.class);
    private final Map<EquipmentSlot, ItemStack> borrowed = new EnumMap<>(EquipmentSlot.class);
    private int borrowedMainSlot;

    void bind(ServerPlayer player, Mob model, boolean creeper) {
        previous.clear();
        for (var slot : EquipmentSlot.values()) previous.put(slot, ItemStack.EMPTY);
        mirror(player, model, creeper);
    }

    boolean mirror(ServerPlayer player, Mob model, boolean creeper) {
        boolean changed = false;
        borrowedMainSlot = player.getInventory().getSelectedSlot();
        for (var slot : EquipmentSlot.values()) {
            ItemStack item = creeper ? ItemStack.EMPTY : player.getItemBySlot(slot).copy();
            if (!ItemStack.matches(model.getItemBySlot(slot), item)) {
                changed = true; model.setItemSlot(slot, item);
            }
            model.setDropChance(slot, 0);
            borrowed.put(slot, item.copy());
        }
        refresh(model);
        return changed;
    }

    /** Write only native mutations to the same borrowed stack, never over a later edit. */
    void finish(ServerPlayer player, Mob model, boolean creeper) {
        if (creeper) return;
        for (var slot : EquipmentSlot.values()) {
            var before = borrowed.getOrDefault(slot, ItemStack.EMPTY);
            var after = model.getItemBySlot(slot);
            if (ItemStack.matches(before, after)) continue;
            if (slot == EquipmentSlot.MAINHAND && borrowedMainSlot != player.getInventory().getSelectedSlot()) continue;
            if (ItemStack.matches(player.getItemBySlot(slot), before)) player.setItemSlot(slot, after.copy());
        }
        refresh(model);
    }

    private void refresh(Mob model) {
        ((MonsterLivingAccessor) model).hardrush$collectEquipmentChanges(previous);
        for (var slot : EquipmentSlot.values()) previous.put(slot, model.getItemBySlot(slot).copy());
    }
}
