package dev.hardrush.hunt;

import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.protection.ProtectionRuntime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A server-thread, read-only ambush search. No ticket, chunk request or terrain edit is used.
 * Each sweep tries its {@link LandingStrategy landing strategies} in order: at sea {@link SeaLanding}; on land
 * {@link WalkLanding}, then {@link BreachLanding}. Vanilla walking pathfinding (no building, wooden doors open, safe
 * drops only) decides who can actually be reached within a short walk, and a score picks the best ambush among them.
 */
public final class SafeDeployment {
    public static final int MIN_HUMAN_DISTANCE = 8;
    public static final int MAX_PATH = 24;
    public static final int IDEAL_PATH = 14;
    /** Vanilla skeleton bow range ({@code RangedBowAttackGoal(..., 15.0F)}). */
    public static final int SKELETON_SIGHT = 15;
    public static final int FALLBACK_RADIUS = 32;
    /** Partial paths may wander this far while looking for the closest reachable spot. */
    public static final int FALLBACK_PATH = 40;
    /** Blocks per second; a walking human (about 4.3) already counts as travelling. */
    public static final double MOVING_SPEED = 4.0;
    public static final long FALLBACK_AFTER_NANOS = 5_000_000_000L;
    public static final long DEPLOY_WAIT_NANOS = 60_000_000_000L;
    public static final long RETRACK_WAIT_NANOS = 10_000_000_000L;
    public static final long SWEEP_INTERVAL_NANOS = 2_000_000_000L;
    public static final long TICK_BUDGET_NANOS = 2_000_000L;
    public static final int PATHS_PER_TICK = 4;
    public static final int MAX_VISITED_NODES = 768;
    static final int TRAIL_CANDIDATES = 12, AHEAD_COLUMNS = 8, RING_COLUMNS = 16, OUTER_COLUMNS = 8, FLOORS_PER_COLUMN = 2;
    /** At sea a drowned may only rise from the water this close; it cannot walk, so there is no path to check. */
    public static final int SEA_RADIUS = 20;
    /** Blocks per second over five seconds: swimming, fishing, drifting or the odd nudge, not rowing. */
    public static final double DRIFTING_SPEED = 2.0;
    /** Water that must cover the head, so the drowned lurks unseen like a native one. */
    public static final int SEA_COVER = 2;
    static final int SEA_COLUMNS = 24;
    /** One sweep never queues more pathfinding than this; earlier sources (trail, ahead, inner ring) win. */
    public static final int MAX_CANDIDATES = 48;
    /** A burrow lies this far from the quarry (pocket floor to feet), and at least the near one from every human. */
    public static final double BURROW_NEAR = 2, BURROW_FAR = 10;
    static final double IDEAL_BURROW = 4;
    /** How far the quarry's own space is explored for walls, in blocks and in cells. */
    static final int BURROW_RADIUS = 11, BURROW_VISITS = 1024, BURROW_CANDIDATES = 12;
    static final double EPSILON = 1.0E-7;
    private static final Set<ResourceKey<Structure>> LAIRS = Set.of(BuiltinStructures.FORTRESS, BuiltinStructures.BASTION_REMNANT,
            BuiltinStructures.WOODLAND_MANSION, BuiltinStructures.MINESHAFT, BuiltinStructures.MINESHAFT_MESA,
            BuiltinStructures.DESERT_PYRAMID, BuiltinStructures.ANCIENT_CITY, BuiltinStructures.STRONGHOLD,
            BuiltinStructures.TRIAL_CHAMBERS);

    public enum Status { PENDING, FOUND, FAILED }
    /**
     * What kind of landing a search found. {@code WALK}: walking reaches the quarry. {@code FALLBACK}: the closest spot
     * walking could reach, after five seconds. {@code SEA}: under water, where a zombie rises as a drowned.
     * {@code BURROW}: inside a burrow that is hollowed out only at departure ({@link HuntBurrows#carve}).
     * {@code NEAR} and {@code BREACH} are not produced yet.
     */
    public enum Landing { WALK, NEAR, FALLBACK, SEA, BURROW, BREACH }
    /**
     * {@code landing} is {@code null} until something is found. {@code position} is the landing's feet (a burrow's
     * floor). {@code above}: a breach through the ceiling; {@code crawlway}: the exit's head room is solid. Both are
     * always {@code false} for now.
     */
    public record Result(Status status, Vec3 position, int candidates, Landing landing, boolean above, boolean crawlway) {
        public boolean fallback() { return landing == Landing.FALLBACK; }
        public boolean submerged() { return landing == Landing.SEA; }
        /** A pocket to hollow out at departure, announced by silverfish, where companions do not follow. */
        public boolean burrowed() { return landing == Landing.BURROW; }
        public boolean breached() { return landing == Landing.BREACH; }
    }

