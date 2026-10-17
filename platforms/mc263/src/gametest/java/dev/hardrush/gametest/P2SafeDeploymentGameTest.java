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
            test.assertTrue(distance >= 32 && distance <= 64, "Target distance stays in the 32-64 horizontal annulus");
            test.assertTrue(search.revalidate(), "Unchanged loaded terrain passes departure revalidation");
            var nearbyHuman = fixture.target.add(33, 0, 0);
            var crowded = complete(fixture.search(fixture.target, List.of(fixture.target, nearbyHuman)));
            test.assertValueEqual(crowded.status(), P2SafeDeployment.Status.FOUND, "The search can use another safe patch when a second human blocks the first");
            test.assertTrue(crowded.position().distanceToSqr(nearbyHuman) >= 24 * 24, "Every active human receives the minimum separation");
            test.assertTrue(!search.revalidate(fixture.target, List.of(result.position())), "Another active human moving to the point revokes admission");
            test.assertTrue(!search.revalidate(result.position(), List.of(fixture.target)), "A moving target cannot produce a face-to-face spawn");
            fixture.level.setBlock(BlockPos.containing(result.position()), Blocks.LAVA.defaultBlockState(), 3);
            test.assertTrue(!search.revalidate(), "A newly flooded point is rejected immediately before teleport");
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void dangerousTerrainAndNarrowEdgesNeverManufactureASpawn(GameTestHelper test) {
        var fixture = new Fixture(test, test.getLevel(), 224);
        try (var ignored = fixture.empty()) {
            // A single-cell ledge is a valid vanilla standing point, but never a safe deployment patch.
            fixture.corridor(Blocks.STONE, 0);
            var before = fixture.level.getBlockState(fixture.feet.offset(33, -1, 0));
            var ledge = complete(fixture.search(fixture.target, List.of(fixture.target)));
            test.assertValueEqual(ledge.status(), P2SafeDeployment.Status.FAILED, "A dangerous edge cannot pass the 5x5 standing patch");
            test.assertValueEqual(fixture.level.getBlockState(fixture.feet.offset(33, -1, 0)), before, "Search preserves the terrain it inspected");
            for (var block : List.of(Blocks.MAGMA_BLOCK, Blocks.CACTUS, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE)) {
                fixture.corridor(block);
                test.assertValueEqual(complete(fixture.search(fixture.target, List.of(fixture.target))).status(),
                        P2SafeDeployment.Status.FAILED, "Hazardous floor cannot be used: " + block);
            }
            fixture.corridor(Blocks.STONE);
            for (var sample : fixture.samples()) for (int x=-2; x<=2; x++) for (int z=-2; z<=2; z++) {
                for (int y=0; y<=9; y++) fixture.level.setBlock(sample.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
                // There is no valid alternate standing surface on top of this suffocating volume.
                fixture.level.setBlock(sample.offset(x, 10, z), Blocks.MAGMA_BLOCK.defaultBlockState(), 2);
            }
            test.assertValueEqual(complete(fixture.search(fixture.target, List.of(fixture.target))).status(),
                    P2SafeDeployment.Status.FAILED, "A solid body-height volume cannot suffocate the deployed player");
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void protectionPortalAndVoidCandidatesAreRejected(GameTestHelper test) {
        var fixture = new Fixture(test, test.getLevel(), 228);
        try (var ignored = fixture.empty()) { fixture.corridor(Blocks.STONE); }
        try (var ignored = fixture.protectedZone()) {
            test.assertValueEqual(complete(fixture.search(fixture.target, List.of(fixture.target))).status(),
                    P2SafeDeployment.Status.FAILED, "Lobby protection rejects every candidate even with safe native ground");
        }
        try (var ignored = fixture.empty()) {
            fixture.corridor(Blocks.STONE);
            var columns = new java.util.HashSet<BlockPos>();
            for (var sample : fixture.samples()) for (int x=-2; x<=2; x++) for (int z=-2; z<=2; z++)
                columns.add(sample.offset(x, 0, z));
            // Control every alternate Y the search can inspect, including the native heightmap.
            // A mountain above the portal must not offer an unrelated, genuinely safe surface.
            for (var column : columns) for (int y=column.getY()-9; y<fixture.level.getMaxY(); y++) {
                var position = column.atY(y);
                var state = (y < column.getY() ? Blocks.STONE
                        : y == column.getY() ? Blocks.END_PORTAL : Blocks.AIR).defaultBlockState();
                if (!fixture.level.getBlockState(position).equals(state)) fixture.level.setBlock(position, state, 2);
            }
            test.assertValueEqual(complete(fixture.search(fixture.target, List.of(fixture.target))).status(),
                    P2SafeDeployment.Status.FAILED, "A non-colliding portal is still an unsafe spawn");
            fixture.corridor(Blocks.AIR);
            // Clear every sampled column down to the native minimum. Adjacent terrain is
            // irrelevant: the candidate's own floor can never be invented across this void.
            for (var sample : fixture.samples()) for (int y=fixture.level.getMinY(); y<sample.getY(); y++)
                fixture.level.setBlock(sample.atY(y), Blocks.AIR.defaultBlockState(), 2);
            test.assertValueEqual(complete(fixture.search(fixture.target, List.of(fixture.target))).status(),
                    P2SafeDeployment.Status.FAILED, "No standing floor means no air or void deployment");
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void unloadedSearchIsBoundedAndNeverLoadsCandidateChunks(GameTestHelper test) {
        var level = test.getLevel();
        var target = new Vec3(26_000_000.5, 100, 26_000_000.5);
        int chunkX = ((int)target.x) >> 4, chunkZ = ((int)target.z) >> 4;
        test.assertTrue(level.getChunkSource().getChunkNow(chunkX, chunkZ) == null, "The test destination starts unloaded");
        var search = new P2SafeDeployment.Search(level, target, List.of(target), fixedRandom(), () -> 0);
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
        var timeout = new P2SafeDeployment.Search(level, target, List.of(target), fixedRandom(), clock::get);
        clock.set(P2SafeDeployment.MAX_SEARCH_NANOS);
        test.assertValueEqual(timeout.tick().status(), P2SafeDeployment.Status.FAILED, "Two elapsed seconds end the search without requiring a candidate");
        test.assertValueEqual(timeout.result().candidates(), 0, "Expired work cannot begin another candidate");
        var unitClock = new AtomicLong();
        var sliced = new P2SafeDeployment.Search(level, target, List.of(target), fixedRandom(), () -> unitClock.getAndAdd(600_000));
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
        var remaining = new P2SafeDeployment.Search(level, target, List.of(target), fixedRandom(), clock::get, 3, 0);
        var result = complete(remaining);
        test.assertValueEqual(result.status(), P2SafeDeployment.Status.FAILED, "The same target in another dimension cannot extend the remaining candidate allowance");
        test.assertValueEqual(result.candidates(), 3, "Only the three remaining standing points are inspected");
        clock.set(P2SafeDeployment.MAX_SEARCH_NANOS);
        var expired = new P2SafeDeployment.Search(level, target, List.of(target), fixedRandom(), clock::get, 3, 0);
        test.assertValueEqual(expired.tick().status(), P2SafeDeployment.Status.FAILED, "A target change does not restart the two-second deadline");
        test.assertValueEqual(expired.result().candidates(), 0, "An inherited expired deadline admits no new work");
        for (int invalid : new int[]{0, 65}) {
            boolean rejected = false;
            try { new P2SafeDeployment.Search(level, target, List.of(target), fixedRandom(), clock::get, invalid, 0); }
            catch (IllegalArgumentException expected) { rejected = true; }
            test.assertTrue(rejected, "Shared budgets outside 1-64 are rejected");
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void nativeNetherRoofAndEndArrivalPlatformAreRejected(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var nether = new Fixture(test, server.getLevel(Level.NETHER), 128);
        var end = new Fixture(test, server.getLevel(Level.END), 120);
        try (var ignored = nether.empty()) {
            nether.corridor(Blocks.BEDROCK);
            for (var sample : nether.samples()) for (int y=-9; y<0; y++)
                nether.level.setBlock(sample.offset(0, y, 0), Blocks.BEDROCK.defaultBlockState(), 2);
            test.assertValueEqual(complete(nether.search(nether.target, List.of(nether.target))).status(),
                    P2SafeDeployment.Status.FAILED, "Native Nether roof deployment is forbidden");
            end.corridor(Blocks.OBSIDIAN);
            test.assertValueEqual(complete(end.search(end.target, List.of(end.target))).status(),
                    P2SafeDeployment.Status.FAILED, "Native End obsidian arrival terrain is forbidden");
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
        final BlockPos feet;
        final Vec3 target;
        Fixture(GameTestHelper test, ServerLevel level, int y) {
            this.level = java.util.Objects.requireNonNull(level);
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
        List<BlockPos> samples() {
            var points = new java.util.ArrayList<BlockPos>();
            for (int i=0; i<64; i++) {
                double angle = i * Math.PI * (3 - Math.sqrt(5));
                points.add(new BlockPos((int)Math.floor(target.x + Math.cos(angle) * 32.5), feet.getY(),
                        (int)Math.floor(target.z + Math.sin(angle) * 32.5)));
            }
            return points;
        }
        P2SafeDeployment.Search search(Vec3 target, List<Vec3> humans) {
            return new P2SafeDeployment.Search(level, target, humans, fixedRandom(), () -> 0);
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
