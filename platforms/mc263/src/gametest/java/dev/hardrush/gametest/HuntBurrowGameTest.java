package dev.hardrush.gametest;

import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.hunt.HuntBurrows;
import dev.hardrush.hunt.SafeDeployment;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/** Burrows beside a sealed tunnel: found by the read-only search after five seconds, hollowed only on departure. */
public final class HuntBurrowGameTest {
    private static final AtomicInteger ARENAS = new AtomicInteger();
    private static final long TICK = 50_000_000L;

    @GameTest(maxTicks = 200)
    public void aSealedTunnelHidesTheMonsterInABurrowAheadAfterFiveSeconds(GameTestHelper test) {
        var f = new Fixture(test, test.getLevel(), 60, Blocks.STONE);
        try (var ignored = f.unprotected()) {
            f.tunnel(7);
            var clock = new AtomicLong();
            var search = f.search(MonsterRole.ZOMBIE, f.digging(new Vec3(1, 0, 0)), List.of(f.target), clock);
            for (int i = 0; i < 90 && clock.get() < SafeDeployment.FALLBACK_AFTER_NANOS - TICK; i++) { search.tick(); clock.addAndGet(TICK); }
            test.assertValueEqual(search.result().status(), SafeDeployment.Status.PENDING, "Nothing is admitted before five seconds");
            var result = complete(search, clock);
            test.assertValueEqual(result.status(), SafeDeployment.Status.FOUND, "A sealed tunnel yields a burrow");
            test.assertTrue(result.burrowed() && !result.fallback() && !result.submerged(), "The landing is a burrow, not a fallback");
            var pocket = BlockPos.containing(result.position());
            test.assertValueEqual(pocket, f.feet.east(2), "The quarry tunnelling east meets the burrow right ahead");
            double distance = result.position().distanceTo(f.target);
            test.assertTrue(distance >= SafeDeployment.BURROW_NEAR && distance <= SafeDeployment.BURROW_FAR, "Two to ten blocks away: " + distance);
            test.assertTrue(f.is(pocket, Blocks.STONE) && f.is(pocket.above(), Blocks.STONE), "The search itself never hollows anything");
            test.assertTrue(search.revalidate(), "Unchanged terrain passes departure revalidation");
            test.assertFalse(search.revalidate(f.target, List.of(result.position().add(1.5, 0, 0))), "A human closer than two blocks revokes it");

            var facingClock = new AtomicLong();
            var facing = complete(f.search(MonsterRole.SKELETON, f.facing(90), List.of(f.target), facingClock), facingClock);
            test.assertTrue(facing.burrowed(), "Any role may be hidden; without mining the facing decides");
            test.assertTrue(BlockPos.containing(facing.position()).getX() < f.feet.getX(), "Facing west, it lies along the tunnel behind: "
                    + facing.position());

            HuntBurrows.carve(f.level, result.position(), f.hunter);
            test.assertTrue(f.level.getBlockState(pocket).isAir() && f.level.getBlockState(pocket.above()).isAir(), "Departure hollows both cells");
            test.assertTrue(f.is(f.feet.east(), Blocks.STONE) && f.is(f.feet.east().above(), Blocks.STONE), "The wall stays for the monster to dig");
            test.assertFalse(search.revalidate(), "A hollowed pocket is no longer a burrow to hand out again");
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void withoutMobGriefingNoBurrowIsOffered(GameTestHelper test) {
        var f = new Fixture(test, test.getLevel(), 60, Blocks.STONE);
        boolean griefing = f.level.getGameRules().get(GameRules.MOB_GRIEFING);
        try (var ignored = f.unprotected()) {
            f.tunnel(7);
            f.level.getGameRules().set(GameRules.MOB_GRIEFING, false, f.level.getServer());
            var clock = new AtomicLong();
            var result = complete(f.search(MonsterRole.ZOMBIE, f.digging(new Vec3(1, 0, 0)), List.of(f.target), clock), clock);
            test.assertFalse(result.burrowed(), "Nobody could dig back out, so nobody is hidden");
        } finally {
            f.level.getGameRules().set(GameRules.MOB_GRIEFING, griefing, f.level.getServer());
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void aBurrowIsSealedSafeAndOnlyInPlainGround(GameTestHelper test) {
        var f = new Fixture(test, test.getLevel(), 60, Blocks.STONE);
        try (var ignored = f.unprotected()) {
            f.tunnel(7);
            var pocket = f.feet.east(2);
            var point = Vec3.atBottomCenterOf(pocket);
            var exit = f.feet;
            test.assertTrue(f.burrow(point, exit), "The plain case is a burrow");
            record Case(String why, BlockPos pos, Block block) {}
            for (var c : List.of(
                    new Case("an ore in the pocket", pocket.above(), Blocks.IRON_ORE),
                    new Case("stone bricks in the wall", f.feet.east().above(), Blocks.STONE_BRICKS),
                    new Case("an opening beside the pocket", pocket.north(), Blocks.AIR),
                    new Case("glass beside the pocket lets it be seen", pocket.above().south(), Blocks.GLASS),
                    new Case("gravel would fall into the pocket", pocket.above(2), Blocks.GRAVEL),
                    new Case("sand would refill the opened wall", f.feet.east().above(2), Blocks.SAND),
                    new Case("water beside the wall would flood in", f.feet.east().north(), Blocks.WATER),
                    new Case("a magma floor", pocket.below(), Blocks.MAGMA_BLOCK))) {
                var previous = f.level.getBlockState(c.pos());
                f.level.setBlock(c.pos(), c.block().defaultBlockState(), 2);
                test.assertFalse(f.burrow(point, exit), "Rejected: " + c.why());
                f.level.setBlock(c.pos(), previous, 2);
            }
            test.assertTrue(f.burrow(point, exit), "Restored terrain is a burrow again");
            test.assertFalse(SafeDeployment.canBurrowAt(f.level, f.hunter, point, exit, f.target.add(-9, 0, 0), List.of()),
                    "More than ten blocks from the quarry is too far");
            test.assertFalse(SafeDeployment.canBurrowAt(f.level, f.hunter, point, exit, f.target, List.of(point.add(0, 0, 1.9))),
                    "Closer than two blocks to any human is too near");
            test.assertFalse(SafeDeployment.canBurrowAt(f.level, f.hunter, point, f.feet.west(), f.target, List.of()),
                    "The wall is exactly one block thick");
            try (var protectedZone = f.protectedLevel()) {
                test.assertFalse(f.burrow(point, exit), "Protected levels are never dug");
            }
        }
        test.succeed();
    }

    @GameTest(maxTicks = 300)
    public void theNetherAndTheEndHideMonstersInTheirOwnGround(GameTestHelper test) {
        var server = test.getLevel().getServer();
        for (var dimension : List.<ResourceKey<Level>>of(Level.NETHER, Level.END)) {
            var level = server.getLevel(dimension);
            test.assertTrue(level != null, "Dimension exists: " + dimension);
            var f = new Fixture(test, level, 70, dimension == Level.NETHER ? Blocks.NETHERRACK : Blocks.END_STONE);
            try (var ignored = f.unprotected()) {
                f.tunnel(7);
                var clock = new AtomicLong();
                var result = complete(f.search(MonsterRole.ZOMBIE, f.digging(new Vec3(1, 0, 0)), List.of(f.target), clock), clock);
                test.assertTrue(result.status() == SafeDeployment.Status.FOUND && result.burrowed(), "A burrow in " + dimension);
            }
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void aMonsterStaysHiddenUntilItsPocketOpens(GameTestHelper test) {
        var f = new Fixture(test, test.getLevel(), 60, Blocks.STONE);
        try (var ignored = f.unprotected()) {
            f.tunnel(7);
            var point = Vec3.atBottomCenterOf(f.feet.east(2));
            HuntBurrows.carve(f.level, point, f.hunter);
            f.hunter.teleportTo(f.level, point.x, point.y, point.z, java.util.Set.of(), 0, 0, true);
            HuntBurrows.entered(f.hunter, f.hunter.position());
            test.assertTrue(HuntBurrows.hidden(f.hunter), "Hidden while its pocket is sealed");
            f.level.setBlock(f.feet.east().above(), Blocks.AIR.defaultBlockState(), 2);
            test.assertFalse(HuntBurrows.hidden(f.hunter), "An opened wall ends the hiding");
            f.level.setBlock(f.feet.east().above(), Blocks.STONE.defaultBlockState(), 2);
            test.assertFalse(HuntBurrows.hidden(f.hunter), "Once out, never hidden again by that pocket");
        }
        test.succeed();
    }

    @GameTest(maxTicks = 300)
    public void aBurrowWinsOverTheClosestWalkAtFiveSeconds(GameTestHelper test) {
        var f = new Fixture(test, test.getLevel(), 60, Blocks.STONE);
        boolean griefing = f.level.getGameRules().get(GameRules.MOB_GRIEFING);
        try (var ignored = f.unprotected()) {
            // Open ground all round the sealed block: plenty of walks that end at its outside wall.
            f.ground(32);
            f.tunnel(7);
            f.level.getGameRules().set(GameRules.MOB_GRIEFING, false, f.level.getServer());
            var plainClock = new AtomicLong();
            var plain = complete(f.search(MonsterRole.ZOMBIE, f.digging(new Vec3(1, 0, 0)), List.of(f.target), plainClock), plainClock);
            test.assertTrue(plain.status() == SafeDeployment.Status.FOUND && plain.fallback() && !plain.burrowed(),
                    "Without burrows the closest walk is admitted at five seconds");
            f.level.getGameRules().set(GameRules.MOB_GRIEFING, true, f.level.getServer());
            var clock = new AtomicLong();
            var result = complete(f.search(MonsterRole.ZOMBIE, f.digging(new Vec3(1, 0, 0)), List.of(f.target), clock), clock);
            test.assertTrue(result.burrowed() && !result.fallback(), "The burrow beside the quarry beats the walk to its outside wall");
            test.assertTrue(clock.get() <= SafeDeployment.FALLBACK_AFTER_NANOS + 3 * TICK, "And answers at five seconds");
        } finally {
            f.level.getGameRules().set(GameRules.MOB_GRIEFING, griefing, f.level.getServer());
        }
        test.succeed();
    }

    @GameTest(maxTicks = 300)
    public void twoMonstersNeverShareOrTouchABurrow(GameTestHelper test) {
        var f = new Fixture(test, test.getLevel(), 60, Blocks.STONE);
        try (var ignored = f.unprotected()) {
            f.tunnel(7);
            var firstClock = new AtomicLong();
            var first = complete(f.search(MonsterRole.ZOMBIE, f.digging(new Vec3(1, 0, 0)), List.of(f.target), firstClock), firstClock);
            HuntBurrows.carve(f.level, first.position(), f.hunter);
            var secondClock = new AtomicLong();
            var second = complete(f.search(MonsterRole.ZOMBIE, f.digging(new Vec3(1, 0, 0)), List.of(f.target), secondClock), secondClock);
            test.assertTrue(first.burrowed() && second.burrowed(), "Both monsters are hidden");
            var a = BlockPos.containing(first.position());
            var b = BlockPos.containing(second.position());
            test.assertTrue(Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ()) > 1
                    && !b.equals(a.above()) && !b.above().equals(a),
                    "The second pocket neither reuses nor touches the hollowed first one: " + a + " / " + b);
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void aSoulSandFloorStillCountsAsStandingInThePocket(GameTestHelper test) {
        var f = new Fixture(test, test.getLevel(), 60, Blocks.NETHERRACK);
        try (var ignored = f.unprotected()) {
            f.tunnel(7);
            var pocket = f.feet.east(2);
            f.level.setBlock(pocket.below(), Blocks.SOUL_SAND.defaultBlockState(), 2);
            var point = Vec3.atBottomCenterOf(pocket);
            test.assertTrue(f.burrow(point, f.feet), "Soul sand is a floor a burrow may stand on");
            HuntBurrows.carve(f.level, point, f.hunter);
            f.hunter.teleportTo(f.level, point.x, point.y, point.z, java.util.Set.of(), 0, 0, true);
            HuntBurrows.entered(f.hunter, point);
            // Settled onto soul sand, the feet sink into the floor block.
            f.hunter.setPosRaw(point.x, point.y - .125, point.z);
            test.assertTrue(HuntBurrows.hidden(f.hunter), "Sunk into soul sand, it is still hidden in its pocket");
        }
        test.succeed();
    }

    private static SafeDeployment.Result complete(SafeDeployment.Search search, AtomicLong clock) {
        for (int i = 0; i < 1400 && search.result().status() == SafeDeployment.Status.PENDING; i++) { search.tick(); clock.addAndGet(TICK); }
        if (search.result().status() == SafeDeployment.Status.PENDING) throw new IllegalStateException("Search failed to yield a bounded result");
        return search.result();
    }

    private static final class Fixture {
        final ServerLevel level;
        final ServerPlayer hunter;
        final BlockPos feet;
        final Vec3 target;
        final Block ground;

        Fixture(GameTestHelper test, ServerLevel level, int y, Block ground) {
            this.level = level; this.ground = ground;
            hunter = test.makeMockServerPlayerInLevel();
            var origin = test.absolutePos(BlockPos.ZERO);
            feet = new BlockPos(origin.getX() + 120_000 + ARENAS.getAndIncrement() * 4096, y, origin.getZ() + 120_000);
            target = Vec3.atBottomCenterOf(feet);
        }

        /** Solid ground all round, and a sealed one-by-two tunnel running {@code length} blocks west to the quarry. */
        void tunnel(int length) {
            for (int x = -14; x <= 14; x++) for (int z = -14; z <= 14; z++) for (int y = -4; y <= 6; y++)
                level.setBlock(feet.offset(x, y, z), ground.defaultBlockState(), 2);
            for (int x = -length + 1; x <= 0; x++) for (int y = 0; y < 2; y++)
                level.setBlock(feet.offset(x, y, 0), Blocks.AIR.defaultBlockState(), 2);
        }
        /** Open walking ground of {@code radius} around the quarry, with rock below. */
        void ground(int radius) {
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
                for (int y = -6; y <= -1; y++) level.setBlock(feet.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = 0; y <= 8; y++) level.setBlock(feet.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        boolean is(BlockPos pos, Block block) { return level.getBlockState(pos).is(block); }
        boolean burrow(Vec3 point, BlockPos exit) { return SafeDeployment.canBurrowAt(level, hunter, point, exit, target, List.of(target)); }
        SafeDeployment.Quarry digging(Vec3 heading) {
            return new SafeDeployment.Quarry(target, target.add(0, 1.62, 0), -90, Vec3.ZERO, List.of(), false, Vec3.ZERO, heading);
        }
        SafeDeployment.Quarry facing(float yaw) {
            return new SafeDeployment.Quarry(target, target.add(0, 1.62, 0), yaw, Vec3.ZERO, List.of());
        }
        SafeDeployment.Search search(MonsterRole kind, SafeDeployment.Quarry quarry, List<Vec3> humans, AtomicLong clock) {
            return new SafeDeployment.Search(level, hunter, kind, quarry, humans,
                    new Random(0) { @Override public double nextDouble() { return 0; } }, clock::get);
        }
        RuntimeFixtures.Scope unprotected() { return RuntimeFixtures.protection(level.getServer(), value -> false); }
        RuntimeFixtures.Scope protectedLevel() { return RuntimeFixtures.protection(level.getServer(), value -> value == level); }
    }
}
