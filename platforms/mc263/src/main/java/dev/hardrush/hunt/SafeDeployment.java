package dev.hardrush.hunt;

import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.protection.ProtectionRuntime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * A server-thread, read-only ambush search. No ticket, chunk request or terrain edit is used.
 * Candidates come from the quarry's own footprints, the road ahead of a runner and a ring around it;
 * vanilla walking pathfinding (no building, wooden doors open, safe drops only) decides who can actually
 * be reached within a short walk, and a score picks the best ambush among them.
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
    private static final double EPSILON = 1.0E-7;
    private static final Set<ResourceKey<Structure>> LAIRS = Set.of(BuiltinStructures.FORTRESS, BuiltinStructures.BASTION_REMNANT,
            BuiltinStructures.WOODLAND_MANSION, BuiltinStructures.MINESHAFT, BuiltinStructures.MINESHAFT_MESA,
            BuiltinStructures.DESERT_PYRAMID, BuiltinStructures.ANCIENT_CITY, BuiltinStructures.STRONGHOLD,
            BuiltinStructures.TRIAL_CHAMBERS);

    public enum Status { PENDING, FOUND, FAILED }
    public enum Source { TRAIL, AHEAD, RING, SEA }
    /** {@code submerged}: a landing under water, where a zombie rises as a drowned. */
    public record Result(Status status, Vec3 position, int candidates, boolean fallback, boolean submerged) {}

    /**
     * Everything the search may know about its quarry: where it stands and looks, how it moves and where it walked;
     * whether it is out at sea and how fast it has been travelling over the last five seconds.
     */
    public record Quarry(Vec3 position, Vec3 eye, float yaw, Vec3 velocity, List<Vec3> trail, boolean atSea, Vec3 cruise) {
        public Quarry {
            Objects.requireNonNull(position); Objects.requireNonNull(eye); Objects.requireNonNull(velocity);
            Objects.requireNonNull(cruise);
            trail = List.copyOf(trail);
        }
        public Quarry(Vec3 position, Vec3 eye, float yaw, Vec3 velocity, List<Vec3> trail) {
            this(position, eye, yaw, velocity, trail, false, velocity);
        }
        public static Quarry of(ServerPlayer player) {
            return new Quarry(player.position(), player.getEyePosition(), player.getYRot(),
                    HuntTrails.velocity(player), HuntTrails.trail(player), SafeDeployment.atSea(player), HuntTrails.velocity(player, 5));
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

    private static final class Candidate {
        final Vec3 point; final Source source;
        double score; double gap; boolean primary;
        Candidate(Vec3 point, Source source) { this.point = point; this.source = source; }
    }
    private SafeDeployment() {}

    public static final class Search {
        private final ServerLevel level;
        private final ServerPlayer player;
        private final MonsterRole kind;
        private final RandomGenerator random;
        private final LongSupplier clock;
        private final long started, wait;
        private Quarry quarry;
        private List<Vec3> humans;
        private Status status = Status.PENDING;
        private Candidate found;
        private int candidates;
        private final ArrayDeque<Candidate> queue = new ArrayDeque<>();
        private boolean sweeping;
        private long sweepStarted, nextSweep;
        private Candidate best, closest, lastClosest;
        private Quarry swept;
        private BlockPos goal;
        private PathNavigationRegion region;
        private StructureStart lair;
        private Mob agent;
        private PathFinder finder;

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
        }
        public Result result() {
            return new Result(status, found == null ? null : found.point, candidates, found != null && !found.primary,
                    found != null && found.source == Source.SEA);
        }
        public long started() { return started; }
        public ServerLevel level() { return level; }
        public Result tick() { return tick(quarry, humans); }

        /** Call once per server tick with the live quarry and all active humans in this dimension. */
        public Result tick(Quarry currentQuarry, List<Vec3> currentHumans) {
            quarry = Objects.requireNonNull(currentQuarry);
            humans = List.copyOf(currentHumans);
            if (status != Status.PENDING) return result();
            long tickStarted = clock.getAsLong();
            if (tickStarted - started >= wait) { status = Status.FAILED; return result(); }
            // The previous sweep's closest spot answers at exactly five seconds, without another full sweep.
            if (tickStarted - started >= FALLBACK_AFTER_NANOS && lastClosest != null && !quarry.atSea()) {
                if (allowed(level, lastClosest.point, quarry.position(), humans, FALLBACK_RADIUS)
                        && canDeployAt(level, player, lastClosest.point)) return found(lastClosest);
                lastClosest = null;
            }
            if (!sweeping) {
                if (tickStarted - nextSweep < 0) return result();
                begin(tickStarted);
            }
            for (int paths = 0; !queue.isEmpty() && paths < PATHS_PER_TICK; paths++) {
                if (paths > 0 && clock.getAsLong() - tickStarted >= TICK_BUDGET_NANOS) return result();
                evaluate(queue.poll());
            }
            if (queue.isEmpty()) return finish(clock.getAsLong());
            return result();
        }

        /** Re-read the player's current body, terrain and live humans immediately before teleport. */
        public boolean revalidate(Vec3 currentTarget, List<Vec3> currentHumans) {
            if (status != Status.FOUND) return false;
            if (found.source == Source.SEA) return allowed(level, found.point, currentTarget, currentHumans, SEA_RADIUS + 4)
                    && canSubmergeAt(level, player, found.point);
            return allowed(level, found.point, currentTarget, currentHumans, found.primary ? MAX_PATH + 4 : FALLBACK_RADIUS)
                    && canDeployAt(level, player, found.point);
        }
        public boolean revalidate() { return revalidate(quarry.position(), humans); }

        /** Facing the quarry, so the monster knows which way to go the moment it lands. */
        public float yaw(Vec3 target) {
            var point = found == null ? target : found.point;
            return facing(point, target);
        }

        private Result found(Candidate candidate) {
            found = candidate; status = Status.FOUND; queue.clear(); sweeping = false; return result();
        }
        private Result finish(long now) {
            sweeping = false;
            if (best != null) return found(best);
            if (closest != null) lastClosest = closest;
            if (now - started >= FALLBACK_AFTER_NANOS && closest != null) return found(closest);
            nextSweep = Math.max(now, sweepStarted + SWEEP_INTERVAL_NANOS);
            return result();
        }

        private void begin(long now) {
            sweeping = true; sweepStarted = now; best = null; closest = null; swept = quarry;
            var center = BlockPos.containing(swept.position());
            int reach = FALLBACK_RADIUS + FALLBACK_PATH + 8;
            region = new PathNavigationRegion(level, center.offset(-reach, -reach, -reach), center.offset(reach, reach, reach));
            goal = ground(region, swept.position());
            lair = lair(level, center);
            var seen = new HashSet<BlockPos>();
            var list = new ArrayList<Candidate>();
            if (swept.atSea()) {
                // Out at sea only a zombie, rising as a drowned, has a chance, and only against a slow quarry.
                // Everyone else waits: once the quarry lands, the land rules put the monster on the shore.
                if (kind == MonsterRole.ZOMBIE && swept.drifting()) {
                    double phase = random.nextDouble() * Math.PI * 2;
                    for (int i = 0; i < SEA_COLUMNS; i++)
                        water(list, seen, phase + i * Math.PI * (3 - Math.sqrt(5)),
                                MIN_HUMAN_DISTANCE + .5 + random.nextDouble() * (SEA_RADIUS - MIN_HUMAN_DISTANCE - 1));
                }
                queue.clear(); queue.addAll(list);
                return;
            }
            // Footprints: the quarry walked here, so the way back to it usually exists.
            Vec3 previous = null; int trail = 0;
            for (var step : swept.trail()) {
                if (trail == TRAIL_CANDIDATES) break;
                if (previous != null && previous.distanceToSqr(step) < 4) continue;
                if (offer(list, seen, step, Source.TRAIL)) { previous = step; trail++; }
            }
            if (swept.moving()) {
                var heading = swept.velocity().normalize();
                double base = Math.atan2(heading.z, heading.x);
                int[] distances = {12, 18, 15, 21};
                for (int i = 0; i < AHEAD_COLUMNS; i++) {
                    double angle = base + Math.toRadians((i / (double) (AHEAD_COLUMNS - 1) - .5) * 90);
                    column(list, seen, angle, distances[i % distances.length], Source.AHEAD);
                }
            }
            double phase = random.nextDouble() * Math.PI * 2;
            for (int i = 0; i < RING_COLUMNS + OUTER_COLUMNS; i++) {
                double angle = phase + i * Math.PI * (3 - Math.sqrt(5));
                double radius = i < RING_COLUMNS
                        ? MIN_HUMAN_DISTANCE + .5 + random.nextDouble() * (MAX_PATH - MIN_HUMAN_DISTANCE - 1)
                        : MAX_PATH + .5 + random.nextDouble() * (FALLBACK_RADIUS - MAX_PATH - 1);
                column(list, seen, angle, radius, Source.RING);
            }
            queue.clear(); queue.addAll(list.subList(0, Math.min(MAX_CANDIDATES, list.size())));
        }

        private void column(List<Candidate> list, Set<BlockPos> seen, double angle, double radius, Source source) {
            int x = Mth.floor(swept.position().x + Math.cos(angle) * radius);
            int z = Mth.floor(swept.position().z + Math.sin(angle) * radius);
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return;
            int qy = Mth.floor(swept.position().y);
            int top = Math.min(qy + 8, level.getMaxY() - 2), bottom = Math.max(qy - 24, level.getMinY() + 1);
            var floors = new ArrayList<BlockPos>();
            var cursor = new BlockPos.MutableBlockPos();
            for (int y = top; y >= bottom; y--) {
                cursor.set(x, y, z);
                if (empty(cursor) && empty(cursor.above()) && !empty(cursor.below())) floors.add(cursor.immutable());
            }
            floors.sort(Comparator.comparingInt(pos -> Math.abs(pos.getY() - qy)));
            for (int i = 0; i < Math.min(FLOORS_PER_COLUMN, floors.size()); i++)
                offer(list, seen, Vec3.atBottomCenterOf(floors.get(i)), source);
        }
        /** The shallowest spot in this column where a whole body, and two more blocks above it, sit in water. */
        private void water(List<Candidate> list, Set<BlockPos> seen, double angle, double radius) {
            int x = Mth.floor(swept.position().x + Math.cos(angle) * radius);
            int z = Mth.floor(swept.position().z + Math.sin(angle) * radius);
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return;
            int qy = Mth.floor(swept.position().y);
            var cursor = new BlockPos.MutableBlockPos();
            for (int y = Math.min(qy, level.getMaxY() - SEA_COVER - 3); y >= Math.max(qy - 24, level.getMinY() + 1); y--) {
                boolean covered = true;
                for (int dy = 0; dy < 2 + SEA_COVER && covered; dy++) {
                    cursor.set(x, y + dy, z);
                    covered = level.getFluidState(cursor).is(net.minecraft.tags.FluidTags.WATER) && (dy >= 2 || empty(cursor));
                }
                if (covered) { offer(list, seen, Vec3.atBottomCenterOf(new BlockPos(x, y, z)), Source.SEA, SEA_RADIUS); return; }
            }
        }
        private boolean empty(BlockPos pos) {
            return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
        }
        private boolean offer(List<Candidate> list, Set<BlockPos> seen, Vec3 point, Source source) {
            return offer(list, seen, point, source, FALLBACK_RADIUS);
        }
        private boolean offer(List<Candidate> list, Set<BlockPos> seen, Vec3 point, Source source, double radius) {
            if (!seen.add(BlockPos.containing(point))
                    || !allowed(level, point, swept.position(), humans, radius)) return false;
            list.add(new Candidate(point, source));
            return true;
        }

        private void evaluate(Candidate candidate) {
            if (candidate.source == Source.SEA) {
                // Open water between them: a drowned swims straight, so the line of sight stands in for a path.
                var eye = candidate.point.add(0, player.getEyeHeight(), 0);
                if (!canSubmergeAt(level, player, candidate.point)) return;
                candidates++;
                if (!clear(eye, swept.eye())) return;
                candidate.primary = true;
                candidate.score = score(candidate, eye.distanceTo(swept.eye()));
                if (best == null || candidate.score > best.score) best = candidate;
                return;
            }
            if (!canDeployAt(level, player, candidate.point)) return;
            candidates++;
            var path = path(candidate.point);
            double length = path != null && path.canReach() ? length(path) : Double.POSITIVE_INFINITY;
            var eye = candidate.point.add(0, player.getEyeHeight(), 0);
            boolean sight = kind == MonsterRole.SKELETON && eye.distanceTo(swept.eye()) <= SKELETON_SIGHT && clear(eye, swept.eye());
            if (length <= MAX_PATH + EPSILON || sight) {
                candidate.primary = true;
                candidate.score = score(candidate, Math.min(length, eye.distanceTo(swept.eye())));
                if (best == null || candidate.score > best.score) best = candidate;
            } else if (path != null) {
                // Closest walkable spot: a reachable long walk counts as no gap at all.
                candidate.gap = Double.isFinite(length) ? 0 : path.getDistToTarget();
                candidate.score = score(candidate, IDEAL_PATH);
                if (closest == null || candidate.gap < closest.gap - EPSILON
                        || Math.abs(candidate.gap - closest.gap) <= EPSILON && candidate.score > closest.score) closest = candidate;
            }
        }

        private double score(Candidate candidate, double walk) {
            var point = candidate.point;
            double score = 1 - Math.min(1, Math.abs(walk - IDEAL_PATH) / 10.0);
            var away = new Vec3(point.x - swept.position().x, 0, point.z - swept.position().z);
            var direction = away.lengthSqr() < EPSILON ? Vec3.ZERO : away.normalize();
            if (swept.moving()) {
                if (direction.dot(swept.velocity().normalize()) >= Math.cos(Math.PI / 4)) score += .8;
            } else {
                if (candidate.source == Source.TRAIL) score += .5;
                double yaw = Math.toRadians(swept.yaw());
                var look = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
                if (direction.dot(look) <= -.5) score += .35;
            }
            if (!clear(swept.eye(), point.add(0, player.getEyeHeight(), 0))) score += .35;
            var block = BlockPos.containing(point);
            if (lair != null && lair.getPieces().stream().anyMatch(piece -> piece.getBoundingBox().isInside(block))) score += .5;
            score += .3 * (1 - level.getMaxLocalRawBrightness(block) / 15.0);
            return score;
        }

        private Path path(Vec3 from) {
            if (agent == null) {
                var type = switch (kind) {
                    case ZOMBIE -> EntityTypes.ZOMBIE; case SKELETON -> EntityTypes.SKELETON; case CREEPER -> EntityTypes.CREEPER;
                };
                agent = type.create(level, new EntitySpawnRequest(EntitySpawnReason.COMMAND, true));
                if (agent == null) return null;
                var walker = new WalkNodeEvaluator();
                // Players open wooden doors and swim; they can neither build nor break terrain.
                walker.setCanOpenDoors(true); walker.setCanPassDoors(true); walker.setCanFloat(true);
                finder = new PathFinder(walker, MAX_VISITED_NODES);
            }
            agent.setPos(from); agent.setOnGround(true);
            return finder.findPath(region, agent, Set.of(goal), FALLBACK_PATH, 1, 1);
        }
        private boolean clear(Vec3 from, Vec3 to) {
            return region.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                    CollisionContext.empty())).getType() == HitResult.Type.MISS;
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
    private static BlockPos ground(PathNavigationRegion region, Vec3 position) {
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

    private static boolean allowed(ServerLevel level, Vec3 point, Vec3 target, List<Vec3> humans, double radius) {
        if (point.distanceToSqr(target) > radius * radius || ProtectionRuntime.protectedAt(level, point)) return false;
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

    private static boolean loaded(ServerLevel level, AABB box) {
        if (box.minY < level.getMinY() || box.maxY > level.getMaxY()) return false;
        for (int x = Mth.floor(box.minX) >> 4; x <= Mth.floor(box.maxX) >> 4; x++)
            for (int z = Mth.floor(box.minZ) >> 4; z <= Mth.floor(box.maxZ) >> 4; z++)
                if (level.getChunkSource().getChunkNow(x, z) == null) return false;
        return true;
    }
}
