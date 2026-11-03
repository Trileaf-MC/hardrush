package dev.hardrush.observe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * Six-row head chest behind the spyglass: humans on top, a pane of gray glass, deployed monsters below as
 * their native mob heads. Each click is resolved from a server-owned slot map, never from item data.
 */
public final class ObservationMenu extends ChestMenu {
    private static final int ROWS = 6, WIDTH = 9;
    private final ServerPlayer owner;
    private final net.minecraft.server.network.ServerGamePacketListenerImpl connection;
    private final Map<Integer, UUID> targets = new HashMap<>();
    private int refreshTicks;

    private ObservationMenu(int id, ServerPlayer owner) {
        super(MenuType.GENERIC_9x6, id, owner.getInventory(), new SimpleContainer(ROWS * WIDTH), ROWS);
        this.owner = owner;
        this.connection = owner.connection;
        rebuild();
    }

    public static boolean isMenu(ServerPlayer player) { return player.containerMenu instanceof ObservationMenu; }

    public static void open(ServerPlayer player) {
        var refusal = Observation.refusal(player);
        if (refusal != null) { Observation.hint(player, refusal); return; }
        player.openMenu(new SimpleMenuProvider((id, inventory, actor) -> actor == player ? new ObservationMenu(id, player) : null,
                Component.literal("望远镜 · 选择观察对象")));
    }

    @Override protected Slot addSlot(Slot slot) {
        return super.addSlot(new Slot(slot.container, slot.getContainerSlot(), slot.x, slot.y) {
            @Override public boolean mayPlace(ItemStack item) { return false; }
            @Override public boolean mayPickup(Player actor) { return false; }
        });
    }

    @Override public boolean stillValid(Player actor) {
        return actor == owner && owner.containerMenu == this && owner.connection == connection
                && owner.level().getServer().getPlayerList().getPlayer(owner.getUUID()) == owner
                && Observation.refusal(owner) == null;
    }

    @Override public void clicked(int slot, int button, ContainerInput input, Player actor) {
        if (!stillValid(actor)) {
            if (owner.containerMenu == this) owner.closeContainer();
            return;
        }
        var target = input == ContainerInput.PICKUP && button >= 0 && button <= 1 ? targets.get(slot) : null;
        if (target != null && Observation.watch(owner, target)) return;
        rebuild();
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
        if (++refreshTicks >= 20) { refreshTicks = 0; rebuild(); }
        super.broadcastChanges();
    }

    private void rebuild() {
        targets.clear();
        getContainer().clearContent();
        var candidates = Observation.candidates(owner);
        var humans = candidates.stream().filter(Observation.Candidate::human).toList();
        var monsters = candidates.stream().filter(c -> !c.human()).toList();
        if (candidates.isEmpty()) {
            getContainer().setItem(4, named(new ItemStack(Items.PAPER), "暂时没有可以观察的玩家", List.of()));
            return;
        }
        int humanRows = Math.max(1, Math.min(ROWS - 2, (humans.size() + WIDTH - 1) / WIDTH));
        place(humans, 0, humanRows * WIDTH);
        for (int i = 0; i < WIDTH; i++) getContainer().setItem(humanRows * WIDTH + i, named(new ItemStack(Items.STAINED_GLASS_PANE.pick(net.minecraft.world.item.DyeColor.GRAY)), " ", List.of()));
        place(monsters, (humanRows + 1) * WIDTH, ROWS * WIDTH);
    }

    private void place(List<Observation.Candidate> candidates, int from, int to) {
        for (int i = 0; i < candidates.size() && from + i < to; i++) {
            var candidate = candidates.get(i);
            var target = candidate.player();
            ItemStack head;
            var lore = new ArrayList<String>();
            if (candidate.human()) {
                head = new ItemStack(Items.PLAYER_HEAD);
                head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(target.getGameProfile()));
                lore.add("已存活 " + duration(candidate.lifeNanos()));
            } else {
                head = new ItemStack(switch (candidate.role()) {
                    case ZOMBIE -> Items.ZOMBIE_HEAD; case SKELETON -> Items.SKELETON_SKULL; case CREEPER -> Items.CREEPER_HEAD;
                });
                lore.add("化身：" + switch (candidate.role()) { case ZOMBIE -> "僵尸"; case SKELETON -> "骷髅"; case CREEPER -> "苦力怕"; });
            }
            lore.add("点击附身观察");
            getContainer().setItem(from + i, named(head, target.getScoreboardName(), lore));
            targets.put(from + i, target.getUUID());
        }
    }

    private static String duration(long nanos) {
        long seconds = nanos / 1_000_000_000L;
        long hours = seconds / 3600, minutes = seconds / 60 % 60;
        return hours > 0 ? String.format("%d:%02d:%02d", hours, minutes, seconds % 60) : String.format("%d:%02d", minutes, seconds % 60);
    }

    private static ItemStack named(ItemStack stack, String name, List<String> lore) {
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.WHITE)));
        stack.set(DataComponents.LORE, new ItemLore(lore.stream().map(line -> (Component) Component.literal(line)
                .withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.GRAY))).toList()));
        return stack;
    }
}
