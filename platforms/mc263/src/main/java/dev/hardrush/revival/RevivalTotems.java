package dev.hardrush.revival;

import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.notice.ItemText;
import dev.hardrush.notice.Notices;
import dev.hardrush.revival.TotemProjection.Decision;
import dev.hardrush.revival.TotemProjection.Holder;
import dev.hardrush.revival.TotemProjection.Identity;
import dev.hardrush.revival.TotemProjection.State;
import java.util.*;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;
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

/**
 * Active humans and camp monsters carry their revival charges as one native stack of totems. The committed round
 * snapshot stays the only balance; held stacks are re-projected from it every tick ({@link TotemProjection} decides
 * between projecting, freezing and clearing). A totem never leaves its owner ({@link TotemKeeping}): a charge on the
 * floor or in a chest would belong to nobody.
 *
 * <p>The item is never a native death guard: it carries no {@code DEATH_PROTECTION}, so vanilla's totem logic ignores
 * it. Its description follows the holder: a human reads how to use it in the world, a camp monster how to use it at
 * the altar.
 */
public final class RevivalTotems {
    public static final int MAX_STACK = 99;
    private static final String KEY = "hardrush_revival_totem";

    /** What the projection reads from the round runtime. */
    public interface Access {
        Optional<RoundSnapshot> committed();
        /** {@code RoundSession.competitionLocked}: no committed balance may be shown as spendable. */
        boolean competitionLocked();
        /**
         * Whether {@code player} is, right now, the verified and unlocked body of {@code participant}'s committed identity:
         * a camp monster's verified lobby body, or an active human's admitted formal-world body of the same life (the
         * body whose death {@code AFTER_DEATH} would capture). Only called for those two roles.
         */
        boolean bodyReady(ServerPlayer player, Participant participant);
    }

    private record Slot(IntSupplier size, IntFunction<ItemStack> get, ObjIntConsumer<ItemStack> set) {}

    private static final Map<MinecraftServer, RevivalTotems> INSTANCES = new IdentityHashMap<>();
    private final Access access;
    /**
     * In-place revivals already performed in the world whose charge is not yet committed, per player
     * ({@code RoundSession.pendingTotemSaves}, bound through {@link #pendingSaves(MinecraftServer, ToIntFunction)}): the
     * stack {@link TotemSave} shrank stays shrunk instead of flickering back until the commit lands.
     */
    private ToIntFunction<UUID> pendingSaves = player -> 0;

    private RevivalTotems(Access access) { this.access = access; }

    public static void configure(MinecraftServer server, Access access) { INSTANCES.put(server, new RevivalTotems(access)); }
    public static void unconfigure(MinecraftServer server) { INSTANCES.remove(server); }

    /** Binds the count of in-flight in-place revivals per player; see the field of the same name. */
    public static void pendingSaves(MinecraftServer server, ToIntFunction<UUID> pending) {
        var totems = INSTANCES.get(server);
        if (totems != null) totems.pendingSaves = Objects.requireNonNull(pending);
    }

    public static boolean is(ItemStack stack) {
        return !stack.isEmpty() && stack.is(Items.TOTEM_OF_UNDYING)
                && stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().contains(KEY);
    }

    // The first line of each description is also what the action bar shows while the totem is held.
    private static final String HUMAN_USAGE = "拿在手上阵亡时原地复生，消耗 " + Notices.key(1) + " 个";
    private static final String MONSTER_USAGE = "站上营地北侧祭坛的金块 " + Notices.key(dev.hardrush.lobby.RevivalAltar.DWELL_TICKS / 20) + " 秒复生";

    private static String usage(Holder holder) { return holder == Holder.HUMAN ? HUMAN_USAGE : MONSTER_USAGE; }

    /** What the totem is for, on the action bar while held; also the first line of its description. */
    public static Optional<String> heldHint(ServerPlayer player, ItemStack stack) {
        return is(stack) ? Optional.of(usage(holder(stack))) : Optional.empty();
    }

