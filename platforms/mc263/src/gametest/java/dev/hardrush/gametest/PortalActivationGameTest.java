package dev.hardrush.gametest;

import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.RoundEngine;
import dev.hardrush.p1.world.P1RoundWorldBundle;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Native item use and portal blocks on a headless server, without a client. */
public final class PortalActivationGameTest {
    private static final BlockPos FRAME = new BlockPos(32, 100, 32);

    @GameTest(maxTicks = 24000)
    public void roundPortalActivationAndEndRouting(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var snapshot = RoundEngine.create(UUID.randomUUID(), 1, 12345, RulesConfig.defaults(), () -> 0).committed();
        var bundle = P1RoundWorldBundle.open(server, snapshot);
        var closed = new CompletableFuture<?>[1];
        test.startSequence()
                .thenWaitUntil(() -> {
                    bundle.tick();
                    if (bundle.failure().isPresent()) throw new IllegalStateException(bundle.failure().orElseThrow());
                    pace();
                    test.assertTrue(bundle.ready(), "Disposable production round finishes preparing");
                })
                .thenExecute(() -> {
                    try {
                        var overworld = bundle.level(Level.OVERWORLD);
                        var nether = bundle.level(Level.NETHER);
                        var end = bundle.level(Level.END);
                        assertEndActivation(test, overworld);
                        assertEndDestination(test, overworld, end);
                        assertEndDestination(test, end, overworld);
                        assertNetherActivation(test, overworld, true);
                        assertNetherActivation(test, nether, true);
                        assertNetherActivation(test, end, false);
                    } finally {
                        closed[0] = bundle.saveAndClose().toCompletableFuture();
                    }
                })
                .thenWaitUntil(() -> {
                    bundle.tick();
                    if (closed[0].isCompletedExceptionally()) closed[0].join();
                    pace();
                    test.assertTrue(closed[0].isDone(), "Disposable round saves and unregisters before success");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 1200)
    public void vanillaPortalActivationIsPreserved(GameTestHelper test) {
        var server = test.getLevel().getServer();
        assertEndActivation(test, server.overworld());
        assertNetherActivation(test, server.overworld(), true);
        assertNetherActivation(test, server.getLevel(Level.NETHER), true);
        assertNetherActivation(test, server.getLevel(Level.END), false);
        test.succeed();
    }

    private static void assertNetherActivation(GameTestHelper test, ServerLevel level, boolean allowed) {
        for (var axis : List.of(Direction.Axis.X, Direction.Axis.Z)) {
            var along = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
            var facing = axis == Direction.Axis.X ? Direction.NORTH : Direction.EAST;
            for (boolean sideIgnition : List.of(false, true)) {
                clear(level);
                for (int x = 0; x < 4; x++) for (int y = 0; y < 5; y++) {
                    if (x == 0 || x == 3 || y == 0 || y == 4) {
                        level.setBlockAndUpdate(FRAME.relative(along, x).above(y), Blocks.OBSIDIAN.defaultBlockState());
                    }
                }
                var ignition = FRAME.relative(along).above(sideIgnition ? 2 : 1);
                var clicked = sideIgnition ? ignition.relative(along.getOpposite()) : ignition.below();
                var face = sideIgnition ? along : Direction.UP;
                if (sideIgnition) {
                    test.assertValueEqual(BaseFireBlock.canBePlacedAt(level, ignition, facing), allowed,
                            "Unsupported side ignition uses portal eligibility in " + level.dimension());
                }
                use(level, Items.FLINT_AND_STEEL.getDefaultInstance(), clicked, face, facing);
                for (int x = 1; x <= 2; x++) for (int y = 1; y <= 3; y++) {
                    var state = level.getBlockState(FRAME.relative(along, x).above(y));
                    test.assertValueEqual(state.is(Blocks.NETHER_PORTAL), allowed,
                            "Flint and steel fills exactly the eligible frame in " + level.dimension());
                    if (allowed) test.assertValueEqual(state.getValue(NetherPortalBlock.AXIS), axis, "Portal axis is preserved");
                }
                // A missing side frame must never become a portal, including after the eligibility fix.
                clear(level);
                for (int x = 0; x < 4; x++) {
                    level.setBlockAndUpdate(FRAME.relative(along, x), Blocks.OBSIDIAN.defaultBlockState());
                }
                use(level, Items.FLINT_AND_STEEL.getDefaultInstance(), FRAME.relative(along), Direction.UP, facing);
                test.assertFalse(level.getBlockState(FRAME.relative(along).above()).is(Blocks.NETHER_PORTAL),
                        "An incomplete frame cannot activate");
            }
        }
        clear(level);
    }

    private static void assertEndActivation(GameTestHelper test, ServerLevel level) {
        clear(level);
        var emptyFrame = FRAME.offset(1, 0, 0);
        for (int i = 1; i <= 3; i++) {
            endFrame(level, FRAME.offset(i, 0, 0), Direction.SOUTH, true);
            endFrame(level, FRAME.offset(i, 0, 4), Direction.NORTH, true);
            endFrame(level, FRAME.offset(0, 0, i), Direction.EAST, true);
            endFrame(level, FRAME.offset(4, 0, i), Direction.WEST, true);
        }
        endFrame(level, emptyFrame, Direction.SOUTH, false);
        test.assertFalse(level.getBlockState(FRAME.offset(2, 0, 2)).is(Blocks.END_PORTAL), "Eleven eyes leave End portal inactive");
        var eye = Items.ENDER_EYE.getDefaultInstance();
        use(level, eye, emptyFrame, Direction.UP, Direction.NORTH);
        test.assertTrue(eye.isEmpty(), "Native Ender Eye item is consumed");
        for (int x = 1; x <= 3; x++) for (int z = 1; z <= 3; z++) {
            test.assertTrue(level.getBlockState(FRAME.offset(x, 0, z)).is(Blocks.END_PORTAL),
                    "Last eye creates all nine End portal blocks in " + level.dimension());
        }
        clear(level);
    }

    private static void endFrame(ServerLevel level, BlockPos pos, Direction facing, boolean eye) {
        level.setBlockAndUpdate(pos, Blocks.END_PORTAL_FRAME.defaultBlockState()
                .setValue(EndPortalFrameBlock.FACING, facing).setValue(EndPortalFrameBlock.HAS_EYE, eye));
    }

    private static void assertEndDestination(GameTestHelper test, ServerLevel source, ServerLevel expected) {
        var entity = EntityTypes.PIG.create(source, EntitySpawnReason.COMMAND);
        test.assertTrue(entity != null, "Native portal entity is available");
        var destination = ((Portal) Blocks.END_PORTAL).getPortalDestination(source, entity, FRAME);
        test.assertTrue(destination != null && destination.newLevel() == expected,
                "Native End entry and return stay in the same production round");
    }

    private static void use(ServerLevel level, ItemStack item, BlockPos clicked, Direction face, Direction facing) {
        // Null player is supported by both native items; only a player's horizontal facing is supplied.
        item.getItem().useOn(new UseOnContext(level, null, InteractionHand.MAIN_HAND, item,
                new BlockHitResult(Vec3.atCenterOf(clicked), face, clicked, false)) {
            @Override public Direction getHorizontalDirection() { return facing; }
        });
    }

    private static void clear(ServerLevel level) {
        for (var pos : BlockPos.betweenClosed(FRAME.offset(-1, -1, -1), FRAME.offset(5, 6, 5))) {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
    }

    private static void pace() {
        // Give native asynchronous chunk and region IO time between accelerated GameTest ticks.
        try { Thread.sleep(4); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
}
