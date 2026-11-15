package dev.hardrush.lobby.pack;

import dev.hardrush.core.lobby.plan.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;

/**
 * World → {@link LobbyPlan}: reads marker armor stands and entity triggers inside an area, resolves them by the
 * position rules of design 4.2 and 5, applies the acceptance rules that need the world ({@link PlanWorldChecks}), finds the content's
 * tight bounds and finally runs {@link LobbyPlanValidator}. The caller must have loaded every chunk of {@code area}
 * including its entity sections ({@link ChunkWarmup}); the scan runs in one call.
 */
public final class MarkerScan {
    /**
     * {@code plan} is null only when nothing was found (then {@code contentBounds} is null too and a BOUNDS error is
     * reported). A non-null plan may still have errors: check {@link #hasErrors}.
     */
    public record ScanResult(LobbyPlan plan, List<Problem> problems, Box contentBounds) {
        public ScanResult { problems = List.copyOf(problems); }
        public boolean hasErrors() { return problems.stream().anyMatch(p -> p.severity() == Severity.ERROR); }
    }

    private MarkerScan() {}

    /** {@code bottomY}: blocks below it do not count toward the bounds (imported saves, design 19.1). */
    public static ScanResult scan(ServerLevel level, Box area, OptionalInt bottomY, int maxHorizontalSize) {
        return new Run(level, area, bottomY).scan(maxHorizontalSize);
    }

    /** The registry id, such as {@code minecraft:stone}. */
    static String blockId(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(); }

    static Map<String, String> properties(BlockState state) {
        var out = new LinkedHashMap<String, String>();
        for (var property : state.getProperties()) out.put(property.getName(), valueName(state, property));
        return out;
    }
    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static final Comparator<Entity> ORDER = Comparator.<Entity>comparingDouble(Entity::getY)
            .thenComparingDouble(Entity::getZ).thenComparingDouble(Entity::getX).thenComparing(Entity::getUUID);

    private static final class Run {
        final ServerLevel level;
        final Box area;
        final OptionalInt bottomY;
        final List<Problem> problems = new ArrayList<>();
        final List<Trigger> triggers = new ArrayList<>();
        final List<Model> models = new ArrayList<>();
        final Map<LobbyFunction, List<Point>> points = new EnumMap<>(LobbyFunction.class);
        final Map<LobbyFunction, List<Zone>> zones = new EnumMap<>(LobbyFunction.class);
        final List<Cell> altarLights = new ArrayList<>();
        final List<TextSlot> texts = new ArrayList<>();
        Box bounds;

        Run(ServerLevel level, Box area, OptionalInt bottomY) { this.level = level; this.area = area; this.bottomY = bottomY; }

        ScanResult scan(int maxHorizontalSize) {
            blocks();
            for (var entity : entities()) entity(entity);
            if (bounds == null) {
                problems.add(Problem.error(ProblemKind.BOUNDS, null, null, "范围内什么都没有：没有方块，也没有标记"));
                return new ScanResult(null, problems, null);
            }
            var plan = new LobbyPlan(bounds, triggers, models, points, zones, altarLights, texts);
            problems.addAll(LobbyPlanValidator.validate(plan, maxHorizontalSize));
            return new ScanResult(plan, problems, bounds);
        }

        /** Non-air blocks, a chunk section at a time; all-air sections are skipped without reading a block. */
        void blocks() {
            int minY = Math.max(area.min().y(), bottomY.orElse(Integer.MIN_VALUE));
            int maxY = area.max().y();
            if (minY > maxY) return;
            for (int cx = area.min().x() >> 4; cx <= area.max().x() >> 4; cx++)
                for (int cz = area.min().z() >> 4; cz <= area.max().z() >> 4; cz++) {
                    var chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) throw new IllegalStateException("区块未加载：" + cx + " " + cz);
                    int x0 = Math.max(area.min().x(), cx << 4), x1 = Math.min(area.max().x(), (cx << 4) + 15);
                    int z0 = Math.max(area.min().z(), cz << 4), z1 = Math.min(area.max().z(), (cz << 4) + 15);
                    int fromSection = Math.max(level.getMinSectionY(), minY >> 4), toSection = Math.min(level.getMaxSectionY(), maxY >> 4);
                    for (int sy = fromSection; sy <= toSection; sy++) {
                        var section = chunk.getSection(level.getSectionIndexFromSectionY(sy));
                        if (section.hasOnlyAir()) continue;
                        int y0 = Math.max(minY, sy << 4), y1 = Math.min(maxY, (sy << 4) + 15);
                        for (int y = y0; y <= y1; y++)
                            for (int z = z0; z <= z1; z++)
                                for (int x = x0; x <= x1; x++)
                                    if (!section.getBlockState(x & 15, y & 15, z & 15).isAir()) include(new Cell(x, y, z));
                    }
                }
        }

        /** Non-player entities whose position is inside the area, in a stable order for stable problem lists. */
        List<Entity> entities() {
            var box = new AABB(area.min().x(), area.min().y(), area.min().z(), area.max().x() + 1, area.max().y() + 1, area.max().z() + 1);
            return level.getEntitiesOfClass(Entity.class, box, e -> !(e instanceof Player) && area.contains(e.getX(), e.getY(), e.getZ()))
                    .stream().sorted(ORDER).toList();
        }

