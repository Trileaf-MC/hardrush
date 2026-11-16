package dev.hardrush.gametest;

import dev.hardrush.core.lobby.plan.Landing;
import dev.hardrush.core.lobby.plan.LobbyFunction;
import dev.hardrush.core.lobby.plan.MarkerTags;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.lobby.ButtonMenu;
import dev.hardrush.lobby.workshop.Draft;
import dev.hardrush.lobby.workshop.Toolkit;
import dev.hardrush.lobby.workshop.Workshop;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.round.RoundRuntime;
import dev.hardrush.round.RoundSession;
import dev.hardrush.world.LobbyDimension;
import dev.hardrush.world.LobbyWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Workshop drafts and the builder identity (design 11) on the production lifecycle and protection binding: native
 * logins and command packets, no graphical client.
 */
public final class WorkshopGameTest {
    @GameTest(maxTicks = 400000)
    public void buildersBorrowTheirLobbyBodyAndGiveItBackUnchanged(GameTestHelper test) {
        if (!Boolean.getBoolean(TestProperties.WORKSHOP)) { test.succeed(); return; }
        var f = new HumanEntryGameTest.Fixture(test);
        var s = new State();
        test.onEachTick(() -> { f.drain(); f.a.tickConnection(); f.b.tickConnection(); });
        var sequence = test.startSequence().thenWaitUntil(() -> f.await(LobbyWorld.open(f.server).ready()
                && RoundRuntime.snapshot(f.server).filter(r -> r.phase() == RoundPhase.WAITING).isPresent()
                && !((RoundSession) f.service("SESSIONS")).mutationPending(), "Workshop camp ready"))
                .thenExecute(() -> { f.bind(); f.callbacks(); });
        f.a.login(sequence); f.b.login(sequence);
        sequence.thenWaitUntil(() -> f.await(f.idle() && workshop(f).loaded(), "Profiles and workshop listing ready"))
                .thenExecute(() -> {
                    test.assertTrue(workshop(f).drafts().isEmpty(), "A fresh world has no drafts");
                    test.assertTrue(workshop(f).enter(f.b.player, "draft1").isPresent(), "Nothing to enter yet");
                    op(f, f.a, PermissionLevel.GAMEMASTERS);
                    packet(f.a.player, "hardrush lobby");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    test.assertTrue(ButtonMenu.isMenu(f.a.player), "/hardrush lobby opens the workshop chest for a 2-level operator in camp");
                    test.assertValueEqual(workshop(f).drafts().size(), 1, "The first look at an empty workshop starts a draft from the current lobby");
                    f.a.player.closeContainer();
                    packet(f.b.player, "hardrush lobby");
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    test.assertFalse(ButtonMenu.isMenu(f.b.player), "Non-operators cannot open the workshop");
                    test.assertValueEqual(workshop(f).enter(f.b.player, "draft1").orElse(""), "草稿还在导入，稍等片刻", "An importing draft cannot be entered");
                })
                .thenWaitUntil(() -> f.await(ready(f, "draft1"), "Draft from the current lobby imported"))
                .thenExecute(() -> {
                    test.assertTrue(workshop(f).create(f.a.player, new Workshop.Seed.Blank()).isEmpty(), "A blank draft starts");
                    // Design 11.1 / 17: Fantasy dimensions keep their own world border.
                    var draft = workshop(f).level("draft1").orElseThrow();
                    var overworld = f.server.overworld().getWorldBorder();
                    double overworldSize = overworld.getSize(), lobbySize = LobbyWorld.open(f.server).holdingLevel().getWorldBorder().getSize();
                    test.assertValueEqual(draft.getWorldBorder().getSize(), (double) dev.hardrush.HardRushProperties.lobbyMaxSize(), "The draft's border is its build range");
                    draft.getWorldBorder().setSize(100);
                    draft.getWorldBorder().setCenter(40, -40);
                    test.assertValueEqual(overworld.getSize(), overworldSize, "The overworld border is independent of a draft's");
                    test.assertValueEqual(overworld.getCenterX(), 0.0, "The overworld border centre is independent");
                    test.assertValueEqual(LobbyWorld.open(f.server).holdingLevel().getWorldBorder().getSize(), lobbySize, "The lobby border is independent");
                    draft.getWorldBorder().setCenter(0, 0);
                    draft.getWorldBorder().setSize(dev.hardrush.HardRushProperties.lobbyMaxSize());
                })
                .thenWaitUntil(() -> f.await(ready(f, "draft2"), "Blank draft built"))
                .thenExecute(() -> {
                    test.assertTrue(workshop(f).level("draft2").orElseThrow().getBlockState(new BlockPos(0, 64, 0)).is(Blocks.STONE), "Blank drafts have a stone pad");
                    // Non-operators and prepared participants stay out (design 11.2).
                    test.assertValueEqual(workshop(f).enter(f.b.player, "draft1").orElse(""), "需要 2 级管理员权限", "Non-operators cannot build");
                    op(f, f.b, PermissionLevel.GAMEMASTERS);
                    f.place(f.b, Landing.RESTORE_READY);
                })
                .thenWaitUntil(() -> f.await(f.ready(f.b) && f.idle(), "Operator B prepares"))
                .thenExecute(() -> {
                    test.assertValueEqual(workshop(f).enter(f.b.player, "draft1").orElse(""), "你已准备参赛，先走出集结区", "A prepared participant cannot build");
                    f.place(f.b, Landing.CENTRAL);
                })
                .thenWaitUntil(() -> f.await(!f.ready(f.b) && f.idle(), "Operator B steps out"))
                .thenExecute(() -> {
                    // A camp-acquired item and a wounded, hungry, experienced body: none of it is in the sealed suspension.
                    f.a.player.getInventory().setItem(3, new ItemStack(Items.PAPER, 5));
                    f.a.player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD, 2));
                    f.a.player.setExperienceLevels(3);
                    f.a.player.setHealth(15);
                    f.a.player.getFoodData().setFoodLevel(12);
                    s.before = Body.of(f.a.player);
                    enter(test, f, "draft1");
                })
                .thenWaitUntil(() -> f.await(markers(workshop(f).level("draft1").orElseThrow()) > 0, "Draft keeps the marker armor stands"))
                .thenExecute(() -> {
                    var draft = workshop(f).level("draft1").orElseThrow();
                    var lectern = LobbyPlanFixtures.block(f.server, LobbyFunction.RULES);
                    draft.getChunk(lectern);
                    test.assertTrue(draft.getBlockState(lectern).equals(LobbyWorld.open(f.server).holdingLevel().getBlockState(lectern)),
                            "The default camp's blocks are copied to the same coordinates");
                    test.assertValueEqual(markers(LobbyWorld.open(f.server).holdingLevel()), 0, "Installed lobbies carry no markers");
                    test.assertFalse(f.profiles.readyForFullCheckpoint(f.round()), "No full checkpoint starts while somebody builds");
                    packet(f.a.player, "give @s diamond_block 64");
                    packet(f.a.player, "xp add @s 30 levels");
                    packet(f.a.player, "effect give @s speed 600");
                    packet(f.a.player, "hardrush lobby");
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    test.assertTrue(f.a.player.getInventory().countItem(Items.DIAMOND_BLOCK) == 64, "Builders run vanilla commands through the network gate");
                    test.assertTrue(ButtonMenu.isMenu(f.a.player) && labels(f.a.player).contains("离开工坊，回到营地"), "A builder's menu offers leaving");
                    f.a.player.closeContainer();
                    f.a.player.getEnderChestInventory().setItem(1, new ItemStack(Items.NETHERITE_INGOT, 64));
                    // Block placement through the real packet.
                    f.a.player.getInventory().setSelectedSlot(0);
                    f.a.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_BLOCK, 64));
                    // High above the camp, where nothing is in the way.
                    f.a.player.teleportTo((ServerLevel) f.a.player.level(), 0.5, 120, 0.5, Set.of(), 0, 0, true);
                    f.a.acknowledge();
                    var target = new BlockPos(3, 118, 0);
                    s.placed = target.above();
                    f.a.player.level().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
                    f.a.player.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(target).add(0, .5, 0), Direction.UP, target, false), 4));
                    test.assertTrue(f.a.player.level().getBlockState(s.placed).is(Blocks.DIAMOND_BLOCK), "Builders place blocks in their draft");
                    // Nobody dies in a workshop.
                    test.assertFalse(f.a.player.hurtServer((ServerLevel) f.a.player.level(), f.a.player.damageSources().genericKill(), Float.MAX_VALUE), "Lethal damage is refused");
                    packet(f.a.player, "kill @s");
                    packet(f.a.player, "gamemode survival");
                    // Out of the draft: refused by command and by direct teleport.
                    packet(f.a.player, "execute in minecraft:overworld run tp @s 0 120 0");
                    test.assertFalse(f.a.player.teleportTo(LobbyWorld.open(f.server).holdingLevel(), 0, 70, 0, Set.of(), 0, 0, true), "A builder cannot teleport to the lobby");
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    test.assertTrue(f.a.player.isAlive() && PlayerProfiles.building(f.a.player), "/kill does not kill a builder");
                    test.assertTrue(LobbyDimension.isWorkshop((ServerLevel) f.a.player.level()), "A builder cannot leave its draft by command");
                    test.assertValueEqual(f.a.player.gameMode.getGameModeForPlayer(), GameType.SURVIVAL, "/gamemode is vanilla's for builders");
                    var level = f.a.player.level();
                    f.a.player.teleportTo((ServerLevel) level, 0.5, level.getMinY() - 30, 0.5, Set.of(), 0, 0, true);
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    test.assertTrue(f.a.player.isAlive() && f.a.player.getY() > f.a.player.level().getMinY(), "A fall into the void ends back at the entry");
                    test.assertTrue(PlayerProfiles.building(f.a.player), "Still building after the void");
                    workshop(f).leave(f.a.player, null);
                    s.before.assertSame(test, f.a.player, "fresh body");
                    test.assertTrue(f.profiles.readyForFullCheckpoint(f.round()), "A checkpoint may start again once the builder left");
                    test.assertTrue(workshop(f).level("draft1").map(l -> l.getBlockState(s.placed).is(Blocks.DIAMOND_BLOCK)).orElse(true), "The draft keeps the build");
                    f.place(f.a, Landing.RESTORE_READY);
                })
                .thenWaitUntil(() -> f.await(f.ready(f.a) && f.idle(), "The returned body prepares normally"))
                .thenExecute(() -> { f.place(f.a, Landing.CENTRAL); })
                .thenWaitUntil(() -> f.await(!f.ready(f.a) && f.idle(), "And steps out again"));
        WorkshopToolSteps.run(test, f, sequence);
        sequence.thenExecute(() -> f.a.disconnect());
        f.a.login(sequence);
        sequence.thenExecute(() -> {
                    // A restored profile (same generation, sealed suspension reloaded on login).
                    s.before = Body.of(f.a.player);
                    enter(test, f, "draft1");
                    packet(f.a.player, "give @s diamond_block 64");
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    workshop(f).leave(f.a.player, null);
                    s.before.assertSame(test, f.a.player, "restored body");
                    enter(test, f, "draft1");
                    packet(f.a.player, "give @s diamond_block 64");
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    test.assertValueEqual(f.a.player.getInventory().countItem(Items.DIAMOND_BLOCK), 64, "Creative items before disconnecting");
                    f.a.disconnect();
                });
        f.a.login(sequence);
        sequence.thenExecute(() -> {
                    test.assertTrue(LobbyWorld.isLobby((ServerLevel) f.a.player.level()), "A builder who disconnected comes back to camp");
                    test.assertValueEqual(f.a.player.gameMode.getGameModeForPlayer(), GameType.ADVENTURE, "…in adventure mode");
                    test.assertValueEqual(f.a.player.getInventory().countItem(Items.DIAMOND_BLOCK), 0, "…without creative items");
                    test.assertFalse(PlayerProfiles.building(f.a.player), "…and not building");
                    // The challenge starting sends builders back; the draft stays.
                    s.before = Body.of(f.a.player);
                    enter(test, f, "draft1");
                    packet(f.a.player, "give @s diamond_block 64");
                    f.place(f.b, Landing.RESTORE_READY);
                })
                .thenWaitUntil(() -> f.await(f.ready(f.b) && f.idle(), "B prepares while A builds"))
                .thenExecute(() -> {
                    test.assertTrue(PlayerProfiles.building(f.a.player), "Countdown keeps the workshop open");
                    f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
                })
                .thenWaitUntil(() -> f.await(f.round().phase() == RoundPhase.RUNNING && !PlayerProfiles.isLocked(f.b.player)
                        && !PlayerProfiles.building(f.a.player) && f.idle(), "The challenge starts and the builder goes back"))
                .thenExecute(() -> {
                    test.assertTrue(LobbyWorld.isLobby((ServerLevel) f.a.player.level()), "Sent back to camp");
                    s.before.assertSame(test, f.a.player, "body sent back by the challenge");
                    test.assertTrue(workshop(f).draft("draft1").isPresent(), "The draft stays");
                    test.assertTrue(workshop(f).enter(f.a.player, "draft1").isPresent(), "No building while the challenge runs");
                    // Deleting is 4-level only; the draft's directory goes.
                    test.assertValueEqual(workshop(f).delete(f.a.player, "draft2").orElse(""), "需要 4 级管理员权限", "2-level operators cannot delete");
                    op(f, f.a, PermissionLevel.OWNERS);
                    test.assertTrue(workshop(f).delete(f.a.player, "draft2").isEmpty(), "4-level operators delete drafts");
                    test.assertTrue(workshop(f).draft("draft2").isEmpty(), "Deleted");
                })
                .thenWaitUntil(() -> f.await(!java.nio.file.Files.exists(f.server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .resolve("hardrush/workshop/draft2")), "Draft files deleted"))
                .thenExecute(() -> { f.a.disconnect(); f.b.disconnect(); })
                .thenSucceed();
    }

    private static final class State {
        Body before;
        BlockPos placed;
    }

    /** What leaving a workshop must give back. */
    record Body(List<ItemStack> inventory, List<ItemStack> enderChest, int level, float progress, float health, int food,
                        List<String> advancements, String dimension, int effects) {
        static Body of(ServerPlayer p) {
            var inventory = new ArrayList<ItemStack>();
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) inventory.add(p.getInventory().getItem(i).copy());
            var ender = new ArrayList<ItemStack>();
            for (int i = 0; i < p.getEnderChestInventory().getContainerSize(); i++) ender.add(p.getEnderChestInventory().getItem(i).copy());
            var done = new ArrayList<String>();
            for (var holder : p.level().getServer().getAdvancements().getAllAdvancements()) {
                var progress = p.getAdvancements().getOrStartProgress(holder);
                if (progress.hasProgress()) done.add(holder.id() + "=" + progress.getCompletedCriteria());
            }
            return new Body(inventory, ender, p.experienceLevel, p.experienceProgress, p.getHealth(), p.getFoodData().getFoodLevel(),
                    done, p.level().dimension().identifier().toString(), p.getActiveEffects().size());
        }
        void assertSame(GameTestHelper test, ServerPlayer p, String what) {
            var now = of(p);
            for (int i = 0; i < inventory.size(); i++)
                test.assertTrue(ItemStack.matches(inventory.get(i), now.inventory.get(i)), what + ": inventory slot " + i + " " + now.inventory.get(i));
            for (int i = 0; i < enderChest.size(); i++)
                test.assertTrue(ItemStack.matches(enderChest.get(i), now.enderChest.get(i)), what + ": ender chest slot " + i + " " + now.enderChest.get(i));
            test.assertValueEqual(now.level, level, what + ": experience level");
            test.assertValueEqual(now.progress, progress, what + ": experience progress");
            test.assertValueEqual(now.health, health, what + ": health");
            test.assertValueEqual(now.food, food, what + ": food");
            test.assertValueEqual(now.advancements, advancements, what + ": advancements");
            test.assertValueEqual(now.dimension, dimension, what + ": dimension");
            test.assertValueEqual(now.effects, effects, what + ": effects");
            test.assertValueEqual(p.gameMode.getGameModeForPlayer(), GameType.ADVENTURE, what + ": adventure mode");
            test.assertFalse(p.getAbilities().mayfly || p.getAbilities().flying || p.getAbilities().instabuild, what + ": no creative abilities");
            test.assertTrue(PlayerProfiles.canMoveInLobby(p) && PlayerProfiles.isLocked(p), what + ": an ordinary camp body again");
        }
    }

    static Workshop workshop(HumanEntryGameTest.Fixture f) { return Workshop.get(f.server).orElseThrow(); }
    static boolean ready(HumanEntryGameTest.Fixture f, String id) {
        return workshop(f).draft(id).filter(d -> d.state() == Draft.State.READY).isPresent();
    }
    static void enter(GameTestHelper test, HumanEntryGameTest.Fixture f, String id) {
        var refusal = workshop(f).enter(f.a.player, id);
        test.assertTrue(refusal.isEmpty(), "Enter: " + refusal.orElse(""));
        f.a.acknowledge();
        test.assertTrue(PlayerProfiles.building(f.a.player) && !PlayerProfiles.sealed(f.a.player), "A builder passes the isolation gates");
        test.assertTrue(PlayerProfiles.isLocked(f.a.player), "…but is never a round body");
        test.assertValueEqual(f.a.player.gameMode.getGameModeForPlayer(), GameType.CREATIVE, "Builders fly in creative");
        test.assertTrue(f.a.player.getAbilities().flying, "…already flying");
        var inventory = f.a.player.getInventory();
        int items = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) if (!inventory.getItem(i).isEmpty()) items++;
        test.assertTrue(items == 2 && Toolkit.kind(inventory.getItem(Toolkit.TOOLBOX_SLOT)).orElse("").equals("toolbox")
                && Toolkit.kind(inventory.getItem(Toolkit.WAND_SLOT)).orElse("").equals("wand"), "…holding only the toolbox and the wand");
    }
    static void op(HumanEntryGameTest.Fixture f, HumanEntryGameTest.Fixture.Peer peer, PermissionLevel level) {
        f.server.getPlayerList().op(new NameAndId(peer.profile), Optional.of(LevelBasedPermissionSet.forLevel(level)), Optional.empty());
    }
    static void packet(ServerPlayer player, String command) {
        player.connection.handleChatCommand(new ServerboundChatCommandPacket(command));
    }
    private static int markers(ServerLevel level) {
        return level.getEntities(EntityTypes.ARMOR_STAND, stand -> stand.entityTags().stream().anyMatch(t -> t.startsWith(MarkerTags.PREFIX))).size();
    }
    static List<String> labels(ServerPlayer player) {
        var out = new ArrayList<String>();
        for (int i = 0; i < Math.min(54, player.containerMenu.slots.size()); i++) out.add(player.containerMenu.getSlot(i).getItem().getHoverName().getString());
        return out;
    }
}
