package dev.hardrush.gametest;

import static dev.hardrush.gametest.WorkshopGameTest.enter;
import static dev.hardrush.gametest.WorkshopGameTest.labels;
import static dev.hardrush.gametest.WorkshopGameTest.op;
import static dev.hardrush.gametest.WorkshopGameTest.ready;
import static dev.hardrush.gametest.WorkshopGameTest.workshop;

import dev.hardrush.HardRushProperties;
import dev.hardrush.core.lobby.plan.Binding;
import dev.hardrush.core.lobby.plan.Cell;
import dev.hardrush.core.lobby.plan.LobbyFunction;
import dev.hardrush.core.lobby.plan.MarkerSpec;
import dev.hardrush.core.lobby.plan.MarkerTags;
import dev.hardrush.core.lobby.plan.Severity;
import dev.hardrush.lobby.ButtonMenu;
import dev.hardrush.lobby.pack.MarkerStands;
import dev.hardrush.lobby.pack.PackContents;
import dev.hardrush.lobby.pack.PackFile;
import dev.hardrush.lobby.pack.PackLimits;
import dev.hardrush.lobby.pack.imports.SampleFiles;
import dev.hardrush.lobby.workshop.Toolkit;
import dev.hardrush.lobby.workshop.Workshop;
import dev.hardrush.world.LobbyDimension;
import dev.hardrush.world.LobbyWorld;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The workshop's tools, publishing and imports (design 4.4, 9, 11.5, 19), appended to {@link WorkshopGameTest}'s
 * sequence so they share its production lifecycle and logins. Operator A builds in the draft made from the current
 * lobby ({@code draft1}); the blank draft is {@code draft2}.
 */
final class WorkshopToolSteps {
    /** A work site far from the camp, outside the range publishing will use. */
    private static final BlockPos SITE = new BlockPos(100, 100, 100);
    /** Where the new rules lectern goes: inside the shrunk range, clear of the camp. */
    private static final BlockPos NEW_LECTERN = new BlockPos(45, 64, 45);
    private static final int CENTER = 8, DIAMETER = 96, BOTTOM = 60;

    private static final class State {
        WorkshopGameTest.Body before;
        BlockPos oldLectern;
        final List<Object[]> assembly = new ArrayList<>();
        Interaction box;
        Display.BlockDisplay statue;
        ArmorStand flood, volume;
        Path pack;
    }

    private WorkshopToolSteps() {}

