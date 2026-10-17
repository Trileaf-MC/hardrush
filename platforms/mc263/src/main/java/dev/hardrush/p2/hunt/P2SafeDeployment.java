package dev.hardrush.p2.hunt;

import dev.hardrush.p1.world.P1RoundWorldBundle;
import dev.hardrush.p2.protection.P2ProtectionRuntime;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** A server-thread, read-only search. No ticket, chunk request, terrain edit or managed wait is used. */
public final class P2SafeDeployment {
    public static final int MAX_CANDIDATES = 64;
    public static final int CANDIDATES_PER_TICK = 4;
    public static final long MAX_SEARCH_NANOS = 2_000_000_000L;
    public static final long TICK_BUDGET_NANOS = 2_000_000L;
    private static final int PATCH_RADIUS = 2;
    private static final int PATCH_CELLS = 25;

    public enum Status { PENDING, FOUND, FAILED }
    public record Result(Status status, Vec3 position, int candidates) {}
    private P2SafeDeployment() {}

    public static final class Search {
        private final ServerLevel level;
        private final RandomGenerator random;
        private final LongSupplier clock;
        private final long started;
        private final int budget;
        private final double phase;
        private Vec3 target;
        private List<Vec3> humans;
        private Status status = Status.PENDING;
        private Vec3 found;
        private Vec3 fallback;
        private int candidates;
        private int columns;
        private Candidate candidate;

        public Search(ServerLevel level, Vec3 target, List<Vec3> humans, RandomGenerator random, LongSupplier clock) {
            this(level, target, humans, random, clock, MAX_CANDIDATES, clock.getAsLong());
        }
        /** Re-searching the SAME target life inherits the original deadline and remaining candidates. */
        public Search(ServerLevel level, Vec3 target, List<Vec3> humans, RandomGenerator random,
                LongSupplier clock, int maxCandidates, long startedNanos) {
            if (maxCandidates < 1 || maxCandidates > MAX_CANDIDATES)
                throw new IllegalArgumentException("候选预算必须为 1～" + MAX_CANDIDATES);
            this.level = Objects.requireNonNull(level);
            this.target = Objects.requireNonNull(target);
            this.humans = List.copyOf(humans);
            this.random = Objects.requireNonNull(random);
            this.clock = Objects.requireNonNull(clock);
            started = startedNanos;
            budget = maxCandidates;
            phase = random.nextDouble() * Math.PI * 2;
        }
        public Search(ServerLevel level, Vec3 target, List<Vec3> humans, RandomGenerator random) {
            this(level, target, humans, random, System::nanoTime);
        }
        public Result result() { return new Result(status, found, candidates); }
        public Result tick() { return tick(target, humans); }

        /** Call once per server tick with the live target and all active humans in this dimension. */
        public Result tick(Vec3 currentTarget, List<Vec3> currentHumans) {
            target = Objects.requireNonNull(currentTarget);
            humans = List.copyOf(currentHumans);
            if (status != Status.PENDING) return result();
            long tickStarted = clock.getAsLong();
            if (expired(tickStarted)) return finish();
            int examined = 0;
            while (true) {
                long now = clock.getAsLong();
                if (expired(now)) return finish();
                if (now - tickStarted >= TICK_BUDGET_NANOS) return result();
                if (candidate == null) {
                    if (candidates == budget) return finish();
                    // Stratify the whole annulus rather than repeatedly sampling one direction.
                    double angle = phase + columns * Math.PI * (3 - Math.sqrt(5));
                    double radius = 32.5 + random.nextDouble() * 30.5;
                    int x = (int)Math.floor(target.x + Math.cos(angle) * radius);
                    int z = (int)Math.floor(target.z + Math.sin(angle) * radius);
                    columns++;
                    candidate = new Candidate(x, z, target);
                }
                if (candidate.feet == null && candidate.vertical <= 17) {
                    if (candidates == budget) return finish();
                    if (examined == CANDIDATES_PER_TICK) return result();
                    candidates++; examined++;
                }
                // Each unit checks at most one standing point or one route step. A route and
                // the 5x5 edge check may span ticks; neither monopolizes the server thread.
                if (candidate.step()) candidate = null;
                if (status == Status.FOUND) return result();
            }
        }

        /** Required immediately before teleporting: moving humans and changed terrain can revoke a result. */
        public boolean revalidate(Vec3 currentTarget, List<Vec3> currentHumans) {
            return status == Status.FOUND && allowed(level, found, currentTarget, currentHumans)
                    && safePatch(level, BlockPos.containing(found));
        }
        public boolean revalidate() { return revalidate(target, humans); }

        private boolean expired(long now) { return now - started >= MAX_SEARCH_NANOS; }
        private Result finish() {
            // A safe but disconnected island/cave is only a last resort after the reachable pass.
            if (fallback != null && allowed(level, fallback, target, humans)
                    && safePatch(level, BlockPos.containing(fallback))) {
                found = fallback; status = Status.FOUND;
            } else status = Status.FAILED;
            candidate = null;
            return result();
        }

        private final class Candidate {
            private final int x, z;
            private final Vec3 anchor;
            private int vertical;
            private BlockPos feet;
            private int patch;
            private int route;
            private BlockPos routeFeet;
            private int routeLength;

