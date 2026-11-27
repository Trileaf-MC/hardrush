package dev.hardrush.gametest;

import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.hunt.BreachLanding;
import dev.hardrush.hunt.HuntBurrows;
import dev.hardrush.hunt.SafeDeployment;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/**
 * Breaches one dig away from the quarry: pockets beside or over a prone quarry's crawlway, open air behind a hut's wall
 * or on a sky box's roof. Read-only searches on hand-built terrain with a fake clock.
 */
public final class HuntBreachGameTest {
    private static final AtomicInteger ARENAS = new AtomicInteger();
    private static final long TICK = 50_000_000L;
    private static final Vec3 EAST = new Vec3(1, 0, 0);

    /** Prone and tunnelling east: it will run into the pocket right ahead before anything overhead. */
    @GameTest(maxTicks = 200)
    public void aProneQuarryTunnellingEastMeetsThePocketAhead(GameTestHelper test) {
        var f = new Fixture(test, 60);
        try (var ignored = f.unprotected()) {
            f.crawlway(7, 6);
            var clock = new AtomicLong();
            var result = complete(f.search(MonsterRole.ZOMBIE, f.prone(EAST), clock), clock);
            test.assertValueEqual(result.status(), SafeDeployment.Status.FOUND, "A sealed crawlway yields a breach");
            test.assertTrue(result.burrowed() && !result.above(), "Tunnelling on, the pocket beside it wins: " + result);
            test.assertValueEqual(BlockPos.containing(result.position()), f.feet.east(2), "The pocket lies right ahead");
            test.assertTrue(result.crawlway(), "The quarry lies in a passage one block high");
        }
        test.succeed();
    }

    /** Prone and still: the pocket over its crawlway wins, and only the pocket is hollowed, never the floor under it. */
    @GameTest(maxTicks = 200)
    public void aProneQuarryLyingStillIsWaitedForOverhead(GameTestHelper test) {
        var f = new Fixture(test, 60);
        try (var ignored = f.unprotected()) {
            f.crawlway(7, 6);
            var clock = new AtomicLong();
            var search = f.search(MonsterRole.ZOMBIE, f.prone(Vec3.ZERO), clock);
            var result = complete(search, clock);
            test.assertValueEqual(result.status(), SafeDeployment.Status.FOUND, "A sealed crawlway yields a breach");
            var stance = BlockPos.containing(result.position());
            test.assertTrue(result.burrowed() && result.above() && result.crawlway(), "Lying still, the pocket overhead wins: " + result);
            test.assertTrue(stance.getY() == f.feet.getY() + 2 && stance.getZ() == f.feet.getZ()
                    && stance.getX() <= f.feet.getX() && stance.getX() > f.feet.getX() - 7, "Right over the crawlway: " + stance);
            HuntBurrows.carve(f.level, result.position(), f.hunter);
            test.assertTrue(f.level.getBlockState(stance).isAir() && f.level.getBlockState(stance.above()).isAir(), "Departure hollows the pocket");
            test.assertTrue(f.is(stance.below(), Blocks.STONE), "The floor under it stays for the monster to dig");
            test.assertFalse(search.revalidate(), "A hollowed pocket is no longer a breach to hand out again");
        }
        test.succeed();
    }

    /** A crawlway under a roof one block thick with open sky above: the monster lands on the roof, nothing is hollowed. */
    @GameTest(maxTicks = 200)
    public void aRoofOneBlockThickIsLandedOnInTheOpen(GameTestHelper test) {
        var f = new Fixture(test, 60);
        try (var ignored = f.unprotected()) {
            f.crawlway(7, 1);
            var clock = new AtomicLong();
            var search = f.search(MonsterRole.ZOMBIE, f.prone(Vec3.ZERO), clock);
            var result = complete(search, clock);
            test.assertValueEqual(result.status(), SafeDeployment.Status.FOUND, "A thin roof yields a breach");
            var stance = BlockPos.containing(result.position());
            test.assertTrue(result.breached() && !result.burrowed() && result.above(), "Open air on the roof, nothing to hollow: " + result);
            test.assertTrue(stance.getY() == f.feet.getY() + 2 && f.is(stance.below(), Blocks.STONE) && f.level.getBlockState(stance).isAir(),
                    "It stands on the roof over the crawlway: " + stance);
            test.assertTrue(search.revalidate(), "Unchanged terrain passes departure revalidation");
        }
        test.succeed();
    }

