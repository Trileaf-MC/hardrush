package dev.hardrush.lobby;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * A vanilla six-row chest of server-owned buttons: a button list, a click callback and a validity check. Every action
 * comes from a server-side slot mapping, never from item metadata; every inventory transfer is sealed. Pages of 45,
 * with previous/refresh/close/next controls on the bottom row; the list is rebuilt every second.
 */
public final class ButtonMenu extends ChestMenu {
    public static final int PAGE_SIZE = 45;
    public static final long BUTTON_COOLDOWN_NANOS = 250_000_000L;

    /** One button; {@code lore} lines appear under the label (a disabled button says why there). */
    public record Button(Item item, String label, String action, String value, List<String> lore) {
        public Button {
            Objects.requireNonNull(item); Objects.requireNonNull(label);
            Objects.requireNonNull(action); Objects.requireNonNull(value);
            lore = List.copyOf(lore);
        }
        public Button(Item item, String label, String action, String value) { this(item, label, action, value, List.of()); }
        /** A greyed-out button that does nothing; {@code reason} tells why. */
        public static Button disabled(String label, String reason) {
            return new Button(Items.STAINED_GLASS_PANE.pick(net.minecraft.world.item.DyeColor.GRAY), label, "info", "", List.of(reason));
        }
    }

    /** What a menu shows and does. Called on the server thread only. */
    public interface Spec {
        /** Checked on every click and tick; false closes the menu. */
        boolean valid(ServerPlayer owner);
        List<Button> buttons(ServerPlayer owner);
        void act(ServerPlayer owner, Button button);
    }

    /**
     * Repeated clicks on the same button within {@link #BUTTON_COOLDOWN_NANOS} are dropped. Keyed by UUID so closing
     * and reopening, or a replacement entity, cannot bypass it; owned by whoever owns the menus.
     */
    public static final class Throttle {
        private final Map<UUID, Map<String, Long>> clickTimes = new HashMap<>();

        boolean accept(UUID player, String key) {
            long now = System.nanoTime();
            var times = clickTimes.computeIfAbsent(player, ignored -> new HashMap<>());
            Long previous = times.get(key);
            if (previous != null && now - previous < BUTTON_COOLDOWN_NANOS) return false;
            times.entrySet().removeIf(entry -> now - entry.getValue() >= BUTTON_COOLDOWN_NANOS);
            times.put(key, now);
            return true;
        }

        /** Releases expired entries. */
        public void expire() {
            long now = System.nanoTime();
            clickTimes.values().removeIf(times -> {
                times.values().removeIf(when -> now - when >= BUTTON_COOLDOWN_NANOS);
                return times.isEmpty();
            });
        }
    }

    /** Opens a menu for {@code player} if {@code spec} is valid for it right now. */
    public static boolean open(ServerPlayer player, Component title, Throttle throttle, Spec spec) {
        var server = player.level().getServer();
        if (!server.isSameThread()) throw new IllegalStateException("Button menus require the server thread");
        if (server.getPlayerList().getPlayer(player.getUUID()) != player || !spec.valid(player)) return false;
        return player.openMenu(new SimpleMenuProvider((id, inventory, actor) ->
                actor == player ? new ButtonMenu(id, player, throttle, spec) : null, title)).isPresent();
    }

    /** Every button menu is sealed the same way; network adapters let only these through for locked players. */
    public static boolean isMenu(ServerPlayer player) { return player.containerMenu instanceof ButtonMenu; }

    private final ServerPlayer owner;
    private final net.minecraft.server.network.ServerGamePacketListenerImpl connection;
    private final UUID playerId;
    private final MinecraftServer server;
    private final Throttle throttle;
    private final Spec spec;
    private final Map<Integer, Button> actions = new HashMap<>();
    private int page;
    private int pageCount;
    private int refreshTicks;

    private ButtonMenu(int id, ServerPlayer owner, Throttle throttle, Spec spec) {
        super(MenuType.GENERIC_9x6, id, owner.getInventory(), new SimpleContainer(54), 6);
        this.owner = owner;
        this.connection = owner.connection;
        this.playerId = owner.getUUID();
        this.server = owner.level().getServer();
        this.throttle = throttle;
        this.spec = spec;
        rebuild();
    }