            Candidate(int x, int z, Vec3 anchor) { this.x = x; this.z = z; this.anchor = anchor; }

            /** True means this candidate is exhausted (or accepted). */
            boolean step() {
                if (feet == null) {
                    var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                    if (chunk == null || vertical > 17) return true;
                    int dy = vertical == 0 ? 0 : (vertical + 1) / 2 * (vertical % 2 == 1 ? 1 : -1);
                    int y = vertical == 17
                            ? chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1
                            : (int)Math.floor(anchor.y) + dy;
                    vertical++;
                    var proposed = new BlockPos(x, y, z);
                    if (allowed(level, Vec3.atBottomCenterOf(proposed), target, humans) && safeCell(level, proposed)) {
                        feet = proposed; patch = 0;
                    }
                    return false;
                }
                if (patch < PATCH_CELLS) {
                    var next = feet.offset(patch % 5 - PATCH_RADIUS, 0, patch / 5 - PATCH_RADIUS);
                    patch++;
                    if (!safeCell(level, next)) { feet = null; return false; }
                    return false;
                }
                if (routeFeet == null) {
                    routeFeet = BlockPos.containing(anchor);
                    routeLength = Math.max(Math.abs(x - routeFeet.getX()), Math.abs(z - routeFeet.getZ()));
                    if (!safeCell(level, routeFeet)) return keepFallback();
                }
                if (route == routeLength) {
                    if (Math.abs(routeFeet.getY() - feet.getY()) > 1) return keepFallback();
                    var point = Vec3.atBottomCenterOf(feet);
                    if (!allowed(level, point, target, humans)) return true;
                    found = point; status = Status.FOUND; return true;
                }
                route++;
                int startX = (int)Math.floor(anchor.x), startZ = (int)Math.floor(anchor.z);
                int nextX = startX + (int)Math.round((x - startX) * (double)route / routeLength);
                int nextZ = startZ + (int)Math.round((z - startZ) * (double)route / routeLength);
                // A walking corridor allows a one-block step, and never claims reachability
                // across an unloaded chunk, a wall, a portal or a lethal drop.
                for (int dy : new int[]{0, 1, -1}) {
                    var next = new BlockPos(nextX, routeFeet.getY() + dy, nextZ);
                    if (safeCell(level, next) && (dy <= 0 || clear(level, routeFeet.above(2)))) {
                        routeFeet = next; return false;
                    }
                }
                return keepFallback();
            }
            private boolean keepFallback() { if (fallback == null) fallback = Vec3.atBottomCenterOf(feet); return true; }
        }
    }

    private static boolean allowed(ServerLevel level, Vec3 point, Vec3 target, List<Vec3> humans) {
        double dx = point.x - target.x, dz = point.z - target.z;
        double distance = dx * dx + dz * dz;
        if (distance < 32 * 32 || distance > 64 * 64 || P2ProtectionRuntime.protectedAt(level, point)) return false;
        for (var human : humans) if (point.distanceToSqr(human) < 24 * 24) return false;
        return true;
    }
    private static boolean safePatch(ServerLevel level, BlockPos feet) {
        for (int x = -PATCH_RADIUS; x <= PATCH_RADIUS; x++) for (int z = -PATCH_RADIUS; z <= PATCH_RADIUS; z++) {
            if (!safeCell(level, feet.offset(x, 0, z))) return false;
        }
        return true;
    }
    private static boolean safeCell(ServerLevel level, BlockPos feet) {
        // Native dimensions and round-owned dimensions share the same dimension type contracts.
        if (level.dimensionType().hasCeiling() && feet.getY() >= level.getMinY() + level.dimensionType().logicalHeight()) return false;
        if (!P1RoundWorldBundle.safeStanding(level, feet) || !clear(level, feet)
                || !clear(level, feet.above()) || !clear(level, feet.above(2))) return false;
        var floor = level.getBlockState(feet.below());
        // Reject the arrival pad even when another isolated obsidian platform has been built.
        if (level.dimensionType().hasEnderDragonFight() && floor.is(Blocks.OBSIDIAN)) return false;
        return !floor.is(Blocks.POWDER_SNOW) && !floor.is(Blocks.POINTED_DRIPSTONE)
                && !floor.is(Blocks.END_PORTAL_FRAME)
                && !P2ProtectionRuntime.protectedAt(level, Vec3.atBottomCenterOf(feet));
    }
    private static boolean clear(ServerLevel level, BlockPos position) {
        if (!level.isInsideBuildHeight(position)
                || level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4) == null) return false;
        var state = level.getBlockState(position);
        return state.getCollisionShape(level, position).isEmpty() && level.getFluidState(position).isEmpty()
                && !state.is(Blocks.NETHER_PORTAL) && !state.is(Blocks.END_PORTAL)
                && !state.is(Blocks.END_GATEWAY) && !state.is(Blocks.FIRE) && !state.is(Blocks.SOUL_FIRE)
                && !state.is(Blocks.SWEET_BERRY_BUSH) && !state.is(Blocks.WITHER_ROSE)
                && !state.is(Blocks.POWDER_SNOW);
    }
}
