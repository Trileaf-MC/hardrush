package dev.hardrush.gametest;

import static dev.hardrush.gametest.WorkshopGameTest.enter;
import static dev.hardrush.gametest.WorkshopGameTest.op;
import static dev.hardrush.gametest.WorkshopGameTest.ready;
import static dev.hardrush.gametest.WorkshopGameTest.workshop;

import dev.hardrush.core.lobby.plan.Binding;
import dev.hardrush.core.lobby.plan.Box;
import dev.hardrush.core.lobby.plan.Cell;
import dev.hardrush.core.lobby.plan.LobbyFunction;
import dev.hardrush.core.lobby.plan.MarkerSpec;
import dev.hardrush.core.lobby.plan.MarkerTags;
import dev.hardrush.lobby.pack.MarkerScan;
import dev.hardrush.lobby.pack.MarkerStands;
import dev.hardrush.lobby.pack.imports.SampleFiles;
import dev.hardrush.lobby.workshop.Workshop;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

/**
 * Building manifests (design “建筑清单与大厅包构建” 1.7), appended to {@link WorkshopGameTest}'s sequence after
 * {@link WorkshopToolSteps}: a broken manifest discards its draft with the reason, a good one brings every binding form,
 * connected fences and sign text into a new draft, and a paste with {@code update_shapes} off leaves the fences loose.
 * Operator A starts and ends at level 2, outside any draft.
 */
final class WorkshopManifestSteps {
    /** {@link dev.hardrush.lobby.workshop.DraftImport#centredOnOrigin} for the sample building. */
    private static final Cell PLACED = new Cell(-SampleFiles.MANIFEST_W / 2, 64, -SampleFiles.MANIFEST_L / 2);
    /** Where A stands to paste: the pasted building's minimum corner is then {@link #PASTED}. */
    private static final BlockPos PASTE_AT = new BlockPos(40, 64, 0);
    private static final Cell PASTED = new Cell(PASTE_AT.getX() - SampleFiles.MANIFEST_W / 2, PASTE_AT.getY(), PASTE_AT.getZ() - SampleFiles.MANIFEST_L / 2);

    private static final String PASTE_MANIFEST = """
            {"format": 1, "update_shapes": false,
             "markers": [{"function": "page_next", "as": "click", "at": [3, 1, 1]}],
             "signs": [{"at": [5, 1, 1], "front": ["贴入"]}]}
            """;
    private static final String BROKEN_MANIFEST = """
            {"format": 1, "markers": [{"function": "lectern", "as": "click", "at": [0, 0, 0]}]}
            """;

    private static final class State {
        Set<String> before;
        String broken, camp;
    }

    private WorkshopManifestSteps() {}

