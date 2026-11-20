package dev.hardrush.gametest;

import dev.hardrush.player.ClientLevelLoad;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.util.Util;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.Relative;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Fixture clients see and answer the server the way a real vanilla client does. */
public final class TestClientsGameTest {
    private static final AtomicInteger ARENAS = new AtomicInteger();
    /** Past the 32 blocks of {@code ClientInformation.createDefault()}, inside an item's 96-block tracking range. */
    private static final int DISTANCE = 40;

    @GameTest(maxTicks = 400)
    public void fixtureClientSeesItemFortyBlocksAway(GameTestHelper test) {
        test.assertValueEqual(test.getLevel().getServer().getPlayerList().getViewDistance(), TestClients.SERVER_VIEW_DISTANCE,
                "The headless GameTest server runs with the dedicated server's view distance");
        var watch = new Watch(test, TestClients.VIEW_DISTANCE);
        test.startSequence()
                .thenWaitUntil(watch::assertReady)
                .thenExecute(watch::drop)
                .thenWaitUntil(() -> test.assertTrue(watch.client.sawEntity(watch.item), "The item 40 blocks away reaches the fixture client"))
                .thenExecute(watch::close)
                .thenSucceed();
    }

    @GameTest(maxTicks = 400)
    public void shortSightedClientMissesItemFortyBlocksAway(GameTestHelper test) {
        var watch = new Watch(test, 2);
        test.startSequence()
                .thenWaitUntil(watch::assertReady)
                .thenExecute(watch::drop)
                .thenIdle(40)
                .thenExecute(() -> {
                    test.assertFalse(watch.client.sawEntity(watch.item), "A short-sighted client sees only 32 blocks, even inside a chunk it tracks");
                    watch.close();
                })
                .thenSucceed();
    }

