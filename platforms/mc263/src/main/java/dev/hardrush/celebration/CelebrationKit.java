package dev.hardrush.celebration;

import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;

/** The winner's native reward: added on top of the round inventory, never replacing it. No client mod is required. */
public final class CelebrationKit {
    private CelebrationKit() {}

    public static void award(ServerPlayer player) {
        var elytra = new ItemStack(Items.ELYTRA);
        elytra.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        var chest = player.getItemBySlot(EquipmentSlot.CHEST);
        player.setItemSlot(EquipmentSlot.CHEST, elytra);
        give(player, chest);
        for (var color : DyeColor.values()) give(player, rocket(color));
        player.inventoryMenu.sendAllDataToRemote();
    }

    public static ItemStack rocket(DyeColor color) {
        var rocket = new ItemStack(Items.FIREWORK_ROCKET, 16);
        rocket.set(DataComponents.FIREWORKS, new Fireworks(1, List.of(new FireworkExplosion(
                FireworkExplosion.Shape.LARGE_BALL, IntList.of(color.getFireworkColor()), IntList.of(), true, true))));
        return rocket;
    }

    /** A full inventory drops the remainder at the winner's feet, like any native overflow. */
    private static void give(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
        if (!player.getInventory().add(stack) && !stack.isEmpty()) player.spawnAtLocation(player.level(), stack);
    }
}