    /**
     * Everything the search may know about its quarry: where it stands and looks, how it moves and where it walked;
     * whether it is out at sea and how fast it has been travelling over the last five seconds; which way it is
     * tunnelling, and whether it lies prone on dry land ({@code crawling}).
     */
    public record Quarry(Vec3 position, Vec3 eye, float yaw, Vec3 velocity, List<Vec3> trail, boolean atSea, Vec3 cruise, Vec3 digging,
                         boolean crawling) {
        public Quarry {
            Objects.requireNonNull(position); Objects.requireNonNull(eye); Objects.requireNonNull(velocity);
            Objects.requireNonNull(cruise); Objects.requireNonNull(digging);
            trail = List.copyOf(trail);
        }
        public Quarry(Vec3 position, Vec3 eye, float yaw, Vec3 velocity, List<Vec3> trail, boolean atSea, Vec3 cruise, Vec3 digging) {
            this(position, eye, yaw, velocity, trail, atSea, cruise, digging, false);
        }
        public Quarry(Vec3 position, Vec3 eye, float yaw, Vec3 velocity, List<Vec3> trail, boolean atSea, Vec3 cruise) {
            this(position, eye, yaw, velocity, trail, atSea, cruise, Vec3.ZERO);
        }
        public Quarry(Vec3 position, Vec3 eye, float yaw, Vec3 velocity, List<Vec3> trail) {
            this(position, eye, yaw, velocity, trail, false, velocity);
        }
        public static Quarry of(ServerPlayer player) {
            return new Quarry(player.position(), player.getEyePosition(), player.getYRot(),
                    HuntTrails.velocity(player), HuntTrails.trail(player), SafeDeployment.atSea(player), HuntTrails.velocity(player, 5),
                    HuntTrails.digging(player), player.getPose() == Pose.SWIMMING && !player.isInWater());
        }
        /** The way it is tunnelling (its last few seconds of mining), else the way it faces; horizontal and unit length. */
        public Vec3 heading() {
            if (digging.horizontalDistanceSqr() > EPSILON) return digging.multiply(1, 0, 1).normalize();
            double radians = Math.toRadians(yaw);
            return new Vec3(-Math.sin(radians), 0, Math.cos(radians));
        }
        /** A still body without footprints, facing south. */
        public static Quarry standing(Vec3 position) {
            return new Quarry(position, position.add(0, 1.62, 0), 0, Vec3.ZERO, List.of());
        }
        /** Afloat or swimming, facing south, travelling at {@code cruise}. */
        public static Quarry afloat(Vec3 position, Vec3 cruise) {
            return new Quarry(position, position.add(0, 1.62, 0), 0, cruise, List.of(), true, cruise);
        }
        public boolean moving() { return velocity.horizontalDistance() >= MOVING_SPEED; }
        /** Slow enough at sea that a drowned rising from the water can still close in. */
        public boolean drifting() { return cruise.horizontalDistance() <= DRIFTING_SPEED; }
    }

    /** Off its feet and in the water, or aboard something floating on it. Wading on a riverbed is land. */
    public static boolean atSea(ServerPlayer player) {
        if (player.onGround()) return false;
        if (player.isInWater()) return true;
        var vehicle = player.getVehicle();
        if (vehicle == null) return false;
        var below = BlockPos.containing(vehicle.position());
        return player.level().getFluidState(below).is(net.minecraft.tags.FluidTags.WATER)
                || player.level().getFluidState(below.below()).is(net.minecraft.tags.FluidTags.WATER);
    }

    private SafeDeployment() {}

