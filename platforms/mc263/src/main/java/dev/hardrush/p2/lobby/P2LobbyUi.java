package dev.hardrush.p2.lobby;

import dev.hardrush.core.lobby.LobbyContext;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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

/** Vanilla six-row lobby menu. Every action comes from a server-owned slot, never item metadata. */
public final class P2LobbyUi {
    public static final int PAGE_SIZE = 45;
    public static final long BUTTON_COOLDOWN_NANOS = 250_000_000L;
    private static final Map<MinecraftServer, Binding> BINDINGS = new IdentityHashMap<>();

    public record Button(Item item, String label, String action, String value) {
        public Button {
            Objects.requireNonNull(item); Objects.requireNonNull(label);
            Objects.requireNonNull(action); Objects.requireNonNull(value);
        }
    }

    public interface Access {
        boolean available(ServerPlayer player);
        UUID round(ServerPlayer player);
        List<Button> buttons(ServerPlayer player);
        void act(ServerPlayer player, UUID expectedRound, String action, String value);
        default boolean available(ServerPlayer player, LobbyContext context) { return context == LobbyContext.COMPETITION && available(player); }
        default List<Button> buttons(ServerPlayer player, LobbyContext context) { return buttons(player); }
        default void act(ServerPlayer player, UUID expectedRound, LobbyContext context, String action, String value) {
            if (context == LobbyContext.COMPETITION) act(player, expectedRound, action, value);
        }
    }

    private P2LobbyUi() {}

    public static void configure(MinecraftServer server, Access access) {
        requireServerThread(server);
        BINDINGS.put(server, new Binding(Objects.requireNonNull(access)));
    }

    public static void unconfigure(MinecraftServer server) {
        requireServerThread(server);
        BINDINGS.remove(server);
    }

    public static boolean open(ServerPlayer player) { return open(player, LobbyContext.COMPETITION); }

    public static boolean open(ServerPlayer player, LobbyContext context) {
        var server = player.level().getServer();
        requireServerThread(server);
        Binding binding = BINDINGS.get(server);
        if (binding == null || server.getPlayerList().getPlayer(player.getUUID()) != player
                || !binding.access.available(player, context)) return false;
        UUID round = binding.access.round(player);
        if (round == null) return false;
        return player.openMenu(new SimpleMenuProvider((id, inventory, actor) ->
                actor == player ? new LobbyMenu(id, player, binding, round, context) : null,
                Component.literal(context == LobbyContext.RULES ? "HardRush 规则" : context == LobbyContext.HISTORY ? "HardRush 历史" : context == LobbyContext.REVIVAL ? "复生祭坛确认" : "HardRush 大厅"))).isPresent();
    }

    /** Also identifies stale menus so the network adapter keeps creative-slot packets sealed. */
    public static boolean isMenu(ServerPlayer player) { return player.containerMenu instanceof LobbyMenu; }

    private static void requireServerThread(MinecraftServer server) {
        Objects.requireNonNull(server);
        if (!server.isSameThread()) throw new IllegalStateException("Lobby GUI requires the server thread");
    }

    private record ActionKey(String action, String value) {}

    private static final class Binding {
        final Access access;
        // UUID keys retain throttling when a player closes/reopens or obtains a replacement entity.
        final Map<UUID, Map<ActionKey, Long>> clickTimes = new HashMap<>();

        Binding(Access access) { this.access = access; }

        boolean accept(UUID player, ActionKey key) {
            long now = System.nanoTime();
            var times = clickTimes.computeIfAbsent(player, ignored -> new HashMap<>());
            Long previous = times.get(key);
            if (previous != null && now - previous < BUTTON_COOLDOWN_NANOS) return false;
            times.entrySet().removeIf(entry -> now - entry.getValue() >= BUTTON_COOLDOWN_NANOS);
            times.put(key, now);
            return true;
        }
    }

    private static final class LobbyMenu extends ChestMenu {
        private final ServerPlayer owner;
        private final net.minecraft.server.network.ServerGamePacketListenerImpl connection;
        private final UUID playerId;
        private final LobbyContext context;
        private final UUID expectedRound;
        private final MinecraftServer server;
        private final Binding binding;
        private final Map<Integer, Button> actions = new HashMap<>();
        private int page;
        private int pageCount;
        private int refreshTicks;

        LobbyMenu(int id, ServerPlayer owner, Binding binding, UUID expectedRound, LobbyContext context) {
            super(MenuType.GENERIC_9x6, id, owner.getInventory(), new SimpleContainer(54), 6);
            this.owner = owner;
            this.connection = owner.connection;
            this.context = context;
            this.playerId = owner.getUUID();
            this.server = owner.level().getServer();
            this.binding = binding;
            this.expectedRound = expectedRound;
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
                    && owner.connection == connection
                    && BINDINGS.get(server) == binding && server.getPlayerList().getPlayer(playerId) == owner
                    && binding.access.available(owner, context) && expectedRound.equals(binding.access.round(owner));
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
                if (!List.copyOf(binding.access.buttons(owner, context)).contains(selected)) {
                    rebuild();
                } else if (binding.accept(playerId, new ActionKey(selected.action(), selected.value()))) {
                    binding.access.act(owner, expectedRound, context, selected.action(), selected.value());
                    if (owner.containerMenu == this && stillValid(owner)) rebuild();
                }
            } else if (slot == 51) {
                owner.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                        net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT, net.minecraft.sounds.SoundSource.PLAYERS,
                        owner.getX(), owner.getY(), owner.getZ(), .3f, 1f, 0));
                owner.closeContainer();
                return;
            } else if (slot == 45 && page > 0 && binding.accept(playerId, new ActionKey("ui:previous", ""))) {
                page--;
                rebuild();
            } else if (slot == 53 && page + 1 < pageCount
                    && binding.accept(playerId, new ActionKey("ui:next", ""))) {
                page++;
                rebuild();
            } else if (slot == 49 && binding.accept(playerId, new ActionKey("ui:refresh", ""))) {
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
            List<Button> buttons = List.copyOf(binding.access.buttons(owner, context));
            pageCount = Math.max(1, (buttons.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            page = Math.min(page, pageCount - 1);
            actions.clear();
            getContainer().clearContent();
            int start = page * PAGE_SIZE;
            for (int index = start; index < Math.min(start + PAGE_SIZE, buttons.size()); index++) {
                Button button = buttons.get(index);
                int slot = index - start;
                actions.put(slot, button);
                getContainer().setItem(slot, icon(button.item(), button.label()));
            }
            if (page > 0) getContainer().setItem(45, icon(Items.ARROW, "上一页"));
            getContainer().setItem(49, icon(Items.COMPASS, "刷新"));
            getContainer().setItem(50, icon(Items.PAPER, "第 " + (page + 1) + " / " + pageCount + " 页"));
            getContainer().setItem(51, icon(Items.BARRIER, context == LobbyContext.REVIVAL ? "继续作为怪物（不消费资格）" : "关闭"));
            if (page + 1 < pageCount) getContainer().setItem(53, icon(Items.ARROW, "下一页"));
        }

        private static ItemStack icon(Item item, String label) {
            ItemStack stack = new ItemStack(item);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(label));
            return stack;
        }
    }
}