    @Override protected Slot addSlot(Slot slot) {
        return super.addSlot(new Slot(slot.container, slot.getContainerSlot(), slot.x, slot.y) {
            @Override public boolean mayPlace(ItemStack item) { return false; }
            @Override public boolean mayPickup(Player actor) { return false; }
        });
    }

    @Override public boolean stillValid(Player actor) {
        return actor == owner && playerId.equals(actor.getUUID()) && owner.containerMenu == this
                && owner.connection == connection && server.getPlayerList().getPlayer(playerId) == owner && spec.valid(owner);
    }

    @Override public void clicked(int slot, int button, ContainerInput input, Player actor) {
        if (!stillValid(actor)) {
            if (owner.containerMenu == this) owner.closeContainer();
            return;
        }
        if (input != ContainerInput.PICKUP || button < 0 || button > 1 || slot < 0 || slot >= 54) {
            sendAllDataToRemote();
            return;
        }
        Button selected = actions.get(slot);
        if (selected != null) {
            // A state/permission change can remove a displayed action before the next periodic rebuild.
            if (!List.copyOf(spec.buttons(owner)).contains(selected)) {
                rebuild();
            } else if (throttle.accept(playerId, selected.action() + "\0" + selected.value())) {
                spec.act(owner, selected);
                if (owner.containerMenu == this && stillValid(owner)) rebuild();
            }
        } else if (slot == 51) {
            owner.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                    net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT, net.minecraft.sounds.SoundSource.PLAYERS,
                    owner.getX(), owner.getY(), owner.getZ(), .3f, 1f, 0));
            owner.closeContainer();
            return;
        } else if (slot == 45 && page > 0 && throttle.accept(playerId, "ui:previous")) {
            page--;
            rebuild();
        } else if (slot == 53 && page + 1 < pageCount && throttle.accept(playerId, "ui:next")) {
            page++;
            rebuild();
        } else if (slot == 49 && throttle.accept(playerId, "ui:refresh")) {
            rebuild();
        }
        if (owner.containerMenu == this) sendAllDataToRemote();
    }

    @Override public ItemStack quickMoveStack(Player actor, int slot) { return ItemStack.EMPTY; }
    @Override public boolean canDragTo(Slot slot) { return false; }
    @Override public boolean canTakeItemForPickAll(ItemStack item, Slot slot) { return false; }
    @Override public boolean clickMenuButton(Player actor, int button) { return false; }

    @Override public void broadcastChanges() {
        if (!stillValid(owner)) {
            if (owner.containerMenu == this) owner.closeContainer();
            return;
        }
        if (++refreshTicks >= 20) {
            refreshTicks = 0;
            rebuild();
        }
        super.broadcastChanges();
    }

    private void rebuild() {
        List<Button> buttons = List.copyOf(spec.buttons(owner));
        pageCount = Math.max(1, (buttons.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pageCount - 1);
        actions.clear();
        getContainer().clearContent();
        int start = page * PAGE_SIZE;
        for (int index = start; index < Math.min(start + PAGE_SIZE, buttons.size()); index++) {
            Button button = buttons.get(index);
            int slot = index - start;
            actions.put(slot, button);
            getContainer().setItem(slot, icon(button.item(), button.label(), button.lore()));
        }
        if (page > 0) getContainer().setItem(45, icon(Items.ARROW, "上一页", List.of()));
        getContainer().setItem(49, icon(Items.COMPASS, "刷新", List.of()));
        getContainer().setItem(50, icon(Items.PAPER, "第 " + (page + 1) + " / " + pageCount + " 页", List.of()));
        getContainer().setItem(51, icon(Items.BARRIER, "关闭", List.of()));
        if (page + 1 < pageCount) getContainer().setItem(53, icon(Items.ARROW, "下一页", List.of()));
    }

    private static ItemStack icon(Item item, String label, List<String> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(label));
        if (!lore.isEmpty()) stack.set(DataComponents.LORE, new ItemLore(lore.stream()
                .map(line -> (Component) Component.literal(line).withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY)))
                .toList()));
        return stack;
    }
}