    /**
     * Times the search, paces its sweeps and drives each sweep's strategies in order. A sweep's first strategy begins
     * in the sweep's first tick; every later one begins in a tick of its own, after the one before it completed, and is
     * judged from the next tick on. A walk or sea landing is taken the moment its strategy completes; a burrow, like the
     * far fallback, only once the search is five seconds old.
     */
    public static final class Search {
        private final ServerLevel level;
        private final ServerPlayer player;
        private final MonsterRole kind;
        private final RandomGenerator random;
        private final LongSupplier clock;
        private final long started, wait;
        private final Sweep.Walker walker;
        private final SeaLanding sea;
        private final WalkLanding walk;
        private final BreachLanding breach;
        private Quarry quarry;
        private List<Vec3> humans;
        private Status status = Status.PENDING;
        private Candidate found;
        private LandingStrategy foundBy;
        private int candidates;
        /** The sweep in progress, or {@code null} between sweeps. */
        private Sweep sweep;
        private List<LandingStrategy> stages;
        private int stage;
        private boolean begun;
        private long sweepStarted, nextSweep;
        /** This sweep's landing to take at once, from the stage that found it. */
        private Candidate best;
        private LandingStrategy bestBy;
        // TODO(M2): a burrow is taken at once like any breach; burrow and lastBurrow go away with the five-second wait.
        /** This sweep's burrow and closest walkable spot, and the latest sweep's ones that answer at five seconds. */
        private Candidate burrow, lastBurrow, closest, lastClosest;

        public Search(ServerLevel level, ServerPlayer player, MonsterRole kind, Quarry quarry, List<Vec3> humans,
                      RandomGenerator random, LongSupplier clock) {
            this(level, player, kind, quarry, humans, random, clock, clock.getAsLong(), DEPLOY_WAIT_NANOS);
        }
        /** Re-searching the SAME target life (another dimension, a revoked point) inherits the original start and wait. */
        public Search(ServerLevel level, ServerPlayer player, MonsterRole kind, Quarry quarry, List<Vec3> humans,
                      RandomGenerator random, LongSupplier clock, long startedNanos, long waitNanos) {
            if (waitNanos <= 0) throw new IllegalArgumentException("等待时长必须为正");
            this.level = Objects.requireNonNull(level);
            this.player = Objects.requireNonNull(player);
            this.kind = Objects.requireNonNull(kind);
            this.quarry = Objects.requireNonNull(quarry);
            this.humans = List.copyOf(humans);
            this.random = Objects.requireNonNull(random);
            this.clock = Objects.requireNonNull(clock);
            started = startedNanos;
            wait = waitNanos;
            walker = new Sweep.Walker(level, kind);
            sea = new SeaLanding(level, player);
            walk = new WalkLanding(level, player);
            breach = new BreachLanding(level, player);
        }
        public Result result() {
            return new Result(status, found == null ? null : found.point, candidates, found == null ? null : found.landing, false, false);
        }
        public long started() { return started; }
        public ServerLevel level() { return level; }
        public Result tick() { return tick(quarry, humans); }

        /** Call once per server tick with the live quarry and all active humans in this dimension. */
        public Result tick(Quarry currentQuarry, List<Vec3> currentHumans) {
            quarry = Objects.requireNonNull(currentQuarry);
            humans = List.copyOf(currentHumans);
            if (sweep != null) sweep.humans(humans);
            if (status != Status.PENDING) return result();
            long tickStarted = clock.getAsLong();
            if (tickStarted - started >= wait) { status = Status.FAILED; return result(); }
            // The previous sweep's burrow, else its closest spot, answers at exactly five seconds, without another full sweep.
            if (tickStarted - started >= FALLBACK_AFTER_NANOS && lastBurrow != null && !quarry.atSea()) {
                if (breach.revalidate(lastBurrow, quarry.position(), humans)) return found(lastBurrow, breach);
                lastBurrow = null;
            }
            if (tickStarted - started >= FALLBACK_AFTER_NANOS && lastClosest != null && !quarry.atSea()) {
                if (walk.revalidate(lastClosest, quarry.position(), humans)) return found(lastClosest, walk);
                lastClosest = null;
            }
            if (sweep == null) {
                if (tickStarted - nextSweep < 0) return result();
                begin(tickStarted);
            }
            var strategy = stages.get(stage);
            if (!begun) {
                begun = true;
                strategy.begin(sweep);
                if (stage > 0) return result();
            }
            if (!strategy.step(sweep, tickStarted)) return result();
            return completed(strategy);
        }

