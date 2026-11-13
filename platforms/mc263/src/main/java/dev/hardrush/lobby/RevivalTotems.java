package dev.hardrush.lobby;

import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.notice.ItemText;
import dev.hardrush.notice.Notices;
import dev.hardrush.round.RoundSession;
import java.util.*;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * Camp monsters carry their revival charges as native totems. The committed round snapshot stays the
 * only balance: held stacks are re-projected from it, and a thrown stack is spawned only after its
 * charges have durably left the owner. Floor stacks live in memory and expire like vanilla items.
 */
public final class RevivalTotems {
    public static final int MAX_STACK = 99;
    private static final String KEY = "hardrush_revival_totem";

    interface Access {
        Optional<RoundSnapshot> committed();
        /** A verified camp monster of the current hardcore round, outside any frozen state. */
        boolean eligible(ServerPlayer player);
        /** Personal deployment/revival work that must not race with a balance change. */
        boolean busy(UUID player);
        RoundSession session();
    }
    private record Slot(IntSupplier size, IntFunction<ItemStack> get, java.util.function.ObjIntConsumer<ItemStack> set) {}

    private static final Map<MinecraftServer, RevivalTotems> INSTANCES = new IdentityHashMap<>();
    private final Access access;

    private RevivalTotems(Access access) { this.access = access; }

    static void configure(MinecraftServer server, Access access) { INSTANCES.put(server, new RevivalTotems(access)); }
    static void unconfigure(MinecraftServer server) { INSTANCES.remove(server); }

    public static boolean is(ItemStack stack) {
        return !stack.isEmpty() && stack.is(Items.TOTEM_OF_UNDYING)
                && stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().contains(KEY);
    }

    private static final String USAGE = "站上营地北侧祭坛的金块 " + Notices.key(RevivalAltar.DWELL_TICKS / 20) + " 秒复生";

    /** What the totem is for, on the action bar while held; also the first line of its description. */
    public static java.util.Optional<String> heldHint(ServerPlayer player, ItemStack stack) {
        return is(stack) ? java.util.Optional.of(USAGE) : java.util.Optional.empty();
    }

    /** The stack a camp monster holds; its count is the balance capped at {@link #MAX_STACK}. */
    public static ItemStack held(UUID round, int charges, int count) {
        var lore = new java.util.ArrayList<>(List.of(USAGE, "每个图腾复生一次，复生为人类后空手出发",
                "丢出可送给营地里的其他怪物", "落地 5 分钟无人拾取即作废 · 本轮结束时清空"));
        // Beyond a full stack the item count no longer shows every charge.
        if (charges > MAX_STACK) lore.add(1, "共 " + Notices.key(charges) + " 个");
        return totem(round, null, count, lore);
    }

    static ItemStack thrown(UUID round, UUID token, int count, String owner) {
        return totem(round, token, count, List.of(
                "来自 " + Notices.key(owner) + " 的 " + Notices.key(count) + " 个复生图腾",
                "营地里的怪物捡起即可获得", "落地 5 分钟无人拾取即作废"));
    }

    private static ItemStack totem(UUID round, UUID token, int count, List<String> lore) {
        var stack = new ItemStack(Items.TOTEM_OF_UNDYING, count);
        // Never a native death guard, even if it somehow reaches a body that can die.
        stack.remove(DataComponents.DEATH_PROTECTION);
        stack.set(DataComponents.MAX_STACK_SIZE, MAX_STACK);
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("复生图腾")
                .withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.GOLD)));
        ItemText.lore(stack, lore);
        var data = new CompoundTag();
        data.putString("round", round.toString());
        // A per-throw token keeps native item merging from combining two separately committed stacks.
        if (token != null) data.putString("token", token.toString());
        var tag = new CompoundTag();
        tag.put(KEY, data);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    public static void tick(MinecraftServer server) {
        var totems = INSTANCES.get(server);
        if (totems != null) totems.reconcile(server);
    }

    private void reconcile(MinecraftServer server) {
        var r = access.committed().orElse(null);
        for (var player : server.getPlayerList().getPlayers()) reproject(player, r);
    }

    static void refresh(ServerPlayer player) {
        var totems = INSTANCES.get(player.level().getServer());
        if (totems != null) totems.reproject(player, totems.access.committed().orElse(null));
    }

    private void reproject(ServerPlayer player, RoundSnapshot r) {
        var p = r == null ? null : r.participants().get(player.getUUID());
        if (p == null || !access.eligible(player)) { project(player, null, 0, 0); return; }
        int balance = Math.max(0, p.revivalCharges() - CampTransfers.pending(player, CampTransfers.Kind.REVIVAL));
        project(player, r.roundId(), balance, Math.min(MAX_STACK, balance));
    }

    /** Converge every copy the player holds, including cursor and 2x2 grid, to exactly {@code want}. */
    private static void project(ServerPlayer player, UUID round, int charges, int want) {
        var inventory = player.getInventory();
        var craft = player.inventoryMenu.getCraftSlots();
        var slots = List.of(
                new Slot(craft::getContainerSize, craft::getItem, (stack, i) -> craft.setItem(i, stack)),
                new Slot(() -> 1, i -> player.containerMenu.getCarried(), (stack, i) -> player.containerMenu.setCarried(stack)),
                new Slot(inventory::getContainerSize, i -> inventory.getItem(inventory.getContainerSize() - 1 - i),
                        (stack, i) -> inventory.setItem(inventory.getContainerSize() - 1 - i, stack)));
        var template = round == null ? ItemStack.EMPTY : held(round, charges, 1);
        int have = 0;
        for (var slot : slots) {
            for (int i = 0; i < slot.size().getAsInt(); i++) {
                var stack = slot.get().apply(i);
                if (!is(stack)) continue;
                // Surplus goes first from the grid and cursor, then from the end of the inventory.
                int keep = Math.min(stack.getCount(), want - have);
                if (keep <= 0) { slot.set().accept(ItemStack.EMPTY, i); continue; }
                if (keep != stack.getCount() || !ItemStack.isSameItemSameComponents(stack, template)) {
                    slot.set().accept(template.copyWithCount(keep), i);
                }
                have += keep;
            }
        }
        if (have < want) inventory.add(template.copyWithCount(want - have));
        player.inventoryMenu.broadcastChanges();
    }

}