    /** A wooden hut on grass: the zombie lands outside its wall, out of sight, at least four blocks off. */
    @GameTest(maxTicks = 300)
    public void aWoodenHutIsBreachedFromBehindItsWall(GameTestHelper test) {
        var f = new Fixture(test, 70);
        try (var ignored = f.unprotected()) {
            f.hut(Blocks.OAK_PLANKS);
            var outside = new BreachLanding.Spot(f.feet.south(5), f.feet.south(3), false, false);
            test.assertTrue(f.breach(outside), "Grass behind a plank wall is a breach");
            var clock = new AtomicLong();
            var search = f.search(MonsterRole.ZOMBIE, SafeDeployment.Quarry.standing(f.target), clock);
            var result = complete(search, clock);
            test.assertValueEqual(result.status(), SafeDeployment.Status.FOUND, "A sealed hut yields a breach");
            test.assertTrue(result.breached() && !result.burrowed() && !result.above() && !result.fallback(),
                    "Open air beside the wall, not a pocket: " + result);
            test.assertTrue(clock.get() < SafeDeployment.FALLBACK_AFTER_NANOS, "Taken at once, not after five seconds");
            var stance = BlockPos.containing(result.position());
            int dx = Math.abs(stance.getX() - f.feet.getX()), dz = Math.abs(stance.getZ() - f.feet.getZ());
            test.assertTrue(stance.getY() == f.feet.getY() && (dx == 5 && dz <= 5 || dz == 5 && dx <= 5), "Right outside the wall: " + stance);
            test.assertTrue(result.position().distanceTo(f.target) >= SafeDeployment.BREACH_OPEN_NEAR, "At least four blocks off");
            test.assertTrue(search.revalidate(), "Unchanged terrain passes departure revalidation");
            test.assertFalse(search.revalidate(f.target, List.of(f.target, result.position().add(3.5, 0, 0))),
                    "A human closer than four blocks revokes it");
        }
        test.succeed();
    }

    /** The same hut walled with glass: whoever is inside would watch the monster land. */
    @GameTest(maxTicks = 300)
    public void aGlassWallGivesTheMonsterAway(GameTestHelper test) {
        var f = new Fixture(test, 70);
        try (var ignored = f.unprotected()) {
            f.hut(Blocks.GLASS);
            test.assertFalse(f.breach(new BreachLanding.Spot(f.feet.south(5), f.feet.south(3), false, false)), "Seen through glass");
            var clock = new AtomicLong();
            var result = complete(f.search(MonsterRole.ZOMBIE, SafeDeployment.Quarry.standing(f.target), clock), clock);
            test.assertTrue(!result.breached() && !result.burrowed(), "No breach behind glass: " + result);
        }
        test.succeed();
    }

    /** Furnaces hold things, and monsters never dig containers. */
    @GameTest(maxTicks = 300)
    public void aWallOfFurnacesCannotBeBreached(GameTestHelper test) {
        var f = new Fixture(test, 70);
        try (var ignored = f.unprotected()) {
            f.hut(Blocks.FURNACE);
            test.assertFalse(f.breach(new BreachLanding.Spot(f.feet.south(5), f.feet.south(3), false, false)), "A furnace wall is not dug");
            var clock = new AtomicLong();
            var result = complete(f.search(MonsterRole.ZOMBIE, SafeDeployment.Quarry.standing(f.target), clock), clock);
            test.assertTrue(!result.breached() && !result.burrowed(), "No breach through furnaces: " + result);
        }
        test.succeed();
    }

    /** Boxed in on top of a pillar: zombie and creeper land on the roof; an obsidian roof is too hard for either. */
    @GameTest(maxTicks = 300)
    public void aSkyBoxIsBreachedThroughItsRoof(GameTestHelper test) {
        var f = new Fixture(test, 80);
        try (var ignored = f.unprotected()) {
            f.skyBox(Blocks.COBBLESTONE);
            for (var kind : List.of(MonsterRole.ZOMBIE, MonsterRole.CREEPER)) {
                var clock = new AtomicLong();
                var result = complete(f.search(kind, SafeDeployment.Quarry.standing(f.target), clock), clock);
                test.assertValueEqual(result.status(), SafeDeployment.Status.FOUND, kind + " finds a breach on the box");
                test.assertTrue(result.breached() && result.above() && !result.burrowed(), kind + " lands in the open over the box: " + result);
                test.assertValueEqual(BlockPos.containing(result.position()), f.feet.above(3), kind + " stands on the roof");
            }
            f.fill(f.feet.offset(-1, 2, -1), f.feet.offset(1, 2, 1), Blocks.OBSIDIAN);
            var clock = new AtomicLong();
            var result = complete(f.search(MonsterRole.ZOMBIE, SafeDeployment.Quarry.standing(f.target), clock), clock);
            test.assertTrue(!result.breached() && !result.burrowed(), "Obsidian is too hard to dig: " + result);
        }
        test.succeed();
    }

