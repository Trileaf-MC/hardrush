package dev.hardrush.lobby;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.round.RoundSession;
import java.util.*;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.entity.EntityTypeTest;

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
    private static final class Loose {
        final UUID round, owner;
        final String ownerName;
        final int count;
        final ItemEntity entity;
        boolean claimed;
        Loose(UUID round, UUID owner, String ownerName, int count, ItemEntity entity) {
            this.round = round; this.owner = owner; this.ownerName = ownerName; this.count = count; this.entity = entity;
        }
    }
    private record Slot(IntSupplier size, IntFunction<ItemStack> get, java.util.function.ObjIntConsumer<ItemStack> set) {}

    private static final Map<MinecraftServer, RevivalTotems> INSTANCES = new IdentityHashMap<>();
    private final Access access;
    private final Map<UUID, Integer> pendingDrops = new HashMap<>();
    private final Map<UUID, Loose> loose = new HashMap<>();
    private UUID round;

    private RevivalTotems(Access access) { this.access = access; }

    static void configure(MinecraftServer server, Access access) { INSTANCES.put(server, new RevivalTotems(access)); }
    static void unconfigure(MinecraftServer server) { INSTANCES.remove(server); }

    public static boolean is(ItemStack stack) {
        return !stack.isEmpty() && stack.is(Items.TOTEM_OF_UNDYING)
                && stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().contains(KEY);
    }

    /** The stack a camp monster holds; its count is the balance capped at {@link #MAX_STACK}. */
    public static ItemStack held(UUID round, int charges, int count) {
        return totem(round, null, count, List.of(
                "数量即你本轮剩余的复生次数（共 " + charges + " 次）",
                "前往营地祭坛右键确认：消耗 1 次，空装复生为人类",
                "丢出可转赠给营地里的其他怪物",
                "落地 5 分钟无人拾取即作废；本轮结束全部清空"));
    }

    private static ItemStack thrown(UUID round, UUID token, int count, String owner) {
        return totem(round, token, count, List.of(
                "来自 " + owner + " 的 " + count + " 次复生",
                "营地里的怪物拾取后获得这些复生次数",
                "落地 5 分钟无人拾取即作废"));
    }

    private static ItemStack totem(UUID round, UUID token, int count, List<String> lore) {
        var stack = new ItemStack(Items.TOTEM_OF_UNDYING, count);
        // Never a native death guard, even if it somehow reaches a body that can die.
        stack.remove(DataComponents.DEATH_PROTECTION);
        stack.set(DataComponents.MAX_STACK_SIZE, MAX_STACK);
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("复生图腾")
                .withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.GOLD)));
        stack.set(DataComponents.LORE, new ItemLore(lore.stream().<Component>map(line -> Component.literal(line)
                .withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.GRAY))).toList()));
        var data = new CompoundTag();
        data.putString("round", round.toString());
        // A per-throw token keeps native item merging from combining two separately committed stacks.
        if (token != null) data.putString("token", token.toString());
        var tag = new CompoundTag();
        tag.put(KEY, data);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    private static Optional<UUID> token(ItemStack stack) {
        var value = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                .getCompoundOrEmpty(KEY).getStringOr("token", "");
        try { return value.isEmpty() ? Optional.empty() : Optional.of(UUID.fromString(value)); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    public static void tick(MinecraftServer server) {
        var totems = INSTANCES.get(server);
        if (totems != null) totems.reconcile(server);
    }

    private void reconcile(MinecraftServer server) {
        var r = access.committed().orElse(null);
        UUID current = r == null ? null : r.roundId();
        if (!Objects.equals(current, round)) { round = current; loose.clear(); }
        for (var player : server.getPlayerList().getPlayers()) {
            var p = r == null ? null : r.participants().get(player.getUUID());
            if (p == null || !access.eligible(player)) { project(player, null, 0, 0); continue; }
            int balance = Math.max(0, p.revivalCharges() - pendingDrops.getOrDefault(p.id(), 0));
            project(player, r.roundId(), p.revivalCharges(), Math.min(MAX_STACK, balance));
        }
        if (server.getTickCount() % 20 == 0) sweep(server, r);
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
    }

    private void sweep(MinecraftServer server, RoundSnapshot r) {
        loose.values().removeIf(entry -> entry.entity.isRemoved() && !entry.claimed);
        boolean open = r != null && r.rules().mode() == GameMode.HARDCORE
                && (r.phase() == RoundPhase.RUNNING || r.phase() == RoundPhase.RESCUE);
        if (!open) loose.values().removeIf(entry -> !entry.claimed);
        for (ServerLevel level : server.getAllLevels()) {
            for (var item : level.getEntities(EntityTypeTest.forClass(ItemEntity.class), item -> is(item.getItem()))) {
                var entry = token(item.getItem()).map(loose::get).orElse(null);
                if (entry == null || entry.entity != item) item.discard();
            }
        }
    }

    /** Q / Ctrl+Q over a totem slot of the personal inventory screen; every other click stays sealed. */
    public static boolean throwsFromInventory(ServerPlayer player, ServerboundContainerClickPacket packet) {
        var menu = player.inventoryMenu;
        return player.containerMenu == menu && packet.containerInput() == ContainerInput.THROW
                && packet.slotNum() >= 0 && packet.slotNum() < menu.slots.size()
                && is(menu.getSlot(packet.slotNum()).getItem());
    }

    /** Vanilla removed {@code stack} from the body; it is committed as a throw or projected back. */
    public static void thrown(ServerPlayer player, ItemStack stack) {
        var totems = INSTANCES.get(player.level().getServer());
        if (totems != null) totems.drop(player, stack.getCount());
    }

    private void drop(ServerPlayer player, int count) {
        var r = access.committed().orElse(null);
        var p = r == null ? null : r.participants().get(player.getUUID());
        if (p == null || !access.eligible(player) || access.busy(p.id()) || access.session().mutationPending()) {
            tell(player, "复生图腾暂时不能丢出，已放回背包；请稍后再试。"); return;
        }
        int n = Math.min(count, p.revivalCharges() - pendingDrops.getOrDefault(p.id(), 0));
        if (n <= 0) return;
        pendingDrops.merge(p.id(), n, Integer::sum);
        access.session().requestRevivalTotemDrop(r.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(), n, reply -> {
            pendingDrops.computeIfPresent(p.id(), (id, pending) -> pending > n ? pending - n : null);
            if (!reply.success()) { tell(player, "复生图腾未能丢出，已放回背包：" + reply.message()); return; }
            var token = UUID.randomUUID();
            var entity = player.createItemStackToDrop(thrown(r.roundId(), token, n, player.getScoreboardName()), false, true);
            int left = remaining(p.id());
            if (entity == null) return;
            player.level().addFreshEntity(entity);
            loose.put(token, new Loose(r.roundId(), p.id(), player.getScoreboardName(), n, entity));
            tell(player, "已丢出 " + n + " 个复生图腾，剩余 " + left + " 次；营地怪物可拾取，5 分钟无人拾取即作废。");
        });
    }

    /** Replaces native pickup for every revival totem entity. */
    public static void touch(ServerPlayer player, ItemEntity item) {
        var totems = INSTANCES.get(player.level().getServer());
        if (totems == null) { item.discard(); return; }
        totems.pickup(player, item);
    }

    private void pickup(ServerPlayer player, ItemEntity item) {
        var r = access.committed().orElse(null);
        var token = token(item.getItem()).orElse(null);
        var entry = token == null ? null : loose.get(token);
        if (r == null || entry == null || entry.entity != item || !entry.round.equals(r.roundId())) {
            if (token != null && entry != null && !entry.claimed) loose.remove(token);
            item.discard(); return;
        }
        var p = r.participants().get(player.getUUID());
        if (entry.claimed || item.hasPickUpDelay() || p == null || p.role() != ParticipantRole.MONSTER_LOBBY
                || !access.eligible(player) || access.busy(p.id()) || access.session().mutationPending()) return;
        entry.claimed = true;
        access.session().requestRevivalTotemPickup(r.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(), entry.count, reply -> {
            if (!reply.success()) { entry.claimed = false; return; }
            loose.remove(token);
            if (!item.isRemoved()) { player.take(item, entry.count); item.discard(); }
            tell(player, "拾取了 " + entry.ownerName + " 的复生图腾：复生次数 +" + entry.count + "，剩余 " + remaining(p.id()) + " 次。");
            var owner = player.level().getServer().getPlayerList().getPlayer(entry.owner);
            if (owner != null && owner != player) tell(owner, player.getScoreboardName() + " 拾取了你丢出的 " + entry.count + " 个复生图腾。");
        });
    }

    private int remaining(UUID player) {
        return access.committed().map(r -> r.participants().get(player)).map(p -> p.revivalCharges()).orElse(0);
    }

    private static void tell(ServerPlayer player, String message) {
        if (player.connection != null) player.sendSystemMessage(Component.literal(message));
    }
}
