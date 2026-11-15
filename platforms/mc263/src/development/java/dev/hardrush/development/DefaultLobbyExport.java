package dev.hardrush.development;

import dev.hardrush.core.lobby.LegacyCampPlan;
import dev.hardrush.core.lobby.plan.*;
import dev.hardrush.lobby.pack.*;
import dev.hardrush.world.CampBlueprint;
import java.nio.file.Path;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.gamerules.GameRules;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;
import xyz.nucleoid.fantasy.util.VoidChunkGenerator;

/**
 * One-off (M2): builds the code-generated camp in a throwaway void dimension at anchor (0, 65, 0), marks it after
 * {@link LegacyCampPlan}, scans it back, checks the scan matches the legacy plan, and writes the result as the
 * built-in {@code default.hrlobby}. Started with {@code -Dhardrush.dev.exportDefaultLobby=<output path>}; stops the
 * server when done. Deleted together with {@link CampBlueprint} once the pack is committed; afterwards the default
 * lobby is edited in the workshop and its published pack replaces the resource.
 */
final class DefaultLobbyExport {
    static final String PROPERTY = "hardrush.dev.exportDefaultLobby";
    private static final BlockPos ANCHOR = new BlockPos(0, 65, 0);
    /** The camp's footprint plus a margin, so the scan proves nothing lies outside it. */
    private static final Box AREA = new Box(new Cell(-40, 56, -32), new Cell(39, 80, 31));
    private static final int MAX_SIZE = 256;

    private final MinecraftServer server;
    private final Path output;
    private RuntimeLevelHandle handle;
    private ChunkWarmup warmup;
    private int stage, wait;

    private DefaultLobbyExport(MinecraftServer server, Path output) { this.server = server; this.output = output; }