    /** A creeper cannot dig its way out of a pocket, so it is never hidden in one; zombies and skeletons are. */
    @GameTest(maxTicks = 400)
    public void aCreeperIsNeverHiddenInAPocket(GameTestHelper test) {
        var f = new Fixture(test, 60);
        try (var ignored = f.unprotected()) {
            f.tunnel(7);
            for (var kind : List.of(MonsterRole.CREEPER, MonsterRole.ZOMBIE, MonsterRole.SKELETON)) {
                var clock = new AtomicLong();
                var result = complete(f.search(kind, f.digging(EAST), clock), clock);
                if (kind == MonsterRole.CREEPER) test.assertTrue(!result.burrowed() && !result.breached(), "No pocket for a creeper: " + result);
                else test.assertTrue(result.burrowed(), kind + " is hidden in a pocket: " + result);
            }
        }
        test.succeed();
    }

    /** Dropping through a ceiling is only offered while the fall to the quarry's floor is at most three blocks. */
    @GameTest(maxTicks = 300)
    public void aBreachOverheadOnlyOverAShortDrop(GameTestHelper test) {
        var f = new Fixture(test, 60);
        try (var ignored = f.unprotected()) {
            f.shaft(2);
            test.assertTrue(f.breach(new BreachLanding.Spot(f.feet.above(3), f.feet.above(), true, true)), "Two blocks of air: a breach overhead");
            var clock = new AtomicLong();
            var low = complete(f.search(MonsterRole.ZOMBIE, SafeDeployment.Quarry.standing(f.target), clock), clock);
            test.assertTrue(low.burrowed() && low.above(), "Over a short drop the still quarry is waited for overhead: " + low);
            f.shaft(3);
            test.assertFalse(f.breach(new BreachLanding.Spot(f.feet.above(4), f.feet.above(2), true, true)), "Three blocks of air: too far a drop");
            var highClock = new AtomicLong();
            var high = complete(f.search(MonsterRole.ZOMBIE, SafeDeployment.Quarry.standing(f.target), highClock), highClock);
            test.assertTrue(!high.above(), "Nothing overhead over a long drop: " + high);
        }
        test.succeed();
    }

    /** A narrow tunnel that is not sealed: walking in along its footprints still wins, from more than eight blocks back. */
    @GameTest(maxTicks = 300)
    public void anOpenTunnelIsStillWalkedInto(GameTestHelper test) {
        var f = new Fixture(test, 60);
        try (var ignored = f.unprotected()) {
            f.ground(32);
            f.tunnel(15);
            var trail = new ArrayList<Vec3>();
            for (int x = -2; x >= -22; x -= 2) trail.add(f.target.add(x, 0, 0));
            var quarry = new SafeDeployment.Quarry(f.target, f.target.add(0, 1.62, 0), -90, Vec3.ZERO, trail);
            var clock = new AtomicLong();
            var result = complete(f.search(MonsterRole.ZOMBIE, quarry, clock), clock);
            test.assertValueEqual(result.status(), SafeDeployment.Status.FOUND, "The open tunnel can be walked");
            test.assertTrue(!result.breached() && !result.burrowed() && !result.fallback() && !result.submerged(), "A walk, not a breach: " + result);
            test.assertTrue(result.position().distanceTo(f.target) >= SafeDeployment.MIN_HUMAN_DISTANCE, "From eight blocks back or more");
        }
        test.succeed();
    }

