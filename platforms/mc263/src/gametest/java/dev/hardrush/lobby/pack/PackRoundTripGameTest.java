package dev.hardrush.lobby.pack;

import static dev.hardrush.core.lobby.plan.LobbyFunction.*;

import dev.hardrush.core.lobby.plan.*;
import dev.hardrush.gametest.TestTeardown;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;
import xyz.nucleoid.fantasy.util.VoidChunkGenerator;

/**
 * World ↔ pack, end to end in throwaway void dimensions: build and mark a small lobby, scan it, capture tiles, write
 * and read a pack, upgrade, sanitize for install, place into a second fresh dimension and compare. The floor sits at
 * y = 94 so content straddles tile boundaries on every axis: x and z at 0, y at 96 (a door's halves, the marker on
 * the lectern).
 */
public final class PackRoundTripGameTest {
    private static final int FLOOR = 94, STAND = 95;
    private static final Box AREA = new Box(new Cell(-16, 80, -16), new Cell(16, 112, 16));
    private static final int MAX_SIZE = 256;
    /** 26.1.2's world version, from that release's {@code version.json}. */
    private static final int DATA_VERSION_26_1 = 4790;

    private static final Cell LECTERN = new Cell(-1, STAND, 3), SIGN = new Cell(2, STAND, -2), CHEST = new Cell(-2, STAND, -1),
            DOOR = new Cell(-1, STAND, 6), SLAB = new Cell(7, STAND, -7);

    /** GameTests tick unthrottled; chunk generation and entity IO run on worker threads by wall clock. */
    @GameTest(maxTicks = 60000)
    public void aMarkedLobbyRoundTripsThroughAPackIntoAFreshDimension(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var source = open(server);
        var target = open(server);
        var warmSource = new ChunkWarmup(source.asLevel(), AREA);
        var warmTarget = new ChunkWarmup(target.asLevel(), AREA);
        TestTeardown.atEnd(test, () -> {
            warmSource.close();
            warmTarget.close();
            source.delete();
            target.delete();
        });
        test.startSequence()
                .thenWaitUntil(() -> {
                    boolean a = warmSource.tick(), b = warmTarget.tick();
                    test.assertTrue(a && b, "Both void dimensions and their entity sections are loaded: " + warmSource.pending() + " / " + warmTarget.pending());
                })
                .thenExecute(() -> build(source.asLevel()))
                .thenExecute(() -> {
                    Path directory = null;
                    try {
                        directory = Files.createDirectories(server.getWorldPath(LevelResource.ROOT).resolve("hardrush-test")
                                .resolve("pack-roundtrip-" + UUID.randomUUID()));
                        roundTrip(test, source.asLevel(), target.asLevel(), directory);
                    } catch (IOException | PackException e) {
                        throw new IllegalStateException("Pack round trip failed", e);
                    } finally {
                        deleteRecursively(directory);
                    }
                })
                .thenSucceed();
    }