    static void register() {
        var target = System.getProperty(PROPERTY);
        if (target == null) return;
        var export = new DefaultLobbyExport[1];
        ServerLifecycleEvents.SERVER_STARTED.register(server -> export[0] = new DefaultLobbyExport(server, Path.of(target)));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (export[0] == null) return;
            try {
                if (export[0].tick()) { export[0] = null; server.halt(false); }
            } catch (Exception error) {
                HardRushDevelopment.LOGGER.error("默认大厅包导出失败", error);
                export[0].close();
                export[0] = null;
                server.halt(false);
            }
        });
    }

    /** True when finished. */
    private boolean tick() throws Exception {
        switch (stage) {
            case 0 -> {
                var config = new RuntimeLevelConfig().setGenerator(new VoidChunkGenerator(server, Biomes.PLAINS)).setFlat(true)
                        .setDimensionType(BuiltinDimensionTypes.OVERWORLD).setDifficulty(Difficulty.PEACEFUL)
                        .setMirrorOverworldGameRules(false).setShouldTickTime(false)
                        .setGameRule(GameRules.SPAWN_MOBS, false).setGameRule(GameRules.RANDOM_TICK_SPEED, 0);
                handle = Fantasy.get(server).openTemporaryLevel(config);
                handle.setTickWhenEmpty(true);
                warmup = new ChunkWarmup(handle.asLevel(), AREA);
                stage++;
            }
            case 1 -> { if (warmup.tick()) { build(handle.asLevel()); stage++; } }
            case 2 -> { if (++wait > 20) stage++; }
            case 3 -> { export(handle.asLevel()); close(); return true; }
            default -> throw new IllegalStateException();
        }
        return false;
    }

    private void close() {
        if (warmup != null) warmup.close();
        if (handle != null) handle.delete();
        warmup = null; handle = null;
    }

    /** Exactly what the old lobby construction did: the same blocks in the same order, then the sign texts. */
    private static void build(ServerLevel level) {
        for (var entry : CampBlueprint.blocks(ANCHOR).entrySet()) level.setBlockAndUpdate(entry.getKey(), entry.getValue());
        CampBlueprint.labels(ANCHOR).forEach((pos, lines) -> label(level, pos, lines));
        var plan = LegacyCampPlan.build(ANCHOR.getX(), ANCHOR.getY(), ANCHOR.getZ());
        // Zones: flood markers stand on a floor cell, the box marker at its bottom centre.
        var assembly = centre((Zone.Floor) plan.zones(LobbyFunction.ASSEMBLY).getFirst());
        marker(level, MarkerSpec.flood(LobbyFunction.ASSEMBLY, Set.of("minecraft:yellow_wool")), assembly.offset(0, 1, 0), 0);
        var pad = (Zone.Floor) plan.zones(LobbyFunction.ALTAR_PAD).getFirst();
        // Any gold cell but the one under the beacon; the flood reaches that one too.
        var gold = pad.floor().stream().min(Comparator.comparingInt(Cell::z).thenComparingInt(Cell::x)).orElseThrow();
        marker(level, MarkerSpec.flood(LobbyFunction.ALTAR_PAD, Set.of()), gold.offset(0, 1, 0), 0);
        var courtyard = ((Zone.Volume) plan.zones(LobbyFunction.COURTYARD).getFirst()).box();
        int hx = courtyard.sizeX() / 2, hz = courtyard.sizeZ() / 2;
        var bottom = new Cell(courtyard.min().x() + hx, courtyard.min().y(), courtyard.min().z() + hz);
        if (!Zone.box(bottom, hx, courtyard.sizeY() - 1, hz).box().equals(courtyard)) throw new IllegalStateException("庭院不是以某格为中心的方框");
        marker(level, MarkerSpec.box(LobbyFunction.COURTYARD, hx, courtyard.sizeY() - 1, hz), bottom, 0);
        // Points and models: the marker stands on the point.
        plan.points().forEach((function, points) -> {
            for (var p : points) spawn(level, MarkerSpec.simple(function, Binding.POINT), p.x(), p.y(), p.z(), p.yaw());
        });
        for (var model : plan.models()) {
            var p = model.point();
            spawn(level, MarkerSpec.simple(model.function(), Binding.MODEL), p.x(), p.y(), p.z(), p.yaw());
        }
        // Lecterns and vaults: on top of the block, so the marker's own cell is air and the block below is bound.
        for (var trigger : plan.triggers()) {
            var binding = trigger.function().bindings().contains(Binding.BLOCK) ? Binding.BLOCK : Binding.CLICK;
            marker(level, MarkerSpec.simple(trigger.function(), binding), trigger.block().offset(0, 1, 0), trigger.anchor().yaw());
        }
        for (var light : plan.altarLights()) marker(level, MarkerSpec.simple(LobbyFunction.ALTAR_LIGHT, Binding.BLOCK), light.offset(0, 1, 0), 0);
        // Signs: the marker stands in the sign's own cell.
        for (var text : plan.texts()) marker(level, MarkerSpec.simple(text.function(), Binding.TEXT), text.sign(), text.anchor().yaw());
    }

    private static Cell centre(Zone.Floor floor) {
        var c = floor.center();
        return floor.floor().stream().min(Comparator.comparingDouble(cell -> Math.hypot(cell.x() + .5 - c.x(), cell.z() + .5 - c.z()))).orElseThrow();
    }
    private static void marker(ServerLevel level, MarkerSpec spec, Cell at, float yaw) {
        spawn(level, spec, at.x() + .5, at.y(), at.z() + .5, yaw);
    }
    private static void spawn(ServerLevel level, MarkerSpec spec, double x, double y, double z, float yaw) {
        MarkerStands.spawn(level, spec, x, y, z, yaw);
    }

    private static void label(ServerLevel level, BlockPos pos, String[] lines) {
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) throw new IllegalStateException("没有告示牌：" + pos);
        var messages = Arrays.stream(lines).map(Component::literal).map(c -> (Component) c).toList();
        var text = new SignText(messages, messages, DyeColor.BLACK, false);
        sign.setText(text, SignTextSlot.FRONT);
        sign.setText(text, SignTextSlot.BACK);
        sign.setWaxed(true);
        sign.setChanged();
    }

    private void export(ServerLevel level) throws Exception {
        var scan = MarkerScan.scan(level, AREA, OptionalInt.empty(), MAX_SIZE);
        if (scan.hasErrors()) throw new IllegalStateException("扫描有错误：" + scan.problems());
        var expected = LegacyCampPlan.build(ANCHOR.getX(), ANCHOR.getY(), ANCHOR.getZ());
        var differences = differences(expected, scan.plan());
        if (!differences.isEmpty()) throw new IllegalStateException("扫描结果与内置营地不一致：\n" + String.join("\n", differences));
        var bounds = scan.contentBounds();
        var tiles = PackCapture.captureAll(level, bounds, OptionalInt.empty());
        var version = FabricLoader.getInstance().getModContainer("hardrush").orElseThrow().getMetadata().getVersion().getFriendlyString();
        var manifest = new PackManifest(PackManifest.FORMAT, "default", "默认营地", List.of("HardRush"), 1,
                "2026-11-15T00:00:00Z", version, PackTiles.currentDataVersion(), bounds);
        PackFile.write(output, manifest, scan.plan(), tiles);
        HardRushDevelopment.LOGGER.info("默认大厅包已写出：{}（{} 块瓦片，范围 {}，警告 {}）", output, tiles.size(), bounds, scan.problems());
    }

    /** Functions, counts and coordinates; the altar pad may also hold the gold cell under the beacon. */
    static List<String> differences(LobbyPlan expected, LobbyPlan actual) {
        var out = new ArrayList<String>();
        check(out, "bounds", expected.bounds(), actual.bounds());
        for (var f : LobbyFunction.values()) {
            check(out, f.id() + " triggers", triggers(expected, f), triggers(actual, f));
            check(out, f.id() + " models", expected.models(f).stream().map(m -> point(m.point())).toList(),
                    actual.models(f).stream().map(m -> point(m.point())).toList());
            check(out, f.id() + " points", expected.points(f).stream().map(DefaultLobbyExport::point).toList(),
                    actual.points(f).stream().map(DefaultLobbyExport::point).toList());
            check(out, f.id() + " texts", expected.texts(f).stream().map(TextSlot::sign).toList(),
                    actual.texts(f).stream().map(TextSlot::sign).toList());
            var want = expected.zones(f);
            var got = actual.zones(f);
            if (f == LobbyFunction.ALTAR_PAD && want.size() == 1 && got.size() == 1) {
                var a = ((Zone.Floor) want.getFirst()).floor();
                var b = ((Zone.Floor) got.getFirst()).floor();
                if (!b.containsAll(a) || b.size() != a.size() + 1) out.add("altar_pad: " + a + " / " + b);
            } else check(out, f.id() + " zones", want, got);
        }
        check(out, "altar lights", expected.altarLights(), actual.altarLights());
        return out;
    }
    private static List<String> triggers(LobbyPlan plan, LobbyFunction f) {
        return plan.triggers(f).stream().map(t -> t.block() + " " + t.blockId()).sorted().toList();
    }
    private static String point(Point p) { return p.x() + " " + p.y() + " " + p.z() + " " + Mth.wrapDegrees(p.yaw()); }
    private static void check(List<String> out, String what, Object want, Object got) {
        if (!Objects.equals(want, got)) out.add(what + "：期望 " + want + "，实际 " + got);
    }
}
