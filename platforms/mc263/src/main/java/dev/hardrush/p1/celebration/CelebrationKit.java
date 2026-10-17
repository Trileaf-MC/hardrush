package dev.hardrush.p1.celebration;

import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.nbt.CompoundTag;

/** Server-created, temporary native items. No client mod is required. */
public final class CelebrationKit {
    private CelebrationKit() {}

    public static void equip(ServerPlayer player) {
        player.getInventory().clearContent();
        for (var slot : EquipmentSlot.values()) player.setItemSlot(slot, ItemStack.EMPTY);
        var elytra = new ItemStack(Items.ELYTRA);
        elytra.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        player.setItemSlot(EquipmentSlot.CHEST, elytra);
        int slot = 0;
        for (var color : DyeColor.values()) player.getInventory().setItem(slot++, rocket(color));
        player.getInventory().setSelectedSlot(0);
    }

    public static ItemStack rocket(DyeColor color) {
        var rocket = new ItemStack(Items.FIREWORK_ROCKET, 16);
        rocket.set(DataComponents.FIREWORKS, new Fireworks(1, List.of(new FireworkExplosion(
                FireworkExplosion.Shape.LARGE_BALL, IntList.of(color.getFireworkColor()), IntList.of(), true, true))));
        var marker = new CompoundTag();
        marker.putBoolean("hardrush_celebration", true);
        rocket.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
        return rocket;
    }

    public static boolean isRocket(ItemStack stack) {
        return stack.is(Items.FIREWORK_ROCKET) && stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag().getBooleanOr("hardrush_celebration", false);
    }
}
