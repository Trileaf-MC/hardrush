package dev.hardrush.lobby.workshop;

import dev.hardrush.lobby.ButtonMenu;
import dev.hardrush.notice.ItemText;
import dev.hardrush.player.PlayerProfiles;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * The camp's way into the workshop for 2-level operators: a bound cartography table, projected into the camp body
 * like the excluded player's spyglass, that opens the workshop chest when right-clicked instead of typing
 * {@code /hardrush lobby}. It is never a real possession: every other body loses it on the next tick, and a body
 * entering a round is stripped of it first ({@link #strip}).
 */
public final class WorkshopTable {
    private static final String KEY = "hardrush_workshop_table";
    static final String USAGE = "右键打开大厅工坊：草稿、建造与发布";
    /** The last hotbar slot, where it is put when free; else the first free slot. */
    public static final int SLOT = 8;

    private WorkshopTable() {}

    public static ItemStack stack() {
        var stack = new ItemStack(Items.CARTOGRAPHY_TABLE);
        var data = new CompoundTag(); data.putBoolean(KEY, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        ItemText.name(stack, "大厅工坊");
        ItemText.lore(stack, USAGE, "只有 2 级管理员在营地时持有", "离开营地或进入挑战时自动收回");
        return stack;
    }

    public static boolean is(ItemStack stack) {
        return stack.is(Items.CARTOGRAPHY_TABLE) && stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().contains(KEY);
    }

    /** The table's usage, on the action bar while held; also the first line of its description. */
    public static Optional<String> heldHint(ServerPlayer player, ItemStack stack) {
        return is(stack) ? Optional.of(USAGE) : Optional.empty();
    }

    /** Who carries the table: a 2-level operator standing in camp with a verified lobby body. */
    static boolean wanted(ServerPlayer player) {
        return player.isAlive() && PlayerProfiles.verifiedLobbyBody(player) && Workshop.gamemaster(player);
    }

    /** Exactly one table for every body that may carry it, none for any other. Server thread, every tick. */
    static void tick(MinecraftServer server) {
        for (var player : server.getPlayerList().getPlayers()) {
            boolean wanted = wanted(player);
            var inventory = player.getInventory();
            int held = 0;
            for (int i = 0; i < inventory.getContainerSize(); i++) if (is(inventory.getItem(i))) held += inventory.getItem(i).getCount();
            if (is(player.containerMenu.getCarried())) held += 2; // Never leave one on the cursor.
            var menu = player.containerMenu;
            // Whatever an open chest holds is never the table's place.
            if (menu != player.inventoryMenu && !(menu instanceof ButtonMenu))
                for (var slot : menu.slots) if (slot.container != inventory && is(slot.getItem())) { slot.set(ItemStack.EMPTY); held += 2; }
            if (held == (wanted ? 1 : 0)) continue;
            strip(player);
            if (wanted) {
                if (inventory.getItem(SLOT).isEmpty()) inventory.setItem(SLOT, stack());
                else inventory.add(stack());
            }
            player.inventoryMenu.broadcastChanges();
        }
    }

    /** Takes every table off the body; a body leaving camp for a round calls this before it goes. */
    public static void strip(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) if (is(inventory.getItem(i))) inventory.setItem(i, ItemStack.EMPTY);
        if (is(player.containerMenu.getCarried())) player.containerMenu.setCarried(ItemStack.EMPTY);
    }

    // ---- Network entry points (server thread) -------------------------------------------------------------------

    /** A right-click in the air with the table. Consumed whenever the table is in that hand. */
    public static boolean useItem(ServerPlayer player, InteractionHand hand) {
        if (!is(player.getItemInHand(hand))) return false;
        open(player);
        return true;
    }

    /** A right-click on a block: the table in the main hand owns the click, and nothing is placed. */
    public static boolean useItemOn(ServerPlayer player, ServerboundUseItemOnPacket packet) {
        boolean main = is(player.getMainHandItem());
        if (!main && !is(player.getItemInHand(packet.hand()))) return false;
        var hit = packet.hitResult();
        player.connection.ackBlockChangesUpTo(packet.sequence());
        player.connection.send(new ClientboundBlockUpdatePacket(player.level(), hit.getBlockPos()));
        player.connection.send(new ClientboundBlockUpdatePacket(player.level(), hit.getBlockPos().relative(hit.getDirection())));
        if (main && packet.hand() != InteractionHand.MAIN_HAND) return true;
        open(player);
        return true;
    }

    private static void open(ServerPlayer player) {
        player.inventoryMenu.sendAllDataToRemote();
        // A stale copy (the tick has not taken it back yet) does nothing.
        if (!wanted(player)) return;
        Workshop.get(player.level().getServer()).ifPresent(workshop -> workshop.openMenu(player));
    }
}
