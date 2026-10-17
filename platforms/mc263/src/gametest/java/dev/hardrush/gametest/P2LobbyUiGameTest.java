package dev.hardrush.gametest;

import dev.hardrush.p2.lobby.P2LobbyUi;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Headless native chest menus and server-owned button dispatch; no graphical client is launched. */
public final class P2LobbyUiGameTest {
    @GameTest
    public void nativeMenuSealsEveryInventoryTransferAndDispatchesOnlyItsServerSlot(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var player = test.makeMockServerPlayerInLevel();
        var access = new FixtureAccess();
        try {
            P2LobbyUi.configure(server, access);
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 17));
            test.assertTrue(P2LobbyUi.open(player), "An admitted native player opens the vanilla chest menu");
            var menu = player.containerMenu;
            test.assertTrue(P2LobbyUi.isMenu(player), "Network adapters can recognize this sealed menu");
            test.assertValueEqual(menu.slots.size(), 90, "Six rows plus the player's normal inventory slots");
            test.assertFalse(menu.getSlot(0).mayPickup(player), "Native slot extraction is independently sealed");
            test.assertFalse(menu.getSlot(81).mayPlace(new ItemStack(Items.EMERALD)), "Player inventory slots are read only inside the menu");
            for (ContainerInput input : ContainerInput.values()) {
                if (input != ContainerInput.PICKUP) menu.clicked(0, 0, input, player);
            }
            menu.clicked(0, 2, ContainerInput.PICKUP, player);
            menu.clicked(81, 0, ContainerInput.PICKUP, player);
            menu.clicked(-999, 0, ContainerInput.PICKUP, player);
            test.assertTrue(menu.quickMoveStack(player, 0).isEmpty(), "Quick move cannot extract any icon");
            test.assertValueEqual(access.actions.size(), 0, "Drag, shift, number, clone, throw and player slots invoke no action");
            test.assertValueEqual(player.getInventory().getItem(0).getCount(), 17, "All rejected transfers preserve existing inventory");
            test.assertTrue(menu.getCarried().isEmpty(), "No rejected transfer puts a menu item on the cursor");
            menu.clicked(0, 0, ContainerInput.PICKUP, player);
            test.assertValueEqual(access.actions, List.of("read:target"), "Primary pickup invokes the server's mapped action");
            test.assertTrue(menu.getCarried().isEmpty() && menu.getSlot(0).getItem().is(Items.EMERALD),
                    "Successful action keeps its icon in the GUI and off the cursor");
            menu.clicked(0, 1, ContainerInput.PICKUP, player);
            test.assertValueEqual(access.actions.size(), 1, "Immediate repeated clicks on the same button are throttled");
            player.closeContainer();
            P2LobbyUi.open(player);
            player.containerMenu.clicked(0, 0, ContainerInput.PICKUP, player);
            test.assertValueEqual(access.actions.size(), 1, "Closing and reopening cannot bypass the button throttle");
            player.containerMenu.clicked(51, 0, ContainerInput.PICKUP, player);
            test.assertFalse(P2LobbyUi.isMenu(player), "The native close control closes the menu");
            test.assertValueEqual(access.actions.size(), 1, "Closing is never an application action");
        } finally {
            player.closeContainer();
            P2LobbyUi.unconfigure(server);
            server.getPlayerList().remove(player);
        }
        test.succeed();
    }

    @GameTest
    public void rightClickAndPaginationUseFreshIndependentIconsAndAuthoritativeActions(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var player = test.makeMockServerPlayerInLevel();
        var access = new FixtureAccess();
        access.buttons.clear();
        for (int index = 0; index < 48; index++) access.buttons.add(button("Target " + index, "target-" + index));
        try {
            P2LobbyUi.configure(server, access);
            P2LobbyUi.open(player);
            var menu = player.containerMenu;
            ItemStack firstIcon = menu.getSlot(0).getItem();
            menu.clicked(53, 0, ContainerInput.PICKUP, player);
            test.assertValueEqual(menu.getSlot(0).getItem().getHoverName().getString(), "Target 45", "Next page reaches target 46");
            test.assertTrue(firstIcon != menu.getSlot(0).getItem(), "Pages never share mutable icon stacks");
            menu.clicked(0, 1, ContainerInput.PICKUP, player);
            test.assertValueEqual(access.actions, List.of("read:target-45"), "Secondary pickup has server-owned read dispatch");
            menu.clicked(45, 0, ContainerInput.PICKUP, player);
            test.assertValueEqual(menu.getSlot(0).getItem().getHoverName().getString(), "Target 0", "Previous page restores the first actions");
            test.assertTrue(firstIcon != menu.getSlot(0).getItem(), "Returning to a page rebuilds independent stacks");
            access.buttons.set(0, button("New authoritative target", "replacement"));
            menu.clicked(0, 0, ContainerInput.PICKUP, player);
            test.assertValueEqual(access.actions.size(), 1, "An action removed before refresh cannot execute from its stale icon");
            test.assertValueEqual(menu.getSlot(0).getItem().getHoverName().getString(), "New authoritative target",
                    "Rejected stale buttons refresh from authoritative state");
        } finally {
            player.closeContainer();
            P2LobbyUi.unconfigure(server);
            server.getPlayerList().remove(player);
        }
        test.succeed();
    }

    @GameTest
    public void staleRoundUnavailableAndWrongNativePlayerObjectsCannotAct(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var player = test.makeMockServerPlayerInLevel();
        var other = test.makeMockServerPlayerInLevel();
        var access = new FixtureAccess();
        try {
            P2LobbyUi.configure(server, access);
            P2LobbyUi.open(player);
            var menu = player.containerMenu;
            menu.clicked(0, 0, ContainerInput.PICKUP, other);
            test.assertValueEqual(access.actions.size(), 0, "A different native player object cannot invoke another player's menu");
            P2LobbyUi.open(player);
            menu = player.containerMenu;
            access.round = UUID.randomUUID();
            menu.clicked(0, 0, ContainerInput.PICKUP, player);
            test.assertValueEqual(access.actions.size(), 0, "An old round menu cannot mutate a new round");
            test.assertFalse(P2LobbyUi.isMenu(player), "A stale round closes immediately");
            P2LobbyUi.open(player);
            menu = player.containerMenu;
            access.available = false;
            menu.clicked(0, 0, ContainerInput.PICKUP, player);
            test.assertValueEqual(access.actions.size(), 0, "Permissions and transition state are checked again at click time");
            test.assertFalse(P2LobbyUi.open(player), "Unavailable players cannot reopen the GUI");
            access.available = true;
            P2LobbyUi.open(player);
            menu = player.containerMenu;
            P2LobbyUi.unconfigure(server);
            menu.clicked(0, 0, ContainerInput.PICKUP, player);
            test.assertValueEqual(access.actions.size(), 0, "An unconfigured runtime cannot keep acting through an old menu");
        } finally {
            player.closeContainer();
            other.closeContainer();
            P2LobbyUi.unconfigure(server);
            server.getPlayerList().remove(player);
            server.getPlayerList().remove(other);
        }
        test.succeed();
    }

    private static P2LobbyUi.Button button(String label, String target) {
        return new P2LobbyUi.Button(Items.EMERALD, label, "read", target);
    }

    private static final class FixtureAccess implements P2LobbyUi.Access {
        UUID round = UUID.randomUUID();
        boolean available = true;
        final List<P2LobbyUi.Button> buttons = new ArrayList<>(List.of(button("阅读章节", "target")));
        final List<String> actions = new ArrayList<>();

        @Override public boolean available(ServerPlayer player) { return available; }
        @Override public UUID round(ServerPlayer player) { return round; }
        @Override public List<P2LobbyUi.Button> buttons(ServerPlayer player) { return List.copyOf(buttons); }
        @Override public void act(ServerPlayer player, UUID expectedRound, String action, String value) {
            if (!round.equals(expectedRound) || !available) throw new AssertionError("Unvalidated application action");
            actions.add(action + ":" + value);
        }
    }
}