    static void run(GameTestHelper test, HumanEntryGameTest.Fixture f, GameTestSequence sequence) {
        var s = new State();
        sequence.thenExecute(() -> {
                    s.before = WorkshopGameTest.Body.of(f.a.player);
                    s.oldLectern = LobbyPlanFixtures.block(f.server, LobbyFunction.RULES);
                    enter(test, f, "draft1");
                    teleport(f, SITE.getX() + .5, SITE.getY() + 1, SITE.getZ() + 3.5);
                    // Test logins load no chunks around themselves; force the work site as a real client's view would.
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                        draft(f, "draft1").setChunkForced((SITE.getX() >> 4) + dx, (SITE.getZ() >> 4) + dz, true);
                })
                // Entities only join a chunk whose entity section is loaded, as around a player who has been there a moment.
                .thenWaitUntil(() -> f.await(settled(f, "draft1", SITE), "The work site is loaded around the builder"))
                .thenExecute(() -> {
                    var level = draft(f, "draft1");
                    for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) level.setBlockAndUpdate(SITE.offset(x, 0, z), Blocks.GOLD_BLOCK.defaultBlockState());
                    // Design 4.4 item 3: the wand on a block lists what it can be bound as.
                    f.a.player.getInventory().setSelectedSlot(Toolkit.WAND_SLOT);
                    useOn(f, SITE);
                })
                .thenWaitUntil(() -> f.await(cooled(), "Button cooldown"))
                .thenExecute(() -> {
                    check(test, ButtonMenu.isMenu(f.a.player), "The wand on a block opens its menu");
                    var labels = labels(f.a.player);
                    check(test, labels.contains("把这个方块绑定为【规则】") && labels.contains("在这里创建点击范围"), "Click functions are offered for any block: " + labels);
                    checkNot(test, labels.stream().anyMatch(l -> l.contains("试炼宝库") || l.contains("状态牌")), "Vaults and signs only on their own blocks: " + labels);
                    click(test, f, "把这个方块绑定为【规则】");
                    var marker = markers(draft(f, "draft1"), new AABB(SITE.above())).stream().findFirst().orElse(null);
                    check(test, marker != null && marker.entityTags().containsAll(Set.of(MarkerTags.MARKER, "hardrush.fn.rules", "hardrush.as.click")),
                            "A click marker stands on the block: " + (marker == null ? null : marker.entityTags()));
                    checkNot(test, ButtonMenu.isMenu(f.a.player), "Choosing closes the menu");
                    useOn(f, SITE.offset(2, 0, 2));
                })
                .thenWaitUntil(() -> f.await(cooled(), "Button cooldown"))
                .thenExecute(() -> click(test, f, "在这里创建点击范围"))
                .thenWaitUntil(() -> f.await(cooled(), "Button cooldown"))
                .thenExecute(() -> click(test, f, "宽 2 × 高 2"))
                .thenWaitUntil(() -> f.await(cooled(), "Button cooldown"))
                .thenExecute(() -> {
                    click(test, f, "点击范围绑定为【上一页】");
                    var boxes = draft(f, "draft1").getEntitiesOfClass(Interaction.class, new AABB(SITE.offset(2, 1, 2)).inflate(.5));
                    test.assertValueEqual(boxes.size(), 1, "An interaction box stands on the block");
                    s.box = boxes.getFirst();
                    check(test, s.box.entityTags().equals(Set.of("hardrush.fn.page_prev")), "…tagged as the click function: " + s.box.entityTags());
                    test.assertValueEqual((double) s.box.getBbWidth(), 2.0, "…2 wide");
                    test.assertValueEqual((double) s.box.getBbHeight(), 2.0, "…2 high");
                    // The wand on an entity: bind, then unbind.
                    var level = draft(f, "draft1");
                    s.statue = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
                    s.statue.snapTo(SITE.getX() - 1.5, SITE.getY() + 1, SITE.getZ() + .5, 0, 0);
                    level.addFreshEntity(s.statue);
                    interact(f, s.statue);
                })
                .thenWaitUntil(() -> f.await(cooled(), "Button cooldown"))
                .thenExecute(() -> {
                    var labels = labels(f.a.player);
                    check(test, labels.contains("绑定为【战绩】") && labels.stream().noneMatch(l -> l.contains("状态牌")),
                            "Click functions for any entity, text ones only for text displays: " + labels);
                    click(test, f, "绑定为【战绩】");
                    check(test, s.statue.entityTags().equals(Set.of("hardrush.fn.history")), "The statue is a history trigger: " + s.statue.entityTags());
                    interact(f, s.statue);
                })
                .thenWaitUntil(() -> f.await(cooled(), "Button cooldown"))
                .thenExecute(() -> {
                    click(test, f, "移除绑定 【战绩】");
                    check(test, s.statue.entityTags().isEmpty(), "Unbound again: " + s.statue.entityTags());
                    interact(f, s.box);
                })
                .thenWaitUntil(() -> f.await(cooled(), "Button cooldown"))
                .thenExecute(() -> {
                    click(test, f, "移除绑定 【上一页】");
                    check(test, s.box.entityTags().isEmpty(), "The interaction box is unbound: " + s.box.entityTags());
                    checkNot(test, ButtonMenu.isMenu(f.a.player), "Unbinding closes the menu");
                    // Design 4.4 item 4: a block in hand toggles a flood marker's materials.
                    var level = draft(f, "draft1");
                    s.flood = MarkerStands.spawn(level, MarkerSpec.flood(LobbyFunction.ASSEMBLY, Set.of()), SITE.getX() - .5, SITE.getY() + 1, SITE.getZ() - .5, 0);
                    s.volume = MarkerStands.spawn(level, MarkerSpec.box(LobbyFunction.COURTYARD, 2, 2, 2), SITE.getX() + 1.5, SITE.getY() + 1, SITE.getZ() - .5, 0);
                    f.a.player.getInventory().setSelectedSlot(0);
                    f.a.player.getInventory().setItem(0, new ItemStack(Items.STONE));
                    interact(f, s.flood);
                    check(test, s.flood.entityTags().contains("hardrush.mat.minecraft+stone"), "Stone is now a floor material: " + s.flood.entityTags());
                    check(test, f.a.player.getInventory().getItem(0).is(Items.STONE) && s.flood.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND).isEmpty(),
                            "Nothing was handed to the armor stand");
                    interact(f, s.flood);
                    checkNot(test, s.flood.entityTags().contains("hardrush.mat.minecraft+stone"), "…and removed again: " + s.flood.entityTags());
                    f.a.player.getInventory().setItem(0, ItemStack.EMPTY);
                    checkNot(test, ButtonMenu.isMenu(f.a.player), "No menu before the box marker: " + labels(f.a.player));
                    interact(f, s.volume);
                    check(test, labels(f.a.player).contains("宽 +5"), "The size menu opens at once: " + labels(f.a.player));
                })
                .thenWaitUntil(() -> f.await(cooled(), "Button cooldown"))
                .thenExecute(() -> {
                    check(test, ButtonMenu.isMenu(f.a.player), "An empty hand on a box marker opens the size menu");
                    click(test, f, "宽 +5");
                    check(test, s.volume.entityTags().contains("hardrush.box.7.2.2") && s.volume.entityTags().stream().filter(t -> t.startsWith(MarkerTags.BOX)).count() == 1,
                            "The width grows by 5: " + s.volume.entityTags());
                    f.a.player.closeContainer();
                    // Design 4.4 item 5: breaking a bound block takes its marker along.
                    check(test, f.a.player.gameMode.destroyBlock(SITE), "The builder breaks the bound block");
                    check(test, markers(draft(f, "draft1"), new AABB(SITE.above())).isEmpty(), "The marker on it went too");
                    // Design 4.4 item 1: a dropped toolbox comes back.
                    f.a.player.getInventory().setItem(Toolkit.TOOLBOX_SLOT, ItemStack.EMPTY);
                })
                .thenWaitUntil(() -> f.await(Toolkit.kind(f.a.player.getInventory().getItem(Toolkit.TOOLBOX_SLOT)).isPresent(), "The toolbox returns"))
                .thenExecute(() -> {
                    // The build range around the camp (design 11.1, 19.1).
                    teleport(f, CENTER + .5, 80, CENTER + .5);
                    force(f, "draft1", true);
                    check(test, workshop(f).setCenter(f.a.player).isEmpty(), "Centre set");
                    check(test, workshop(f).resize(f.a.player, -160).isEmpty(), "Range shrunk");
                    var draft = workshop(f).draft("draft1").orElseThrow();
                    check(test, draft.borderCenterX() == CENTER && draft.borderCenterZ() == CENTER && draft.borderDiameter() == DIAMETER, "Range recorded: " + draft);
                    test.assertValueEqual(draft(f, "draft1").getWorldBorder().getSize(), (double) DIAMETER, "The world border follows");
                })
                // The light check sees the whole camp once its chunks and entities are loaded around the builder.
                .thenWaitUntil(() -> f.await(workshop(f).checkNow("draft1").orElseThrow().stream().noneMatch(p -> p.severity() == Severity.ERROR),
                        "The camp passes the light check: " + workshop(f).checkNow("draft1").orElseThrow()))
                .thenExecute(() -> {
                    check(test, workshop(f).draft("draft1").orElseThrow().entry() != null, "The central landing becomes the entry");
                    // Publishing without any assembly zone fails and writes nothing (design 9).
                    for (var marker : markers(draft(f, "draft1"), new AABB(-64, -64, -64, 80, 320, 80))) {
                        if (!marker.entityTags().contains("hardrush.fn.assembly")) continue;
                        s.assembly.add(new Object[] {Set.copyOf(marker.entityTags()), marker.position(), marker.getYRot()});
                        marker.discard();
                    }
                    checkNot(test, s.assembly.isEmpty(), "The camp has assembly markers");
                    check(test, workshop(f).publish(f.a.player, "draft1").isPresent(), "2-level operators cannot publish");
                    op(f, f.a, PermissionLevel.OWNERS);
                    check(test, workshop(f).publish(f.a.player, "draft1").isEmpty(), "Publishing starts");
                })
                .thenWaitUntil(() -> f.await(state(f, "draft1").startsWith("FAILED"), "Publishing without an assembly zone fails"))
                .thenExecute(() -> {
                    check(test, state(f, "draft1").contains("集结区"), "The missing assembly is named: " + state(f, "draft1"));
                    check(test, packsOf(f, "draft1").isEmpty(), "Nothing was written");
                    workshop(f).dismissPublish("draft1");
                    var level = draft(f, "draft1");
                    for (var a : s.assembly) {
                        @SuppressWarnings("unchecked") var tags = (Set<String>) a[0];
                        var at = (Vec3) a[1];
                        var spec = ((MarkerTags.Ok) MarkerTags.parseMarker(tags)).spec();
                        MarkerStands.spawn(level, spec, at.x, at.y, at.z, (float) a[2]);
                    }
                    // A new lectern with a rules marker placed the vanilla way, the old marker knocked off.
                    level.setBlockAndUpdate(NEW_LECTERN, Blocks.LECTERN.defaultBlockState());
                    teleport(f, NEW_LECTERN.getX() + .5, NEW_LECTERN.getY() + 2, NEW_LECTERN.getZ() + 3.5);
                    f.a.player.getInventory().setItem(0, MarkerStands.item(MarkerSpec.simple(LobbyFunction.RULES, Binding.CLICK)));
                    // An empty lectern takes the click itself; like a player, sneak to place on it.
                    f.a.player.setShiftKeyDown(true);
                    useOn(f, NEW_LECTERN);
                    f.a.player.setShiftKeyDown(false);
                    test.assertValueEqual(markers(level, new AABB(NEW_LECTERN.above())).size(), 1, "The marker item places a marker on the lectern");
                    var old = markers(level, new AABB(s.oldLectern).expandTowards(0, 1, 0)).stream()
                            .filter(m -> m.entityTags().contains("hardrush.fn.rules")).toList();
                    checkNot(test, old.isEmpty(), "The old lectern has its rules marker");
                    for (var marker : old) f.a.player.attack(marker);
                    check(test, old.stream().allMatch(Entity::isRemoved), "A creative hit removes the marker");
                    check(test, workshop(f).publish(f.a.player, "draft1").isEmpty(), "Publishing starts again");
                })
                .thenWaitUntil(() -> f.await(state(f, "draft1").startsWith("CONFIRM"), "Publishing waits for the authors"))
                .thenExecute(() -> check(test, workshop(f).confirmPublish(f.a.player, "draft1").isEmpty(), "Confirmed"))
                .thenWaitUntil(() -> f.await(state(f, "draft1").startsWith("DONE"), "Published"))
                .thenExecute(() -> {
                    var packs = packsOf(f, "draft1");
                    test.assertValueEqual(packs.size(), 1, "One pack written: " + packs);
                    test.assertValueEqual(packs.getFirst().getFileName().toString(), "draft1-v1.hrlobby", "Named after the draft and its first version");
                    var contents = read(packs.getFirst());
                    var rules = contents.plan().triggers(LobbyFunction.RULES).stream().map(t -> t.block()).toList();
                    check(test, rules.contains(new Cell(NEW_LECTERN.getX(), NEW_LECTERN.getY(), NEW_LECTERN.getZ())), "The new lectern is a rules trigger: " + rules);
                    checkNot(test, rules.contains(new Cell(s.oldLectern.getX(), s.oldLectern.getY(), s.oldLectern.getZ())), "The old one is not: " + rules);
                    check(test, contents.manifest().authors().contains(f.a.player.getScoreboardName()), "The builder is an author");
                    test.assertValueEqual(workshop(f).draft("draft1").orElseThrow().publishedVersion(), 1, "draft.json records the version");
                    workshop(f).dismissPublish("draft1");
                    // Content a save brings along: deep ground below the bottom height and a far building outside the range.
                    var level = draft(f, "draft1");
                    level.setBlockAndUpdate(new BlockPos(0, 40, 0), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(100, 64, 100), Blocks.STONE.defaultBlockState());
                    op(f, f.a, PermissionLevel.GAMEMASTERS);
                    force(f, "draft1", false);
                    workshop(f).leave(f.a.player, null);
                    s.before.assertSame(test, f.a.player, "body after tools and publishing");
                })
                .thenWaitUntil(() -> f.await(f.server.getLevel(LobbyDimension.workshopKey("draft1")) == null, "The idle draft is saved and unloaded"))
                .thenExecute(() -> {
                    // Design 19.1: the draft's region files become a "save" in the import folder.
                    var root = f.server.getWorldPath(LevelResource.ROOT);
                    var from = LobbyDimension.folder(root, LobbyDimension.workshopKey("draft1"));
                    var save = root.resolve("hardrush/workshop/import/camp-save");
                    copy(from, save);
                    check(test, workshop(f).create(f.a.player, new Workshop.Seed.Import("camp-save")).isPresent(), "Saves are 4-level only");
                    op(f, f.a, PermissionLevel.OWNERS);
                    check(test, workshop(f).create(f.a.player, new Workshop.Seed.Import("camp-save")).isEmpty(), "Importing the save starts");
                })
                .thenWaitUntil(() -> f.await(ready(f, "draft3"), "The save is a draft"))
                .thenExecute(() -> {
                    var level = draft(f, "draft3");
                    check(test, level.getBlockState(NEW_LECTERN).is(Blocks.LECTERN), "The save's blocks keep their coordinates");
                    check(test, level.getBlockState(new BlockPos(0, 40, 0)).is(Blocks.STONE), "…deep ground included");
                    check(test, level.getBlockState(new BlockPos(100, 64, 100)).is(Blocks.STONE), "…and the far building");
                    enter(test, f, "draft3");
                    // Standing on a floor at BOTTOM keeps that floor and leaves the layer below it out.
                    level.setBlockAndUpdate(new BlockPos(CENTER, BOTTOM, CENTER), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(CENTER, BOTTOM - 1, CENTER), Blocks.STONE.defaultBlockState());
                    teleport(f, CENTER + .5, BOTTOM + 1, CENTER + .5);
                    check(test, workshop(f).setCenter(f.a.player).isEmpty() && workshop(f).resize(f.a.player, -160).isEmpty()
                            && workshop(f).setBottom(f.a.player, Workshop.bottomUnderfoot(f.a.player)).isEmpty(), "Range and bottom height set");
                    test.assertValueEqual(workshop(f).draft("draft3").orElseThrow().bottomY(), BOTTOM, "Bottom height recorded");
                    check(test, workshop(f).publish(f.a.player, "draft3").isEmpty(), "Publishing the save starts");
                    // The scan and the tile captures span ticks: the mod's own bulk edits wait for the publish.
                    try {
                        SampleFiles.write(f.server.getWorldPath(LevelResource.ROOT).resolve("hardrush/workshop/import"), "sample.schem",
                                SampleFiles.schemV2().tag());
                    } catch (IOException e) { throw new UncheckedIOException(e); }
                    var pasting = workshop(f).paste(f.a.player, "sample.schem");
                    check(test, pasting.filter(r -> r.contains("正在发布")).isPresent(), "Pasting waits for the publish: " + pasting);
                    var clearing = workshop(f).clearMobs(f.a.player, n -> {});
                    check(test, clearing.filter(r -> r.contains("正在发布")).isPresent(), "Clearing mobs waits for the publish: " + clearing);
                })
                .thenWaitUntil(() -> f.await(state(f, "draft3").startsWith("CONFIRM") || state(f, "draft3").startsWith("FAILED"), "The save is packed"))
                .thenExecute(() -> {
                    check(test, state(f, "draft3").startsWith("CONFIRM"), "The imported camp is publishable: " + state(f, "draft3"));
                    workshop(f).confirmPublish(f.a.player, "draft3");
                })
                .thenWaitUntil(() -> f.await(state(f, "draft3").startsWith("DONE"), "The save is published"))
                .thenExecute(() -> {
                    var contents = read(packsOf(f, "draft3").getFirst());
                    int maxX = CENTER - DIAMETER / 2 + DIAMETER - 1;
                    boolean lectern = false, floor = false;
                    for (var entry : contents.tiles().entrySet()) {
                        var key = entry.getKey();
                        for (var b : entry.getValue().getListOrEmpty("blocks")) {
                            var block = (net.minecraft.nbt.CompoundTag) b;
                            var pos = block.getListOrEmpty("pos");
                            int x = key.originX() + pos.getIntOr(0, 0), y = key.originY() + pos.getIntOr(1, 0), z = key.originZ() + pos.getIntOr(2, 0);
                            check(test, y >= BOTTOM, "Nothing below the bottom height: " + x + " " + y + " " + z);
                            check(test, x <= maxX && z <= maxX, "Nothing outside the range: " + x + " " + y + " " + z);
                            if (x == CENTER && y == BOTTOM && z == CENTER) floor = true;
                            if (x == NEW_LECTERN.getX() && y == NEW_LECTERN.getY() && z == NEW_LECTERN.getZ())
                                lectern = block.getCompoundOrEmpty("nbt").getStringOr("id", "").equals("minecraft:lectern");
                        }
                    }
                    check(test, lectern, "The camp inside the range is in the pack");
                    check(test, floor, "The floor the bottom height was set on is in the pack");
                    workshop(f).dismissPublish("draft3");
                    workshop(f).leave(f.a.player, null);
                    // Design 19.2: a building file becomes a draft centred on the origin at Y 64.
                    var sample = SampleFiles.schemV2();
                    try {
                        SampleFiles.write(f.server.getWorldPath(LevelResource.ROOT).resolve("hardrush/workshop/import"), "sample.schem", sample.tag());
                    } catch (IOException e) { throw new UncheckedIOException(e); }
                    check(test, workshop(f).create(f.a.player, new Workshop.Seed.Import("sample.schem")).isEmpty(), "Importing the building file starts");
                })
                .thenWaitUntil(() -> f.await(ready(f, "draft4"), "The building file is a draft"))
                .thenExecute(() -> {
                    var level = draft(f, "draft4");
                    var expected = SampleFiles.schemV2().expected();
                    int ox = -expected.sx() / 2, oz = -expected.sz() / 2;
                    for (var entry : expected.states().entrySet()) {
                        var c = entry.getKey();
                        var pos = new BlockPos(ox + c.x(), 64 + c.y(), oz + c.z());
                        var id = entry.getValue().contains("[") ? entry.getValue().substring(0, entry.getValue().indexOf('[')) : entry.getValue();
                        test.assertValueEqual(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString(), id,
                                "Building block at " + pos);
                    }
                    op(f, f.a, PermissionLevel.GAMEMASTERS);
                });
    }

    private static ServerLevel draft(HumanEntryGameTest.Fixture f, String id) { return workshop(f).level(id).orElseThrow(); }
    /** Forces (or releases) the camp's chunks and the work site's, as a client standing there would keep them. */
    private static void force(HumanEntryGameTest.Fixture f, String id, boolean on) {
        var level = draft(f, id);
        for (int cx = -3; cx <= 3; cx++) for (int cz = -3; cz <= 3; cz++) level.setChunkForced(cx, cz, on);
        if (!on) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            level.setChunkForced((SITE.getX() >> 4) + dx, (SITE.getZ() >> 4) + dz, false);
    }

    /** The chunks around {@code at} are loaded with their entities. */
    private static boolean settled(HumanEntryGameTest.Fixture f, String id, BlockPos at) {
        var level = draft(f, id);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            var chunk = new net.minecraft.world.level.ChunkPos((at.getX() >> 4) + dx, (at.getZ() >> 4) + dz);
            if (!level.areEntitiesLoaded(chunk.pack())) return false;
        }
        return true;
    }
    private static String state(HumanEntryGameTest.Fixture f, String id) { return workshop(f).publishState(id).orElse(""); }

    private static void teleport(HumanEntryGameTest.Fixture f, double x, double y, double z) {
        f.a.player.teleportTo((ServerLevel) f.a.player.level(), x, y, z, Set.of(), 180, 30, true);
        f.a.acknowledge();
    }

    private static void useOn(HumanEntryGameTest.Fixture f, BlockPos target) {
        f.a.player.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(target).add(0, .5, 0), Direction.UP, target, false), 7));
    }

    private static void interact(HumanEntryGameTest.Fixture f, Entity entity) {
        f.a.player.connection.handleInteract(new ServerboundInteractPacket(entity.getId(), InteractionHand.MAIN_HAND, Vec3.ZERO, false));
    }

    /** Clicks the button labelled {@code label} in the open button menu. */
    private static void click(GameTestHelper test, HumanEntryGameTest.Fixture f, String label) {
        int slot = labels(f.a.player).indexOf(label);
        check(test, slot >= 0 && slot < ButtonMenu.PAGE_SIZE, "Button " + label + " in " + labels(f.a.player)
                + " (menu " + f.a.player.containerMenu.getClass().getSimpleName() + " #" + f.a.player.containerMenu.containerId + ")");
        f.a.player.containerMenu.clicked(slot, 0, ContainerInput.PICKUP, f.a.player);
        lastClick = System.nanoTime();
    }

    /**
     * Button menus drop a repeated click within {@link ButtonMenu#BUTTON_COOLDOWN_NANOS} of wall time, and game tests
     * tick far faster than a player clicks; steps that click wait this out.
     */
    private static long lastClick;

    /**
     * A failed assertion inside {@code thenExecute} is recorded, but the steps queued for the same tick still run and
     * may fail differently; the test then reports the last failure. Print the first one where it happened.
     */
    private static void check(GameTestHelper test, boolean condition, String message) {
        if (!condition) System.out.println("Workshop tools step failed: " + message);
        test.assertTrue(condition, message);
    }
    private static void checkNot(GameTestHelper test, boolean condition, String message) { check(test, !condition, message); }
    private static boolean cooled() { return System.nanoTime() - lastClick > ButtonMenu.BUTTON_COOLDOWN_NANOS + 50_000_000L; }

    private static List<ArmorStand> markers(ServerLevel level, AABB box) {
        return level.getEntitiesOfClass(ArmorStand.class, box, MarkerStands::is);
    }

    private static List<Path> packsOf(HumanEntryGameTest.Fixture f, String id) {
        var packs = LobbyWorld.open(f.server).storage().packs();
        if (!Files.isDirectory(packs)) return List.of();
        try (var list = Files.list(packs)) {
            return list.filter(p -> p.getFileName().toString().startsWith(id + "-v")).sorted().toList();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static PackContents read(Path pack) {
        try { return PackFile.read(pack, PackLimits.forServer(HardRushProperties.lobbyMaxSize())); }
        catch (dev.hardrush.lobby.pack.PackException e) { throw new IllegalStateException(e); }
    }

    private static void copy(Path from, Path to) {
        try (var walk = Files.walk(from)) {
            for (var p : walk.toList()) {
                var rel = from.relativize(p).toString();
                if (!(rel.startsWith("region") || rel.startsWith("entities") || rel.startsWith("poi"))) continue;
                var dest = to.resolve(rel);
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else { Files.createDirectories(dest.getParent()); Files.copy(p, dest); }
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