    @GameTest
    public void aMarkerItemPlacesTheSameMarkerAsSpawningOne(GameTestHelper test) {
        var level = test.getLevel();
        var spec = MarkerSpec.flood(ASSEMBLY, Set.of("minecraft:yellow_wool"));
        var spawnFloor = test.absolutePos(new BlockPos(1, 0, 1));
        var itemFloor = test.absolutePos(new BlockPos(4, 0, 1));
        level.setBlockAndUpdate(spawnFloor, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(itemFloor, Blocks.STONE.defaultBlockState());
        var spawned = MarkerStands.spawn(level, spec, spawnFloor.getX() + .5, spawnFloor.getY() + 1, spawnFloor.getZ() + .5, 0);

        Player player = test.makeMockPlayer(GameType.CREATIVE);
        var stack = MarkerStands.item(spec);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var hit = new BlockHitResult(Vec3.atCenterOf(itemFloor).add(0, .5, 0), Direction.UP, itemFloor, false);
        test.assertTrue(stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction(), "Vanilla places the marker item");
        var placed = level.getEntitiesOfClass(ArmorStand.class, new AABB(itemFloor.above()), e -> e != spawned);
        test.assertValueEqual(placed.size(), 1, "One armor stand was placed");
        var item = placed.getFirst();

        test.assertValueEqual(MarkerTags.parseMarker(item.entityTags()), new MarkerTags.Ok(spec), "The placed marker parses to the same spec");
        test.assertValueEqual(item.entityTags(), spawned.entityTags(), "Same tags");
        var a = save(spawned);
        var b = save(item);
        for (var key : MarkerStands.data(spec).keySet())
            test.assertValueEqual(b.get(key), a.get(key), "Same " + key + " whether spawned or placed from the item");
        test.assertTrue(item.isSmall() && item.isNoGravity() && !item.showBasePlate() && item.isCustomNameVisible(), "Marker look");
        test.assertValueEqual(item.getCustomName().getString(), "【集结区】", "Named after the function");
        test.assertTrue(item.getItemBySlot(EquipmentSlot.HEAD).is(Items.WOOL.lime()), "Wears the function's icon");
        test.assertTrue(!item.isPushable(), "Cannot be pushed");
        spawned.discard();
        item.discard();
        test.succeed();
    }

    private static RuntimeLevelHandle open(net.minecraft.server.MinecraftServer server) {
        var config = new RuntimeLevelConfig().setGenerator(new VoidChunkGenerator(server))
                .setDimensionType(BuiltinDimensionTypes.OVERWORLD).setDifficulty(Difficulty.PEACEFUL)
                .setMirrorOverworldGameRules(false).setShouldTickTime(false)
                .setGameRule(GameRules.SPAWN_MOBS, false).setGameRule(GameRules.RANDOM_TICK_SPEED, 0);
        var handle = Fantasy.get(server).openTemporaryLevel(config);
        handle.setTickWhenEmpty(true);
        return handle;
    }

    private static void build(ServerLevel level) {
        for (int x = -8; x <= 8; x++)
            for (int z = -8; z <= 8; z++) {
                var block = Blocks.STONE_BRICKS;
                if (sq(x + 4) + sq(z + 4) <= 4) block = Blocks.WOOL.lime();
                else if (sq(x - 4) + sq(z + 4) <= 1) block = Blocks.WOOL.red();
                level.setBlock(new BlockPos(x, FLOOR, z), block.defaultBlockState(), 3);
            }
        level.setBlock(pos(LECTERN), Blocks.LECTERN.defaultBlockState(), 3);
        level.setBlock(pos(SIGN), Blocks.OAK_SIGN.defaultBlockState(), 3);
        if (level.getBlockEntity(pos(SIGN)) instanceof SignBlockEntity sign) {
            var lines = List.<Component>of(Component.literal("大厅"), Component.literal("往返"), Component.empty(), Component.empty());
            sign.setText(new SignText(lines, lines, DyeColor.BLACK, false), SignTextSlot.FRONT);
        }
        level.setBlock(pos(CHEST), Blocks.CHEST.defaultBlockState(), 3);
        if (level.getBlockEntity(pos(CHEST)) instanceof ChestBlockEntity chest) chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
        var door = Blocks.OAK_DOOR.defaultBlockState();
        level.setBlock(pos(DOOR), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 18);
        level.setBlock(pos(DOOR).above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 18);
        // Walled in so the water stays in the slab instead of flowing over the floor.
        for (var side : Direction.Plane.HORIZONTAL) level.setBlock(pos(SLAB).relative(side), Blocks.STONE_BRICKS.defaultBlockState(), 3);
        level.setBlock(pos(SLAB), Blocks.STONE_BRICK_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED, true), 3);

        var display = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
        display.snapTo(3.5, 96.5, 0.5);
        display.setItemStack(new ItemStack(Items.WRITABLE_BOOK));
        display.addTag(MarkerTags.FUNCTION + HISTORY.id());
        level.addFreshEntity(display);

        MarkerStands.spawn(level, MarkerSpec.flood(ASSEMBLY, Set.of()), -3.5, STAND, -3.5, 0);
        MarkerStands.spawn(level, MarkerSpec.flood(ALTAR_PAD, Set.of()), 4.5, STAND, -3.5, 0);
        MarkerStands.spawn(level, MarkerSpec.box(COURTYARD, 2, 3, 2), 4.5, STAND, 4.5, 0);
        MarkerStands.spawn(level, MarkerSpec.simple(LANDING_CENTRAL, Binding.POINT), 0.5, STAND, 0.5, 90);
        MarkerStands.spawn(level, MarkerSpec.simple(LANDING_MONSTER, Binding.POINT), 5.5, STAND, 5.5, 180);
        MarkerStands.spawn(level, MarkerSpec.simple(DISPLAY_SLOT, Binding.POINT), -4.5, STAND, 4.5, 0);
        MarkerStands.spawn(level, MarkerSpec.simple(RULES, Binding.CLICK), LECTERN.x() + .5, STAND + 1, LECTERN.z() + .5, 0);
        MarkerStands.spawn(level, MarkerSpec.simple(STATUS, Binding.TEXT), SIGN.x() + .5, STAND, SIGN.z() + .5, 0);
    }

