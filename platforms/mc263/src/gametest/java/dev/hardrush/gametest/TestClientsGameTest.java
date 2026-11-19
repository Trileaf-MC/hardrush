package dev.hardrush.gametest;

import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
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
            level.getChunkSource().move(player);
            player.getInventory().setItem(player.getInventory().getSelectedSlot(), new ItemStack(client == alice ? Items.DIAMOND : Items.EMERALD));
        }
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(level.isPositionEntityTicking(floor), "The arena is loaded"))
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
            level.getChunkSource().move(player);
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
