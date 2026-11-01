package dev.hardrush.monster;

import dev.hardrush.core.round.Participant;
import dev.hardrush.gametest.MonsterRoleGameTest;
import dev.hardrush.gametest.RuntimeFixtures;
import dev.hardrush.protection.ProtectionRuntime;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Headless native player/door tests; actual client prediction and appearance remain manual checks. */
public final class MonsterDoorBreakingGameTest {
    @GameTest(maxTicks = 200)
    public void woodAndCopperDoorsUseNativeDurationWithoutClickResets(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            var doors = new ArrayList<>(Blocks.COPPER_DOOR.asList());
            doors.add(Blocks.OAK_DOOR);
            for (var form : List.of(MonsterForm.ZOMBIE, MonsterForm.DROWNED)) {
                MonsterRoles.select(f.player, "zombie");
                if (form == MonsterForm.DROWNED) MonsterRoles.convert(f.player, form);
                f.emptyHand();
                for (var block : doors) {
                    f.door(block);
                    f.mining(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, f.lower);
                    for (int i = 0; i < 239; i++) {
                        if (i % 4 == 0) f.mining(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, f.lower.above());
                        f.hold(1);
                    }
                    test.assertTrue(f.level.getBlockState(f.lower).is(block)
                            && !f.level.getBlockState(f.lower).getValue(DoorBlock.OPEN), "Door stays closed through tick 239");
                    f.hold(1);
                    test.assertTrue(f.level.getBlockState(f.lower).isAir() && f.level.getBlockState(f.lower.above()).isAir(),
                            "Native break removes both halves at tick 240");
                    f.door(block);
                    f.tick(240);
                    test.assertTrue(f.level.getBlockState(f.lower).is(block), "Completed action cannot destroy a replacement door");
                }
            }
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void onlyEligibleDoorsAndBodiesCanStartBreaking(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            for (var block : List.of(Blocks.IRON_DOOR, Blocks.OAK_TRAPDOOR, Blocks.OAK_FENCE_GATE, Blocks.STONE)) {
                f.door(block);
                test.assertFalse(f.use(), "Iron doors, trapdoors, fence gates and ordinary blocks cannot be broken");
                f.mining(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, f.lower);
                f.mining(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, f.lower);
                test.assertTrue(f.level.getBlockState(f.lower).is(block), "Mining packets cannot bypass the ordinary-block restriction");
            }
            f.door(Blocks.OAK_DOOR);
            for (var difficulty : List.of(Difficulty.PEACEFUL, Difficulty.EASY, Difficulty.NORMAL)) {
                f.level.getServer().setDifficulty(difficulty, true);
                test.assertFalse(f.use(), "Only Hard difficulty permits breaking");
            }
            f.level.getServer().setDifficulty(Difficulty.HARD, true);
            f.griefing(false);
            test.assertFalse(f.use(), "Mob griefing is required");
            f.griefing(true);
            f.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
            test.assertFalse(f.use(), "A tool does not grant mining or accelerated door breaking");
            f.emptyHand();
            f.open();
            test.assertFalse(f.use(), "Open doors cannot be broken");
            f.door(Blocks.OAK_DOOR);
            for (var kind : List.of("skeleton", "creeper")) {
                MonsterRoles.select(f.player, kind); f.emptyHand();
                test.assertFalse(f.use(), "Other monster roles cannot break doors");
            }
            MonsterRoles.clear(f.player);
            test.assertFalse(f.use(), "Human bodies do not receive the ability");
            MonsterRoles.select(f.player, "zombie"); f.emptyHand();
            f.player.setGameMode(GameType.SPECTATOR);
            test.assertFalse(f.use(), "Spectators cannot break doors");
            f.player.setGameMode(GameType.ADVENTURE);
            test.assertTrue(f.use(), "Eligible zombie can still start afterwards");
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void interruptionsDiscardProgressAndProtectionIsCheckedWhileBreaking(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            var origin = f.player.position();
            for (int scenario = 0; scenario < 9; scenario++) {
                f.door(Blocks.OAK_DOOR); f.emptyHand();
                f.player.setPosRaw(origin.x, origin.y, origin.z); f.faceDoor();
                test.assertTrue(f.use(), "Start each interruption scenario");
                f.hold(200);
                switch (scenario) {
                    case 0 -> f.player.setYRot(180);
                    case 1 -> f.player.setPosRaw(origin.x, origin.y, origin.z - 3);
                    case 2 -> f.open();
                    case 3 -> f.door(Blocks.BIRCH_DOOR);
                    case 4 -> f.griefing(false);
                    case 5 -> f.level.getServer().setDifficulty(Difficulty.NORMAL, true);
                    case 6 -> { MonsterRoles.onDisconnect(f.player); MonsterRoles.onJoin(f.player); }
                    case 7 -> MonsterRoles.convert(f.player, MonsterForm.DROWNED);
                    case 8 -> MonsterRoles.useControl(f.player, InteractionHand.MAIN_HAND);
                    default -> throw new AssertionError();
                }
                f.tick(40);
                test.assertFalse(f.level.getBlockState(f.lower).isAir(), "Interruption prevents completion with old progress");
                f.griefing(true); f.level.getServer().setDifficulty(Difficulty.HARD, true);
                f.player.setPosRaw(origin.x, origin.y, origin.z); f.faceDoor(); f.emptyHand(); f.door(Blocks.OAK_DOOR);
                test.assertTrue(f.use(), "Can begin a new attempt");
                f.hold(239);
                test.assertTrue(f.level.getBlockState(f.lower).is(Blocks.OAK_DOOR), "A fresh attempt needs the entire duration");
                f.hold(1);
                test.assertTrue(f.level.getBlockState(f.lower).isAir(), "Fresh attempt completes normally");
            }
            f.door(Blocks.OAK_DOOR);
            test.assertTrue(f.use(), "Begin outside protection"); f.hold(200);
            try (var ignored = f.protect(Vec3.atCenterOf(f.lower))) {
                f.tick(40);
                test.assertTrue(f.level.getBlockState(f.lower).is(Blocks.OAK_DOOR), "New protection cancels an in-progress break");
                test.assertFalse(f.use(), "Protected target cannot start another break");
            }
            try (var ignored = f.protect(f.player.position())) {
                test.assertFalse(f.use(), "A protected player cannot break an unprotected target");
            }
        }
        test.succeed();
    }

    @GameTest(maxTicks = 400)
    public void regularServerTicksDriveTheAdventurePlayerAction(GameTestHelper test) {
        var f = new Fixture(test);
        boolean[] held = {false};
        test.onEachTick(() -> { if (held[0]) f.punch(); });
        test.runAfterDelay(80, () -> {
            try {
                f.level.getServer().setDifficulty(Difficulty.HARD, true); f.griefing(true);
                f.door(Blocks.OAK_DOOR); f.faceDoor();
                f.mining(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, f.lower);
                f.punch();
            } catch (RuntimeException | Error failure) { f.close(); throw failure; }
            test.runAfterDelay(20, () -> {
                try {
                    test.assertTrue(f.level.getBlockState(f.lower).is(Blocks.OAK_DOOR), "A single punch never auto-completes");
                    test.assertTrue(f.packets.stream().anyMatch(p -> p instanceof ClientboundBlockDestructionPacket progress
                            && progress.getId() == ~f.player.getId() && progress.getProgress() == -1), "Missing held input clears the owner's cracks");
                    f.mining(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, f.lower);
                    held[0] = true;
                } catch (RuntimeException | Error failure) { f.close(); throw failure; }
                test.runAfterDelay(242, () -> {
                    held[0] = false;
                    try {
                        test.assertTrue(f.level.getBlockState(f.lower).isAir() && f.level.getBlockState(f.lower.above()).isAir(),
                                "Production tick hook and continuous native punches complete the door");
                        test.assertValueEqual(f.player.gameMode.getGameModeForPlayer(), GameType.ADVENTURE, "Body remains in adventure mode");
                    } finally { f.close(); }
                    test.succeed();
                });
            });
        });
    }

    @GameTest(maxTicks = 200)
    public void releasingAttackResetsProgressAndRightClickNeverStartsIt(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            f.door(Blocks.OAK_DOOR);
            f.tick(260);
            test.assertTrue(f.level.getBlockState(f.lower).is(Blocks.OAK_DOOR), "Looking without holding attack cannot break a door");
            test.assertValueEqual(f.interact(f.lower), InteractionResult.FAIL, "Old right-click activation has been removed");
            for (var pos : List.of(f.lower, f.lower.above()))
                test.assertTrue(f.packets.stream().anyMatch(p -> p instanceof ClientboundBlockUpdatePacket update && update.getPos().equals(pos)),
                        "Rejected right-click corrects both predicted-open halves");
            f.tick(260);
            test.assertTrue(f.level.getBlockState(f.lower).is(Blocks.OAK_DOOR), "Right-click plus looking still does not break it");
            for (var stop : List.of(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)) {
                f.mining(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, f.lower);
                f.hold(200);
                f.mining(stop, f.lower);
                f.tick(260);
                test.assertTrue(f.level.getBlockState(f.lower).is(Blocks.OAK_DOOR), "Release or premature finish stops progress immediately");
            }
            f.mining(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, f.lower.above());
            f.hold(239);
            test.assertTrue(f.level.getBlockState(f.lower).is(Blocks.OAK_DOOR), "Restart after release requires a full duration");
            f.hold(1);
            test.assertTrue(f.level.getBlockState(f.lower).isAir(), "Holding for the full duration completes");
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void inputPacketsKeepAdventureAuthorityAndRestoreMiningAttributes(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            var speed = f.player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
            speed.setBaseValue(2.5);
            f.door(Blocks.OAK_DOOR); f.packets.clear(); f.tick(1);
            test.assertValueEqual(f.player.gameMode.getGameModeForPlayer(), GameType.ADVENTURE, "Server game mode stays adventure");
            test.assertFalse(f.player.mayBuild(), "Server never grants building permission");
            test.assertValueEqual(f.player.getDestroySpeed(f.level.getBlockState(f.lower)), 0f, "Vanilla prediction cannot finish early");
            test.assertTrue(f.clientMode(GameType.SURVIVAL), "Only the owner's client receives native mining input mode");
            test.assertTrue(f.packets.stream().anyMatch(ClientboundUpdateAttributesPacket.class::isInstance), "Zero mining speed is sent to the owner");
            f.mining(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, f.lower);
            f.player.connection.tick();
            test.assertTrue(f.packets.stream().anyMatch(ClientboundBlockChangedAckPacket.class::isInstance), "Native handler acknowledges prediction sequences");
            f.hold(50);
            test.assertTrue(f.packets.stream().anyMatch(p -> p instanceof ClientboundBlockDestructionPacket progress
                    && progress.getId() == ~f.player.getId() && progress.getProgress() > 0), "Owner gets cracks under an ID distinct from local mining");
            f.mining(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, f.lower);
            test.assertTrue(f.packets.getLast() instanceof ClientboundBlockUpdatePacket, "Release resynchronizes the door");
            test.assertTrue(f.packets.stream().anyMatch(p -> p instanceof ClientboundBlockDestructionPacket progress
                    && progress.getId() == ~f.player.getId() && progress.getProgress() == -1), "Release clears owner progress");
            f.packets.clear(); MonsterDoorBreaking.refreshInput(f.player);
            test.assertTrue(f.clientMode(GameType.SURVIVAL), "Cosmetic respawn can restore the active input mode");
            f.player.setYRot(180); f.tick(1);
            test.assertValueEqual(speed.getValue(), 2.5, "Looking away restores the original attribute without overwriting its base");
            test.assertTrue(f.clientMode(GameType.ADVENTURE), "Looking away restores native adventure input");
            f.faceDoor(); f.tick(1); MonsterRoles.onDisconnect(f.player);
            test.assertValueEqual(speed.getValue(), 2.5, "Disconnect leaves no mining modifier on the body");
            MonsterRoles.onJoin(f.player);
        }
        test.succeed();
    }

    private static final class Fixture implements AutoCloseable {
        final ServerPlayer player;
        final ServerLevel level;
        final BlockPos lower;
        final Difficulty difficulty;
        final boolean mobGriefing;
        final ServerGamePacketListenerImpl originalConnection;
        final EmbeddedChannel channel;
        final ArrayList<Packet<?>> packets;
        int sequence;

        Fixture(GameTestHelper test) {
            player = MonsterRoleGameTest.actor(test); level = player.level(); lower = player.blockPosition().south();
            originalConnection = player.connection;
            var connection = new RecordingConnection();
            channel = new EmbeddedChannel(connection); packets = connection.packets;
            player.connection = new ServerGamePacketListenerImpl(level.getServer(), connection, player,
                    CommonListenerCookie.createInitial(player.getGameProfile(), false));
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            difficulty = level.getDifficulty(); mobGriefing = level.getGameRules().get(GameRules.MOB_GRIEFING);
            level.getServer().setDifficulty(Difficulty.HARD, true); griefing(true);
            MonsterRoles.select(player, "zombie"); emptyHand(); faceDoor();
        }

        void emptyHand() { player.getInventory().setSelectedSlot(0); player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); }
        void faceDoor() { player.setYRot(0); player.setXRot(0); }
        void griefing(boolean value) { level.getGameRules().set(GameRules.MOB_GRIEFING, value, level.getServer()); }
        void tick(int count) { for (int i = 0; i < count; i++) MonsterDoorBreaking.tick(player); }
        void punch() { player.connection.handlePunch(ServerboundPunchPacket.INSTANCE); }
        void hold(int count) { for (int i = 0; i < count; i++) { punch(); MonsterDoorBreaking.tick(player); } }
        void mining(ServerboundPlayerActionPacket.Action action, BlockPos pos) {
            player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(action, pos, Direction.NORTH, ++sequence));
        }
        boolean use() { return MonsterDoorBreaking.start(player, lower); }
        boolean clientMode(GameType mode) {
            return packets.stream().anyMatch(p -> p instanceof ClientboundGameEventPacket game
                    && game.getEvent() == ClientboundGameEventPacket.CHANGE_GAME_MODE && game.getParam() == mode.getId());
        }
        InteractionResult interact(BlockPos clicked) {
            return UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(clicked), Direction.NORTH, clicked, false));
        }
        void door(Block block) {
            level.setBlockAndUpdate(lower.above(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(lower, Blocks.AIR.defaultBlockState());
            var state = block.defaultBlockState();
            if (block instanceof DoorBlock) {
                state = state.setValue(DoorBlock.FACING, Direction.NORTH);
                level.setBlockAndUpdate(lower, state.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
                level.setBlockAndUpdate(lower.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            } else level.setBlockAndUpdate(lower, state);
        }
        void open() { ((DoorBlock) level.getBlockState(lower).getBlock()).setOpen(player, level, level.getBlockState(lower), lower, true); }
        RuntimeFixtures.Scope protect(Vec3 center) {
            return RuntimeFixtures.protection(level.getServer(), new ProtectionRuntime.Access() {
                public Optional<ProtectionRuntime.Zone> zone(ServerLevel candidate) {
                    return candidate == level ? Optional.of(new ProtectionRuntime.Zone(UUID.randomUUID(), center, .1, false)) : Optional.empty();
                }
                public Optional<Participant> participant(UUID id) { return Optional.empty(); }
                public boolean leaveProtection(UUID id, UUID playerId, int life) { return false; }
            });
        }
        @Override public void close() {
            MonsterRoles.clear(player);
            player.connection = originalConnection;
            channel.finishAndReleaseAll();
            MonsterRoleGameTest.remove(player);
            level.getServer().setDifficulty(difficulty, true); griefing(mobGriefing);
        }
    }

    private static final class RecordingConnection extends Connection {
        final ArrayList<Packet<?>> packets = new ArrayList<>();
        RecordingConnection() { super(PacketFlow.SERVERBOUND); }
        private void record(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) bundle.subPackets().forEach(this::record);
            else packets.add(packet);
        }
        @Override public void send(Packet<?> packet) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { record(packet); }
    }
}
