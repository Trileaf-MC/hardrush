package dev.hardrush.gametest;

import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.core.round.Participant;
import dev.hardrush.hunt.SafeDeployment;
import dev.hardrush.protection.ProtectionRuntime;
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

/** Controlled native server terrain and a fake 20 TPS clock. No graphical client or terrain creation by the search. */
public final class SafeDeploymentGameTest {
    private static final AtomicInteger ARENAS = new AtomicInteger();
    private static final long TICK = 50_000_000L;

    @GameTest(maxTicks=200)
    public void openGroundLandsWithinAShortWalkFacingTheQuarry(GameTestHelper test) {
        var fixture = new Fixture(test, test.getLevel(), 220);
        try (var ignored = fixture.empty()) {
            fixture.flat(Blocks.STONE, 34);
            var clock = new AtomicLong();
            var search = fixture.search(MonsterRole.ZOMBIE, SafeDeployment.Quarry.standing(fixture.target), List.of(fixture.target), clock);
            var result = complete(search, clock);
            test.assertValueEqual(result.status(), SafeDeployment.Status.FOUND, "Open loaded ground finds a landing");
            test.assertFalse(result.fallback(), "A reachable landing is never a fallback");
            double distance = result.position().distanceTo(fixture.target);
            test.assertTrue(distance >= SafeDeployment.MIN_HUMAN_DISTANCE && distance <= SafeDeployment.MAX_PATH,
                    "Open ground lands 8-24 blocks away: " + distance);
            test.assertTrue(clock.get() < SafeDeployment.FALLBACK_AFTER_NANOS, "A reachable landing needs no five-second wait");
            float yaw = search.yaw(fixture.target);
            double radians = Math.toRadians(yaw);
            var look = new Vec3(-Math.sin(radians), 0, Math.cos(radians));
            test.assertTrue(look.dot(fixture.target.subtract(result.position()).multiply(1, 0, 1).normalize()) > .99, "The landing faces the quarry");
            test.assertTrue(search.revalidate(), "Unchanged loaded terrain passes departure revalidation");
            var point = result.position();
            test.assertTrue(!search.revalidate(point.add(SafeDeployment.MAX_PATH + 4.01, 0, 0), List.of()), "A quarry that ran off revokes departure");
            test.assertTrue(search.revalidate(fixture.target, List.of(point.add(8, 0, 0))), "Another human 8 blocks away is permitted");
            test.assertTrue(!search.revalidate(fixture.target, List.of(point.add(7.99, 0, 0))), "Another human closer than 8 blocks revokes departure");
            fixture.level.setBlock(BlockPos.containing(point), Blocks.LAVA.defaultBlockState(), 3);
            test.assertTrue(!search.revalidate(), "A newly flooded point is rejected immediately before teleport");
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void aStillQuarryIsApproachedAlongItsOwnFootprints(GameTestHelper test) {
        var fixture = new Fixture(test, test.getLevel(), 232);
        try (var ignored = fixture.empty()) {
            fixture.flat(Blocks.STONE, 34);
            var trail = new java.util.ArrayList<Vec3>();
            for (int x = -2; x >= -22; x -= 2) trail.add(fixture.target.add(x, 0, 0));
            // Facing +X: the footprints lie straight behind it.
            var quarry = new SafeDeployment.Quarry(fixture.target, fixture.target.add(0, 1.62, 0), -90, Vec3.ZERO, trail);
            var clock = new AtomicLong();
            var result = complete(fixture.search(MonsterRole.ZOMBIE, quarry, List.of(fixture.target), clock), clock);
            test.assertValueEqual(result.status(), SafeDeployment.Status.FOUND, "Footprints yield a landing");
            test.assertTrue(trail.contains(result.position()), "A still quarry is approached from its own footprints: " + result.position());
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void aRunnerIsInterceptedAheadOfItsHeading(GameTestHelper test) {
        var fixture = new Fixture(test, test.getLevel(), 234);
        try (var ignored = fixture.empty()) {
            fixture.flat(Blocks.STONE, 34);
            var quarry = new SafeDeployment.Quarry(fixture.target, fixture.target.add(0, 1.62, 0), -90, new Vec3(5.6, 0, 0), List.of());
            var clock = new AtomicLong();
            var result = complete(fixture.search(MonsterRole.ZOMBIE, quarry, List.of(fixture.target), clock), clock);
            test.assertValueEqual(result.status(), SafeDeployment.Status.FOUND, "A runner can be intercepted");
            var away = result.position().subtract(fixture.target).multiply(1, 0, 1).normalize();
            test.assertTrue(away.dot(new Vec3(1, 0, 0)) >= Math.cos(Math.PI / 4) - 1e-6, "The landing lies within 45 degrees ahead: " + away);
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void anUnreachableQuarryFallsBackToTheClosestWalkAfterFiveSeconds(GameTestHelper test) {
        var fixture = new Fixture(test, test.getLevel(), 236);
        try (var ignored = fixture.empty()) {
            fixture.flat(Blocks.STONE, 34);
            // A pillar the quarry built: six blocks up, no way for a walker to climb.
            for (int y = 0; y < 6; y++) fixture.level.setBlock(fixture.feet.above(y), Blocks.STONE.defaultBlockState(), 2);
            var top = fixture.target.add(0, 6, 0);
            var clock = new AtomicLong();
            var zombie = fixture.search(MonsterRole.ZOMBIE, SafeDeployment.Quarry.standing(top), List.of(top), clock);
            for (int i = 0; i < 90 && clock.get() < SafeDeployment.FALLBACK_AFTER_NANOS - TICK; i++) { zombie.tick(); clock.addAndGet(TICK); }
            test.assertValueEqual(zombie.result().status(), SafeDeployment.Status.PENDING, "Nothing is admitted before five seconds");
            var result = complete(zombie, clock);
            test.assertValueEqual(result.status(), SafeDeployment.Status.FOUND, "The closest walkable spot is admitted after five seconds");
            test.assertTrue(result.fallback(), "The landing is reported as a fallback");
            test.assertTrue(clock.get() <= SafeDeployment.FALLBACK_AFTER_NANOS + 3 * TICK, "The fallback answers at five seconds");
            test.assertTrue(result.position().distanceTo(top) <= SafeDeployment.FALLBACK_RADIUS, "A fallback stays within 32 blocks");

            var skeletonClock = new AtomicLong();
            var skeleton = complete(fixture.search(MonsterRole.SKELETON, SafeDeployment.Quarry.standing(top), List.of(top), skeletonClock), skeletonClock);
            test.assertValueEqual(skeleton.status(), SafeDeployment.Status.FOUND, "A skeleton may land within bow range of a pillar");
            test.assertFalse(skeleton.fallback(), "Sight within 15 blocks is a full landing for a skeleton");
            test.assertTrue(skeleton.position().add(0, 1.62, 0).distanceTo(top.add(0, 1.62, 0)) <= SafeDeployment.SKELETON_SIGHT + 1e-6,
                    "The skeleton stands within vanilla bow range");
        }
        test.succeed();
    }

    @GameTest(maxTicks=400)
    public void theNetherRoofIsOnlyUsedWhenTheQuarryIsAlreadyUpThere(GameTestHelper test) {
        var nether = new Fixture(test, test.getLevel().getServer().getLevel(Level.NETHER), 120);
        try (var ignored = nether.empty()) {
            // Solid rock below a bedrock roof at y=127; the quarry stands in a sealed pocket at y=120.
            for (int x = -34; x <= 34; x++) for (int z = -34; z <= 34; z++) {
                for (int y = 94; y <= 126; y++) nether.level.setBlock(nether.feet.offset(x, y - 120, z), Blocks.NETHERRACK.defaultBlockState(), 2);
                nether.level.setBlock(nether.feet.offset(x, 7, z), Blocks.BEDROCK.defaultBlockState(), 2);
                for (int y = 128; y <= 131; y++) nether.level.setBlock(nether.feet.offset(x, y - 120, z), Blocks.AIR.defaultBlockState(), 2);
            }
            for (int y = 0; y < 2; y++) nether.level.setBlock(nether.feet.above(y), Blocks.AIR.defaultBlockState(), 2);
            var clock = new AtomicLong();
            var below = complete(nether.search(MonsterRole.ZOMBIE, SafeDeployment.Quarry.standing(nether.target), List.of(nether.target), clock), clock);
            test.assertValueEqual(below.status(), SafeDeployment.Status.FAILED, "A quarry under the roof never sends a monster onto it");
            var roof = nether.target.add(0, 8, 0);
            var roofClock = new AtomicLong();
            var above = complete(nether.search(MonsterRole.ZOMBIE, SafeDeployment.Quarry.standing(roof), List.of(roof), roofClock), roofClock);
            test.assertValueEqual(above.status(), SafeDeployment.Status.FOUND, "A quarry on the roof may be hunted on the roof");
            test.assertTrue(above.position().y >= 128, "The roof landing stands on the bedrock roof");
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
        test.assertTrue(SafeDeployment.canDeployAt(fixture.level, fixture.hunter, point),
                "A partial block outside the actual body does not require a wholly empty block");
        fixture.level.setBlock(landing, Blocks.AIR.defaultBlockState(), 2);
        var ceiling = Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF,
                        net.minecraft.world.level.block.state.properties.Half.TOP);
        fixture.level.setBlock(landing.above(), ceiling, 2);
        test.assertTrue(!fixture.level.noCollision(EntityTypes.ZOMBIE.getSpawnAABB(point)),
                "The control ceiling is too low for the native zombie spawn box");
        test.assertTrue(SafeDeployment.canDeployAt(fixture.level, fixture.hunter, point),
                "A ceiling above the player's real head may be below the visual monster's head");
        fixture.level.setBlock(landing.above(), Blocks.STONE_SLAB.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP), 2);
        fixture.hunter.setPose(net.minecraft.world.entity.Pose.CROUCHING);
        test.assertTrue(fixture.hunter.getBoundingBox().getYsize() < 1.6, "The control body is actually crouching");
        test.assertTrue(!SafeDeployment.canDeployAt(fixture.level, fixture.hunter, point),
                "A crouching hunter still needs room to stand up at its landing");
        fixture.hunter.setPose(net.minecraft.world.entity.Pose.STANDING);
        fixture.level.setBlock(landing.above(), Blocks.AIR.defaultBlockState(), 2);
        for (var floor : List.of(Blocks.GLASS, Blocks.OAK_LEAVES, Blocks.STONE_SLAB)) {
            fixture.level.setBlock(landing.below(), floor.defaultBlockState(), 2);
            test.assertTrue(SafeDeployment.canDeployAt(fixture.level, fixture.hunter, point),
                    "Physically supported non-spawnable floors are also allowed: " + floor);
        }
        fixture.level.setBlock(landing.below(), Blocks.STONE.defaultBlockState(), 2);
        fixture.level.setBlock(landing.east(), Blocks.STONE.defaultBlockState(), 2);
        var original = fixture.hunter.getBoundingBox();
        test.assertTrue(SafeDeployment.canDeployAt(fixture.level, fixture.hunter, point), "Ordinary player body fits beside a wall");
        try {
            fixture.hunter.setBoundingBox(original.inflate(.5, 0, .5));
            test.assertTrue(!SafeDeployment.canDeployAt(fixture.level, fixture.hunter, point),
                    "A changed actual player width is re-read rather than using a fixed or monster box");
        } finally { fixture.hunter.setBoundingBox(original); }
        fixture.level.setBlock(landing.above(), Blocks.STONE.defaultBlockState(), 2);
        test.assertTrue(!SafeDeployment.canDeployAt(fixture.level, fixture.hunter, point), "A solid wall through the head is rejected");
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
                    test.assertTrue(SafeDeployment.canDeployAt(fixture.level, fixture.hunter, point),
                            "Every vanilla-legal placement is admitted: " + type + "/" + floor + "/" + inside);
                }
            }
        }
        test.assertTrue(comparisons >= 30, "The differential test exercised a meaningful set of vanilla-legal placements");
        test.succeed();
    }

    @GameTest(maxTicks=400)
    public void atSeaOnlyAZombieRisesAndOnlyAgainstADriftingQuarry(GameTestHelper test) {
        var fixture = new Fixture(test, test.getLevel(), 240);
        try (var ignored = fixture.empty()) {
            fixture.sea(20, 12, 34);
            var drifting = SafeDeployment.Quarry.afloat(fixture.target, new Vec3(.5, 0, 0));
            var clock = new AtomicLong();
            var search = fixture.search(MonsterRole.ZOMBIE, drifting, List.of(fixture.target), clock);
            var risen = complete(search, clock);
            test.assertValueEqual(risen.status(), SafeDeployment.Status.FOUND, "A drifting quarry lets a zombie rise from the water");
            test.assertTrue(risen.submerged() && !risen.fallback(), "The landing is reported as submerged");
            double distance = risen.position().distanceTo(fixture.target);
            test.assertTrue(distance >= SafeDeployment.MIN_HUMAN_DISTANCE && distance <= SafeDeployment.SEA_RADIUS,
                    "The drowned rises 8-20 blocks away: " + distance);
            test.assertTrue(SafeDeployment.canSubmergeAt(fixture.level, fixture.hunter, risen.position()), "The whole body and two blocks above are water");
            test.assertTrue(search.revalidate(), "Unchanged water passes departure revalidation");
            fixture.level.setBlock(BlockPos.containing(risen.position()).above(3), Blocks.STONE.defaultBlockState(), 2);
            test.assertFalse(search.revalidate(), "Losing the water cover revokes departure");

            var rowingClock = new AtomicLong();
            var rowing = complete(fixture.search(MonsterRole.ZOMBIE, SafeDeployment.Quarry.afloat(fixture.target, new Vec3(8, 0, 0)),
                    List.of(fixture.target), rowingClock), rowingClock);
            test.assertValueEqual(rowing.status(), SafeDeployment.Status.FAILED, "A rowing quarry is never chased by a drowned");
            test.assertValueEqual(rowing.candidates(), 0, "Nothing is inspected while it rows; the shore is not used either");
            for (var kind : List.of(MonsterRole.SKELETON, MonsterRole.CREEPER)) {
                var waitClock = new AtomicLong();
                var waiting = complete(fixture.search(kind, drifting, List.of(fixture.target), waitClock), waitClock);
                test.assertValueEqual(waiting.status(), SafeDeployment.Status.FAILED, kind + " waits for the quarry to land instead of the shore");
                test.assertValueEqual(waiting.candidates(), 0, kind + " inspects nothing at sea");
            }
        }
        test.succeed();
    }

    @GameTest(maxTicks=400)
    public void protectionAndUnsupportedVoidCandidatesAreRejected(GameTestHelper test) {
        var fixture = new Fixture(test, test.getLevel(), 228);
        try (var ignored = fixture.empty()) { fixture.flat(Blocks.STONE, 34); }
        try (var ignored = fixture.protectedZone()) {
            var clock = new AtomicLong();
            test.assertValueEqual(complete(fixture.search(MonsterRole.ZOMBIE, SafeDeployment.Quarry.standing(fixture.target),
                    List.of(fixture.target), clock), clock).status(), SafeDeployment.Status.FAILED, "Lobby protection still guards gameplay admission");
        }
        try (var ignored = fixture.empty()) {
            fixture.flat(Blocks.AIR, 34);
            for (int x = -34; x <= 34; x++) for (int z = -34; z <= 34; z++) for (int y = -26; y < 0; y++)
                fixture.level.setBlock(fixture.feet.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            var clock = new AtomicLong();
            test.assertValueEqual(complete(fixture.search(MonsterRole.ZOMBIE, SafeDeployment.Quarry.standing(fixture.target),
                    List.of(fixture.target), clock), clock).status(), SafeDeployment.Status.FAILED, "No floor means no air or void deployment");
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void unloadedSearchWaitsSixtySecondsAndNeverLoadsCandidateChunks(GameTestHelper test) {
        var level = test.getLevel();
        var target = new Vec3(26_000_000.5, 100, 26_000_000.5);
        int chunkX = ((int)target.x) >> 4, chunkZ = ((int)target.z) >> 4;
        test.assertTrue(level.getChunkSource().getChunkNow(chunkX, chunkZ) == null, "The test destination starts unloaded");
        var clock = new AtomicLong();
        var search = new SafeDeployment.Search(level, test.makeMockServerPlayerInLevel(), MonsterRole.ZOMBIE,
                SafeDeployment.Quarry.standing(target), List.of(target), fixedRandom(), clock::get);
        var result = complete(search, clock);
        test.assertValueEqual(result.status(), SafeDeployment.Status.FAILED, "Unloaded terrain fails closed");
        test.assertTrue(clock.get() >= SafeDeployment.DEPLOY_WAIT_NANOS, "The hunt keeps waiting for the full minute");
        test.assertValueEqual(result.candidates(), 0, "No unloaded standing point is inspected");
        for (int x=chunkX-5; x<=chunkX+5; x++) for (int z=chunkZ-5; z<=chunkZ+5; z++) {
            test.assertTrue(level.getChunkSource().getChunkNow(x, z) == null, "Search did not synchronously load or generate any chunk");
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void inheritedStartBoundsTheWaitAndEachTickBoundsThePathfinding(GameTestHelper test) {
        var fixture = new Fixture(test, test.getLevel(), 238);
        try (var ignored = fixture.empty()) {
            fixture.flat(Blocks.STONE, 34);
            var clock = new AtomicLong(SafeDeployment.DEPLOY_WAIT_NANOS);
            var expired = new SafeDeployment.Search(fixture.level, fixture.hunter, MonsterRole.ZOMBIE, SafeDeployment.Quarry.standing(fixture.target),
                    List.of(fixture.target), fixedRandom(), clock::get, 0, SafeDeployment.DEPLOY_WAIT_NANOS);
            test.assertValueEqual(expired.tick().status(), SafeDeployment.Status.FAILED, "A dimension change does not restart the minute");
            test.assertValueEqual(expired.result().candidates(), 0, "An inherited expired wait admits no new work");
            var retrack = new SafeDeployment.Search(fixture.level, fixture.hunter, MonsterRole.ZOMBIE, SafeDeployment.Quarry.standing(fixture.target),
                    List.of(fixture.target), fixedRandom(), clock::get, clock.get() - SafeDeployment.RETRACK_WAIT_NANOS, SafeDeployment.RETRACK_WAIT_NANOS);
            test.assertValueEqual(retrack.tick().status(), SafeDeployment.Status.FAILED, "A retrack waits at most ten seconds");
            var unit = new AtomicLong();
            var sliced = new SafeDeployment.Search(fixture.level, fixture.hunter, MonsterRole.ZOMBIE, SafeDeployment.Quarry.standing(fixture.target),
                    List.of(fixture.target), fixedRandom(), unit::get);
            var first = sliced.tick();
            test.assertTrue(first.candidates() <= SafeDeployment.PATHS_PER_TICK, "At most four paths are computed per tick");
            boolean rejected = false;
            try { new SafeDeployment.Search(fixture.level, fixture.hunter, MonsterRole.ZOMBIE, SafeDeployment.Quarry.standing(fixture.target),
                    List.of(), fixedRandom(), unit::get, 0, 0); }
            catch (IllegalArgumentException expected) { rejected = true; }
            test.assertTrue(rejected, "A non-positive wait is rejected");
        }
        test.succeed();
    }

    /** Ticks at a fake 20 TPS until the search answers; a little over a minute is the most any search may take. */
    private static SafeDeployment.Result complete(SafeDeployment.Search search, AtomicLong clock) {
        for (int i=0; i<1400 && search.result().status() == SafeDeployment.Status.PENDING; i++) { search.tick(); clock.addAndGet(TICK); }
        if (search.result().status() == SafeDeployment.Status.PENDING) throw new IllegalStateException("Search failed to yield a bounded result");
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
            // structure origin so an earlier arena cannot leak into today's scene.
            var origin = test.absolutePos(BlockPos.ZERO);
            feet = new BlockPos(origin.getX() + 80_000 + ARENAS.getAndIncrement() * 4096, y, origin.getZ() + 80_000);
            target = Vec3.atBottomCenterOf(feet);
        }
        /** A square of floor with three blocks of air above, and solid rock below so no lower floor exists. */
        void flat(Block floor, int radius) {
            for (int x=-radius; x<=radius; x++) for (int z=-radius; z<=radius; z++) {
                var point = feet.offset(x, 0, z);
                level.setBlock(point.below(), floor.defaultBlockState(), 2);
                for (int y=0; y<4; y++) level.setBlock(point.above(y), Blocks.AIR.defaultBlockState(), 2);
                for (int y=2; y<=25; y++) level.setBlock(point.below(y), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        /** Open water {@code depth} deep within {@code radius} of the quarry, whose surface is at its feet; a stone shore beyond. */
        void sea(int radius, int depth, int shore) {
            for (int x=-shore; x<=shore; x++) for (int z=-shore; z<=shore; z++) {
                var point = feet.offset(x, 0, z);
                boolean water = x * x + z * z <= radius * radius;
                for (int y=1; y<=depth; y++) level.setBlock(point.below(y), (water ? Blocks.WATER : Blocks.STONE).defaultBlockState(), 2);
                level.setBlock(point.below(depth + 1), Blocks.STONE.defaultBlockState(), 2);
                for (int y=0; y<4; y++) level.setBlock(point.above(y), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        void firstLanding(java.util.function.IntBinaryOperator height, int clearance) {
            var center = samples().getFirst();
            for (int x=-2; x<=2; x++) for (int z=-2; z<=2; z++) {
                int dy = height.applyAsInt(x, z);
                for (int y=-6; y<=12; y++) {
                    Block block = y < dy || y >= dy + clearance ? Blocks.STONE : Blocks.AIR;
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
        SafeDeployment.Search search(MonsterRole kind, SafeDeployment.Quarry quarry, List<Vec3> humans, AtomicLong clock) {
            return new SafeDeployment.Search(level, hunter, kind, quarry, humans, fixedRandom(), clock::get);
        }
        dev.hardrush.gametest.RuntimeFixtures.Scope empty() { return scope(false); }
        dev.hardrush.gametest.RuntimeFixtures.Scope protectedZone() { return scope(true); }
        dev.hardrush.gametest.RuntimeFixtures.Scope scope(boolean enabled) {
            return dev.hardrush.gametest.RuntimeFixtures.protection(level.getServer(), new ProtectionRuntime.Access() {
                @Override public Optional<ProtectionRuntime.Zone> zone(ServerLevel value) {
                    return enabled && value == level ? Optional.of(new ProtectionRuntime.Zone(UUID.randomUUID(), target, 40, true)) : Optional.empty();
                }
                @Override public Optional<Participant> participant(UUID id) { return Optional.empty(); }
                @Override public boolean leaveProtection(UUID round, UUID id, int life) { return false; }
                @Override public void quarantine(ServerPlayer player) { throw new IllegalStateException("Search cannot move players"); }
            });
        }
    }
}
