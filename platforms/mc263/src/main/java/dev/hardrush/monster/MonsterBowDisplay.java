package dev.hardrush.monster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * Owner-only bow presentation of the volley skill. Authoritative stacks stay vanilla, so drops, chests,
 * observers and settlement never see it; only the owner's inventory packets carry the name, glint and bar.
 * Only the sortie's own bows carry the volley ({@link MonsterRoles#volleyBow}), so only they are dressed:
 * a picked-up world bow keeps its own name and durability, like a bow a native skeleton picks up.
 */
public final class MonsterBowDisplay {
    static final String HOW_TO = "潜行时拉满弓松手，触发 3 连射";
    /** Pixel width of the vanilla item bar; the cooldown advances one visible pixel at a time. */
    static final int BAR_WIDTH = 13;
    private static final Map<UUID, Dressing> DRESSINGS = new ConcurrentHashMap<>();
    private MonsterBowDisplay() {}

    /** filled is the lit bar width while cooling down; a ready bow has none and glints instead. */
    record Look(String name, boolean ready, int filled) {}
    /** The sortie binding is captured on the server thread so packet rewriting never reads role state. */
    private record Dressing(String sortie, Look look) {
        boolean dresses(ServerPlayer owner, ItemStack stack) {
            return stack.is(Items.BOW) && MonsterItems.owned(owner.getUUID(), sortie, stack);
        }
    }

    /** Null for forms without a bow skill; an added form must choose here before it compiles. */
    public static String bowName(MonsterForm form) {
        return switch (form) {
            case SKELETON -> "骷髅之弓";
            case STRAY -> "流浪者之弓";
            case ZOMBIE, DROWNED, CREEPER -> null;
        };
    }

    static Look look(MonsterForm form, long remainingMillis, long cooldownMillis) {
        if (remainingMillis <= 0 || cooldownMillis <= 0) return new Look(bowName(form), true, 0);
        double recovered = 1 - Math.min(1, (double) remainingMillis / cooldownMillis);
        return new Look(bowName(form), false, Math.clamp((int) Math.floor(recovered * BAR_WIDTH), 1, BAR_WIDTH - 1));
    }

