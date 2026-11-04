package dev.hardrush.lobby;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.round.MonsterCoins;
import dev.hardrush.core.round.Participant;
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

/** Both camp currencies move through durable balances before a native floor stack can be claimed. */
public final class CampTransfers {
    enum Kind {
        REVIVAL("复生图腾", "个"), COIN("怪物币", "枚");
        final String title, unit;
        Kind(String title, String unit) { this.title = title; this.unit = unit; }
        int balance(Participant player) { return this == REVIVAL ? player.revivalCharges() : player.wallet().coins(); }
    }
    private record Holder(UUID player, Kind kind) {}
    private static final class Loose {
        final UUID round, owner;
        final String ownerName;
        final Kind kind;
        final int count;
        final ItemEntity entity;
        boolean claimed;
        Loose(UUID round, ServerPlayer owner, Kind kind, int count, ItemEntity entity) {
            this.round = round; this.owner = owner.getUUID(); this.ownerName = owner.getScoreboardName();
            this.kind = kind; this.count = count; this.entity = entity;
        }
    }
    private static final String TOKEN = "hardrush_camp_transfer";
    private static final Map<MinecraftServer, CampTransfers> INSTANCES = new IdentityHashMap<>();
    private final RevivalTotems.Access access;
    private final Map<Holder, Integer> pendingDrops = new HashMap<>();
    private final Map<UUID, Loose> loose = new HashMap<>();
    private UUID round;

    private CampTransfers(RevivalTotems.Access access) { this.access = access; }
    static void configure(MinecraftServer server, RevivalTotems.Access access) { INSTANCES.put(server, new CampTransfers(access)); }
    static void unconfigure(MinecraftServer server) { INSTANCES.remove(server); }

    public static boolean is(ItemStack stack) { return RevivalTotems.is(stack) || CampKit.isCoin(stack); }
    static int pending(ServerPlayer player, Kind kind) {
        var transfers = INSTANCES.get(player.level().getServer());
        return transfers == null ? 0 : transfers.pendingDrops.getOrDefault(new Holder(player.getUUID(), kind), 0);
    }

    /** Only native Q / Ctrl+Q over a currency slot escapes the isolated camp inventory lock. */
    public static boolean throwsFromInventory(ServerPlayer player, ServerboundContainerClickPacket packet) {
        var menu = player.inventoryMenu;
        return player.containerMenu == menu && packet.containerInput() == ContainerInput.THROW
                && packet.slotNum() >= 0 && packet.slotNum() < menu.slots.size()
                && is(menu.getSlot(packet.slotNum()).getItem());
    }

    /** Vanilla has removed this stack. On refusal its authoritative projection must repair prediction. */
    public static void thrown(ServerPlayer player, ItemStack stack) {
        var transfers = INSTANCES.get(player.level().getServer());
        if (transfers != null) transfers.drop(player, RevivalTotems.is(stack) ? Kind.REVIVAL : Kind.COIN, stack.getCount());
        else player.inventoryMenu.sendAllDataToRemote();
    }

    private void refresh(ServerPlayer player) {
        RevivalTotems.refresh(player);
        CampKit.refresh(player);
        // A rejected action may leave the server stack unchanged; diff-only broadcasts cannot undo prediction.
        player.inventoryMenu.sendAllDataToRemote();
    }

    private void drop(ServerPlayer player, Kind kind, int count) {
        var r = access.committed().orElse(null);
        var p = r == null ? null : r.participants().get(player.getUUID());
        if (p == null || !access.eligible(player) || access.busy(p.id()) || access.session().mutationPending()
                || kind == Kind.COIN && !MonsterCoins.enabled(r.rules())) {
            refresh(player);
            dev.hardrush.notice.Notices.refuse(player, "现在不能丢出，物品已放回背包"); return;
        }
        var holder = new Holder(p.id(), kind);
        int n = Math.min(count, kind.balance(p) - pendingDrops.getOrDefault(holder, 0));
        if (n <= 0) { refresh(player); return; }
        pendingDrops.merge(holder, n, Integer::sum);
        Consumer<RoundSession.Reply> done = reply -> {
            pendingDrops.computeIfPresent(holder, (id, pending) -> pending > n ? pending - n : null);
            refresh(player);
            if (!reply.success()) { dev.hardrush.notice.Replies.refuse(player, reply, "现在不能丢出，物品已放回背包"); return; }
            var token = UUID.randomUUID();
            var entity = player.createItemStackToDrop(thrownStack(r.roundId(), token, kind, n, player.getScoreboardName()), false, true);
            if (entity == null) return;
            player.level().addFreshEntity(entity);
            loose.put(token, new Loose(r.roundId(), player, kind, n, entity));
        };
        if (kind == Kind.REVIVAL) access.session().requestRevivalTotemDrop(r.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(), n, done);
        else access.session().requestMonsterCoinDrop(r.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(), n, done);
        refresh(player);
    }

    private static ItemStack thrownStack(UUID round, UUID token, Kind kind, int count, String owner) {
        var stack = kind == Kind.REVIVAL ? RevivalTotems.thrown(round, token, count, owner) : CampKit.coin(count);
        if (kind == Kind.COIN) stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("来自 " + owner + " 的 " + count + " 枚怪物币").withStyle(ChatFormatting.GRAY),
                Component.literal("营地里的怪物拾取后获得对应余额").withStyle(ChatFormatting.GRAY),
                Component.literal("落地 5 分钟无人拾取即作废").withStyle(ChatFormatting.GRAY))));
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
                    + " 捡走了你丢出的 " + dev.hardrush.notice.Notices.key(entry.count) + " " + entry.kind.unit + entry.kind.title);
        };
        if (entry.kind == Kind.REVIVAL) access.session().requestRevivalTotemPickup(r.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(), entry.count, done);
        else access.session().requestMonsterCoinPickup(r.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(), entry.count, done);
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
        for (var level : server.getAllLevels()) {
            for (var item : level.getEntities(EntityTypeTest.forClass(ItemEntity.class), item -> is(item.getItem()))) {
                var entry = token(item.getItem()).map(loose::get).orElse(null);
                if (entry == null || entry.entity != item) item.discard();
            }
        }
    }

}
