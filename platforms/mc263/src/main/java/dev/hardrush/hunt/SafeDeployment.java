package dev.hardrush.hunt;

import dev.hardrush.protection.ProtectionRuntime;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A server-thread, read-only search. No ticket, chunk request or terrain edit is used. */
public final class SafeDeployment {
    public static final int MIN_TARGET_DISTANCE = 24;
    public static final int MAX_TARGET_DISTANCE = 40;
    public static final int MIN_HUMAN_DISTANCE = 16;
    public static final int MAX_CANDIDATES = 64;
    public static final int CANDIDATES_PER_TICK = 4;
    public static final long MAX_SEARCH_NANOS = 2_000_000_000L;
    public static final long TICK_BUDGET_NANOS = 2_000_000L;
    private static final double EPSILON = 1.0E-7;

    public enum Status { PENDING, FOUND, FAILED }
    public record Result(Status status, Vec3 position, int candidates) {}
    private SafeDeployment() {}

    public static final class Search {
        private final ServerLevel level;
        private final ServerPlayer player;
        private final RandomGenerator random;
        private final LongSupplier clock;
        private final long started;
        private final int budget;
        private final double phase;
        private Vec3 target;
        private List<Vec3> humans;
        private Status status = Status.PENDING;
        private Vec3 found;
        private int candidates;
        private int columns;
        private int vertical;
        private int x, z;
        private double anchorY;

        public Search(ServerLevel level, ServerPlayer player, Vec3 target, List<Vec3> humans,
                RandomGenerator random, LongSupplier clock) {
            this(level, player, target, humans, random, clock, MAX_CANDIDATES, clock.getAsLong());
        }
        /** Re-searching the SAME target life inherits the original deadline and remaining candidates. */
        public Search(ServerLevel level, ServerPlayer player, Vec3 target, List<Vec3> humans,
                RandomGenerator random, LongSupplier clock, int maxCandidates, long startedNanos) {
            if (maxCandidates < 1 || maxCandidates > MAX_CANDIDATES)
                throw new IllegalArgumentException("候选预算必须为 1～" + MAX_CANDIDATES);
            this.level = Objects.requireNonNull(level);
            this.player = Objects.requireNonNull(player);
            this.target = Objects.requireNonNull(target);
            this.humans = List.copyOf(humans);
            this.random = Objects.requireNonNull(random);
            this.clock = Objects.requireNonNull(clock);
            started = startedNanos;
            budget = maxCandidates;
            phase = random.nextDouble() * Math.PI * 2;
        }
        public Result result() { return new Result(status, found, candidates); }
        public Result tick() { return tick(target, humans); }

        /** Call once per server tick with the live target and all active humans in this dimension. */
        public Result tick(Vec3 currentTarget, List<Vec3> currentHumans) {
            target = Objects.requireNonNull(currentTarget);
            humans = List.copyOf(currentHumans);
            if (status != Status.PENDING) return result();
            long tickStarted = clock.getAsLong();
            for (int examined = 0; examined < CANDIDATES_PER_TICK; examined++) {
                long now = clock.getAsLong();
                if (now - started >= MAX_SEARCH_NANOS || candidates == budget) {
                    status = Status.FAILED; return result();
                }
                if (now - tickStarted >= TICK_BUDGET_NANOS) return result();
                if (vertical == 0) {
                    double angle = phase + columns++ * Math.PI * (3 - Math.sqrt(5));
                    // Leave one block of margin for rounding to a block center.
                    double radius = MIN_TARGET_DISTANCE + 1
                            + random.nextDouble() * (MAX_TARGET_DISTANCE - MIN_TARGET_DISTANCE - 2);
                    x = Mth.floor(target.x + Math.cos(angle) * radius);
                    z = Mth.floor(target.z + Math.sin(angle) * radius);
                    anchorY = target.y;
                }
                candidates++;
                var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) { vertical = 0; continue; }
                int dy = vertical == 0 ? 0 : (vertical + 1) / 2 * (vertical % 2 == 1 ? 1 : -1);
                int y = vertical == 17
                        ? chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1
                        : Mth.floor(anchorY) + dy;
                vertical = (vertical + 1) % 18;
                var point = Vec3.atBottomCenterOf(new BlockPos(x, y, z));
                if (allowed(level, point, target, humans) && canDeployAt(level, player, point)) {
                    found = point; status = Status.FOUND; return result();
                }
            }
            if (candidates == budget) status = Status.FAILED;
            return result();
        }

        /** Re-read the player's current body, terrain and live humans immediately before teleport. */
        public boolean revalidate(Vec3 currentTarget, List<Vec3> currentHumans) {
            return status == Status.FOUND && allowed(level, found, currentTarget, currentHumans)
                    && canDeployAt(level, player, found);
        }
        public boolean revalidate() { return revalidate(target, humans); }
    }

    private static boolean allowed(ServerLevel level, Vec3 point, Vec3 target, List<Vec3> humans) {
        double dx = point.x - target.x, dz = point.z - target.z;
        double distance = dx * dx + dz * dz;
        if (distance < MIN_TARGET_DISTANCE * MIN_TARGET_DISTANCE || distance > MAX_TARGET_DISTANCE * MAX_TARGET_DISTANCE
                || ProtectionRuntime.protectedAt(level, point)) return false;
        for (var human : humans) if (point.distanceToSqr(human) < MIN_HUMAN_DISTANCE * MIN_HUMAN_DISTANCE) return false;
        return true;
    }

    /** Terrain admission only: vanilla ground semantics, relaxed to other physically supported floors.
     * Uses the real player's current body, never the visual monster's larger spawn box. */
    public static boolean canDeployAt(ServerLevel level, ServerPlayer player, Vec3 point) {
        var body = player.getBoundingBox().move(point.subtract(player.position())).deflate(EPSILON);
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

    private static boolean loaded(ServerLevel level, AABB box) {
        if (box.minY < level.getMinY() || box.maxY > level.getMaxY()) return false;
        for (int x = Mth.floor(box.minX) >> 4; x <= Mth.floor(box.maxX) >> 4; x++)
            for (int z = Mth.floor(box.minZ) >> 4; z <= Mth.floor(box.maxZ) >> 4; z++)
                if (level.getChunkSource().getChunkNow(x, z) == null) return false;
        return true;
    }
}