    /** Without mob griefing no breach of any kind is offered: nobody could dig through. */
    @GameTest(maxTicks = 400)
    public void withoutMobGriefingNoBreachOfAnyKind(GameTestHelper test) {
        var level = test.getLevel();
        boolean griefing = level.getGameRules().get(GameRules.MOB_GRIEFING);
        var tunnel = new Fixture(test, 60);
        var crawl = new Fixture(test, 60);
        var hut = new Fixture(test, 70);
        var box = new Fixture(test, 80);
        try (var ignored = tunnel.unprotected()) {
            tunnel.tunnel(7);
            crawl.crawlway(7, 1);
            hut.hut(Blocks.OAK_PLANKS);
            box.skyBox(Blocks.COBBLESTONE);
            record Case(String what, Fixture fixture, BreachLanding.Spot spot) {}
            var cases = List.of(
                    new Case("a pocket beside", tunnel, new BreachLanding.Spot(tunnel.feet.east(2), tunnel.feet, false, true)),
                    new Case("a pocket overhead", tunnel, new BreachLanding.Spot(tunnel.feet.above(3), tunnel.feet.above(), true, true)),
                    new Case("open air beside", hut, new BreachLanding.Spot(hut.feet.south(5), hut.feet.south(3), false, false)),
                    new Case("open air overhead", box, new BreachLanding.Spot(box.feet.above(3), box.feet.above(), true, false)),
                    new Case("open air on a crawlway's roof", crawl, new BreachLanding.Spot(crawl.feet.above(2), crawl.feet, true, false)));
            for (var c : cases) test.assertTrue(c.fixture().breach(c.spot()), "With griefing: " + c.what());
            level.getGameRules().set(GameRules.MOB_GRIEFING, false, level.getServer());
            for (var c : cases) test.assertFalse(c.fixture().breach(c.spot()), "Without griefing: " + c.what());
            for (var fixture : List.of(tunnel, hut, box)) {
                var clock = new AtomicLong();
                var result = complete(fixture.search(MonsterRole.ZOMBIE, SafeDeployment.Quarry.standing(fixture.target), clock), clock);
                test.assertTrue(!result.breached() && !result.burrowed(), "No breach is searched without griefing: " + result);
            }
        } finally {
            level.getGameRules().set(GameRules.MOB_GRIEFING, griefing, level.getServer());
        }
        test.succeed();
    }

    /** On soul sand the feet sink into the floor block; the quarry's space still starts from its body. */
    @GameTest(maxTicks = 200)
    public void aQuarryOnSoulSandStillHasItsSpace(GameTestHelper test) {
        var f = new Fixture(test, 60);
        try (var ignored = f.unprotected()) {
            f.tunnel(7);
            f.level.setBlock(f.feet.below(), Blocks.SOUL_SAND.defaultBlockState(), 2);
            var sunk = f.target.add(0, -.125, 0);
            var quarry = new SafeDeployment.Quarry(sunk, sunk.add(0, 1.62, 0), -90, Vec3.ZERO, List.of(), false, Vec3.ZERO, EAST);
            var clock = new AtomicLong();
            var search = new SafeDeployment.Search(f.level, f.hunter, MonsterRole.ZOMBIE, quarry, List.of(sunk), fixedRandom(), clock::get);
            var found = complete(search, clock);
            test.assertTrue(found.burrowed(), "Sunk into soul sand, the tunnel is still explored: " + found);
            test.assertValueEqual(BlockPos.containing(found.position()), f.feet.east(2), "The pocket ahead, as on any floor");
        }
        test.succeed();
    }

    private static SafeDeployment.Result complete(SafeDeployment.Search search, AtomicLong clock) {
        for (int i = 0; i < 1400 && search.result().status() == SafeDeployment.Status.PENDING; i++) { search.tick(); clock.addAndGet(TICK); }
        if (search.result().status() == SafeDeployment.Status.PENDING) throw new IllegalStateException("Search failed to yield a bounded result");
        return search.result();
    }

    private static Random fixedRandom() { return new Random(0) { @Override public double nextDouble() { return 0; } }; }

    private static final class Fixture {
        final ServerLevel level;
        final ServerPlayer hunter;
        final BlockPos feet;
        final Vec3 target;

        Fixture(GameTestHelper test, int y) {
            level = test.getLevel();
            hunter = test.makeMockServerPlayerInLevel();
            var origin = test.absolutePos(BlockPos.ZERO);
            feet = new BlockPos(origin.getX() + 160_000 + ARENAS.getAndIncrement() * 4096, y, origin.getZ() + 160_000);
            target = Vec3.atBottomCenterOf(feet);
        }