    static ItemStack dress(ItemStack bow, Look look) {
        var stack = bow.copy();
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(look.name()));
        stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(HOW_TO),
                Component.literal(look.ready() ? "技能已就绪" : "冷却中"))));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, look.ready());
        if (stack.isDamageableItem()) {
            int max = stack.getMaxDamage();
            stack.setDamageValue(look.ready() ? 0
                    : Math.clamp(Math.round((float) (BAR_WIDTH - look.filled()) * max / BAR_WIDTH), 1, max - 1));
        }
        return stack;
    }

    /** Server thread. Resends the owner's bows only when the visible name, readiness or bar pixel changes. */
    static void update(ServerPlayer player, MonsterForm form, long remainingMillis, long cooldownMillis) {
        var next = new Dressing(MonsterRoles.binding(player), look(form, remainingMillis, cooldownMillis));
        var previous = DRESSINGS.put(player.getUUID(), next);
        if (next.equals(previous)) return;
        refresh(player);
        if (previous != null && !previous.look().ready() && next.look().ready()) cue(player, SoundEvents.CROSSBOW_LOADING_END, 1.0F);
    }

    /** Server thread. The owner's client receives the authoritative bows again. */
    static void clear(ServerPlayer player) {
        if (DRESSINGS.remove(player.getUUID()) != null) refresh(player);
    }

    static void clearAll() { DRESSINGS.clear(); }

    /** A private "full draw" click: releasing now looses the volley. */
    static void fullDrawCue(ServerPlayer player) { cue(player, SoundEvents.CROSSBOW_QUICK_CHARGE_3, 1.2F); }

    private static void cue(ServerPlayer player, net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound, float pitch) {
        player.connection.send(new ClientboundSoundPacket(sound, SoundSource.PLAYERS,
                player.getX(), player.getY(), player.getZ(), 0.8F, pitch, player.getRandom().nextLong()));
    }

    /** Every bow is resent: a dressed bow may have just stopped being the sortie's own. */
    private static void refresh(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) resend(player, inventory, slot);
        resend(player, inventory, Inventory.SLOT_OFFHAND);
        var carried = player.containerMenu.getCarried();
        if (carried.is(Items.BOW)) player.connection.send(new ClientboundSetCursorItemPacket(carried.copy()));
    }

    private static void resend(ServerPlayer player, Inventory inventory, int slot) {
        var stack = inventory.getItem(slot);
        if (stack.is(Items.BOW)) player.connection.send(new ClientboundSetPlayerInventoryPacket(slot, stack.copy()));
    }

    /** Never mutates the shared packet or its stacks. */
    public static Packet<?> rewrite(ServerPlayer owner, Packet<?> packet) {
        var dressing = DRESSINGS.get(owner.getUUID());
        if (dressing == null) return packet;
        var look = dressing.look();
        if (packet instanceof ClientboundBundlePacket bundle) {
            List<Packet<? super ClientGamePacketListener>> rewritten = new ArrayList<>();
            boolean changed = false;
            for (Packet<? super ClientGamePacketListener> child : bundle.subPackets()) {
                Packet<?> replacement = rewrite(owner, child);
                changed |= replacement != child;
                @SuppressWarnings("unchecked") var game = (Packet<? super ClientGamePacketListener>) replacement;
                rewritten.add(game);
            }
            return changed ? new ClientboundBundlePacket(rewritten) : packet;
        }
        if (packet instanceof ClientboundSetPlayerInventoryPacket slot && dressing.dresses(owner, slot.contents()))
            return new ClientboundSetPlayerInventoryPacket(slot.slot(), dress(slot.contents(), look));
        if (packet instanceof ClientboundSetCursorItemPacket cursor && dressing.dresses(owner, cursor.contents()))
            return new ClientboundSetCursorItemPacket(dress(cursor.contents(), look));
        if (packet instanceof ClientboundContainerSetSlotPacket slot && dressing.dresses(owner, slot.getItem())) {
            var menu = menu(owner, slot.getContainerId());
            if (menu != null && ownSlot(owner, menu, slot.getSlot()))
                return new ClientboundContainerSetSlotPacket(slot.getContainerId(), slot.getStateId(), slot.getSlot(), dress(slot.getItem(), look));
        }
        if (packet instanceof ClientboundContainerSetContentPacket content) {
            var menu = menu(owner, content.containerId());
            if (menu == null) return packet;
            boolean changed = false;
            var items = new ArrayList<ItemStack>(content.items().size());
            for (int i = 0; i < content.items().size(); i++) {
                var stack = content.items().get(i);
                boolean own = dressing.dresses(owner, stack) && ownSlot(owner, menu, i);
                items.add(own ? dress(stack, look) : stack);
                changed |= own;
            }
            var carried = content.carriedItem();
            if (dressing.dresses(owner, carried)) { carried = dress(carried, look); changed = true; }
            if (changed) return new ClientboundContainerSetContentPacket(content.containerId(), content.stateId(), items, carried);
        }
        return packet;
    }

    /** Menu slots are only resolvable on the server thread, where vanilla sends container packets. */
    private static AbstractContainerMenu menu(ServerPlayer owner, int containerId) {
        if (!owner.level().getServer().isSameThread()) return null;
        if (containerId == owner.inventoryMenu.containerId) return owner.inventoryMenu;
        return containerId == owner.containerMenu.containerId ? owner.containerMenu : null;
    }

    private static boolean ownSlot(ServerPlayer owner, AbstractContainerMenu menu, int index) {
        return index >= 0 && index < menu.slots.size() && menu.slots.get(index).container == owner.getInventory();
    }
}
