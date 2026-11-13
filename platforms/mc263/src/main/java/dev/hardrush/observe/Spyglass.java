package dev.hardrush.observe;

import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.player.PlayerProfiles;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * An excluded player's one way back into the match: a bound native spyglass, projected while the body is in
 * the camp (or out watching) and removed when the round forgets the exclusion.
 */
public final class Spyglass {
    private static final String KEY = "hardrush_observation_spyglass";
    private static final String USAGE = "右键选择一名玩家，附身观察";

    private Spyglass() {}

    public static ItemStack stack() {
        var stack = new ItemStack(Items.SPYGLASS);
        var data = new CompoundTag(); data.putBoolean(KEY, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("望远镜").withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.AQUA)));
        dev.hardrush.notice.ItemText.lore(stack, USAGE, "A/D 切换人选 · 潜行退出", "本轮结束时收回");
        return stack;
    }

    /** The spyglass's usage, on the action bar while held; also the first line of its description. */
    public static java.util.Optional<String> heldHint(net.minecraft.server.level.ServerPlayer player, ItemStack stack) {
        return is(stack) ? java.util.Optional.of(USAGE) : java.util.Optional.empty();
    }

    public static boolean is(ItemStack stack) {
        return stack.is(Items.SPYGLASS) && stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().contains(KEY);
    }


    /** Exactly one spyglass while excluded; none otherwise. Converges every tick like the camp kit. */
    public static void tick(MinecraftServer server, RoundSnapshot round) {
        for (var player : server.getPlayerList().getPlayers()) {
            var p = round == null ? null : round.participants().get(player.getUUID());
            boolean wanted = p != null && p.role().isExcluded() && player.isAlive()
                    && (PlayerProfiles.verifiedLobbyBody(player) || PlayerProfiles.observing(player));
            var inventory = player.getInventory();
            int held = 0;
            for (int i = 0; i < inventory.getContainerSize(); i++) if (is(inventory.getItem(i))) held += inventory.getItem(i).getCount();
            if (is(player.containerMenu.getCarried())) held += 2; // Never leave one on the cursor.
            if (held == (wanted ? 1 : 0)) continue;
            for (int i = 0; i < inventory.getContainerSize(); i++) if (is(inventory.getItem(i))) inventory.setItem(i, ItemStack.EMPTY);
            if (is(player.containerMenu.getCarried())) player.containerMenu.setCarried(ItemStack.EMPTY);
            if (wanted) inventory.add(stack());
            player.inventoryMenu.broadcastChanges();
        }
    }

    /** Right-clicking the bound spyglass opens the head menu instead of zooming. */
    public static boolean use(ServerPlayer player, InteractionHand hand) {
        if (!is(player.getItemInHand(hand))) return false;
        player.stopUsingItem();
        ObservationMenu.open(player);
        return true;
    }
}