    private static void roundTrip(GameTestHelper test, ServerLevel source, ServerLevel target, Path directory)
            throws IOException, PackException {
        // Scan.
        var scan = MarkerScan.scan(source, AREA, OptionalInt.empty(), MAX_SIZE);
        var plan = scan.plan();
        test.assertTrue(plan != null, "The scan found content: " + scan.problems());
        for (var p : scan.problems()) {
            if (p.severity() == Severity.ERROR)
                test.assertTrue(p.kind() == ProblemKind.MISSING && p.function().fallback() != Fallback.NONE,
                        "Only functions with a stand-in are missing: " + p.message());
        }
        test.assertValueEqual(scan.contentBounds(), new Box(new Cell(-8, FLOOR, -8), new Cell(8, 98, 8)),
                "Bounds: the floor, the door top, the marker on the lectern and the courtyard box");
        test.assertValueEqual(floorSize(plan, ASSEMBLY), 13, "The lime disc of radius 2");
        test.assertValueEqual(floorSize(plan, ALTAR_PAD), 5, "The red cross");
        test.assertValueEqual(plan.zones(COURTYARD), List.<Zone>of(Zone.box(new Cell(4, STAND, 4), 2, 3, 2)), "Courtyard box");
        test.assertValueEqual(plan.points(LANDING_CENTRAL), List.of(new Point(0.5, STAND, 0.5, 90)), "Central landing point and facing");
        test.assertValueEqual(plan.points(LANDING_MONSTER).size(), 1, "Monster landing");
        test.assertValueEqual(plan.displayCapacity(), 1, "One display slot");
        var rules = plan.triggers(RULES);
        test.assertTrue(rules.size() == 1 && rules.getFirst().block().equals(LECTERN)
                && "minecraft:lectern".equals(rules.getFirst().blockId()), "The marker on the lectern binds the lectern: " + rules);
        var status = plan.texts(STATUS);
        test.assertTrue(status.size() == 1 && SIGN.equals(status.getFirst().sign()), "The marker in the sign's cell binds the sign: " + status);
        var history = plan.triggers(HISTORY);
        test.assertTrue(history.size() == 1 && history.getFirst().block() == null, "The tagged item display is an entity trigger: " + history);

        // The publish scan spread over steps: with no time budget, one chunk column per step, then the markers.
        var stepped = MarkerScan.begin(source, AREA, OptionalInt.empty(), MAX_SIZE);
        test.assertValueEqual(stepped.total(), 9, "The area spans 3×3 chunk columns");
        int steps = 1;
        while (!stepped.step(0)) {
            test.assertValueEqual(stepped.scanned(), steps, "One column per step");
            steps++;
        }
        test.assertValueEqual(steps, 10, "Nine column steps, then the step that resolves the markers");
        test.assertValueEqual(stepped.result().plan(), plan, "The stepped scan finds the same plan");
        test.assertValueEqual(stepped.result().problems(), scan.problems(), "The stepped scan reports the same problems");
        test.assertValueEqual(stepped.result().contentBounds(), scan.contentBounds(), "The stepped scan finds the same bounds");

        // Capture and write.
        var bounds = scan.contentBounds();
        var keys = PackCapture.tiles(bounds, OptionalInt.empty());
        test.assertValueEqual(keys.size(), 8, "Bounds touch 2×2×2 tiles");
        var tiles = new LinkedHashMap<TileKey, CompoundTag>();
        for (var key : keys) PackCapture.capture(source, key, bounds, OptionalInt.empty()).ifPresent(tile -> tiles.put(key, tile));
        test.assertValueEqual(tiles.size(), 6, "The two upper tiles over z < 0 hold nothing and are left out");
        var manifest = new PackManifest(PackManifest.FORMAT, "roundtrip", "往返测试", List.of("gametest"), 1,
                "2026-11-14T00:00:00Z", "test", PackTiles.currentDataVersion(), bounds);
        var file = directory.resolve(manifest.fileName());
        PackFile.write(file, manifest, plan, tiles);

        // Read, upgrade, sanitize, place.
        var limits = PackLimits.forServer(MAX_SIZE);
        var contents = PackFile.read(file, limits);
        test.assertValueEqual(contents.plan(), plan, "pack.json keeps the scanned plan");
        var installed = new LinkedHashMap<TileKey, CompoundTag>();
        var report = SanitizeReport.EMPTY;
        for (var entry : contents.tiles().entrySet()) {
            var tile = PackTiles.upgrade(entry.getValue(), NbtUtils.getDataVersion(entry.getValue(), contents.manifest().dataVersion()));
            report = report.plus(TileSanitizer.sanitize(tile, SanitizeMode.INSTALL));
            installed.put(entry.getKey(), tile);
        }
        TileSanitizer.checkTotals(installed.values());
        test.assertValueEqual(report.removedEntities(), 8, "Exactly the eight markers are dropped on install");
        test.assertTrue(report.removedIds().isEmpty() && report.clearedContainers() == 1, "Nothing unsafe; the chest is emptied: " + report);
        for (var entry : installed.entrySet()) PackPlacer.place(target, entry.getKey(), entry.getValue(), contents.manifest().bounds());

        // Compare.
        for (int x = bounds.min().x(); x <= bounds.max().x(); x++)
            for (int y = bounds.min().y(); y <= bounds.max().y(); y++)
                for (int z = bounds.min().z(); z <= bounds.max().z(); z++) {
                    var p = new BlockPos(x, y, z);
                    test.assertValueEqual(target.getBlockState(p), source.getBlockState(p), "Block state at " + p.toShortString());
                }
        test.assertTrue(target.getBlockState(pos(SLAB)).getValue(BlockStateProperties.WATERLOGGED)
                && target.getBlockState(pos(DOOR).above()).getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER, "Water and the upper door half survive");
        test.assertTrue(source.getBlockState(pos(SLAB).relative(Direction.UP)).isAir(), "The fixture's water stayed in its slab");
        if (!(target.getBlockEntity(pos(SIGN)) instanceof SignBlockEntity sign)) throw new IllegalStateException("The sign has its block entity");
        test.assertValueEqual(sign.getText(SignTextSlot.FRONT).getMessages(false).stream().map(Component::getString).toList(),
                List.of("大厅", "往返", "", ""), "Sign text");
        test.assertTrue(source.getBlockEntity(pos(CHEST)) instanceof ChestBlockEntity full && !full.isEmpty(), "The source chest still has its diamonds");
        test.assertTrue(target.getBlockEntity(pos(CHEST)) instanceof ChestBlockEntity empty && empty.isEmpty(), "The installed chest is empty");
        var box = new AABB(bounds.min().x(), bounds.min().y(), bounds.min().z(), bounds.max().x() + 1, bounds.max().y() + 1, bounds.max().z() + 1);
        var entities = target.getEntitiesOfClass(Entity.class, box);
        test.assertTrue(entities.stream().noneMatch(e -> e.entityTags().contains(MarkerTags.MARKER)), "No marker armor stand is installed");
        test.assertTrue(entities.size() == 1 && entities.getFirst() instanceof Display.ItemDisplay display
                && display.entityTags().contains(MarkerTags.FUNCTION + HISTORY.id()) && display.getItemStack().is(Items.WRITABLE_BOOK),
                "The tagged item display is installed: " + entities);
        var rescan = MarkerScan.scan(target, AREA, OptionalInt.empty(), MAX_SIZE);
        test.assertValueEqual(rescan.plan().triggers(HISTORY), history, "The installed entity trigger still scans the same");

        // An older tile upgrades through the DataFixer.
        var older = new LinkedHashMap<>(tiles);
        var first = older.keySet().iterator().next();
        older.put(first, NbtUtils.addDataVersion(tiles.get(first).copy(), DATA_VERSION_26_1));
        var oldFile = directory.resolve("older.hrlobby");
        PackFile.write(oldFile, manifest, plan, older);
        var oldTile = PackFile.read(oldFile, limits).tiles().get(first);
        test.assertValueEqual(NbtUtils.getDataVersion(oldTile, -1), DATA_VERSION_26_1, "The tile keeps its own data version");
        var upgraded = PackTiles.upgrade(oldTile, NbtUtils.getDataVersion(oldTile, manifest.dataVersion()));
        test.assertValueEqual(NbtUtils.getDataVersion(upgraded, -1), PackTiles.currentDataVersion(), "Stamped with this game's version");
        test.assertValueEqual(upgraded.getListOrEmpty("blocks").size(), installed.get(first).getListOrEmpty("blocks").size(), "No block lost");
        test.assertValueEqual(upgraded.get("palette"), installed.get(first).get("palette"), "Same palette after upgrading");
        PackTiles.load(target.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK), upgraded);
    }

    private static int floorSize(LobbyPlan plan, LobbyFunction f) {
        return plan.zones(f).stream().mapToInt(z -> ((Zone.Floor) z).floor().size()).sum();
    }

    private static CompoundTag save(Entity entity) {
        var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        entity.saveWithoutId(out);
        return out.buildResult();
    }

    private static int sq(int v) { return v * v; }
    private static BlockPos pos(Cell c) { return new BlockPos(c.x(), c.y(), c.z()); }

    private static void deleteRecursively(Path directory) {
        if (directory == null) return;
        try (var walk = Files.walk(directory)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover test directory is harmless.
        }
    }
}
