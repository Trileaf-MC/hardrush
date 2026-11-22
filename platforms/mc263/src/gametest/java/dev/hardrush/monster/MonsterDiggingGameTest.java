package dev.hardrush.monster;

import dev.hardrush.gametest.MonsterRoleGameTest;
import dev.hardrush.gametest.RuntimeFixtures;
import dev.hardrush.gametest.TestClient;
import java.util.List;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Headless digging rules; the feel of cracks on a real client remains a manual check. */
public final class MonsterDiggingGameTest {
    @GameTest(maxTicks = 200)
    public void bareHandedTimeIsCappedAtStoneAndTheSlowerBlockTimesThePair(GameTestHelper test) {
        int cap = 150;
        test.assertValueEqual(MonsterDiggable.ticks(Blocks.STONE.defaultBlockState(), cap), 150, "Bare-handed stone is 7.5 seconds");
        test.assertValueEqual(MonsterDiggable.ticks(Blocks.DIRT.defaultBlockState(), cap), 15, "Dirt needs no tool: hardness x 30");
        test.assertValueEqual(MonsterDiggable.ticks(Blocks.GRAVEL.defaultBlockState(), cap), 18, "Gravel needs no tool");
        test.assertValueEqual(MonsterDiggable.ticks(Blocks.NETHERRACK.defaultBlockState(), cap), 40, "Netherrack needs a tool: hardness x 100");
        test.assertValueEqual(MonsterDiggable.ticks(Blocks.DEEPSLATE.defaultBlockState(), cap), cap, "Harder blocks stop at the cap");
        test.assertValueEqual(MonsterDiggable.ticks(Blocks.END_STONE.defaultBlockState(), cap), cap, "End stone stops at the cap");
        test.assertValueEqual(MonsterDiggable.ticks(Blocks.STONE.defaultBlockState(), 60), 60, "A configured cap shortens stone");
        try (var f = new Fixture(test)) {
            f.wall(Blocks.DIRT, Blocks.STONE);
            f.start(f.front);
            f.hold(149);
            test.assertTrue(f.solid(f.front) && f.solid(f.front.above()), "The pair waits for the slower stone");
            f.hold(1);
            test.assertTrue(f.air(f.front) && f.air(f.front.above()), "Both blocks open together");
            test.assertTrue(f.level.getEntitiesOfClass(ItemEntity.class, new AABB(f.front).inflate(3)).isEmpty(), "Digging drops nothing");
            f.wall(Blocks.DIRT, Blocks.DIRT);
            f.start(f.front);
            f.hold(14);
            test.assertTrue(f.solid(f.front), "Dirt holds through tick 14");
            f.hold(1);
            test.assertTrue(f.air(f.front) && f.air(f.front.above()), "Dirt opens at its bare-handed time");
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void thePairFitsTheBodyAndClimbsOneStepAtATime(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            // Head height beside the body: feet and head open.
            f.wall(Blocks.STONE, Blocks.STONE);
            f.dig(f.front.above());
            test.assertTrue(f.air(f.front) && f.air(f.front.above()), "Aiming at head height opens feet and head");
            // A partner that is not diggable stays.
            f.wall(Blocks.STONE, Blocks.OAK_PLANKS);
            f.dig(f.front);
            test.assertTrue(f.air(f.front) && f.level.getBlockState(f.front.above()).is(Blocks.OAK_PLANKS), "Only the diggable block goes");
            // Below the feet beside the body: only that block.
            f.wall(Blocks.STONE, Blocks.STONE);
            f.set(f.front, Blocks.AIR); f.set(f.front.above(), Blocks.AIR); f.set(f.front.below(), Blocks.STONE);
            f.dig(f.front.below());
            test.assertTrue(f.air(f.front.below()), "Digging down beside the body takes one block");
            f.set(f.front.below(), Blocks.SMOOTH_STONE);
            // The way up: first the own ceiling, then the block above head height beside the body.
            f.wall(Blocks.STONE, Blocks.STONE);
            f.set(f.front.above(2), Blocks.STONE); f.set(f.feet.above(2), Blocks.STONE);
            f.dig(f.feet.above(2));
            test.assertTrue(f.air(f.feet.above(2)) && f.solid(f.feet.above(3)), "Straight up takes only the aimed block");
            f.dig(f.front.above(2));
            test.assertTrue(f.air(f.front.above(2)) && f.air(f.front.above()) && f.solid(f.front),
                    "Above head height opens it and the block below, keeping the step");
        }
        test.succeed();
    }

    /** Soul sand (14/16 high) and a bottom slab floor the feet inside the floor block; the pair still fits the body. */
    @GameTest(maxTicks = 200)
    public void lowFloorsStillOpenTheCellsTheBodyFills(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            for (var floor : List.of(Blocks.SOUL_SAND.defaultBlockState(), Blocks.STONE_SLAB.defaultBlockState())) {
                f.level.setBlockAndUpdate(f.feet, floor);
                double height = floor.getCollisionShape(f.level, f.feet).max(Direction.Axis.Y);
                f.player.setPosRaw(f.feet.getX() + .5, f.feet.getY() + height, f.feet.getZ() + .5);
                f.player.setOnGround(true);
                test.assertValueEqual(MonsterDiggable.bodyCell(f.player), f.feet.above(), "The body fills the cell above the low floor");
                f.set(f.front, Blocks.STONE); f.set(f.front.above(), Blocks.STONE); f.set(f.front.above(2), Blocks.STONE);
                f.dig(f.front.above());
                test.assertTrue(f.air(f.front.above()) && f.air(f.front.above(2)) && f.solid(f.front),
                        "Aiming straight ahead opens the two cells the body fills, not the floor ahead: " + floor);
            }
            f.set(f.feet, Blocks.AIR);
            f.player.setPosRaw(f.feet.getX() + .5, f.feet.getY(), f.feet.getZ() + .5);
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void onlyHandsOnPlainGroundOutsideProtectionMayDig(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            for (var block : List.of(Blocks.OAK_PLANKS, Blocks.IRON_ORE, Blocks.STONE_BRICKS, Blocks.SAND, Blocks.OBSIDIAN)) {
                f.wall(block, block);
                test.assertFalse(f.use(f.front), "Only the listed plain ground can be dug: " + block);
            }
            f.wall(Blocks.STONE, Blocks.STONE);
            for (var difficulty : List.of(Difficulty.EASY, Difficulty.NORMAL)) {
                f.level.getServer().setDifficulty(difficulty, true);
                test.assertTrue(f.use(f.front), "Digging is not limited to Hard difficulty");
                f.release();
            }
            f.level.getServer().setDifficulty(Difficulty.HARD, true);
            f.griefing(false);
            test.assertFalse(f.use(f.front), "Mob griefing is required");
            f.griefing(true);
            f.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
            test.assertFalse(f.use(f.front), "Only empty hands dig, and a pickaxe never speeds it up");
            f.emptyHand();
            f.player.setOnGround(false);
            test.assertFalse(f.use(f.front), "A dig starts standing on the ground");
            f.player.setOnGround(true);
            MonsterRoles.select(f.player, "creeper"); f.emptyHand();
            test.assertFalse(f.use(f.front), "A creeper has no hands");
            for (var kind : List.of("skeleton", "zombie")) {
                MonsterRoles.select(f.player, kind); f.emptyHand(); f.player.setOnGround(true);
                test.assertTrue(f.use(f.front), "Zombies and skeletons dig: " + kind);
                f.release();
            }
            MonsterRoles.clear(f.player);
            test.assertFalse(f.use(f.front), "Human bodies do not receive the ability");
            MonsterRoles.select(f.player, "zombie"); f.emptyHand(); f.player.setOnGround(true);
            try (var ignored = f.protect()) {
                test.assertFalse(f.use(f.front), "A protected level cannot be dug");
            }
            test.assertTrue(f.use(f.front), "Eligible zombie can still start afterwards");
            f.hold(100);
            try (var ignored = f.protect()) {
                f.hold(60);
                test.assertTrue(f.solid(f.front), "New protection cancels a dig in progress");
            }
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void interruptionsDiscardProgressAndCracksShowOnBothBlocks(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            for (int scenario = 0; scenario < 5; scenario++) {
                f.wall(Blocks.STONE, Blocks.STONE);
                f.start(f.front);
                f.hold(100);
                switch (scenario) {
                    case 0 -> f.look(f.front.above());
                    case 1 -> f.set(f.front.above(), Blocks.COBBLESTONE);
                    case 2 -> f.mining(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, f.front);
                    case 3 -> f.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
                    case 4 -> f.player.setPosRaw(f.player.getX(), f.player.getY(), f.player.getZ() - 5);
                    default -> throw new AssertionError();
                }
                MonsterMining.tick(f.player);
                f.player.setPosRaw(f.feet.getX() + .5, f.feet.getY(), f.feet.getZ() + .5);
                f.wall(Blocks.STONE, Blocks.STONE); f.emptyHand(); f.look(f.front);
                f.hold(60);
                test.assertTrue(f.solid(f.front), "Interruption " + scenario + " discards the old progress");
                f.release();
            }
            f.wall(Blocks.STONE, Blocks.STONE);
            f.client.clearPackets();
            f.start(f.front);
            f.hold(80);
            for (int cell = 0; cell < 2; cell++) {
                int id = MonsterMining.crackId(f.player, cell);
                var pos = cell == 0 ? f.front : f.front.above();
                test.assertTrue(f.client.packets().stream().anyMatch(p -> p instanceof ClientboundBlockDestructionPacket crack
                        && crack.getId() == id && crack.getPos().equals(pos) && crack.getProgress() > 0), "Both blocks crack under their own ID");
            }
            test.assertTrue(MonsterMining.crackId(f.player, 0) != MonsterMining.crackId(f.player, 1)
                    && MonsterMining.crackId(f.player, 0) < 0 && MonsterMining.crackId(f.player, 1) < 0, "Crack IDs belong to no entity");
            f.release();
            test.assertTrue(f.client.packets().stream().anyMatch(p -> p instanceof ClientboundBlockDestructionPacket crack
                    && crack.getId() == MonsterMining.crackId(f.player, 1) && crack.getProgress() == -1), "Release clears the cracks");
        }
        test.succeed();
    }

    private static final class Fixture implements AutoCloseable {
        final ServerPlayer player;
        final ServerLevel level;
        final BlockPos feet, front;
        final Difficulty difficulty;
        final boolean mobGriefing;
        final TestClient client;
        final GameTestHelper test;
        int sequence;

        Fixture(GameTestHelper test) {
            this.test = test;
            player = MonsterRoleGameTest.actor(test); level = player.level();
            feet = player.blockPosition(); front = feet.south();
            client = new TestClient(level.getServer(), player.getGameProfile());
            client.attach(player);
            difficulty = level.getDifficulty(); mobGriefing = level.getGameRules().get(GameRules.MOB_GRIEFING);
            level.getServer().setDifficulty(Difficulty.HARD, true); griefing(true);
            // The arena's barrier caps the column three blocks up; digging the own ceiling needs a block there instead.
            set(feet.above(3), Blocks.OBSIDIAN);
            MonsterRoles.select(player, "zombie"); emptyHand(); player.setOnGround(true);
        }

        void emptyHand() { player.getInventory().setSelectedSlot(8); player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); }
        void griefing(boolean value) { level.getGameRules().set(GameRules.MOB_GRIEFING, value, level.getServer()); }
        void set(BlockPos pos, Block block) { level.setBlockAndUpdate(pos, block.defaultBlockState()); }
        void wall(Block feetBlock, Block headBlock) {
            set(front, feetBlock); set(front.above(), headBlock); set(front.above(2), Blocks.AIR);
            set(feet.above(2), Blocks.AIR); look(front);
        }
        boolean air(BlockPos pos) { return level.getBlockState(pos).isAir(); }
        boolean solid(BlockPos pos) { return !air(pos); }
        /** Aims at whichever face of the block the eye can actually see, as a player would. */
        void look(BlockPos pos) {
            var eye = player.getEyePosition();
            for (var face : Direction.values()) {
                var point = Vec3.atCenterOf(pos).add(new Vec3(face.getStepX(), face.getStepY(), face.getStepZ()).scale(.49));
                var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, point.add(point.subtract(eye).normalize().scale(.05)),
                        net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
                if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                    player.lookAt(EntityAnchorArgument.Anchor.EYES, point); return;
                }
            }
            player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
        }
        void punch() { player.connection.handlePunch(ServerboundPunchPacket.INSTANCE); }
        void hold(int count) { for (int i = 0; i < count; i++) { punch(); MonsterMining.tick(player); } }
        void mining(ServerboundPlayerActionPacket.Action action, BlockPos pos) {
            player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(action, pos, Direction.NORTH, ++sequence));
        }
        boolean use(BlockPos pos) { look(pos); return MonsterMining.start(player, pos); }
        void start(BlockPos pos) {
            look(pos);
            mining(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos);
            punch();
        }
        void release() { mining(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, front); MonsterMining.clear(player); }
        /** Holds until the dig completes, at most the cap. */
        void dig(BlockPos pos) {
            start(pos);
            for (int i = 0; i < 160 && !air(pos); i++) hold(1);
            test.assertTrue(air(pos), "Dig completes within the cap: " + pos);
        }
        RuntimeFixtures.Scope protect() { return RuntimeFixtures.protection(level.getServer(), candidate -> candidate == level); }
        @Override public void close() {
            MonsterRoles.clear(player);
            client.close();
            MonsterRoleGameTest.remove(player);
            level.getServer().setDifficulty(difficulty, true); griefing(mobGriefing);
        }
    }
}
