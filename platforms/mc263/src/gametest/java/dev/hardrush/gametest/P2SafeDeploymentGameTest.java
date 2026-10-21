package dev.hardrush.gametest;

import dev.hardrush.core.round.Participant;
import dev.hardrush.p2.hunt.P2SafeDeployment;
import dev.hardrush.p2.protection.P2ProtectionRuntime;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** S04 uses controlled native server terrain. No graphical client or terrain creation by the search. */
public final class P2SafeDeploymentGameTest {
    private static final AtomicInteger ARENAS = new AtomicInteger();

    @GameTest(maxTicks=200)
    public void reachableLoadedTerrainUsesRingAndRevalidatesMovingHumans(GameTestHelper test) {
        var fixture = new Fixture(test, test.getLevel(), 220);
        try (var ignored = fixture.empty()) {
            fixture.corridor(Blocks.STONE);
            var search = fixture.search(fixture.target, List.of(fixture.target));
            var result = complete(search);
            test.assertValueEqual(result.status(), P2SafeDeployment.Status.FOUND, "Loaded walking corridor finds a safe spawn");
            double distance = result.position().subtract(fixture.target).horizontalDistance();
            test.assertTrue(distance >= 24 && distance <= 40, "Target distance stays in the closer 24-40 horizontal annulus");
            test.assertTrue(search.revalidate(), "Unchanged loaded terrain passes departure revalidation");
            test.assertTrue(search.revalidate(result.position().add(-24, 0, 0), List.of(fixture.target)), "The 24-block target boundary is admitted");
            test.assertTrue(!search.revalidate(result.position().add(-23.99, 0, 0), List.of(fixture.target)), "A target inside 24 blocks revokes departure");
            test.assertTrue(search.revalidate(result.position().add(-40, 0, 0), List.of(fixture.target)), "The 40-block target boundary is admitted");
            test.assertTrue(!search.revalidate(result.position().add(-40.01, 0, 0), List.of(fixture.target)), "A target beyond 40 blocks revokes departure");
            test.assertTrue(search.revalidate(fixture.target, List.of(result.position().add(16, 0, 0))), "Another human 16 blocks away is permitted");
            test.assertTrue(!search.revalidate(fixture.target, List.of(result.position().add(15.99, 0, 0))), "Another human closer than 16 blocks revokes departure");
            var nearbyHuman = fixture.target.add(25, 0, 0);
            var crowded = complete(fixture.search(fixture.target, List.of(fixture.target, nearbyHuman)));
            test.assertValueEqual(crowded.status(), P2SafeDeployment.Status.FOUND, "The search can use another safe patch when a second human blocks the first");
            test.assertTrue(crowded.position().distanceToSqr(nearbyHuman) >= 16 * 16, "Every active human receives the 16-block minimum separation");
            test.assertTrue(!search.revalidate(fixture.target, List.of(result.position())), "Another active human moving to the point revokes admission");
            test.assertTrue(!search.revalidate(result.position(), List.of(fixture.target)), "A moving target cannot produce a face-to-face spawn");
            fixture.level.setBlock(BlockPos.containing(result.position()), Blocks.LAVA.defaultBlockState(), 3);
            test.assertTrue(!search.revalidate(), "A newly flooded point is rejected immediately before teleport");
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void steppedSlopesAndTwoBlockHighWalledCavesAllowDeployment(GameTestHelper test) {
        var fixture = new Fixture(test, test.getLevel(), 222);
        try (var ignored = fixture.empty()) {
            var landing = fixture.samples().getFirst();
            fixture.firstLanding((x, z) -> x, 12);
            var slope = fixture.search(fixture.target, List.of(fixture.target));
            test.assertValueEqual(complete(slope).position(), Vec3.atBottomCenterOf(landing),
                    "A normal one-block-per-column slope needs no same-height flat patch");
            test.assertTrue(slope.revalidate(), "Sloped ground uses the same safety rules before teleport");

            fixture.firstLanding((x, z) -> 0, 2);
            // A one-block-wide, two-block-high cave has walls directly beside the body.
            for (int x=-2; x<=2; x++) for (int z : new int[]{-1, 1}) for (int y=0; y<2; y++)
                fixture.level.setBlock(landing.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
            var cave = fixture.search(fixture.target, List.of(fixture.target));
            test.assertValueEqual(complete(cave).position(), Vec3.atBottomCenterOf(landing),
                    "A low cave with solid side walls remains a valid landing");
            test.assertTrue(cave.revalidate(), "Departure revalidation permits cave walls and a two-block ceiling");
            fixture.level.setBlock(landing.above(), Blocks.STONE.defaultBlockState(), 2);
            test.assertTrue(!cave.revalidate(), "A wall inside the actual body space still revokes departure");
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void narrowLedgesAreAllowedButLosingTheActualFloorRevokesDeparture(GameTestHelper test) {
        var fixture = new Fixture(test, test.getLevel(), 226);
        try (var ignored = fixture.empty()) {
            fixture.corridor(Blocks.STONE, 0);
            var search = fixture.search(fixture.target, List.of(fixture.target));
            var result = complete(search);
            test.assertValueEqual(result.status(), P2SafeDeployment.Status.FOUND,
                    "A supported narrow ledge is usable even when the neighboring columns are deep drops");
            var landing = BlockPos.containing(result.position());
            fixture.level.setBlock(landing.offset(1, 0, 1), Blocks.LAVA.defaultBlockState(), 2);
            test.assertTrue(search.revalidate(), "Lava outside the actual body does not veto a vanilla-legal landing");
            var floor = fixture.level.getBlockState(landing.below());
            fixture.level.setBlock(landing.below(), Blocks.AIR.defaultBlockState(), 2);
            test.assertTrue(!search.revalidate(), "Removing actual support prevents an immediate fall or void deployment");
            fixture.level.setBlock(landing.below(), floor, 2);
            fixture.level.setBlock(landing, Blocks.LAVA.defaultBlockState(), 2);
            test.assertTrue(!search.revalidate(), "Lava intersecting the actual player body revokes departure");
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void actualPlayerBoxAllowsPartialBlocksAndShortCeilingsButRejectsWalls(GameTestHelper test) {
        var fixture = new Fixture(test, test.getLevel(), 224);
        var landing = fixture.samples().getFirst();
        var point = Vec3.atBottomCenterOf(landing);
        fixture.firstLanding((x, z) -> 0, 12);
        var side = Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN, true)
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,
                        net.minecraft.core.Direction.EAST);
        fixture.level.setBlock(landing, side, 2);
        test.assertTrue(P2SafeDeployment.canDeployAt(fixture.level, fixture.hunter, point),
                "A partial block outside the actual body does not require a wholly empty block");
        fixture.level.setBlock(landing, Blocks.AIR.defaultBlockState(), 2);
        var ceiling = Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF,
                        net.minecraft.world.level.block.state.properties.Half.TOP);
        fixture.level.setBlock(landing.above(), ceiling, 2);
        test.assertTrue(!fixture.level.noCollision(EntityTypes.ZOMBIE.getSpawnAABB(point)),
                "The control ceiling is too low for the native zombie spawn box");
        test.assertTrue(P2SafeDeployment.canDeployAt(fixture.level, fixture.hunter, point),
                "A ceiling above the player's real head may be below the visual monster's head");
        fixture.level.setBlock(landing.above(), Blocks.AIR.defaultBlockState(), 2);
        for (var floor : List.of(Blocks.GLASS, Blocks.OAK_LEAVES, Blocks.STONE_SLAB)) {
            fixture.level.setBlock(landing.below(), floor.defaultBlockState(), 2);
            test.assertTrue(P2SafeDeployment.canDeployAt(fixture.level, fixture.hunter, point),
                    "Physically supported non-spawnable floors are also allowed: " + floor);
        }
        fixture.level.setBlock(landing.below(), Blocks.STONE.defaultBlockState(), 2);
        fixture.level.setBlock(landing.east(), Blocks.STONE.defaultBlockState(), 2);
        var original = fixture.hunter.getBoundingBox();
        test.assertTrue(P2SafeDeployment.canDeployAt(fixture.level, fixture.hunter, point), "Ordinary player body fits beside a wall");
        try {
            fixture.hunter.setBoundingBox(original.inflate(.5, 0, .5));
            test.assertTrue(!P2SafeDeployment.canDeployAt(fixture.level, fixture.hunter, point),
                    "A changed actual player width is re-read rather than using a fixed or monster box");
        } finally { fixture.hunter.setBoundingBox(original); }
        fixture.level.setBlock(landing.above(), Blocks.STONE.defaultBlockState(), 2);
        test.assertTrue(!P2SafeDeployment.canDeployAt(fixture.level, fixture.hunter, point), "A solid wall through the head is rejected");
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void vanillaLegalGroundAndBodyPlacementsAreNeverMadeStricter(GameTestHelper test) {
        var fixture = new Fixture(test, test.getLevel(), 230);
        var landing = fixture.samples().getFirst();
        var point = Vec3.atBottomCenterOf(landing);
        fixture.firstLanding((x, z) -> 0, 12);
        int comparisons = 0;
        for (var floor : List.of(Blocks.STONE, Blocks.DIRT, Blocks.BEDROCK, Blocks.OBSIDIAN,
                Blocks.GLASS, Blocks.OAK_LEAVES, Blocks.STONE_SLAB, Blocks.MAGMA_BLOCK,
                Blocks.CACTUS, Blocks.CAMPFIRE, Blocks.SOUL_SAND, Blocks.MUD, Blocks.FARMLAND,
                Blocks.HONEY_BLOCK, Blocks.SLIME_BLOCK, Blocks.SNOW_BLOCK)) {
            fixture.level.setBlock(landing.below(), floor.defaultBlockState(), 2);
            for (var inside : List.of(Blocks.AIR, Blocks.TORCH, Blocks.STONE_BUTTON, Blocks.SHORT_GRASS, Blocks.FIRE)) {
                fixture.level.setBlock(landing, inside.defaultBlockState(), 2);
                for (var type : List.of(EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.CREEPER)) {
                    if (!SpawnPlacementTypes.ON_GROUND.isSpawnPositionOk(fixture.level, landing, type)
                            || !fixture.level.noCollision(type.getSpawnAABB(point))) continue;
                    comparisons++;
                    test.assertTrue(P2SafeDeployment.canDeployAt(fixture.level, fixture.hunter, point),
                            "Every vanilla-legal placement is admitted: " + type + "/" + floor + "/" + inside);
                }
            }
        }
        test.assertTrue(comparisons >= 30, "The differential test exercised a meaningful set of vanilla-legal placements");
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void protectionAndUnsupportedVoidCandidatesAreRejected(GameTestHelper test) {
        var fixture = new Fixture(test, test.getLevel(), 228);
        try (var ignored = fixture.empty()) { fixture.corridor(Blocks.STONE); }
        try (var ignored = fixture.protectedZone()) {
            test.assertValueEqual(complete(fixture.search(fixture.target, List.of(fixture.target))).status(),
                    P2SafeDeployment.Status.FAILED, "Lobby protection still guards gameplay admission");
        }
        try (var ignored = fixture.empty()) {
            fixture.corridor(Blocks.AIR);
            for (var sample : fixture.samples()) for (int y=fixture.level.getMinY(); y<sample.getY(); y++)
                fixture.level.setBlock(sample.atY(y), Blocks.AIR.defaultBlockState(), 2);
            test.assertValueEqual(complete(fixture.search(fixture.target, List.of(fixture.target))).status(),
                    P2SafeDeployment.Status.FAILED, "No floor means no air or void deployment");
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void unloadedSearchIsBoundedAndNeverLoadsCandidateChunks(GameTestHelper test) {
        var level = test.getLevel();
        var target = new Vec3(26_000_000.5, 100, 26_000_000.5);
        int chunkX = ((int)target.x) >> 4, chunkZ = ((int)target.z) >> 4;
        test.assertTrue(level.getChunkSource().getChunkNow(chunkX, chunkZ) == null, "The test destination starts unloaded");
        var search = new P2SafeDeployment.Search(level, test.makeMockServerPlayerInLevel(), target, List.of(target), fixedRandom(), () -> 0);
        var first = search.tick();
        test.assertValueEqual(first.status(), P2SafeDeployment.Status.PENDING, "One tick cannot consume the entire candidate budget");
        test.assertValueEqual(first.candidates(), P2SafeDeployment.CANDIDATES_PER_TICK, "At most four fresh candidates are begun per tick");
        var result = complete(search);
        test.assertValueEqual(result.status(), P2SafeDeployment.Status.FAILED, "Unloaded terrain fails closed");
        test.assertValueEqual(result.candidates(), 64, "The whole attempt is capped at 64 candidate standing points");
        for (int x=chunkX-5; x<=chunkX+5; x++) for (int z=chunkZ-5; z<=chunkZ+5; z++) {
            test.assertTrue(level.getChunkSource().getChunkNow(x, z) == null, "Search did not synchronously load or generate any chunk");
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void monotonicClockEnforcesTwoSecondAndPerTickBudgets(GameTestHelper test) {
        var level = test.getLevel();
        var target = new Vec3(26_100_000.5, 100, 26_100_000.5);
        var clock = new AtomicLong();
        var timeout = new P2SafeDeployment.Search(level, test.makeMockServerPlayerInLevel(), target, List.of(target), fixedRandom(), clock::get);
        clock.set(P2SafeDeployment.MAX_SEARCH_NANOS);
        test.assertValueEqual(timeout.tick().status(), P2SafeDeployment.Status.FAILED, "Two elapsed seconds end the search without requiring a candidate");
        test.assertValueEqual(timeout.result().candidates(), 0, "Expired work cannot begin another candidate");
        var unitClock = new AtomicLong();
        var sliced = new P2SafeDeployment.Search(level, test.makeMockServerPlayerInLevel(), target, List.of(target), fixedRandom(), () -> unitClock.getAndAdd(600_000));
        var first = sliced.tick();
        test.assertValueEqual(first.status(), P2SafeDeployment.Status.PENDING, "A two-millisecond tick slice preserves the attempt for the next tick");
        test.assertTrue(first.candidates() < P2SafeDeployment.CANDIDATES_PER_TICK, "Clock budget stops work before the four-candidate count budget");
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void sameTargetDimensionSearchInheritsRemainingCandidatesAndOriginalDeadline(GameTestHelper test) {
        var level = test.getLevel();
        var target = new Vec3(26_200_000.5, 100, 26_200_000.5);
        var clock = new AtomicLong(1_500_000_000L);
        var remaining = new P2SafeDeployment.Search(level, test.makeMockServerPlayerInLevel(), target, List.of(target), fixedRandom(), clock::get, 3, 0);
        var result = complete(remaining);
        test.assertValueEqual(result.status(), P2SafeDeployment.Status.FAILED, "The same target in another dimension cannot extend the remaining candidate allowance");
        test.assertValueEqual(result.candidates(), 3, "Only the three remaining standing points are inspected");
        clock.set(P2SafeDeployment.MAX_SEARCH_NANOS);
        var expired = new P2SafeDeployment.Search(level, test.makeMockServerPlayerInLevel(), target, List.of(target), fixedRandom(), clock::get, 3, 0);
        test.assertValueEqual(expired.tick().status(), P2SafeDeployment.Status.FAILED, "A target change does not restart the two-second deadline");
        test.assertValueEqual(expired.result().candidates(), 0, "An inherited expired deadline admits no new work");
        for (int invalid : new int[]{0, 65}) {
            boolean rejected = false;
            try { new P2SafeDeployment.Search(level, test.makeMockServerPlayerInLevel(), target, List.of(target), fixedRandom(), clock::get, invalid, 0); }
            catch (IllegalArgumentException expected) { rejected = true; }
            test.assertTrue(rejected, "Shared budgets outside 1-64 are rejected");
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void nativeNetherRoofAndEndObsidianDoNotAddTerrainBans(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var nether = new Fixture(test, server.getLevel(Level.NETHER), 128);
        var end = new Fixture(test, server.getLevel(Level.END), 120);
        try (var ignored = nether.empty()) {
            nether.corridor(Blocks.BEDROCK);
            for (var sample : nether.samples()) for (int y=-9; y<0; y++)
                nether.level.setBlock(sample.offset(0, y, 0), Blocks.BEDROCK.defaultBlockState(), 2);
            test.assertValueEqual(complete(nether.search(nether.target, List.of(nether.target))).status(),
                    P2SafeDeployment.Status.FOUND, "The Nether ceiling adds no extra terrain ban");
            end.corridor(Blocks.OBSIDIAN);
            test.assertValueEqual(complete(end.search(end.target, List.of(end.target))).status(),
                    P2SafeDeployment.Status.FOUND, "Supported End obsidian adds no extra terrain ban");
            end.corridor(Blocks.END_STONE);
            test.assertValueEqual(complete(end.search(end.target, List.of(end.target))).status(),
                    P2SafeDeployment.Status.FOUND, "Ordinary loaded End island ground remains usable");
        }
        test.succeed();
    }

    private static P2SafeDeployment.Result complete(P2SafeDeployment.Search search) {
        for (int i=0; i<2000 && search.result().status() == P2SafeDeployment.Status.PENDING; i++) search.tick();
        if (search.result().status() == P2SafeDeployment.Status.PENDING) throw new IllegalStateException("Search failed to yield a bounded result");
        return search.result();
    }
    private static Random fixedRandom() { return new Random(0) { @Override public double nextDouble() { return 0; } }; }
    private static final class Fixture {
        final ServerLevel level;
        final ServerPlayer hunter;
        final BlockPos feet;
        final Vec3 target;
        Fixture(GameTestHelper test, ServerLevel level, int y) {
            this.level = java.util.Objects.requireNonNull(level);
            hunter = test.makeMockServerPlayerInLevel();
            // The test server retains terrain between runs. Follow this run's randomized
            // structure origin so an earlier wide corridor cannot fill today's narrow ledge.
            var origin = test.absolutePos(BlockPos.ZERO);
            feet = new BlockPos(origin.getX() + 80_000 + ARENAS.getAndIncrement() * 4096, y, origin.getZ() + 80_000);
            target = Vec3.atBottomCenterOf(feet);
        }
        void corridor(Block floor) { corridor(floor, 3); }
        void corridor(Block floor, int width) {
            for (int x=-3; x<=68; x++) for (int z=-width; z<=width; z++) {
                standing(feet.offset(x, 0, z), floor);
            }
            // Every deterministic sampled column has controlled height and floor material;
            // the native heightmap cannot accidentally fall back to unrelated terrain below.
            int pad = width == 0 ? 0 : 2;
            for (var sample : samples()) for (int x=-pad; x<=pad; x++) for (int z=-pad; z<=pad; z++)
                standing(sample.offset(x, 0, z), floor);
        }
        void standing(BlockPos point, Block floor) {
            level.setBlock(point.below(), floor.defaultBlockState(), 2);
            for (int y=0; y<3; y++) level.setBlock(point.above(y), Blocks.AIR.defaultBlockState(), 2);
        }
        void firstLanding(java.util.function.IntBinaryOperator height, int clearance) {
            var center = samples().getFirst();
            for (int x=-2; x<=2; x++) for (int z=-2; z<=2; z++) {
                int dy = height.applyAsInt(x, z);
                for (int y=-6; y<=12; y++) {
                    Block block = y < dy || y >= dy + clearance ? Blocks.STONE : Blocks.AIR;
                    // Never offer the top of the test cave as an alternate landing.
                    if (y == 12) block = Blocks.MAGMA_BLOCK;
                    level.setBlock(center.offset(x, y, z), block.defaultBlockState(), 2);
                }
            }
        }
        List<BlockPos> samples() {
            var points = new java.util.ArrayList<BlockPos>();
            for (int i=0; i<64; i++) {
                double angle = i * Math.PI * (3 - Math.sqrt(5));
                points.add(new BlockPos((int)Math.floor(target.x + Math.cos(angle) * 25), feet.getY(),
                        (int)Math.floor(target.z + Math.sin(angle) * 25)));
            }
            return points;
        }
        P2SafeDeployment.Search search(Vec3 target, List<Vec3> humans) {
            return new P2SafeDeployment.Search(level, hunter, target, humans, fixedRandom(), () -> 0);
        }
        dev.hardrush.gametest.RuntimeFixtures.Scope empty() { return scope(false); }
        dev.hardrush.gametest.RuntimeFixtures.Scope protectedZone() { return scope(true); }
        dev.hardrush.gametest.RuntimeFixtures.Scope scope(boolean enabled) {
            return dev.hardrush.gametest.RuntimeFixtures.protection(level.getServer(), new P2ProtectionRuntime.Access() {
                @Override public Optional<P2ProtectionRuntime.Zone> zone(ServerLevel value) {
                    return enabled && value == level ? Optional.of(new P2ProtectionRuntime.Zone(UUID.randomUUID(), target, 32, true)) : Optional.empty();
                }
                @Override public Optional<Participant> participant(UUID id) { return Optional.empty(); }
                @Override public boolean leaveProtection(UUID round, UUID id, int life) { return false; }
                @Override public void quarantine(ServerPlayer player) { throw new IllegalStateException("Search cannot move players"); }
            });
        }
    }
}
