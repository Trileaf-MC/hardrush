package dev.hardrush.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;

/** Fixture clients receive entities as far away as a real vanilla client would, not only within 32 blocks. */
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
                .thenWaitUntil(() -> { watch.acknowledge(); watch.assertReady(); })
                .thenExecute(watch::drop)
                .thenWaitUntil(() -> { watch.acknowledge(); test.assertTrue(watch.sawItem(), "The item 40 blocks away reaches the fixture client"); })
                .thenExecute(watch::close)
                .thenSucceed();
    }

    @GameTest(maxTicks = 400)
    public void shortSightedClientMissesItemFortyBlocksAway(GameTestHelper test) {
        var watch = new Watch(test, 2);
        test.startSequence()
                .thenWaitUntil(() -> { watch.acknowledge(); watch.assertReady(); })
                .thenExecute(watch::drop)
                .thenExecuteFor(40, watch::acknowledge)
                .thenExecute(() -> {
                    test.assertFalse(watch.sawItem(), "A short-sighted client sees only 32 blocks, even inside a chunk it tracks");
                    watch.close();
                })
                .thenSucceed();
    }

    /** A watcher {@link #DISTANCE} blocks east of an item drop, in an arena of its own. */
    private static final class Watch {
        final GameTestHelper test;
        final ServerLevel level;
        final BlockPos drop;
        final ServerPlayer player;
        final RecordingConnection connection = new RecordingConnection();
        final EmbeddedChannel channel = new EmbeddedChannel(connection);
        final int expectedView;
        int acknowledgedBatches;
        ItemEntity item;

        Watch(GameTestHelper test, int viewDistance) {
            this.test = test;
            level = test.getLevel();
            expectedView = Math.min(viewDistance, TestClients.SERVER_VIEW_DISTANCE);
            // Chunk-centred, far from every other arena, three chunks from the watcher: tracked even at view 2, but 40 blocks away.
            drop = new BlockPos(24008 + ARENAS.getAndIncrement() * 512, 150, 8);
            level.setChunkForced(drop.getX() >> 4, drop.getZ() >> 4, true);
            var profile = new GameProfile(UUID.randomUUID(), "HRWatch" + ARENAS.get());
            player = new ServerPlayer(level.getServer(), level, profile, TestClients.information(viewDistance));
            UnmanagedProfileFixtures.run(() -> level.getServer().getPlayerList().placeNewPlayer(connection, player,
                    TestClients.cookie(profile, viewDistance)));
            player.teleportTo(drop.getX() + DISTANCE + .5, drop.getY(), drop.getZ() + .5);
            level.getChunkSource().move(player);
        }

        /** The chunk batches a vanilla client confirms, so the server keeps streaming the view. */
        void acknowledge() {
            int batches = (int) connection.packets.stream().filter(ClientboundChunkBatchFinishedPacket.class::isInstance).count();
            while (acknowledgedBatches < batches) {
                acknowledgedBatches++;
                player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(64));
            }
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

        boolean sawItem() {
            return connection.packets.stream().anyMatch(packet -> packet instanceof ClientboundAddEntityPacket add && add.getId() == item.getId());
        }

        void close() {
            item.discard();
            level.getServer().getPlayerList().remove(player);
            channel.finishAndReleaseAll();
            level.setChunkForced(drop.getX() >> 4, drop.getZ() >> 4, false);
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
