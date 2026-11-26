package dev.hardrush.monster;

import com.mojang.authlib.GameProfile;
import dev.hardrush.gametest.MonsterRoleGameTest;
import dev.hardrush.gametest.RuntimeFixtures;
import dev.hardrush.gametest.TestClient;
import dev.hardrush.lobby.LobbyInteractions;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Headless digging rules; the feel of cracks on a real client remains a manual check. */
public final class MonsterDiggingGameTest {
    @GameTest(maxTicks = 200)
    public void bareHandedTimeIsCappedAtStoneAndTheSlowerBlockTimesThePair(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            int cap = 150;
            test.assertValueEqual(f.time(Blocks.STONE, cap), 150, "Bare-handed stone is 7.5 seconds");
            test.assertValueEqual(f.time(Blocks.DIRT, cap), 15, "Dirt needs no tool: hardness x 30");
            test.assertValueEqual(f.time(Blocks.GRAVEL, cap), 18, "Gravel needs no tool");
            test.assertValueEqual(f.time(Blocks.NETHERRACK, cap), 40, "Netherrack needs a tool: hardness x 100");
            test.assertValueEqual(f.time(Blocks.DEEPSLATE, cap), cap, "Harder natural blocks stop at the cap");
            test.assertValueEqual(f.time(Blocks.END_STONE, cap), cap, "End stone stops at the cap");
            test.assertValueEqual(f.time(Blocks.COBBLESTONE, cap), cap, "Cobblestone's bare 200 ticks stop at the cap");
            test.assertValueEqual(f.time(Blocks.STONE, 60), 60, "A configured cap shortens stone");
            test.assertValueEqual(f.time(Blocks.OAK_LEAVES, cap), 6, "Leaves are hardness x 30");
            test.assertValueEqual(f.time(Blocks.OAK_PLANKS, cap), 60, "Planks are not natural, so the cap does not shorten them");
            test.assertValueEqual(f.time(Blocks.STONE_BRICKS, cap), 150, "Stone bricks need a tool");
            test.assertValueEqual(f.time(Blocks.IRON_BLOCK, cap), 500, "Iron keeps its bare-handed time");
            test.assertValueEqual(f.time(Blocks.INFESTED_STONE, cap), f.time(Blocks.STONE, cap), "Infested stone takes as long as its host");
            f.wall(Blocks.OAK_PLANKS, Blocks.STONE);
            f.start(f.front);
            f.hold(60);
            test.assertTrue(f.solid(f.front) && f.solid(f.front.above()), "Planks wait for the slower stone");
            f.hold(90);
            test.assertTrue(f.air(f.front) && f.air(f.front.above()), "The pair opens at the stone's time");
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
            f.wall(Blocks.COBBLESTONE, Blocks.COBBLESTONE);
            f.start(f.front);
            f.hold(149);
            test.assertTrue(f.solid(f.front), "Capped cobblestone holds through tick 149");
            f.hold(1);
            test.assertTrue(f.air(f.front) && f.air(f.front.above()), "Capped cobblestone opens at the natural limit");
            f.wall(Blocks.OAK_LEAVES, Blocks.OAK_LEAVES);
            f.start(f.front);
            f.hold(5);
            test.assertTrue(f.solid(f.front), "Leaves hold through tick 5");
            f.hold(1);
            test.assertTrue(f.air(f.front) && f.air(f.front.above()), "Leaves open at 6 ticks");
            f.wall(Blocks.STONE_BRICKS, Blocks.STONE_BRICKS);
            f.start(f.front);
            f.hold(149);
            test.assertTrue(f.solid(f.front), "Stone bricks hold through tick 149");
            f.hold(1);
            test.assertTrue(f.air(f.front) && f.air(f.front.above()), "Stone bricks open at 150 ticks");
            f.wall(Blocks.IRON_BLOCK, Blocks.IRON_BLOCK);
            f.start(f.front);
            f.hold(150);
            test.assertTrue(f.solid(f.front), "Iron is not shortened by the natural cap");
            f.hold(349);
            test.assertTrue(f.solid(f.front), "Iron holds through tick 499");
            f.hold(1);
            test.assertTrue(f.air(f.front) && f.air(f.front.above()), "Iron opens at 500 ticks");
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
            f.wall(Blocks.STONE, Blocks.CHEST);
            f.dig(f.front);
            test.assertTrue(f.air(f.front) && f.level.getBlockState(f.front.above()).is(Blocks.CHEST), "A container partner stays");
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
            for (var block : List.of(Blocks.IRON_ORE, Blocks.OBSIDIAN, Blocks.BEDROCK)) {
                f.wall(block, block);
                test.assertFalse(f.use(f.front), "Ores and unbreakable blocks cannot be dug: " + block);
            }
            for (var block : List.of(Blocks.OAK_PLANKS, Blocks.STONE_BRICKS, Blocks.SAND)) {
                f.wall(block, block);
                test.assertTrue(f.use(f.front), "Ordinary blocks can be dug: " + block);
                f.release();
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
            test.assertTrue(f.use(f.front), "A held pickaxe can start a dig");
            f.release();
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

    @GameTest(maxTicks = 200)
    public void excludedBlocksNameTheirVerdictAndAShortLimitMakesIronTooHard(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            test.assertValueEqual(MonsterDiggable.maxTicks(f.player), 600, "The default limit is 30 seconds");
            f.expect(Blocks.OAK_DOOR, MonsterDiggable.Verdict.DOOR);
            f.expect(Blocks.CHEST, MonsterDiggable.Verdict.CONTAINER);
            f.expect(Blocks.IRON_ORE, MonsterDiggable.Verdict.ORE);
            f.expect(Blocks.DEEPSLATE_DIAMOND_ORE, MonsterDiggable.Verdict.ORE);
            f.expect(Blocks.NETHER_QUARTZ_ORE, MonsterDiggable.Verdict.ORE);
            f.expect(Blocks.ANCIENT_DEBRIS, MonsterDiggable.Verdict.ORE);
            f.expect(Blocks.OBSIDIAN, MonsterDiggable.Verdict.TOO_HARD);
            f.expect(Blocks.BEDROCK, MonsterDiggable.Verdict.TOO_HARD);
            f.set(f.feet.north(2), Blocks.IRON_BLOCK);
            test.assertValueEqual(MonsterDiggable.verdict(f.level, f.feet.north(2), 150, 600), MonsterDiggable.Verdict.DIGGABLE,
                    "Iron is diggable under the default 30 seconds");
            // 399 ms is 8 ticks, the same rounding digMaxMillis uses; iron's 500 ticks are then too hard.
            test.assertValueEqual(MonsterDiggable.verdict(f.level, f.feet.north(2), 150, (399 + 49) / 50), MonsterDiggable.Verdict.TOO_HARD,
                    "Under 400 ms, iron is too hard");
            f.set(f.feet.north(2), Blocks.AIR);
            test.assertTrue(MonsterDiggable.verdict(f.level, f.feet.north(2), 150, 600) == null, "Air is nothing to dig");
        }
        test.succeed();
    }

    /** Burrows hide monsters in natural ground, so that ground is judged by its capped time and is never too hard. */
    @GameTest(maxTicks = 200)
    public void naturalGroundIsJudgedByItsCappedTime(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            var pos = f.feet.north(2);
            f.set(pos, Blocks.STONE);
            test.assertValueEqual(MonsterDiggable.verdict(f.level, pos, 100, 120), MonsterDiggable.Verdict.DIGGABLE,
                    "Stone's bare 150 ticks are over a 6 s limit, but its 5 s cap is not");
            test.assertValueEqual(MonsterDiggable.verdict(f.level, pos, 150, 100), MonsterDiggable.Verdict.DIGGABLE,
                    "A limit below the natural cap still leaves stone diggable");
            f.set(pos, Blocks.INFESTED_STONE);
            test.assertValueEqual(MonsterDiggable.verdict(f.level, pos, 150, 100), MonsterDiggable.Verdict.DIGGABLE,
                    "Infested stone answers like its host");
            f.set(pos, Blocks.STONE_BRICKS);
            test.assertValueEqual(MonsterDiggable.verdict(f.level, pos, 150, 100), MonsterDiggable.Verdict.TOO_HARD,
                    "Built blocks are not capped, so the short limit still rejects them");
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void theFloorUnderADoorAnswersForTheDoor(GameTestHelper test) {
        var f = new Fixture(test);
        try {
            var floor = f.front;
            f.set(floor, Blocks.OAK_PLANKS);
            f.door(floor.above(), Blocks.IRON_DOOR);
            test.assertValueEqual(MonsterDiggable.verdict(f.player, floor), MonsterDiggable.Verdict.DOOR, "An iron door's floor is the door's");
            test.assertValueEqual(f.swing(floor), "铁门敲不开", "The floor under an iron door names the iron door");
            test.assertFalse(f.use(floor), "The floor under an iron door cannot be dug");
            f.set(floor.above(2), Blocks.AIR); f.set(floor.above(), Blocks.AIR);
            f.door(floor.above(), Blocks.OAK_DOOR);
            test.assertTrue(f.level.getBlockState(floor.above()).is(Blocks.OAK_DOOR), "The wooden door stands on the floor");
            MonsterRoles.select(f.player, "skeleton"); f.emptyHand(); f.player.setOnGround(true);
            test.assertValueEqual(f.swing(floor), "只有僵尸能敲开木门", "The floor under a wooden door names the wooden door");
            MonsterRoles.select(f.player, "zombie"); f.emptyHand(); f.player.setOnGround(true);
            f.level.getServer().setDifficulty(Difficulty.NORMAL, true);
            test.assertValueEqual(f.swing(floor), "困难难度下僵尸才能敲开木门", "Below Hard the floor names the difficulty");
            f.level.getServer().setDifficulty(Difficulty.HARD, true);
            test.assertValueEqual(f.swing(floor), "", "A zombie on Hard aiming at the floor hears nothing");
            test.assertFalse(f.use(floor), "A zombie on Hard cannot dig the floor either");
            f.client.clearPackets();
            f.hold(40);
            test.assertTrue(f.level.getBlockState(floor).is(Blocks.OAK_PLANKS) && f.level.getBlockState(floor.above()).is(Blocks.OAK_DOOR),
                    "Holding at the floor neither digs it nor knocks the door");
            test.assertTrue(f.client.packets().stream().noneMatch(ClientboundBlockDestructionPacket.class::isInstance),
                    "No crack, not even a knock, shows on the floor or the door");
        } finally { f.close(); }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void anAirPartnerLeavesTheDigToItsPrimary(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            f.wall(Blocks.STONE, Blocks.AIR);
            f.client.clearPackets();
            f.start(f.front);
            f.hold(50);
            test.assertTrue(f.client.packets().stream().noneMatch(p -> p instanceof ClientboundBlockDestructionPacket crack
                    && crack.getId() == MonsterMining.crackId(f.player, 1)), "An air partner gets no crack");
            f.set(f.front.above(), Blocks.COBBLESTONE);
            f.hold(99);
            test.assertTrue(f.solid(f.front), "Stone holds through tick 149");
            f.hold(1);
            test.assertTrue(f.air(f.front), "A block placed where the partner was air does not void the dig");
            test.assertTrue(f.level.getBlockState(f.front.above()).is(Blocks.COBBLESTONE), "The block placed above was never part of it");
        }
        test.succeed();
    }

    /** A real client: an adventure swing arms survival input, then a held button sends START and a punch every tick. */
    @GameTest(maxTicks = 200)
    public void aHeldItemDigsOnlyWhileTheBreakIsHeld(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            for (boolean viaStart : List.of(false, true)) {
                f.wall(Blocks.OAK_PLANKS, Blocks.OAK_PLANKS);
                f.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
                f.client.clearPackets();
                f.punch();
                test.assertTrue(f.clientMode(GameType.SURVIVAL), "The swing arms survival input");
                f.tick(30);
                test.assertTrue(f.solid(f.front), "Until the break is held, time does not run");
                test.assertTrue(f.client.packets().stream().noneMatch(p -> p instanceof ClientboundBlockDestructionPacket crack
                        && crack.getProgress() >= 0), "Until the break is held, nothing cracks");
                if (viaStart) f.mining(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, f.front);
                f.hold(59);
                test.assertTrue(f.solid(f.front) && f.solid(f.front.above()), "A held break takes bare-handed time");
                f.client.clearPackets();
                f.hold(1);
                test.assertTrue(f.air(f.front) && f.air(f.front.above()), "A held break opens planks at 60 ticks");
                test.assertTrue(f.clientMode(GameType.ADVENTURE), "Finishing returns a held item to adventure input");
            }
            // A bow's refreshed bar changes the client's item components: it restarts the break as ABORT then START.
            f.wall(Blocks.STONE, Blocks.STONE);
            f.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
            f.punch();
            f.hold(100);
            f.mining(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, f.front);
            f.mining(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, f.front);
            f.hold(49);
            test.assertTrue(f.solid(f.front), "Stone holds through tick 149");
            f.hold(1);
            test.assertTrue(f.air(f.front) && f.air(f.front.above()), "ABORT then START in one batch keeps the progress");
            // A lone ABORT is a real release: settled on the next tick.
            f.wall(Blocks.STONE, Blocks.STONE);
            f.punch();
            f.hold(100);
            f.client.clearPackets();
            f.mining(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, f.front);
            test.assertFalse(f.clientMode(GameType.ADVENTURE), "The release waits for the tick");
            MonsterMining.tick(f.player);
            test.assertTrue(f.clientMode(GameType.ADVENTURE), "A released held item returns to adventure input");
            test.assertTrue(f.client.packets().stream().anyMatch(p -> p instanceof ClientboundBlockDestructionPacket crack
                    && crack.getId() == MonsterMining.crackId(f.player, 0) && crack.getProgress() == -1), "The release clears the crack");
            test.assertTrue(f.client.packets().stream().anyMatch(p -> p instanceof net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket update
                    && update.getPos().equals(f.front)), "The release resynchronizes the block");
            f.mining(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, f.front);
            f.hold(100);
            test.assertTrue(f.solid(f.front), "The released progress is gone");
        }
        test.succeed();
    }

    /** The arming grace is measured in server ticks, so this one waits for real ones. */
    @GameTest(maxTicks = 200)
    public void aLoneSwingWithAHeldItemGivesUpWithoutDigging(GameTestHelper test) {
        var f = new Fixture(test);
        try {
            // Glass, not leaves: unanchored leaves may decay while this waits.
            f.wall(Blocks.GLASS, Blocks.GLASS);
            f.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
            f.client.clearPackets();
            f.punch();
            test.assertTrue(f.clientMode(GameType.SURVIVAL), "The swing arms survival input");
            f.client.clearPackets();
        } catch (Throwable failure) { f.close(); throw failure; }
        test.runAfterDelay(30, () -> {
            try {
                test.assertTrue(f.level.getBlockState(f.front).is(Blocks.GLASS), "One click does not open nine-tick glass");
                // Neighbouring arenas are within crack range, so only this body's crack IDs count.
                test.assertTrue(f.client.packets().stream().noneMatch(p -> p instanceof ClientboundBlockDestructionPacket crack
                        && (crack.getId() == MonsterMining.crackId(f.player, 0) || crack.getId() == MonsterMining.crackId(f.player, 1))
                        && crack.getProgress() >= 0), "One click shows no crack");
                test.assertTrue(f.clientMode(GameType.ADVENTURE), "After the grace the held item returns to adventure input");
            } finally { f.close(); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void heldToolsDigAtBareHandedSpeedUntilTheBowIsDrawn(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            for (var held : List.of(Items.IRON_PICKAXE, Items.BOW)) {
                f.emptyHand();
                f.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(held));
                f.wall(Blocks.OAK_PLANKS, Blocks.OAK_PLANKS);
                f.start(f.front);
                f.hold(59);
                test.assertTrue(f.solid(f.front) && f.solid(f.front.above()), "A " + held + " does not finish planks early");
                f.hold(1);
                test.assertTrue(f.air(f.front) && f.air(f.front.above()), "A " + held + " finishes planks at bare-handed time");
            }
            f.wall(Blocks.OAK_PLANKS, Blocks.OAK_PLANKS);
            f.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
            f.player.startUsingItem(InteractionHand.MAIN_HAND);
            test.assertFalse(f.use(f.front), "Drawing the bow cannot dig");
            f.player.stopUsingItem();
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void aSkeletonOpensALeafCage(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            MonsterRoles.select(f.player, "skeleton");
            f.emptyHand();
            f.player.setOnGround(true);
            var inside = f.feet.east(3);
            for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 2; dy++) for (int dz = -1; dz <= 1; dz++) {
                boolean hollow = dx == 0 && dz == 0 && dy <= 1;
                f.set(inside.offset(dx, dy, dz), hollow ? Blocks.AIR : Blocks.OAK_LEAVES);
            }
            var profile = new GameProfile(UUID.randomUUID(), "HRCage");
            var humanClient = new TestClient(f.level.getServer(), profile);
            try {
                var human = humanClient.place(f.level);
                human.teleportTo(inside.getX() + .5, inside.getY(), inside.getZ() + .5);
                human.setGameMode(GameType.SURVIVAL);
                humanClient.acknowledge();
                var wall = inside.west();
                f.look(wall);
                f.start(wall);
                f.hold(5);
                test.assertTrue(f.solid(wall), "The leaf wall holds through tick 5");
                f.hold(1);
                test.assertTrue(f.air(wall), "The skeleton opens the cage at bare-handed leaf time");
                test.assertTrue(human.isAlive() && f.level.getBlockState(inside.east()).is(Blocks.OAK_LEAVES), "The far side of the cage remains");
            } finally { humanClient.close(); }
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void breakingInfestedStoneDoesNotSpawnSilverfish(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            f.wall(Blocks.INFESTED_STONE, Blocks.INFESTED_STONE);
            f.start(f.front);
            f.hold(149);
            test.assertTrue(f.solid(f.front), "Infested stone holds for as long as stone");
            f.hold(1);
            test.assertTrue(f.air(f.front) && f.air(f.front.above()), "Infested stone opens at stone's time");
            test.assertTrue(f.level.getEntitiesOfClass(Silverfish.class, new AABB(f.front).inflate(4)).isEmpty(),
                    "Breaking infested stone without drops does not spawn a silverfish");
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void aSwingNamesWhyTheBlockStays(GameTestHelper test) {
        var f = new Fixture(test);
        try {
            f.door(f.front, Blocks.OAK_DOOR);
            MonsterRoles.select(f.player, "creeper");
            test.assertValueEqual(f.swing(f.front), "苦力怕挖不动 · 引爆能炸开方块", "A creeper is told to blast");
            f.punch();
            test.assertValueEqual(f.shown(), "苦力怕挖不动 · 引爆能炸开方块", "The same swing stays quiet for 3 seconds");
            MonsterRoles.select(f.player, "skeleton");
            f.emptyHand();
            f.level.getServer().setDifficulty(Difficulty.HARD, true);
            test.assertValueEqual(f.swing(f.front), "只有僵尸能敲开木门", "A skeleton cannot knock");
            MonsterRoles.select(f.player, "zombie");
            f.emptyHand();
            f.player.setOnGround(true);
            f.level.getServer().setDifficulty(Difficulty.NORMAL, true);
            test.assertValueEqual(f.swing(f.front), "困难难度下僵尸才能敲开木门", "A zombie below Hard is told so");
            f.door(f.front, Blocks.IRON_DOOR);
            test.assertValueEqual(f.swing(f.front), "铁门敲不开", "An iron door is shut to everyone");
            f.set(f.front, Blocks.CHEST);
            test.assertValueEqual(f.swing(f.front), "里面装着东西，挖不动", "A container explains itself");
            f.set(f.front, Blocks.IRON_ORE);
            test.assertValueEqual(f.swing(f.front), "矿石挖不动", "Ore explains itself");
            f.set(f.front, Blocks.OBSIDIAN);
            test.assertValueEqual(f.swing(f.front), "太硬了，挖不动", "Obsidian explains itself");
        } catch (Throwable failure) {
            f.close();
            throw failure;
        }
        // Give the global difficulty and griefing rule back before the wait. The pig punch does not
        // need them, and restoring them only when the chunk ticks would clobber a test that started later.
        f.level.getServer().setDifficulty(f.difficulty, true);
        f.griefing(f.mobGriefing);
        // Forced arena chunks become entity-ticking asynchronously. Until then getEntities skips
        // the pig's section, so a same-tick punch reads as a clear shot at the obsidian behind it.
        // That finishes inline when this test runs alone, and not yet when monster_* is busy.
        MonsterRoleGameTest.whenArenaTicking(test, f.player, () -> {
            var pig = EntityTypes.PIG.create(f.level, EntitySpawnReason.COMMAND);
            try {
                pig.setNoAi(true);
                pig.setNoGravity(true);
                pig.snapTo(Vec3.atCenterOf(f.front).add(0, 0, -.6));
                f.level.addFreshEntity(pig);
                MonsterMining.forget(f.player);
                LobbyInteractions.get(f.level.getServer()).ifPresent(router -> router.hints(f.player).clear("notice"));
                f.client.clearPackets();
                f.look(f.front);
                f.punch();
                test.assertTrue(f.shown().isEmpty(), "Swinging at a creature does not explain the block behind it: [" + f.shown() + "] eye=" + f.player.getEyePosition() + " pig=" + pig.getBoundingBox());
            } finally {
                pig.discard();
                MonsterRoles.clear(f.player);
                f.client.close();
                MonsterRoleGameTest.remove(f.player);
            }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void heldItemsKeepAdventureInputUntilTheSwing(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            f.wall(Blocks.OAK_PLANKS, Blocks.OAK_PLANKS);
            f.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
            f.client.clearPackets();
            f.look(f.front);
            MonsterMining.tick(f.player);
            test.assertFalse(f.clientMode(GameType.SURVIVAL), "Aiming with a tool keeps adventure input");
            test.assertValueEqual(f.player.gameMode.getGameModeForPlayer(), GameType.ADVENTURE, "The body stays in adventure");
            f.client.clearPackets();
            f.punch();
            test.assertTrue(f.clientMode(GameType.SURVIVAL), "The swing arms survival input");
            f.client.clearPackets();
            f.player.setYRot(180);
            MonsterMining.tick(f.player);
            test.assertTrue(f.clientMode(GameType.ADVENTURE), "Looking away returns adventure input");
            f.look(f.front);
            f.client.clearPackets();
            MonsterMining.tick(f.player);
            test.assertFalse(f.clientMode(GameType.SURVIVAL), "Aiming again does not re-arm a held tool");
            f.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
            f.punch();
            f.client.clearPackets();
            f.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
            MonsterMining.tick(f.player);
            test.assertTrue(f.clientMode(GameType.ADVENTURE), "Changing the held item returns adventure input");
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
        void door(BlockPos lower, Block block) {
            var state = block.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
            level.setBlockAndUpdate(lower, state.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
            level.setBlockAndUpdate(lower.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }
        int time(Block block, int cap) {
            var pos = feet.north(2);
            set(pos, block);
            return MonsterDiggable.ticks(level, pos, level.getBlockState(pos), cap);
        }
        void expect(Block block, MonsterDiggable.Verdict verdict) {
            var pos = feet.north(2);
            set(pos, block);
            test.assertValueEqual(MonsterDiggable.verdict(player, pos), verdict, block.toString());
        }
        String swing(BlockPos pos) {
            MonsterMining.forget(player);
            LobbyInteractions.get(level.getServer()).ifPresent(router -> router.hints(player).clear("notice"));
            client.clearPackets();
            look(pos);
            punch();
            return shown();
        }
        String shown() {
            var router = LobbyInteractions.get(level.getServer());
            if (router.isPresent()) return router.get().hints(player).current(System.currentTimeMillis()).orElse("");
            return client.packets().stream().filter(ClientboundSetActionBarTextPacket.class::isInstance)
                    .reduce((first, second) -> second).map(packet -> ((ClientboundSetActionBarTextPacket) packet).text().getString()).orElse("");
        }
        boolean clientMode(GameType mode) {
            return client.packets().stream().anyMatch(packet -> packet instanceof ClientboundGameEventPacket game
                    && game.getEvent() == ClientboundGameEventPacket.CHANGE_GAME_MODE && game.getParam() == mode.getId());
        }
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
        void tick(int count) { for (int i = 0; i < count; i++) MonsterMining.tick(player); }
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
