package dev.hardrush.revival;

import com.mojang.authlib.GameProfile;
import dev.hardrush.HardRushProperties;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.gametest.TestClients;
import dev.hardrush.gametest.TestTeardown;
import dev.hardrush.notice.Notices;
import dev.hardrush.revival.TotemProjection.Identity;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShelfBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Revival totems as held items, on a real fixture player outside the competition runtime: the projection's three
 * states against a scripted round (design 2.2), death without spilling them (2.6), and every way they could leave
 * their holder (2.7). Runs in the default headless storage; {@code -PhardrushRevivalTotems=true} runs it alone.
 */
public final class RevivalTotemGameTest {
    private static final AtomicInteger ARENAS = new AtomicInteger();
    private static final String HUMAN_USAGE = "拿在手上阵亡时原地复生，消耗 1 个";

    @GameTest(maxTicks = 200)
    public void projectionFreezesWhileLockedFollowsTheHolderAndSurvivesDeath(GameTestHelper test) {
        if (HardRushProperties.competitionEnabled()) { test.succeed(); return; }
        var f = new Fixture(test);
        TestTeardown.atEnd(test, f::close);
        var access = new ScriptedAccess();
        RevivalTotems.configure(f.server, access);
        TestTeardown.atEnd(test, () -> RevivalTotems.unconfigure(f.server));
        var round = UUID.randomUUID();
        test.startSequence().thenWaitUntil(f::awaitArena).thenExecute(() -> {
            access.set(round, f.player, ParticipantRole.HUMAN_ACTIVE, 1, 3, 0, true);
            RevivalTotems.refresh(f.player);
            test.assertValueEqual(total(f.player), 3, "An eligible human holds the committed balance");
            var held = first(f.player);
            test.assertValueEqual(held.getHoverName().getString(), "复生图腾", "The same gold-named item as in camp");
            test.assertFalse(held.has(DataComponents.DEATH_PROTECTION), "Never a native death guard");
            test.assertValueEqual(lore(held, 0), HUMAN_USAGE, "A human reads how to use it in the world");
            test.assertValueEqual(lore(held, 1), "没拿在手上就阵亡，可到营地祭坛复生", "Second human line");
            test.assertValueEqual(lore(held, 2), "阵亡不掉落 · 不能丢弃或转赠", "Third human line");
            test.assertValueEqual(RevivalTotems.heldHint(f.player, held).map(h -> Notices.format(h).getString()),
                    Optional.of(HUMAN_USAGE), "The held hint is the first description line");

            // The player keeps it in the off-hand, ready for an in-place revival.
            var inventory = f.player.getInventory();
            int slot = slot(f.player);
            inventory.setItem(Inventory.SLOT_OFFHAND, inventory.getItem(slot));
            inventory.setItem(slot, ItemStack.EMPTY);
            RevivalTotems.refresh(f.player);
            test.assertValueEqual(offhand(f.player), 3, "A projected stack stays in the slot the player chose");

            access.locked = true;
            access.set(round, f.player, ParticipantRole.HUMAN_ACTIVE, 1, 5, 0, true);
            RevivalTotems.refresh(f.player);
            test.assertTrue(offhand(f.player) == 3 && total(f.player) == 3, "A competition lock freezes every slot");
            access.locked = false;
            access.body = false;
            RevivalTotems.refresh(f.player);
            test.assertTrue(offhand(f.player) == 3 && total(f.player) == 3, "An unverified or locked body is frozen too");
            access.body = true;
            access.set(round, f.player, ParticipantRole.HUMAN_ACTIVE, 1, 5, 0, false);
            RevivalTotems.refresh(f.player);
            test.assertTrue(offhand(f.player) == 3 && total(f.player) == 3, "So is an offline participant");
            access.set(round, f.player, ParticipantRole.HUMAN_ACTIVE, 1, 5, 0, true);
            RevivalTotems.refresh(f.player);
            test.assertTrue(offhand(f.player) == 5 && total(f.player) == 5, "Unlocked again, the balance grows in place");

            // Death committed: the same player is now a camp monster with the same charges.
            access.set(round, f.player, ParticipantRole.MONSTER_LOBBY, 1, 5, 0, true);
            RevivalTotems.refresh(f.player);
            test.assertTrue(offhand(f.player) == 5 && total(f.player) == 5, "A holder change keeps the count");
            test.assertValueEqual(lore(f.player.getOffhandItem(), 0), "站上营地北侧祭坛的金块 4 秒复生", "The description follows the holder");
            test.assertValueEqual(lore(f.player.getOffhandItem(), 1), "每个图腾复生一次，复生后空手出发，其余图腾随身带走",
                    "The camp line says the other totems come along");
            test.assertValueEqual(lore(f.player.getOffhandItem(), 2), "不能丢弃或转赠 · 本轮结束时清空", "Third camp line");

            // Altar revival: a new human life holds the remaining totems with the human description.
            access.set(round, f.player, ParticipantRole.HUMAN_ACTIVE, 2, 4, 0, true);
            RevivalTotems.refresh(f.player);
            test.assertTrue(total(f.player) == 4 && lore(first(f.player), 0).equals(HUMAN_USAGE), "Back to the human description");

            // A real identity change while not yet eligible removes the old life's copies instead of freezing them.
            access.body = false;
            access.set(round, f.player, ParticipantRole.MONSTER_LOBBY, 2, 4, 0, true);
            RevivalTotems.refresh(f.player);
            test.assertValueEqual(total(f.player), 0, "Copies of another identity are cleared");
            access.body = true;
            access.set(round, f.player, ParticipantRole.HUMAN_ACTIVE, 3, 120, 0, true);
            RevivalTotems.refresh(f.player);
            test.assertValueEqual(total(f.player), RevivalTotems.MAX_STACK, "One full stack at most");
            test.assertValueEqual(lore(first(f.player), 1), "共 120 个", "Beyond a full stack the total is written out");
            access.set(round, f.player, ParticipantRole.HUMAN_ACTIVE, 3, 2, 0, true);
            RevivalTotems.pendingSaves(f.server, player -> player.equals(f.player.getUUID()) ? 1 : 0);
            RevivalTotems.refresh(f.player);
            test.assertValueEqual(total(f.player), 1, "An in-flight in-place revival is already taken off");
            RevivalTotems.pendingSaves(f.server, player -> 0);
            RevivalTotems.refresh(f.player);
            test.assertValueEqual(total(f.player), 2, "Without it the whole balance shows");

            // Death never spills the human's own totems; the rest of the inventory drops as usual.
            f.player.getInventory().setItem(5, new ItemStack(Items.STICK));
            var at = f.player.position();
            f.player.containerMenu.setCarried(ItemStack.EMPTY);
            test.assertTrue(f.player.hurtServer(f.level, f.player.damageSources().genericKill(), Float.MAX_VALUE) && f.player.isDeadOrDying(),
                    "The fixture human dies");
            test.assertValueEqual(total(f.player), 0, "The dead body keeps no totem to drop later");
            var floor = f.level.getEntitiesOfClass(ItemEntity.class, new AABB(at, at).inflate(6));
            test.assertTrue(floor.stream().anyMatch(item -> item.getItem().is(Items.STICK)), "Ordinary loot drops");
            test.assertFalse(floor.stream().anyMatch(item -> RevivalTotems.is(item.getItem())), "No revival totem among the drops");
            f.player.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            var respawned = f.server.getPlayerList().getPlayer(f.profile.id());
            test.assertTrue(respawned != null && respawned != f.player && respawned.isAlive(), "The native respawn replaces the body");
            f.player = respawned;
            access.set(round, f.player, ParticipantRole.MONSTER_LOBBY, 3, 2, 0, true);
            RevivalTotems.refresh(f.player);
            test.assertValueEqual(total(f.player), 2, "In camp the same balance is held again");
        }).thenIdle(1).thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void totemNeverLeavesThroughThrowsMenusBundlesOrWorldDisplays(GameTestHelper test) {
        if (HardRushProperties.competitionEnabled()) { test.succeed(); return; }
        var f = new Fixture(test);
        TestTeardown.atEnd(test, f::close);
        test.startSequence().thenWaitUntil(f::awaitArena).thenExecute(() -> {
            var inventory = f.player.getInventory();
            inventory.setItem(0, RevivalTotems.held(new Identity(UUID.randomUUID(), ParticipantRole.HUMAN_ACTIVE, 1, 0), 3, 3));
            f.select(0);

            // Q and Ctrl+Q, Q over an inventory slot, a cursor stack dropped outside the window, any direct drop.
            for (var action : new ServerboundPlayerActionPacket.Action[] {
                    ServerboundPlayerActionPacket.Action.DROP_ITEM, ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS}) {
                int sent = f.resyncs();
                f.player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(action, BlockPos.ZERO, Direction.DOWN));
                test.assertTrue(inventory.getItem(0).getCount() == 3 && f.resyncs() > sent, "Q is refused and the slot resent: " + action);
            }
            var menu = f.player.inventoryMenu;
            f.refused(() -> menu.clicked(36, 1, ContainerInput.THROW, f.player), "inventory Q");
            menu.clicked(36, 0, ContainerInput.PICKUP, f.player);
            menu.clicked(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, 0, ContainerInput.PICKUP, f.player);
            test.assertTrue(RevivalTotems.is(menu.getCarried()), "Clicking outside the window keeps the totem on the cursor");
            menu.clicked(36, 0, ContainerInput.PICKUP, f.player);
            f.player.drop(inventory.removeItem(0, 1), true, Prediction.PREDICTED);
            test.assertValueEqual(total(f.player), 3, "Every throw path leaves the count intact");
            f.assertNoFloorTotem("throws");

            // A chest: click, right-click, shift-click, number-key swap, off-hand swap and drag.
            var chestPos = f.base.offset(2, 0, 0);
            f.level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
            var chest = (ChestBlockEntity) f.level.getBlockEntity(chestPos);
            f.player.openMenu(chest);
            test.assertTrue(f.player.containerMenu instanceof ChestMenu, "The chest menu is open");
            refuseInto(test, f, 27 + 27, 0);
            f.refused(() -> f.player.containerMenu.clicked(54, 0, ContainerInput.QUICK_MOVE, f.player), "chest shift-click");
            test.assertTrue(chest.isEmpty() && RevivalTotems.is(inventory.getItem(0)), "Shift-click does not move it into the chest");
            int sent = f.resyncs();
            f.player.containerMenu.clicked(4, 0, ContainerInput.SWAP, f.player);
            test.assertTrue(chest.isEmpty() && RevivalTotems.is(inventory.getItem(0)) && f.resyncs() > sent,
                    "A number-key swap into a chest slot is refused and resent");
            inventory.setItem(Inventory.SLOT_OFFHAND, inventory.getItem(0));
            inventory.setItem(0, ItemStack.EMPTY);
            f.player.containerMenu.clicked(5, Inventory.SLOT_OFFHAND, ContainerInput.SWAP, f.player);
            test.assertTrue(chest.isEmpty() && offhand(f.player) == 3, "The off-hand key cannot swap it in either");
            inventory.setItem(0, inventory.getItem(Inventory.SLOT_OFFHAND));
            inventory.setItem(Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
            var chestMenu = f.player.containerMenu;
            chestMenu.clicked(54, 0, ContainerInput.PICKUP, f.player);
            chestMenu.clicked(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, AbstractContainerMenu.getQuickcraftMask(0, 0), ContainerInput.QUICK_CRAFT, f.player);
            chestMenu.clicked(0, AbstractContainerMenu.getQuickcraftMask(1, 0), ContainerInput.QUICK_CRAFT, f.player);
            chestMenu.clicked(55, AbstractContainerMenu.getQuickcraftMask(1, 0), ContainerInput.QUICK_CRAFT, f.player);
            f.refused(() -> chestMenu.clicked(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, AbstractContainerMenu.getQuickcraftMask(2, 0), ContainerInput.QUICK_CRAFT, f.player), "drag release");
            test.assertTrue(chest.isEmpty() && inventory.getItem(1).getCount() == 3 && chestMenu.getCarried().isEmpty(),
                    "A drag spreads the totem over the player's own slots only");
            inventory.setItem(0, inventory.getItem(1));
            inventory.setItem(1, ItemStack.EMPTY);
            f.player.closeContainer();

            // A shulker box: its slot never asks super.mayPlace, the menu rule still holds.
            var boxPos = f.base.offset(-2, 0, 2);
            f.level.setBlockAndUpdate(boxPos, Blocks.SHULKER_BOX.defaultBlockState());
            var box = (ShulkerBoxBlockEntity) f.level.getBlockEntity(boxPos);
            f.player.openMenu(box);
            test.assertTrue(f.player.containerMenu instanceof ShulkerBoxMenu, "The shulker box menu is open");
            refuseInto(test, f, 54, 0);
            f.refused(() -> f.player.containerMenu.clicked(54, 0, ContainerInput.QUICK_MOVE, f.player), "shulker shift-click");
            test.assertTrue(box.isEmpty() && RevivalTotems.is(inventory.getItem(0)), "Shift-click does not move it into the box");
            f.player.closeContainer();

            // A bundle takes nothing in either direction.
            inventory.setItem(1, new ItemStack(Items.BUNDLE));
            menu.clicked(36, 0, ContainerInput.PICKUP, f.player);
            f.refused(() -> menu.clicked(37, 0, ContainerInput.PICKUP, f.player), "totem onto bundle");
            test.assertTrue(RevivalTotems.is(menu.getCarried()), "A carried totem clicked onto a bundle stays on the cursor");
            menu.clicked(36, 0, ContainerInput.PICKUP, f.player);
            menu.clicked(37, 0, ContainerInput.PICKUP, f.player);
            f.refused(() -> menu.clicked(36, 0, ContainerInput.PICKUP, f.player), "bundle onto totem");
            test.assertTrue(menu.getCarried().is(Items.BUNDLE), "A carried bundle clicked onto a totem picks nothing up");
            menu.clicked(37, 0, ContainerInput.PICKUP, f.player);
            test.assertTrue(inventory.getItem(1).get(DataComponents.BUNDLE_CONTENTS).isEmpty() && inventory.getItem(0).getCount() == 3,
                    "The bundle stays empty");

            // A shelf, held and powered (the powered row swaps the right end of the hotbar).
            var shelfPos = f.base.offset(0, 0, -3);
            f.level.setBlockAndUpdate(shelfPos, Blocks.OAK_SHELF.defaultBlockState().setValue(ShelfBlock.FACING, Direction.NORTH));
            var shelf = (ShelfBlockEntity) f.level.getBlockEntity(shelfPos);
            var front = new BlockHitResult(Vec3.atCenterOf(shelfPos).add(0, 0, -.5), Direction.NORTH, shelfPos, false);
            f.select(0);
            f.refused(() -> f.player.gameMode.useItemOn(f.player, f.level, f.player.getMainHandItem(), InteractionHand.MAIN_HAND, front), "shelf");
            test.assertTrue(shelf.isEmpty() && inventory.getItem(0).getCount() == 3, "A shelf does not take the held totem");
            f.level.setBlockAndUpdate(shelfPos.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
            test.assertTrue(f.level.getBlockState(shelfPos).getValue(ShelfBlock.POWERED), "The shelf is powered");
            inventory.setItem(7, inventory.getItem(0));
            inventory.setItem(0, new ItemStack(Items.STICK));
            f.refused(() -> f.player.gameMode.useItemOn(f.player, f.level, f.player.getMainHandItem(), InteractionHand.MAIN_HAND, front), "powered shelf");
            test.assertTrue(shelf.isEmpty() && inventory.getItem(7).getCount() == 3, "A powered shelf does not swap a hotbar totem away");
            inventory.setItem(0, inventory.getItem(7));
            inventory.setItem(7, ItemStack.EMPTY);

            // An item frame, an armor stand with arms, a decorated pot and an allay.
            var frame = new ItemFrame(f.level, f.base.offset(3, 1, 3), Direction.NORTH);
            f.level.addFreshEntity(frame);
            f.refused(() -> f.player.interactOn(frame, InteractionHand.MAIN_HAND, Vec3.ZERO), "item frame");
            test.assertTrue(frame.getItem().isEmpty() && inventory.getItem(0).getCount() == 3, "An item frame does not take it");
            var stand = new ArmorStand(f.level, f.base.getX() - 3 + .5, f.base.getY(), f.base.getZ() - 3 + .5);
            stand.setShowArms(true);
            f.level.addFreshEntity(stand);
            f.refused(() -> f.player.interactOn(stand, InteractionHand.MAIN_HAND, new Vec3(0, 1, 0)), "armor stand");
            test.assertTrue(stand.getMainHandItem().isEmpty() && inventory.getItem(0).getCount() == 3, "An armor stand does not take it");
            var potPos = f.base.offset(3, 0, -1);
            f.level.setBlockAndUpdate(potPos, Blocks.DECORATED_POT.defaultBlockState());
            f.refused(() -> f.player.gameMode.useItemOn(f.player, f.level, f.player.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(potPos).add(0, .5, 0), Direction.UP, potPos, false)), "decorated pot");
            test.assertTrue(((DecoratedPotBlockEntity) f.level.getBlockEntity(potPos)).getTheItem().isEmpty()
                    && inventory.getItem(0).getCount() == 3, "A decorated pot does not take it");
            var allay = EntityTypes.ALLAY.create(f.level, EntitySpawnReason.COMMAND);
            allay.setPos(f.base.getX() + .5, f.base.getY(), f.base.getZ() + 2.5);
            f.level.addFreshEntity(allay);
            f.refused(() -> f.player.interactOn(allay, InteractionHand.MAIN_HAND, Vec3.ZERO), "allay");
            test.assertTrue(allay.getMainHandItem().isEmpty() && inventory.getItem(0).getCount() == 3, "An allay is not handed it");
            test.assertValueEqual(total(f.player), 3, "Nothing was lost on the way");
            f.assertNoFloorTotem("world displays");
            frame.discard(); stand.discard(); allay.discard();
        }).thenIdle(1).thenSucceed();
    }

    /** Picks the hotbar-0 totem up in an open menu and tries both buttons on a foreign slot, then puts it back. */
    private static void refuseInto(GameTestHelper test, Fixture f, int hotbar0, int foreign) {
        var menu = f.player.containerMenu;
        menu.clicked(hotbar0, 0, ContainerInput.PICKUP, f.player);
        test.assertTrue(RevivalTotems.is(menu.getCarried()), "The player's own slot gives the totem to the cursor");
        int sent = f.resyncs();
        menu.clicked(foreign, 0, ContainerInput.PICKUP, f.player);
        menu.clicked(foreign, 1, ContainerInput.PICKUP, f.player);
        test.assertTrue(menu.getSlot(foreign).getItem().isEmpty() && menu.getCarried().getCount() == 3 && f.resyncs() > sent,
                "Neither button places it in a foreign slot, and the menu is resent");
        menu.clicked(hotbar0, 0, ContainerInput.PICKUP, f.player);
        test.assertTrue(menu.getCarried().isEmpty() && RevivalTotems.is(f.player.getInventory().getItem(0)), "Back in the hotbar");
    }

    private static String lore(ItemStack stack, int line) { return stack.get(DataComponents.LORE).lines().get(line).getString(); }

    private static int total(ServerPlayer player) {
        int count = RevivalTotems.is(player.containerMenu.getCarried()) ? player.containerMenu.getCarried().getCount() : 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) if (RevivalTotems.is(inventory.getItem(i))) count += inventory.getItem(i).getCount();
        var craft = player.inventoryMenu.getCraftSlots();
        for (int i = 0; i < craft.getContainerSize(); i++) if (RevivalTotems.is(craft.getItem(i))) count += craft.getItem(i).getCount();
        return count;
    }