    static void run(GameTestHelper test, HumanEntryGameTest.Fixture f, GameTestSequence sequence) {
        var s = new State();
        sequence.thenExecute(() -> {
                    var folder = f.server.getWorldPath(LevelResource.ROOT).resolve("hardrush/workshop/import");
                    var building = SampleFiles.manifestBuilding().tag();
                    try {
                        SampleFiles.write(folder, "camp.schem", building);
                        Files.writeString(folder.resolve("camp.hardrush.json"), SampleFiles.MANIFEST);
                        SampleFiles.write(folder, "loose.schem", building);
                        Files.writeString(folder.resolve("loose.hardrush.json"), PASTE_MANIFEST);
                        SampleFiles.write(folder, "broken.schem", building);
                        Files.writeString(folder.resolve("broken.hardrush.json"), BROKEN_MANIFEST);
                    } catch (IOException e) { throw new UncheckedIOException(e); }
                    op(f, f.a, PermissionLevel.OWNERS);
                    s.before = ids(f);
                    f.a.client.clearPackets();
                    check(test, workshop(f).create(f.a.player, new Workshop.Seed.Import("broken.schem")).isEmpty(), "Importing the broken manifest starts");
                    s.broken = added(f, s.before);
                })
                .thenWaitUntil(() -> f.await(workshop(f).draft(s.broken).isEmpty(), "A manifest mistake discards the draft"))
                .thenWaitUntil(() -> f.await(chat(f).stream().anyMatch(l -> l.contains("没能建好")), "The importer hears why"))
                .thenExecute(() -> {
                    var reason = chat(f).stream().filter(l -> l.contains("没能建好")).findFirst().orElseThrow();
                    check(test, reason.contains("broken.hardrush.json") && reason.contains("第 1 条标记") && reason.contains("未知功能 lectern"),
                            "The reason names the file, the entry and the problem: " + reason);
                    s.before = ids(f);
                    f.a.client.clearPackets();
                    check(test, workshop(f).create(f.a.player, new Workshop.Seed.Import("camp.schem")).isEmpty(), "Importing the building with its manifest starts");
                    s.camp = added(f, s.before);
                })
                .thenWaitUntil(() -> f.await(ready(f, s.camp), "The building with its manifest is a draft"))
                .thenExecute(() -> {
                    var done = chat(f).stream().filter(l -> l.contains("已建好")).findFirst().orElse("");
                    check(test, done.contains("已按清单放置 7 个标记、写了 1 块告示牌"), "The notice counts markers and signs: " + done);
                    check(test, done.contains("形状更新移除了 1 个失去支撑的方块"), "…the floating torch the shape update removed: " + done);
                    check(test, done.contains("第 2 条告示牌") && done.contains("不是告示牌"), "…and the sign entry that names no sign: " + done);
                    var entry = workshop(f).draft(s.camp).orElseThrow().entry();
                    check(test, entry != null && entry.x() == PLACED.x() + 3.5 && entry.y() == PLACED.y() + 1 && entry.z() == PLACED.z() + 2.5,
                            "The first central landing is the entry: " + entry);
                    enter(test, f, s.camp);
                    force(f, s.camp, PLACED, true);
                })
                .thenWaitUntil(() -> f.await(settled(f, s.camp, PLACED), "The imported building is loaded with its entities"))
                .thenExecute(() -> {
                    var level = draft(f, s.camp);
                    // Design 1.2: where each binding form's marker stands, in local building cells.
                    var expected = List.of(
                            new Expected(MarkerSpec.flood(LobbyFunction.ASSEMBLY, Set.of()), 0.5, 1, 0.5, 0),
                            new Expected(MarkerSpec.box(LobbyFunction.COURTYARD, 1, 1, 1), 5.5, 1, 3.5, 0),
                            new Expected(MarkerSpec.simple(LobbyFunction.LANDING_CENTRAL, Binding.POINT), 3.5, 1, 2.5, 180),
                            new Expected(MarkerSpec.simple(LobbyFunction.ROLE_ZOMBIE, Binding.MODEL), 1.5, 1, 3.5, 90),
                            // Air above the gold block: the marker stands on it.
                            new Expected(MarkerSpec.simple(LobbyFunction.RULES, Binding.CLICK), 3.5, 2, 1.5, 0),
                            // Stone above the tinted glass: the marker stands inside it.
                            new Expected(MarkerSpec.simple(LobbyFunction.ALTAR_LIGHT, Binding.BLOCK), 6.5, 1, 3.5, 0),
                            new Expected(MarkerSpec.simple(LobbyFunction.STATUS, Binding.TEXT), 5.5, 2, 1.5, 0));
                    var stands = markers(level, PLACED);
                    test.assertValueEqual(stands.size(), expected.size(), "One marker per manifest entry: " + stands);
                    for (var e : expected) {
                        var stand = stands.stream().filter(m -> m.entityTags().contains(MarkerTags.FUNCTION + e.spec.function().id())).findFirst().orElse(null);
                        check(test, stand != null, "A marker for " + e.spec.function().id());
                        check(test, MarkerTags.parseMarker(stand.entityTags()) instanceof MarkerTags.Ok ok && ok.spec().equals(e.spec),
                                e.spec.function().id() + " parses back to the manifest's spec: " + stand.entityTags());
                        double x = PLACED.x() + e.x, y = PLACED.y() + e.y, z = PLACED.z() + e.z;
                        check(test, stand.getX() == x && stand.getY() == y && stand.getZ() == z,
                                e.spec.function().id() + " stands at " + x + " " + y + " " + z + ": " + stand.position());
                        check(test, Mth.degreesDifferenceAbs(stand.getYRot(), e.yaw) < 1e-3, e.spec.function().id() + " faces " + e.yaw + ": " + stand.getYRot());
                    }
                    // The flood zone resolves to the lime wool patch: its start block's material, four cells.
                    var floor = MarkerScan.previewFlood(level, area(PLACED), new Cell(PLACED.x(), PLACED.y() + 1, PLACED.z()), Set.of()).cells();
                    test.assertValueEqual(floor, Set.of(local(PLACED, 0, 0, 0), local(PLACED, 1, 0, 0), local(PLACED, 0, 0, 1), local(PLACED, 1, 0, 1)),
                            "The flood floor is the lime wool patch");
                    // update_shapes: the fences join up, the floating torch is gone.
                    checkFences(test, level, PLACED, true);
                    check(test, level.getBlockState(pos(local(PLACED, 6, 2, 0))).isAir(), "The unsupported torch was removed");
                    var sign = (SignBlockEntity) level.getBlockEntity(pos(local(PLACED, 5, 1, 1)));
                    test.assertValueEqual(lines(sign, SignTextSlot.FRONT), List.of("清单写的字", "第二行", "", ""), "The sign's front reads the manifest");
                    test.assertValueEqual(lines(sign, SignTextSlot.BACK), List.of("", "", "", ""), "An empty back clears it");
                    check(test, !sign.isWaxed(), "The sign is not waxed");
                    check(test, level.getBlockState(pos(local(PLACED, 3, 1, 1))).is(Blocks.GOLD_BLOCK), "The non-sign entry left the gold block alone");
                    // Pasting into the draft goes through the same manifest, here without shape updates.
                    op(f, f.a, PermissionLevel.GAMEMASTERS);
                    force(f, s.camp, PASTED, true);
                    f.a.player.teleportTo(level, PASTE_AT.getX() + .5, PASTE_AT.getY(), PASTE_AT.getZ() + .5, Set.of(), 0, 0, true);
                    f.a.acknowledge();
                    f.a.client.clearPackets();
                    var pasting = workshop(f).paste(f.a.player, "loose.schem");
                    check(test, pasting.isEmpty(), "Pasting the building starts: " + pasting);
                })
                .thenWaitUntil(() -> f.await(chat(f).stream().anyMatch(l -> l.contains("建筑已贴入") || l.contains("贴入建筑失败")), "The paste finishes"))
                .thenWaitUntil(() -> f.await(settled(f, s.camp, PASTED), "The pasted building is loaded with its entities"))
                .thenExecute(() -> {
                    var done = chat(f).stream().filter(l -> l.contains("建筑已贴入") || l.contains("贴入建筑失败")).findFirst().orElse("");
                    check(test, done.contains("已按清单放置 1 个标记、写了 1 块告示牌") && !done.contains("形状更新"), "The paste follows its manifest: " + done);
                    var level = draft(f, s.camp);
                    checkFences(test, level, PASTED, false);
                    check(test, level.getBlockState(pos(local(PASTED, 6, 2, 0))).is(Blocks.TORCH), "Without update_shapes the torch keeps floating");
                    var stands = markers(level, PASTED);
                    check(test, stands.size() == 1 && stands.getFirst().entityTags().equals(MarkerTags.format(MarkerSpec.simple(LobbyFunction.PAGE_NEXT, Binding.CLICK)))
                            && stands.getFirst().getX() == PASTED.x() + 3.5 && stands.getFirst().getY() == PASTED.y() + 2 && stands.getFirst().getZ() == PASTED.z() + 1.5,
                            "The paste's marker stands on its gold block: " + stands);
                    var sign = (SignBlockEntity) level.getBlockEntity(pos(local(PASTED, 5, 1, 1)));
                    test.assertValueEqual(lines(sign, SignTextSlot.FRONT), List.of("贴入", "", "", ""), "The pasted sign reads its manifest");
                    test.assertValueEqual(lines(sign, SignTextSlot.BACK).getFirst(), "", "A side the manifest leaves out keeps what the file had");
                    force(f, s.camp, PLACED, false);
                    force(f, s.camp, PASTED, false);
                    workshop(f).leave(f.a.player, null);
                });
    }

