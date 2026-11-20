package dev.hardrush.lobby;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.round.MonsterCoins;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.round.RoundSession;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.entity.EntityTypeTest;

/**
 * Monster coins are the one camp item that may be thrown: a gift moves through the durable balance
 * before its native floor stack can be claimed. Revival totems stay with their owner ({@code revival.TotemKeeping});
 * the floor totems a human's death leaves are {@code revival.TotemDrops}.
 */
public final class CampTransfers {
    private static final class Loose {
        final UUID round, owner;
        final String ownerName;
        final int count;
        final ItemEntity entity;
        boolean claimed;
        Loose(UUID round, ServerPlayer owner, int count, ItemEntity entity) {
            this.round = round; this.owner = owner.getUUID(); this.ownerName = owner.getScoreboardName();
            this.count = count; this.entity = entity;
        }
    }
    private static final String TOKEN = "hardrush_camp_transfer";
    private static final Map<MinecraftServer, CampTransfers> INSTANCES = new IdentityHashMap<>();
    private final CampAccess access;
    private final Map<UUID, Integer> pendingDrops = new HashMap<>();
    private final Map<UUID, Loose> loose = new HashMap<>();
    private UUID round;

    private CampTransfers(CampAccess access) { this.access = access; }
    static void configure(MinecraftServer server, CampAccess access) { INSTANCES.put(server, new CampTransfers(access)); }
    static void unconfigure(MinecraftServer server) { INSTANCES.remove(server); }

    public static boolean is(ItemStack stack) { return CampKit.isCoin(stack); }
    static int pending(ServerPlayer player) {
        var transfers = INSTANCES.get(player.level().getServer());
        return transfers == null ? 0 : transfers.pendingDrops.getOrDefault(player.getUUID(), 0);
    }

    /** Only native Q / Ctrl+Q over a coin slot escapes the isolated camp inventory lock. */
    public static boolean throwsFromInventory(ServerPlayer player, ServerboundContainerClickPacket packet) {
        var menu = player.inventoryMenu;
        return player.containerMenu == menu && packet.containerInput() == ContainerInput.THROW
                && packet.slotNum() >= 0 && packet.slotNum() < menu.slots.size()
                && is(menu.getSlot(packet.slotNum()).getItem());
    }

    /** Vanilla has removed this stack. On refusal its authoritative projection must repair prediction. */
    public static void thrown(ServerPlayer player, ItemStack stack) {
        var transfers = INSTANCES.get(player.level().getServer());
        if (transfers != null) transfers.drop(player, stack.getCount());
        else player.inventoryMenu.sendAllDataToRemote();
    }

    private void refresh(ServerPlayer player) {
        CampKit.refresh(player);
        // A rejected action may leave the server stack unchanged; diff-only broadcasts cannot undo prediction.
        player.inventoryMenu.sendAllDataToRemote();
    }

    private void drop(ServerPlayer player, int count) {
        var r = access.committed().orElse(null);
        var p = r == null ? null : r.participants().get(player.getUUID());
        if (p == null || !access.eligible(player) || access.busy(p.id()) || access.session().mutationPending()
                || !MonsterCoins.enabled(r.rules())) {
            refresh(player);
            dev.hardrush.notice.Notices.refuse(player, "现在不能丢出，物品已放回背包"); return;
        }
        var holder = p.id();
        int n = Math.min(count, p.wallet().coins() - pendingDrops.getOrDefault(holder, 0));
        if (n <= 0) { refresh(player); return; }
        pendingDrops.merge(holder, n, Integer::sum);
        Consumer<RoundSession.Reply> done = reply -> {
            pendingDrops.computeIfPresent(holder, (id, pending) -> pending > n ? pending - n : null);
            refresh(player);
            if (!reply.success()) { dev.hardrush.notice.Replies.refuse(player, reply, "现在不能丢出，物品已放回背包"); return; }
            var token = UUID.randomUUID();
            var entity = player.createItemStackToDrop(thrownStack(token, n, player.getScoreboardName()), false, true);
            if (entity == null) return;
            player.level().addFreshEntity(entity);
            loose.put(token, new Loose(r.roundId(), player, n, entity));
        };
        access.session().requestMonsterCoinDrop(r.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(), n, done);
        refresh(player);
    }

    private static ItemStack thrownStack(UUID token, int count, String owner) {
        var stack = CampKit.coin(count);
        dev.hardrush.notice.ItemText.lore(stack,
                "来自 " + dev.hardrush.notice.Notices.key(owner) + " 的 " + dev.hardrush.notice.Notices.key(count) + " 枚怪物币",
                "营地里的怪物捡起即可获得", "落地 5 分钟无人拾取即作废");
        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        data.putString(TOKEN, token.toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return stack;
    }

    private static Optional<UUID> token(ItemStack stack) {
        var value = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr(TOKEN, "");
        try { return value.isEmpty() ? Optional.empty() : Optional.of(UUID.fromString(value)); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    public static void touch(ServerPlayer player, ItemEntity item) {
        var transfers = INSTANCES.get(player.level().getServer());
        if (transfers == null) { item.discard(); return; }
        transfers.pickup(player, item);
    }

    private void pickup(ServerPlayer player, ItemEntity item) {
        var r = access.committed().orElse(null);
        var token = token(item.getItem()).orElse(null);
        var entry = token == null ? null : loose.get(token);
        if (r == null || entry == null || entry.entity != item || !entry.round.equals(r.roundId())) {
            item.discard(); return;
        }
        var p = r.participants().get(player.getUUID());
        if (entry.claimed || item.isRemoved() || item.hasPickUpDelay() || p == null || !access.eligible(player)
                || access.busy(p.id()) || access.session().mutationPending()) return;
        entry.claimed = true;
        Consumer<RoundSession.Reply> done = reply -> {
            if (!reply.success()) { entry.claimed = false; return; }
            loose.remove(token);
            if (!item.isRemoved()) { player.take(item, entry.count); item.discard(); }
            refresh(player);
            // The picker sees the native pickup; only the thrower, possibly far away, is told.
            var owner = player.level().getServer().getPlayerList().getPlayer(entry.owner);
            if (owner != null && owner != player) dev.hardrush.notice.Notices.hint(owner, dev.hardrush.notice.Notices.key(player.getScoreboardName())
                    + " 捡走了你丢出的 " + dev.hardrush.notice.Notices.key(entry.count) + " 枚怪物币");
        };
        access.session().requestMonsterCoinPickup(r.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(), entry.count, done);
    }

    public static void tick(MinecraftServer server) {
        var transfers = INSTANCES.get(server);
        if (transfers != null) transfers.sweep(server);
    }

    private void sweep(MinecraftServer server) {
        var r = access.committed().orElse(null);
        UUID current = r == null ? null : r.roundId();
        if (!Objects.equals(current, round)) { round = current; loose.clear(); }
        if (server.getTickCount() % 20 != 0) return;
        loose.values().removeIf(entry -> entry.entity.isRemoved() && !entry.claimed);
        boolean open = r != null && r.rules().mode() == GameMode.HARDCORE
                && (r.phase() == RoundPhase.RUNNING || r.phase() == RoundPhase.RESCUE);
        if (!open) loose.values().removeIf(entry -> !entry.claimed);
        // Floor revival totems are TotemDrops' business; only coin stacks are swept here.
        for (var level : server.getAllLevels()) {
            for (var item : level.getEntities(EntityTypeTest.forClass(ItemEntity.class), item -> is(item.getItem()))) {
                var entry = token(item.getItem()).map(loose::get).orElse(null);
                if (entry == null || entry.entity != item) item.discard();
            }
        }
    }

}