    private static int slot(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) if (RevivalTotems.is(inventory.getItem(i))) return i;
        throw new IllegalStateException("No revival totem held");
    }

    private static ItemStack first(ServerPlayer player) { return player.getInventory().getItem(slot(player)); }

    private static int offhand(ServerPlayer player) {
        var stack = player.getOffhandItem();
        return RevivalTotems.is(stack) ? stack.getCount() : 0;
    }

    /** The committed round a scripted test drives, plus the session lock and the body check. */
    private static final class ScriptedAccess implements RevivalTotems.Access {
        RoundSnapshot round;
        boolean locked;
        boolean body = true;

        void set(UUID roundId, ServerPlayer player, ParticipantRole role, int life, int charges, int sortie, boolean online) {
            var participant = new Participant(player.getUUID(), player.getScoreboardName(), role, online, false, 0, 0, life, charges,
                    MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, sortie, Map.of(), MonsterWallet.EMPTY, 0, false);
            round = new RoundSnapshot(roundId, 1, 1, 0, RulesConfig.defaults(), RoundPhase.RUNNING, PauseReason.NONE, 0, 0,
                    Map.of(player.getUUID(), participant), false, false, null);
        }

        @Override public Optional<RoundSnapshot> committed() { return Optional.ofNullable(round); }
        @Override public boolean competitionLocked() { return locked; }
        @Override public boolean bodyReady(ServerPlayer player, Participant participant) { return body; }
    }

    private static final class Fixture {
        final ServerLevel level;
        final MinecraftServer server;
        final GameProfile profile;
        final EmbeddedChannel channel;
        final RecordingConnection connection = new RecordingConnection();
        final BlockPos base;
        ServerPlayer player;

        Fixture(GameTestHelper test) {
            level = test.getLevel();
            server = level.getServer();
            base = new BlockPos(30000 + ARENAS.getAndIncrement() * 64, 150, 3000);
            level.setChunkForced(base.getX() >> 4, base.getZ() >> 4, true);
            for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
                level.setBlockAndUpdate(base.offset(dx, -1, dz), Blocks.SMOOTH_STONE.defaultBlockState());
                for (int dy = 0; dy < 3; dy++) level.setBlockAndUpdate(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
            }
            profile = new GameProfile(UUID.randomUUID(), "HRTotem" + ARENAS.get());
            player = new ServerPlayer(server, level, profile, TestClients.information());
            channel = new EmbeddedChannel(connection);
            dev.hardrush.gametest.UnmanagedProfileFixtures.run(() -> server.getPlayerList().placeNewPlayer(connection, player,
                    TestClients.cookie(profile)));
            player.teleportTo(base.getX() + .5, base.getY(), base.getZ() + .5);
            player.setGameMode(GameType.SURVIVAL);
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        }

        /** Floor stacks and the fixture entities are only visible once the forced arena simulates entities. */
        void awaitArena() {
            if (!level.isPositionEntityTicking(base)) throw new net.minecraft.gametest.framework.GameTestAssertException(
                    net.minecraft.network.chat.Component.literal("The arena is not entity ticking yet"), 0);
        }

        void select(int slot) { player.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(slot)); }

        int resyncs() { return connection.contents.get(); }

        /** The action reached a revival-totem refusal: it resent the open menu to undo the client's prediction. */
        void refused(Runnable action, String what) {
            int sent = resyncs();
            action.run();
            if (resyncs() <= sent) throw new IllegalStateException("No refusal for " + what);
        }

        void assertNoFloorTotem(String stage) {
            if (!level.getEntitiesOfClass(ItemEntity.class, new AABB(base).inflate(16), item -> RevivalTotems.is(item.getItem())).isEmpty())
                throw new IllegalStateException("A revival totem reached the floor: " + stage);
        }

        void close() {
            var current = server.getPlayerList().getPlayer(profile.id());
            if (current != null) server.getPlayerList().remove(current);
            channel.finishAndReleaseAll();
            level.setChunkForced(base.getX() >> 4, base.getZ() >> 4, false);
        }
    }

    /** Counts the full menu resends a refusal sends to correct the client's prediction. */
    private static final class RecordingConnection extends Connection {
        final AtomicInteger contents = new AtomicInteger();
        RecordingConnection() { super(PacketFlow.SERVERBOUND); }
        private void record(Packet<?> packet) { if (packet instanceof ClientboundContainerSetContentPacket) contents.incrementAndGet(); }
        @Override public void send(Packet<?> packet) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { record(packet); }
    }
}