    private record Expected(MarkerSpec spec, double x, double y, double z, float yaw) {}

    /** The three fences in a row: the middle one joins both neighbours, the ends one each, when shapes were updated. */
    private static void checkFences(GameTestHelper test, ServerLevel level, Cell placed, boolean connected) {
        for (int x = 2; x <= 4; x++) {
            var state = level.getBlockState(pos(local(placed, x, 1, 4)));
            check(test, state.is(Blocks.OAK_FENCE), "A fence at " + x + ": " + state);
            boolean west = connected && x > 2, east = connected && x < 4;
            check(test, state.getValue(FenceBlock.WEST) == west && state.getValue(FenceBlock.EAST) == east
                    && !state.getValue(FenceBlock.NORTH) && !state.getValue(FenceBlock.SOUTH), "Fence " + x + " connections: " + state);
        }
    }

    private static Set<String> ids(HumanEntryGameTest.Fixture f) {
        var out = new HashSet<String>();
        for (var d : workshop(f).drafts()) out.add(d.id());
        return out;
    }
    private static String added(HumanEntryGameTest.Fixture f, Set<String> before) {
        var now = ids(f);
        now.removeAll(before);
        if (now.size() != 1) throw new IllegalStateException("Expected one new draft: " + now);
        return now.iterator().next();
    }