        /** Re-read the player's current body, terrain and live humans immediately before teleport. */
        public boolean revalidate(Vec3 currentTarget, List<Vec3> currentHumans) {
            if (status != Status.FOUND) return false;
            return foundBy.revalidate(found, currentTarget, currentHumans);
        }
        public boolean revalidate() { return revalidate(quarry.position(), humans); }

        /** Facing the quarry, so the monster knows which way to go the moment it lands. */
        public float yaw(Vec3 target) {
            var point = found == null ? target : found.point;
            return facing(point, target);
        }

        private Result found(Candidate candidate, LandingStrategy by) {
            found = candidate; foundBy = by; status = Status.FOUND; sweep = null; return result();
        }

        private void begin(long now) {
            sweepStarted = now; best = null; bestBy = null; closest = null; burrow = null;
            sweep = new Sweep(level, player, kind, quarry, humans, random, clock, walker, () -> candidates++);
            stages = sweep.quarry.atSea() ? List.of(sea) : List.of(walk, breach);
            stage = 0; begun = false;
        }

        /** The current stage judged its last candidate: take its landing, or move on to the next stage that applies. */
        private Result completed(LandingStrategy strategy) {
            if (strategy == breach) burrow = breach.best();
            else if (strategy.best() != null) { best = strategy.best(); bestBy = strategy; }
            if (strategy == walk) closest = walk.closest();
            if (best == null) {
                while (++stage < stages.size()) {
                    if (stages.get(stage).applies(sweep)) { begun = false; return result(); }
                }
            }
            return finish(clock.getAsLong());
        }

        private Result finish(long now) {
            sweep = null;
            if (best != null) return found(best, bestBy);
            if (burrow != null) lastBurrow = burrow;
            if (closest != null) lastClosest = closest;
            if (now - started >= FALLBACK_AFTER_NANOS && burrow != null) return found(burrow, breach);
            if (now - started >= FALLBACK_AFTER_NANOS && closest != null) return found(closest, walk);
            nextSweep = Math.max(now, sweepStarted + SWEEP_INTERVAL_NANOS);
            return result();
        }
    }

    static double length(Path path) {
        double length = 0;
        for (int i = 1; i < path.getNodeCount(); i++) length += path.getNode(i - 1).distanceTo(path.getNode(i));
        return length;
    }

    static float facing(Vec3 from, Vec3 to) {
        return (float) (Mth.atan2(to.z - from.z, to.x - from.x) * Mth.RAD_TO_DEG) - 90;
    }

    /** The block the quarry stands on, even mid-jump. */
    static BlockPos ground(PathNavigationRegion region, Vec3 position) {
        var pos = BlockPos.containing(position);
        for (int i = 0; i < 3 && region.getBlockState(pos.below()).getCollisionShape(region, pos.below()).isEmpty(); i++)
            pos = pos.below();
        return pos;
    }

    /** The listed structure the quarry is inside, read only from already loaded chunks. */
    static StructureStart lair(ServerLevel level, BlockPos pos) {
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return null;
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        for (var entry : chunk.getAllReferences().entrySet()) {
            var key = registry.getResourceKey(entry.getKey());
            if (key.isEmpty() || !LAIRS.contains(key.get())) continue;
            for (long reference : entry.getValue()) {
                var owner = level.getChunkSource().getChunkNow(net.minecraft.world.level.ChunkPos.getX(reference),
                        net.minecraft.world.level.ChunkPos.getZ(reference));
                var start = owner == null ? null : owner.getStartForStructure(entry.getKey());
                if (start != null && start.isValid()
                        && start.getPieces().stream().anyMatch(piece -> piece.getBoundingBox().isInside(pos))) return start;
            }
        }
        return null;
    }

    /** Within {@code radius} of the quarry, not under a ceiling it is beneath, and at least {@link #MIN_HUMAN_DISTANCE} from every human. */
    static boolean allowed(ServerLevel level, Vec3 point, Vec3 target, List<Vec3> humans, double radius) {
        if (point.distanceToSqr(target) > radius * radius || ProtectionRuntime.protectedLevel(level)) return false;
        // Above a ceiling (the Nether bedrock roof) only when the quarry is up there too.
        int ceiling = level.getMinY() + level.dimensionType().logicalHeight();
        if (target.y < ceiling && point.y >= ceiling) return false;
        for (var human : humans) if (point.distanceToSqr(human) < MIN_HUMAN_DISTANCE * MIN_HUMAN_DISTANCE) return false;
        return true;
    }