        void entity(Entity entity) {
            var tags = entity.entityTags();
            var at = point(entity);
            if (tags.contains(MarkerTags.MARKER)) {
                include(at.cell());
                switch (MarkerTags.parseMarker(tags)) {
                    case MarkerTags.Failed failed -> markerError(null, at.cell(), failed.errors());
                    case MarkerTags.Ok ok -> marker(ok.spec(), at);
                }
                return;
            }
            var parsed = MarkerTags.parseTrigger(tags);
            if (parsed.isEmpty()) {
                // Decorations installing keeps belong to the content even when they float away from any block.
                if (TileSanitizer.ALLOWED_ENTITIES.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()))
                    include(at.cell());
                return;
            }
            include(at.cell());
            switch (parsed.get()) {
                case MarkerTags.Failed failed -> markerError(null, at.cell(), failed.errors());
                case MarkerTags.Ok ok -> {
                    var function = ok.spec().function();
                    if (ok.spec().binding() == Binding.CLICK) triggers.add(new Trigger(function, null, null, at));
                    else if (entity instanceof Display.TextDisplay) texts.add(new TextSlot(function, null, at));
                    else markerError(function, at.cell(), List.of("【" + function.displayName() + "】是文字功能，只能绑定在告示牌或 text_display 实体上"));
                }
            }
        }

        void marker(MarkerSpec spec, Point at) {
            var f = spec.function();
            switch (spec.binding()) {
                case POINT -> {
                    PlanWorldChecks.standing(level, f, at, Severity.ERROR).ifPresent(problems::add);
                    points.computeIfAbsent(f, k -> new ArrayList<>()).add(at);
                }
                case MODEL -> {
                    // Models are packet entities and show even when blocked, so a bad spot only looks wrong.
                    PlanWorldChecks.standing(level, f, at, Severity.WARNING).ifPresent(problems::add);
                    models.add(new Model(f, at));
                }
                case CLICK, BLOCK, TEXT -> boundBlock(spec, at);
                case ZONE -> zone(spec, at);
            }
        }

        void boundBlock(MarkerSpec spec, Point at) {
            var f = spec.function();
            var cell = at.cell();
            if (level.getBlockState(pos(cell)).isAir()) cell = cell.offset(0, -1, 0);
            var state = level.getBlockState(pos(cell));
            if (state.isAir() || !area.contains(cell)) {
                problems.add(Problem.error(ProblemKind.BLOCK, f, at.cell(), "【" + f.displayName() + "】标记所在的格子和下面一格都是空气，没有可绑定的方块"));
                return;
            }
            var id = blockId(state);
            var anchor = new Point(cell.x() + .5, cell.y() + 1, cell.z() + .5, at.yaw());
            switch (spec.binding()) {
                case CLICK -> triggers.add(new Trigger(f, cell, id, anchor));
                case BLOCK -> {
                    var wrong = PlanWorldChecks.requirement(f, cell, state);
                    if (wrong.isPresent()) {
                        problems.add(wrong.get());
                        return;
                    }
                    if (f == LobbyFunction.ALTAR_LIGHT) altarLights.add(cell);
                    else triggers.add(new Trigger(f, cell, id, anchor));
                }
                case TEXT -> {
                    var wrong = PlanWorldChecks.sign(f, cell, state);
                    if (wrong.isPresent()) {
                        problems.add(wrong.get());
                        return;
                    }
                    texts.add(new TextSlot(f, cell, anchor));
                }
                default -> throw new IllegalStateException(spec.binding().name());
            }
        }

        void zone(MarkerSpec spec, Point at) {
            var f = spec.function();
            var cell = at.cell();
            switch (spec.shape()) {
                case FLOOD -> {
                    var result = FloodFill.fill(this::lookup, cell.offset(0, -1, 0), spec.materials(), FloodFill.LIMIT);
                    if (result.overflow()) {
                        problems.add(Problem.error(ProblemKind.ZONE, f, cell, "【" + f.displayName() + "】连片区域超过 " + FloodFill.LIMIT
                                + " 格，可能连到了大片同材质的地面；换一种地板材质或改用方框区域"));
                    }
                    zones.computeIfAbsent(f, k -> new ArrayList<>()).add(new Zone.Floor(result.cells()));
                }
                case BOX -> {
                    var box = Zone.box(cell, spec.boxX(), spec.boxY(), spec.boxZ());
                    // The box reaches into open air above its floor; that air belongs to the lobby too. Parts outside
                    // the area stay outside the bounds, so the validator reports them.
                    var inside = clip(box.box());
                    if (inside != null) include(inside);
                    zones.computeIfAbsent(f, k -> new ArrayList<>()).add(box);
                }
            }
        }

        /** Air outside the area, so a flood never leaves what the caller loaded. */
        String lookup(int x, int y, int z) {
            if (!area.contains(new Cell(x, y, z))) return BlockLookup.AIR;
            return blockId(level.getBlockState(new BlockPos(x, y, z)));
        }

        void markerError(LobbyFunction f, Cell where, List<String> errors) {
            problems.add(Problem.error(ProblemKind.MARKER, f, where, "标记有误（" + where.x() + " " + where.y() + " " + where.z() + "）："
                    + String.join("；", errors)));
        }

        void include(Cell c) { bounds = bounds == null ? new Box(c, c) : bounds.union(c); }
        void include(Box b) { bounds = bounds == null ? b : bounds.union(b); }

        Box clip(Box b) {
            var min = new Cell(Math.max(b.min().x(), area.min().x()), Math.max(b.min().y(), area.min().y()), Math.max(b.min().z(), area.min().z()));
            var max = new Cell(Math.min(b.max().x(), area.max().x()), Math.min(b.max().y(), area.max().y()), Math.min(b.max().z(), area.max().z()));
            return min.x() > max.x() || min.y() > max.y() || min.z() > max.z() ? null : new Box(min, max);
        }

        static Point point(Entity e) { return new Point(e.getX(), e.getY(), e.getZ(), e.getYRot()); }
        static BlockPos pos(Cell c) { return new BlockPos(c.x(), c.y(), c.z()); }
    }
}