    /** A's system messages since the last {@code clearPackets}. */
    private static List<String> chat(HumanEntryGameTest.Fixture f) {
        return f.a.client.packets(ClientboundSystemChatPacket.class).stream().map(p -> p.content().getString()).toList();
    }

    private static List<String> lines(SignBlockEntity sign, SignTextSlot slot) {
        return sign.getText(slot).getMessages(false).stream().map(Component::getString).toList();
    }

    private static ServerLevel draft(HumanEntryGameTest.Fixture f, String id) { return workshop(f).level(id).orElseThrow(); }
    private static Cell local(Cell placed, int x, int y, int z) { return placed.offset(x, y, z); }
    private static BlockPos pos(Cell c) { return new BlockPos(c.x(), c.y(), c.z()); }
    /** The building's cells with a margin above, where its markers stand. */
    private static Box area(Cell placed) {
        return new Box(placed, placed.offset(SampleFiles.MANIFEST_W - 1, SampleFiles.MANIFEST_H, SampleFiles.MANIFEST_L - 1));
    }
    private static List<ArmorStand> markers(ServerLevel level, Cell placed) {
        var box = area(placed);
        return level.getEntitiesOfClass(ArmorStand.class, new AABB(box.min().x(), box.min().y(), box.min().z(),
                box.max().x() + 1, box.max().y() + 1, box.max().z() + 1), MarkerStands::is);
    }

    /** Test logins load no chunks around themselves; force the building's as a client standing there would. */
    private static void force(HumanEntryGameTest.Fixture f, String id, Cell placed, boolean on) {
        var level = draft(f, id);
        var box = area(placed);
        for (int cx = box.min().x() >> 4; cx <= box.max().x() >> 4; cx++)
            for (int cz = box.min().z() >> 4; cz <= box.max().z() >> 4; cz++) level.setChunkForced(cx, cz, on);
    }
    private static boolean settled(HumanEntryGameTest.Fixture f, String id, Cell placed) {
        var level = draft(f, id);
        var box = area(placed);
        for (int cx = box.min().x() >> 4; cx <= box.max().x() >> 4; cx++)
            for (int cz = box.min().z() >> 4; cz <= box.max().z() >> 4; cz++)
                if (!level.areEntitiesLoaded(new ChunkPos(cx, cz).pack())) return false;
        return true;
    }

    private static void check(GameTestHelper test, boolean condition, String message) {
        if (!condition) System.out.println("Workshop manifest step failed: " + message);
        test.assertTrue(condition, message);
    }
}