    /** Two players join, each throws an item with the Q key, and each client receives the other's item. */
    @GameTest(maxTicks = 200)
    public void playersSeeEachOthersThrownItems(GameTestHelper test) {
        var level = test.getLevel();
        var floor = arena(level);
        var alice = TestClient.named(level.getServer(), "HRAlice");
        var bob = TestClient.named(level.getServer(), "HRBob");
        for (var client : new TestClient[] {alice, bob}) {
            var player = client.place(level);
            player.setGameMode(GameType.SURVIVAL);
            player.teleportTo(floor.getX() + (client == alice ? -2.5 : 3.5), floor.getY() + 1, floor.getZ() + .5);
            player.getInventory().setItem(player.getInventory().getSelectedSlot(), new ItemStack(client == alice ? Items.DIAMOND : Items.EMERALD));
        }
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(level.isPositionEntityTicking(floor)
                        && alice.receivedChunksAround(floor, 0) && bob.receivedChunksAround(floor, 0), "Both clients have received the arena"))
                .thenExecute(() -> { alice.dropSelected(false); bob.dropSelected(false); })
                .thenWaitUntil(() -> {
                    var diamond = thrown(level, floor, Items.DIAMOND.getDefaultInstance());
                    var emerald = thrown(level, floor, Items.EMERALD.getDefaultInstance());
                    test.assertTrue(diamond != null && emerald != null, "Both throws reach the level");
                    test.assertTrue(bob.sawEntity(diamond) && alice.sawEntity(emerald), "Each client receives the item the other threw");
                })
                .thenExecute(() -> {
                    level.getEntitiesOfClass(ItemEntity.class, new AABB(floor).inflate(8)).forEach(ItemEntity::discard);
                    alice.close();
                    bob.close();
                    clear(level, floor);
                })
                .thenSucceed();
    }

    /** Two relative teleports in one tick: the client accepts each at the destination the server already applied. */
    @GameTest(maxTicks = 200)
    public void relativeTeleportsAreAcceptedAtTheirDestination(GameTestHelper test) {
        var level = test.getLevel();
        var floor = arena(level);
        var client = TestClient.named(level.getServer(), "HRRelative");
        var player = client.place(level);
        player.teleportTo(floor.getX() + .5, floor.getY() + 1, floor.getZ() + .5);
        client.acknowledge();
        var start = player.position();
        var step = new PositionMoveRotation(new Vec3(1, 0, 0), Vec3.ZERO, 0, 0);
        client.listener().teleport(step, Relative.ALL);
        client.listener().teleport(step, Relative.ALL);
        var sentIds = client.packets(ClientboundPlayerPositionPacket.class).stream().map(ClientboundPlayerPositionPacket::id).toList();
        int sent = sentIds.size();
        client.acknowledge();
        // The server moved the player itself; what proves the client is the two acknowledgements it sent.
        var accepted = client.acceptedTeleports();
        accepted = accepted.subList(accepted.size() - 2, accepted.size());
        test.assertValueEqual(accepted.stream().map(TestClient.Teleport::id).toList(), sentIds.subList(sent - 2, sent),
                "Both relative teleports are acknowledged, in order");
        test.assertTrue(accepted.get(0).position().distanceTo(start.add(1, 0, 0)) < 1.0E-6
                && accepted.get(1).position().distanceTo(start.add(2, 0, 0)) < 1.0E-6, "Each is acknowledged at its own destination: " + accepted);
        test.assertTrue(client.connected(), "Accepting both relative teleports keeps the client connected");
        test.assertValueEqual(client.packets(ClientboundPlayerPositionPacket.class).size(), sent,
                "No acceptance reads as moving too quickly and draws a corrective teleport");
        test.assertTrue(client.player().position().distanceTo(start.add(2, 0, 0)) < 1.0E-6,
                "Each relative offset applies once: " + start + " -> " + client.player().position());
        client.close();
        clear(level, floor);
        test.succeed();
    }

    /** A kick completes: the disconnect packet's send listener closes the connection and the player leaves. */
    @GameTest(maxTicks = 100)
    public void serverKickClosesTheConnection(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var client = TestClient.named(server, "HRKicked");
        client.place(test.getLevel());
        client.listener().disconnect(Component.literal("Kicked by TestClientsGameTest"));
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(!client.connected() && server.getPlayerList().getPlayer(client.id()) == null,
                        "The kicked client is disconnected and removed from the player list"))
                .thenExecute(() -> {
                    test.assertTrue(client.player() == null, "The kicked session is over, with no player left to control");
                    client.place(test.getLevel());
                    test.assertTrue(client.connected() && server.getPlayerList().getPlayer(client.id()) == client.player(),
                            "The same client object joins again after the kick");
                    client.close();
                })
                .thenSucceed();
    }

    /**
     * The connection ticks every server tick like a real one, and stays open across keep-alive deadlines because the client
     * answers each challenge: the server sends the next one only after the previous was answered, and disconnects otherwise.
     */
    @GameTest(maxTicks = 100)
    public void connectionTicksAnswerKeepAlivesOnTheirOwn(GameTestHelper test) {
        var client = TestClient.named(test.getLevel().getServer(), "HRKeepAlive");
        client.place(test.getLevel());
        test.startSequence()
                .thenExecute(() -> keepAliveDue(client))
                .thenWaitUntil(() -> test.assertValueEqual(client.packets(ClientboundKeepAlivePacket.class).size(), 1,
                        "The next connection tick sends a challenge"))
                .thenExecute(() -> keepAliveDue(client))
                .thenWaitUntil(() -> test.assertValueEqual(client.packets(ClientboundKeepAlivePacket.class).size(), 2,
                        "The first challenge was answered, so the next deadline sends another"))
                .thenExecute(() -> {
                    test.assertTrue(client.connected(), "Answered challenges keep the connection open");
                    client.close();
                })
                .thenSucceed();
    }

    /** The player's own body ticks with its connection, as on a real server; a frozen client's stands still until stepped. */
    @GameTest(maxTicks = 100)
    public void playerBodyTicksWithItsConnectionUnlessFrozen(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var running = TestClient.named(server, "HRRunning");
        var frozen = TestClient.named(server, "HRFrozen").freeze();
        var runner = running.place(test.getLevel());
        var statue = frozen.place(test.getLevel());
        for (var player : List.of(runner, statue)) player.addEffect(new MobEffectInstance(MobEffects.SPEED, 100));
        test.startSequence()
                .thenIdle(10)
                .thenExecute(() -> {
                    test.assertTrue(runner.getEffect(MobEffects.SPEED).getDuration() <= 90, "Ten server ticks count the effect down");
                    test.assertValueEqual(statue.getEffect(MobEffects.SPEED).getDuration(), 100, "A frozen player's effect stands still");
                    frozen.tickConnection();
                    test.assertValueEqual(statue.getEffect(MobEffects.SPEED).getDuration(), 99, "Stepping the frozen connection ticks the body once");
                    running.close();
                    frozen.close();
                })
                .thenSucceed();
    }

    /** After a level change the client reports the new level loaded once, and a withheld report is still sent on resume. */
    @GameTest(maxTicks = 300)
    public void levelChangeWaitsForAFreshLoadReport(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var client = TestClient.named(server, "HRRespawn");
        client.place(test.getLevel());
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(ClientLevelLoad.loaded(client.player()), "The joined client reports its level loaded"))
                .thenExecute(() -> {
                    client.withhold(TestClient.Reply.REPORT_LOADED);
                    var nether = server.getLevel(Level.NETHER);
                    client.player().teleport(new TeleportTransition(nether, new Vec3(.5, 128, .5), Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
                    // A report still in flight from the previous level: the server ignores it before the arrival is acknowledged.
                    client.reportLoaded();
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    test.assertFalse(ClientLevelLoad.loaded(client.player()), "The new level waits for the client's report");
                    client.resume(TestClient.Reply.REPORT_LOADED);
                })
                .thenWaitUntil(() -> test.assertTrue(ClientLevelLoad.loaded(client.player()), "The resumed client reports the new level loaded"))
                .thenExecute(client::close)
                .thenSucceed();
    }

    /** Two absolute teleports in one bundle: the client applies them in turn, each at the destination it names. */
    @GameTest(maxTicks = 100)
    public void bundledTeleportsAreAcceptedEachAtItsOwnDestination(GameTestHelper test) {
        var client = TestClient.named(test.getLevel().getServer(), "HRBundle");
        client.place(test.getLevel());
        client.acknowledge();
        var first = new Vec3(10.5, 200, 10.5);
        var second = new Vec3(20.5, 200, 20.5);
        // Ids the server is not waiting for, so it ignores both acknowledgements and the test sees only the client.
        client.listener().send(new ClientboundBundlePacket(List.of(
                ClientboundPlayerPositionPacket.of(9001, new PositionMoveRotation(first, Vec3.ZERO, 0, 0), Set.of()),
                ClientboundPlayerPositionPacket.of(9002, new PositionMoveRotation(second, Vec3.ZERO, 0, 0), Set.of()))));
        client.acknowledge();
        var accepted = client.acceptedTeleports();
        accepted = accepted.subList(accepted.size() - 2, accepted.size());
        test.assertValueEqual(accepted.stream().map(TestClient.Teleport::id).toList(), List.of(9001, 9002), "Both bundled teleports are acknowledged, in order");
        test.assertTrue(accepted.get(0).position().equals(first) && accepted.get(1).position().equals(second),
                "Each is acknowledged at its own destination: " + accepted);
        client.close();
        test.succeed();
    }

    /** An absolute teleport then a relative one in one bundle: the relative one starts where the first put the client. */
    @GameTest(maxTicks = 100)
    public void relativeTeleportStartsWhereTheBundlesPreviousTeleportLeftTheClient(GameTestHelper test) {
        var client = TestClient.named(test.getLevel().getServer(), "HRMixed");
        client.place(test.getLevel());
        client.acknowledge();
        var absolute = new Vec3(10.5, 200, 10.5);
        client.listener().send(new ClientboundBundlePacket(List.of(
                ClientboundPlayerPositionPacket.of(9101, new PositionMoveRotation(absolute, Vec3.ZERO, 0, 0), Set.of()),
                ClientboundPlayerPositionPacket.of(9102, new PositionMoveRotation(new Vec3(1, 0, 0), Vec3.ZERO, 0, 0),
                        Set.of(Relative.X, Relative.Y, Relative.Z)))));
        client.acknowledge();
        var accepted = client.acceptedTeleports();
        accepted = accepted.subList(accepted.size() - 2, accepted.size());
        test.assertTrue(accepted.get(0).position().equals(absolute) && accepted.get(1).position().equals(absolute.add(1, 0, 0)),
                "The relative teleport applies to the bundle's absolute destination: " + accepted);
        client.close();
        test.succeed();
    }

    /** Replacing a placed player by a new one of the same UUID within a tick ends the old client's session. */
    @GameTest(maxTicks = 100)
    public void sameUuidReplacementEndsTheOldSession(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var old = TestClient.named(server, "HRReplaced");
        old.place(test.getLevel());
        server.getPlayerList().remove(old.player());
        var replacement = new TestClient(server, old.profile());
        replacement.place(test.getLevel());
        test.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    test.assertFalse(old.connected(), "The replaced client's session ended while the new entity stays online");
                    test.assertTrue(replacement.connected() && server.getPlayerList().getPlayer(old.id()) == replacement.player(),
                            "The replacement keeps the UUID");
                    replacement.close();
                })
                .thenSucceed();
    }

    /** While another client is attached, the player's own client stays quiet: its old teleport cannot pull the player back. */
    @GameTest(maxTicks = 100)
    public void attachedOverClientDoesNotAnswer(GameTestHelper test) {
        var level = test.getLevel();
        var floor = arena(level);
        var owner = TestClient.named(level.getServer(), "HROwner");
        var player = owner.place(level);
        var first = Vec3.atBottomCenterOf(floor.above());
        var second = first.add(3, 0, 0);
        owner.withhold(TestClient.Reply.ACCEPT_TELEPORTS);
        player.teleportTo(first.x, first.y, first.z);
        int pending = owner.packets(ClientboundPlayerPositionPacket.class).getLast().id();
        var attached = new TestClient(level.getServer(), player.getGameProfile());
        attached.attach(player);
        owner.resume(TestClient.Reply.ACCEPT_TELEPORTS);
        player.teleportTo(second.x, second.y, second.z);
        test.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    test.assertTrue(owner.acceptedTeleports().stream().noneMatch(teleport -> teleport.id() == pending),
                            "The client attached over sends no acknowledgement");
                    test.assertTrue(player.position().distanceTo(second) < 1.0E-6, "The player stays where the attached connection put it: " + player.position());
                    attached.close();
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    test.assertTrue(player.position().distanceTo(second) < 1.0E-6,
                            "Once handed back, the original client's stale teleport cannot pull the player back: " + player.position());
                    test.assertTrue(owner.connected() && player.connection == owner.listener(), "The original client is in control again");
                    owner.close();
                    clear(level, floor);
                })
                .thenSucceed();
    }

    /** Kicking an attached client disconnects the player, as kicking any real client does. */
    @GameTest(maxTicks = 100)
    public void kickingAnAttachedClientRemovesThePlayer(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var owner = TestClient.named(server, "HRAttachKick");
        var player = owner.place(test.getLevel());
        var attached = new TestClient(server, player.getGameProfile());
        attached.attach(player);
        attached.listener().disconnect(Component.literal("Kicked by TestClientsGameTest"));
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(!attached.connected() && server.getPlayerList().getPlayer(player.getUUID()) == null,
                        "The kicked attached client is disconnected and the player has left"))
                .thenExecute(() -> {
                    attached.close();
                    owner.close();
                })
                .thenSucceed();
    }

    /** Pitch is clamped to ±90° at every step, as vanilla's calculateAbsolute does. */
    @GameTest(maxTicks = 100)
    public void relativePitchIsClampedAtEveryStep(GameTestHelper test) {
        var client = TestClient.named(test.getLevel().getServer(), "HRPitch");
        client.place(test.getLevel());
        client.acknowledge();
        var at = new Vec3(10.5, 200, 10.5);
        client.listener().send(new ClientboundBundlePacket(List.of(
                ClientboundPlayerPositionPacket.of(9201, new PositionMoveRotation(at, Vec3.ZERO, 0, 80), Set.of()),
                ClientboundPlayerPositionPacket.of(9202, new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, 0, 30), Set.of(Relative.X, Relative.Y, Relative.Z, Relative.X_ROT)),
                ClientboundPlayerPositionPacket.of(9203, new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, 0, -20), Set.of(Relative.X, Relative.Y, Relative.Z, Relative.X_ROT)))));
        client.acknowledge();
        var accepted = client.acceptedTeleports();
        var pitches = accepted.subList(accepted.size() - 3, accepted.size()).stream().map(TestClient.Teleport::xRot).toList();
        test.assertValueEqual(pitches, List.of(80f, 90f, 70f), "Each relative pitch starts from the clamped one before it");
        client.close();
        test.succeed();
    }

    /** A riding client applies no position packet: it acknowledges the teleport where it is. */
    @GameTest(maxTicks = 100)
    public void ridingClientAcknowledgesWhereItIs(GameTestHelper test) {
        var level = test.getLevel();
        var client = TestClient.named(level.getServer(), "HRRider");
        var player = client.place(level);
        client.acknowledge();
        var seat = new ArmorStand(level, player.getX(), player.getY(), player.getZ());
        test.assertTrue(level.addFreshEntity(seat) && player.startRiding(seat, true, false), "The player rides the stand");
        var riding = player.position();
        client.listener().send(new ClientboundBundlePacket(List.of(
                ClientboundPlayerPositionPacket.of(9301, new PositionMoveRotation(riding.add(30, 0, 30), Vec3.ZERO, 45, 10), Set.of()))));
        client.acknowledge();
        var accepted = client.acceptedTeleports().getLast();
        test.assertTrue(accepted.id() == 9301 && accepted.position().equals(riding), "The rider acknowledges at its own position: " + accepted);
        player.stopRiding();
        seat.discard();
        client.close();
        test.succeed();
    }

    /** Disconnecting while the native login is still loading player data cancels that login. */
    @GameTest(maxTicks = 100)
    public void disconnectCancelsAPreparingLogin(GameTestHelper test) {
        var client = TestClient.named(test.getLevel().getServer(), "HRPreparing");
        client.beginLogin();
        test.assertTrue(rejects(client::beginLogin), "A second login cannot start while one is preparing");
        test.assertTrue(rejects(() -> client.place(test.getLevel())), "Nor can the client join another way meanwhile");
        client.disconnect();
        test.assertTrue(rejects(client::finishLogin), "The cancelled login cannot finish");
        test.assertFalse(client.connected(), "The client never joined");
        test.succeed();
    }

    private static boolean rejects(Runnable action) {
        try { action.run(); return false; }
        catch (IllegalStateException expected) { return true; }
    }

    /** Closing an attached client after a respawn hands the new entity back to its own connection. */
    @GameTest(maxTicks = 100)
    public void closingAnAttachedClientAfterRespawnRestoresTheNewEntity(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var owner = TestClient.named(server, "HRAttached");
        var player = owner.place(test.getLevel());
        var attached = new TestClient(server, player.getGameProfile());
        attached.attach(player);
        player.hurtServer(test.getLevel(), player.damageSources().genericKill(), Float.MAX_VALUE);
        attached.listener().handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        var respawned = server.getPlayerList().getPlayer(owner.id());
        test.assertTrue(respawned != null && respawned != player && respawned.connection == attached.listener(),
                "The respawn replaces the entity on the attached connection");
        attached.close();
        test.assertTrue(respawned.connection == owner.listener() && owner.player() == respawned,
                "The original connection takes back the respawned entity");
        owner.close();
        test.succeed();
    }

    /** Makes the connection's keep-alive timer due, instead of waiting fifteen real seconds. */
    private static void keepAliveDue(TestClient client) {
        try {
            var field = ServerCommonPacketListenerImpl.class.getDeclaredField("keepAliveTime");
            field.setAccessible(true);
            field.setLong(client.listener(), Util.getMillis() - 15_001L);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    /** A stone floor far from every other arena, kept loaded for the test. */
    private static BlockPos arena(ServerLevel level) {
        var floor = new BlockPos(24008 + ARENAS.getAndIncrement() * 512, 149, 8);
        level.setChunkForced(floor.getX() >> 4, floor.getZ() >> 4, true);
        for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) level.setBlockAndUpdate(floor.offset(dx, 0, dz), Blocks.STONE.defaultBlockState());
        return floor;
    }

    private static void clear(ServerLevel level, BlockPos floor) {
        for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) level.setBlockAndUpdate(floor.offset(dx, 0, dz), Blocks.AIR.defaultBlockState());
        level.setChunkForced(floor.getX() >> 4, floor.getZ() >> 4, false);
    }

    private static ItemEntity thrown(ServerLevel level, BlockPos floor, ItemStack kind) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(floor).inflate(8), item -> ItemStack.isSameItem(item.getItem(), kind))
                .stream().findFirst().orElse(null);
    }

    /** A watcher {@link #DISTANCE} blocks east of an item drop, in an arena of its own. */
    private static final class Watch {
        final GameTestHelper test;
        final ServerLevel level;
        final BlockPos drop;
        final TestClient client;
        final ServerPlayer player;
        final int expectedView;
        ItemEntity item;

        Watch(GameTestHelper test, int viewDistance) {
            this.test = test;
            level = test.getLevel();
            expectedView = Math.min(viewDistance, TestClients.SERVER_VIEW_DISTANCE);
            // Chunk-centred, far from every other arena, three chunks from the watcher: tracked even at view 2, but 40 blocks away.
            drop = new BlockPos(24008 + ARENAS.getAndIncrement() * 512, 150, 8);
            level.setChunkForced(drop.getX() >> 4, drop.getZ() >> 4, true);
            client = TestClient.named(level.getServer(), "HRWatch").viewDistance(viewDistance);
            player = client.place(level);
            player.teleportTo(drop.getX() + DISTANCE + .5, drop.getY(), drop.getZ() + .5);
        }

        /** Both watchers track the drop chunk (vanilla adds a ring beyond the view border), so only distance decides. */
        void assertReady() {
            test.assertTrue(level.isPositionEntityTicking(drop), "The drop chunk is loaded");
            var dropChunk = ChunkPos.containing(drop);
            test.assertTrue(level.getChunkSource().chunkMap.isChunkTracked(player, dropChunk.x(), dropChunk.z()),
                    "The watcher's client has received the drop chunk");
            test.assertTrue(player.getChunkTrackingView() instanceof ChunkTrackingView.Positioned view && view.viewDistance() == expectedView,
                    "The watcher tracks the view distance its client asked for, within the server's");
        }

        void drop() {
            item = new ItemEntity(level, drop.getX() + .5, drop.getY(), drop.getZ() + .5, new ItemStack(Items.DIAMOND));
            item.setNoGravity(true);
            item.setDeltaMovement(0, 0, 0);
            test.assertTrue(level.addFreshEntity(item), "The item joins the level");
        }

        void close() {
            item.discard();
            client.close();
            level.setChunkForced(drop.getX() >> 4, drop.getZ() >> 4, false);
        }
    }
}