    /** Terrain admission only: vanilla ground semantics, relaxed to other physically supported floors.
     * Uses the real player's current body, never the visual monster's larger spawn box. */
    public static boolean canDeployAt(ServerLevel level, ServerPlayer player, Vec3 point) {
        // The actual body, but never shorter than standing: a crouching hunter must be able to stand up there.
        var actual = player.getBoundingBox().move(point.subtract(player.position()));
        double standing = player.getDimensions(net.minecraft.world.entity.Pose.STANDING).height();
        var body = actual.setMaxY(Math.max(actual.maxY, actual.minY + standing)).deflate(EPSILON);
        var support = body.expandTowards(0, -1, 0);
        // Guard every column the body/support queries can read; never load a missing chunk.
        if (!loaded(level, support) || !level.getWorldBorder().isWithinBounds(BlockPos.containing(point))
                || !level.noBlockCollision(player, body) || !level.noEntityCollision(player, body)) return false;
        var below = BlockPos.containing(point).below();
        var floor = level.getBlockState(below);
        boolean vanillaGround = floor.isValidSpawn(level, below, EntityTypes.PLAYER);
        // Non-spawnable floors (e.g. glass, leaves, slabs) are also usable when the real body
        // has collision support. Do not add block blacklists to vanilla-legal ground.
        if (!vanillaGround && (EntityTypes.PLAYER.isBlockDangerous(floor) || !floor.getFluidState().isEmpty())) return false;
        if (level.noBlockCollision(player, body.move(0, -1, 0))) return false;
        for (var pos : BlockPos.betweenClosed(Mth.floor(body.minX), Mth.floor(body.minY), Mth.floor(body.minZ),
                Mth.floor(body.maxX), Mth.floor(body.maxY), Mth.floor(body.maxZ))) {
            var state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty() || EntityTypes.PLAYER.isBlockDangerous(state)) return false;
        }
        // The short settling distance onto a slab must not carry the body through lava.
        for (var pos : BlockPos.betweenClosed(Mth.floor(support.minX), Mth.floor(support.minY), Mth.floor(support.minZ),
                Mth.floor(support.maxX), Mth.floor(support.maxY), Mth.floor(support.maxZ))) {
            if (level.getFluidState(pos).is(net.minecraft.tags.FluidTags.LAVA)) return false;
        }
        return true;
    }

    /** A standing body wholly under water with {@link #SEA_COVER} more blocks of water overhead; no solid or entity in the way. */
    public static boolean canSubmergeAt(ServerLevel level, ServerPlayer player, Vec3 point) {
        double standing = player.getDimensions(net.minecraft.world.entity.Pose.STANDING).height();
        var body = new AABB(point.x - .3, point.y, point.z - .3, point.x + .3, point.y + standing, point.z + .3).deflate(EPSILON);
        var covered = body.setMaxY(Math.floor(body.maxY) + SEA_COVER + .5);
        if (!loaded(level, covered) || !level.getWorldBorder().isWithinBounds(BlockPos.containing(point))
                || !level.noBlockCollision(player, body) || !level.noEntityCollision(player, body)) return false;
        for (var pos : BlockPos.betweenClosed(Mth.floor(covered.minX), Mth.floor(covered.minY), Mth.floor(covered.minZ),
                Mth.floor(covered.maxX), Mth.floor(covered.maxY), Mth.floor(covered.maxZ)))
            if (!level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)) return false;
        return true;
    }

    static boolean loaded(ServerLevel level, AABB box) {
        if (box.minY < level.getMinY() || box.maxY > level.getMaxY()) return false;
        for (int x = Mth.floor(box.minX) >> 4; x <= Mth.floor(box.maxX) >> 4; x++)
            for (int z = Mth.floor(box.minZ) >> 4; z <= Mth.floor(box.maxZ) >> 4; z++)
                if (level.getChunkSource().getChunkNow(x, z) == null) return false;
        return true;
    }
}