        void fill(BlockPos from, BlockPos to, Block block) {
            for (var pos : BlockPos.betweenClosed(from, to)) level.setBlock(pos, block.defaultBlockState(), 2);
        }
        /** Stone all round, and a sealed one-by-two tunnel running {@code length} blocks west to the quarry. */
        void tunnel(int length) {
            fill(feet.offset(-14, -4, -14), feet.offset(14, 6, 14), Blocks.STONE);
            fill(feet.offset(-length + 1, 0, 0), feet.offset(0, 1, 0), Blocks.AIR);
        }
        /**
         * A crawlway one block high running {@code length} blocks west to the quarry, under {@code cover} blocks of
         * stone; open sky above that.
         */
        void crawlway(int length, int cover) {
            fill(feet.offset(-14, -4, -14), feet.offset(14, cover, 14), Blocks.STONE);
            fill(feet.offset(-14, cover + 1, -14), feet.offset(14, cover + 8, 14), Blocks.AIR);
            fill(feet.offset(-length + 1, 0, 0), feet, Blocks.AIR);
        }
        /** Stone all round, and the quarry at the foot of a sealed one-by-one shaft {@code height} blocks high. */
        void shaft(int height) {
            fill(feet.offset(-14, -4, -14), feet.offset(14, 8, 14), Blocks.STONE);
            fill(feet, feet.above(height - 1), Blocks.AIR);
        }
        /** Open walking ground of {@code radius} around the quarry, with rock below. */
        void ground(int radius) {
            fill(feet.offset(-radius, -6, -radius), feet.offset(radius, -1, radius), Blocks.STONE);
            fill(feet.offset(-radius, 0, -radius), feet.offset(radius, 8, radius), Blocks.AIR);
        }
        /** A closed hut, inside seven by seven and three high, walled with {@code wall} and roofed with planks, on grass. */
        void hut(Block wall) {
            fill(feet.offset(-16, -6, -16), feet.offset(16, -2, 16), Blocks.DIRT);
            fill(feet.offset(-16, -1, -16), feet.offset(16, -1, 16), Blocks.GRASS_BLOCK);
            fill(feet.offset(-16, 0, -16), feet.offset(16, 8, 16), Blocks.AIR);
            fill(feet.offset(-4, 0, -4), feet.offset(4, 2, 4), wall);
            fill(feet.offset(-3, 0, -3), feet.offset(3, 2, 3), Blocks.AIR);
            fill(feet.offset(-4, 3, -4), feet.offset(4, 3, 4), Blocks.OAK_PLANKS);
        }
        /**
         * A box three by three by four of {@code block} on a pillar ten blocks over stone ground, with the quarry in its
         * one-by-two inside; air all round.
         */
        void skyBox(Block block) {
            fill(feet.offset(-14, -16, -14), feet.offset(14, -12, 14), Blocks.STONE);
            fill(feet.offset(-14, -11, -14), feet.offset(14, 8, 14), Blocks.AIR);
            fill(feet.offset(0, -11, 0), feet.offset(0, -2, 0), Blocks.COBBLESTONE);
            fill(feet.offset(-1, -1, -1), feet.offset(1, 2, 1), block);
            fill(feet, feet.above(), Blocks.AIR);
        }
        boolean is(BlockPos pos, Block block) { return level.getBlockState(pos).is(block); }
        boolean breach(BreachLanding.Spot spot) { return BreachLanding.canBreachAt(level, hunter, spot, target, List.of(target)); }
        /** Prone on dry land, facing east, tunnelling along {@code digging} (zero: not mining). */
        SafeDeployment.Quarry prone(Vec3 digging) {
            return new SafeDeployment.Quarry(target, target.add(0, .4, 0), -90, Vec3.ZERO, List.of(), false, Vec3.ZERO, digging, true);
        }
        SafeDeployment.Quarry digging(Vec3 heading) {
            return new SafeDeployment.Quarry(target, target.add(0, 1.62, 0), -90, Vec3.ZERO, List.of(), false, Vec3.ZERO, heading);
        }
        SafeDeployment.Search search(MonsterRole kind, SafeDeployment.Quarry quarry, AtomicLong clock) {
            return new SafeDeployment.Search(level, hunter, kind, quarry, List.of(quarry.position()), fixedRandom(), clock::get);
        }
        RuntimeFixtures.Scope unprotected() { return RuntimeFixtures.protection(level.getServer(), value -> false); }
    }
}