    /** Explains why a throw, transfer or placement of a revival totem bounced; nothing for any other stack. */
    public static void refuse(ServerPlayer player, ItemStack stack) {
        if (!is(stack)) return;
        Notices.refuse(player, holder(stack) == Holder.CAMP_MONSTER
                ? "复生图腾不能丢弃 · 站上营地北侧祭坛的金块复生" : "复生图腾不能丢弃或转赠");
    }

    /** The stack {@code identity} holds; its count is the balance capped at {@link #MAX_STACK}. */
    static ItemStack held(Identity identity, int charges, int count) {
        var lore = new ArrayList<>(identity.holder() == Holder.HUMAN
                ? List.of(HUMAN_USAGE, "没拿在手上就阵亡，可到营地祭坛复生", "阵亡不掉落 · 不能丢弃或转赠")
                : List.of(MONSTER_USAGE, "每个图腾复生一次，复生后空手出发，其余图腾随身带走", "不能丢弃或转赠 · 本轮结束时清空"));
        // Beyond a full stack the item count no longer shows every charge.
        if (charges > MAX_STACK) lore.add(1, "共 " + Notices.key(charges) + " 个");
        var stack = new ItemStack(Items.TOTEM_OF_UNDYING, count);
        // Never a native death guard, even if it somehow reaches a body that can die.
        stack.remove(DataComponents.DEATH_PROTECTION);
        stack.set(DataComponents.MAX_STACK_SIZE, MAX_STACK);
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("复生图腾")
                .withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.GOLD)));
        ItemText.lore(stack, lore);
        var data = new CompoundTag();
        data.putString("round", identity.round().toString());
        data.putString("role", identity.role().name());
        data.putInt("life", identity.life());
        data.putInt("sortie", identity.sortie());
        var tag = new CompoundTag();
        tag.put(KEY, data);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /**
     * The floor stack a human's death by a human leaves (design 2.3): the held item's look, its own description, and the
     * drop token plus round instead of a holder identity, so it never passes for a projected copy. {@link #is} still
     * holds, so every keeping rule applies to it; were it ever to reach an inventory, the projection would replace it.
     */
    static ItemStack dropped(UUID token, UUID round) {
        var stack = held(new Identity(round, ParticipantRole.HUMAN_ACTIVE, 0, 0), 1, 1);
        ItemText.lore(stack, List.of(DROP_USAGE));
        var data = new CompoundTag();
        data.putString(DROP, token.toString());
        data.putString("round", round.toString());
        var tag = new CompoundTag();
        tag.put(KEY, data);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /** What a dropped totem's description says; also the gametest's reference. */
    static final String DROP_USAGE = "人类捡起即得 · 拿在手上阵亡时原地复生";
    private static final String DROP = "drop";

    /** The drop token of a floor totem; empty for a projected copy (or anything else). */
    public static Optional<UUID> dropToken(ItemStack stack) {
        if (!is(stack)) return Optional.empty();
        var value = stack.get(DataComponents.CUSTOM_DATA).copyTag().getCompoundOrEmpty(KEY).getStringOr(DROP, "");
        try { return value.isEmpty() ? Optional.empty() : Optional.of(UUID.fromString(value)); }
        catch (IllegalArgumentException unreadable) { return Optional.empty(); }
    }

    /** The identity a copy was projected for; empty for a copy from before identities were stamped. */
    static Optional<Identity> identity(ItemStack stack) {
        if (!is(stack)) return Optional.empty();
        var data = stack.get(DataComponents.CUSTOM_DATA).copyTag().getCompoundOrEmpty(KEY);
        try {
            return Optional.of(new Identity(UUID.fromString(data.getStringOr("round", "")),
                    ParticipantRole.valueOf(data.getStringOr("role", "")), data.getIntOr("life", -1), data.getIntOr("sortie", -1)));
        } catch (IllegalArgumentException unreadable) { return Optional.empty(); }
    }

    /** Whose wording a copy speaks; a copy without a readable identity is treated as a human's. */
    private static Holder holder(ItemStack stack) { return identity(stack).map(Identity::holder).orElse(Holder.HUMAN); }

    public static void tick(MinecraftServer server) {
        var totems = INSTANCES.get(server);
        if (totems != null) totems.reconcile(server);
    }

    private void reconcile(MinecraftServer server) {
        var r = access.committed().orElse(null);
        for (var player : server.getPlayerList().getPlayers()) reproject(player, r);
    }

    /** Re-projects one player at once, e.g. after a refused transfer took the stack out of its slot. */
    public static void refresh(ServerPlayer player) {
        var totems = INSTANCES.get(player.level().getServer());
        if (totems != null) totems.reproject(player, totems.access.committed().orElse(null));
    }

    private void reproject(ServerPlayer player, RoundSnapshot r) {
        project(player, decision(player, r));
    }

    private Decision decision(ServerPlayer player, RoundSnapshot r) {
        var p = r == null ? null : r.participants().get(player.getUUID());
        boolean holds = p != null && (p.role() == ParticipantRole.HUMAN_ACTIVE || p.role() == ParticipantRole.MONSTER_LOBBY);
        return TotemProjection.decide(r, player.getUUID(), player.isSpectator(), access.competitionLocked(),
                holds && access.bodyReady(player, p), p == null ? 0 : pendingSaves.applyAsInt(p.id()));
    }

    /**
     * Whether the player's inventory shows an active human's committed balance right now ({@link State#PROJECT} for a
     * human): the one test of who may take a dropped totem ({@link TotemDrops}), so pickup and holding agree.
     */
    public static boolean projectsHuman(ServerPlayer player) {
        var totems = INSTANCES.get(player.level().getServer());
        if (totems == null) return false;
        var decision = totems.decision(player, totems.access.committed().orElse(null));
        return decision.state() == State.PROJECT && decision.identity().holder() == Holder.HUMAN;
    }

    /**
     * Converges every copy the player holds, including cursor and 2x2 grid, to the decision: exactly the balance as
     * the current identity's template; or, while frozen, the current identity's copies untouched and every other copy
     * gone; or nothing. A full inventory simply keeps fewer copies: the balance stays in the round snapshot.
     */
    static void project(ServerPlayer player, Decision decision) {
        var inventory = player.getInventory();
        var craft = player.inventoryMenu.getCraftSlots();
        var slots = List.of(
                new Slot(craft::getContainerSize, craft::getItem, (stack, i) -> craft.setItem(i, stack)),
                new Slot(() -> 1, i -> player.containerMenu.getCarried(), (stack, i) -> player.containerMenu.setCarried(stack)),
                new Slot(inventory::getContainerSize, i -> inventory.getItem(inventory.getContainerSize() - 1 - i),
                        (stack, i) -> inventory.setItem(inventory.getContainerSize() - 1 - i, stack)));
        boolean projecting = decision.state() == State.PROJECT;
        var template = projecting ? held(decision.identity(), decision.charges(), 1) : ItemStack.EMPTY;
        int want = projecting ? Math.min(MAX_STACK, decision.charges()) : 0;
        int have = 0;
        for (var slot : slots) {
            for (int i = 0; i < slot.size().getAsInt(); i++) {
                var stack = slot.get().apply(i);
                if (!is(stack)) continue;
                if (decision.state() == State.FREEZE) {
                    if (!identity(stack).filter(decision.identity()::equals).isPresent()) slot.set().accept(ItemStack.EMPTY, i);
                    continue;
                }
                // Copies are kept first in the grid and cursor, then from the end of the inventory; surplus goes from its start.
                int keep = Math.min(stack.getCount(), want - have);
                if (keep <= 0) { slot.set().accept(ItemStack.EMPTY, i); continue; }
                // A holder change (human and camp monster) changes the template and so replaces every old copy.
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
