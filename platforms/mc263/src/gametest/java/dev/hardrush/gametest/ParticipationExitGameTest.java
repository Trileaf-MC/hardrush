package dev.hardrush.gametest;

import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.lobby.LobbyLayout;
import dev.hardrush.round.RoundRuntime;
import dev.hardrush.round.RoundSession;
import dev.hardrush.combat.CombatRuntime;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.record.TabListRuntime;
import dev.hardrush.world.LobbyWorld;
import dev.hardrush.combat.MonsterCombatRuntime;
import dev.hardrush.lobby.LobbyMenus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.core.Direction;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

/** Native command/menu packets, profile archives, death-screen replacement and a complete rotation. */
public final class ParticipationExitGameTest {
    @GameTest(maxTicks = 60000)
    public void durableExitsKeepObservationSeparateFromEligibility(GameTestHelper test) {
        if (!Boolean.getBoolean(TestProperties.PARTICIPATION_EXIT)) { test.succeed(); return; }
        var f = new HumanEntryGameTest.Fixture(test); var c = f.new Peer("CampHumanC");
        var arrow = new Arrow[1]; var archive = new Path[1]; var sealed = new byte[1][];
        test.onEachTick(() -> { f.drain(); f.a.tickConnection(); f.b.tickConnection(); c.tickConnection(); });
        var sequence = test.startSequence().thenWaitUntil(() -> f.await(LobbyWorld.open(f.server).ready()
                && RoundRuntime.snapshot(f.server).filter(r -> r.phase() == RoundPhase.WAITING).isPresent()
                && !((RoundSession) f.service("SESSIONS")).mutationPending(), "Participation exits camp ready"))
                .thenExecute(() -> { f.bind(); f.callbacks(); });
        f.a.login(sequence); f.b.login(sequence); c.login(sequence);
        sequence.thenWaitUntil(() -> f.await(f.idle(), "All profiles ready"))
                .thenExecute(() -> {
                    var reader = f.server.createCommandSourceStack().withPermission(LevelBasedPermissionSet.forLevel(PermissionLevel.GAMEMASTERS));
                    execute(reader, "hardrush admin status");
                    try {
                        reader.getServer().getCommands().getDispatcher().execute("hardrush admin disqualify CampHumanA 越权", reader);
                        throw new IllegalStateException("OP 2 must not disqualify");
                    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { /* Native permission denial. */ }
                    packet(f.b.player, "hardrush admin disqualify CampHumanA 越权");
                    var before = f.round().participants().get(f.a.profile.id());
                    test.assertTrue(before == null || !before.role().isExcluded(), "Non-admin cannot disqualify others");
                    f.place(f.a, LobbyLayout.Landing.RESTORE_READY);
                })
                .thenWaitUntil(() -> f.await(f.ready(f.a) && f.idle(), "First ready"))
                .thenExecute(() -> {
                    shortcut(f.a.player, true);
                    test.assertTrue(LobbyMenus.isMenu(f.a.player), "Ready player can reach confirmation through the lobby inventory lock: sneak="
                            + f.a.player.isShiftKeyDown() + ", eligible=" + dev.hardrush.participation.ParticipationControl.canWithdraw(f.a.player));
                    f.a.player.connection.handleContainerClose(new ServerboundContainerClosePacket(f.a.player.containerMenu.containerId));
                    test.assertTrue(f.ready(f.a) && f.idle(), "Closing confirmation keeps spatial readiness");
                    sneak(f.a.player, false);
                })
                .thenExecute(() -> f.place(f.b, LobbyLayout.Landing.RESTORE_READY))
                .thenWaitUntil(() -> f.await(f.ready(f.b) && f.idle(), "Second ready"))
                .thenExecute(() -> f.place(c, LobbyLayout.Landing.RESTORE_READY))
                .thenWaitUntil(() -> f.await(f.ready(c) && f.idle(), "Third ready"))
                .thenExecute(() -> f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30)))
                .thenWaitUntil(() -> f.await(f.round().onlineHumans() == 3 && !PlayerProfiles.isLocked(f.a.player)
                        && !PlayerProfiles.isLocked(f.b.player) && !PlayerProfiles.isLocked(c.player) && f.idle(), "Three humans admitted"))
                .thenExecute(() -> {
                    f.a.player.getInventory().setItem(4, new ItemStack(Items.DIAMOND, 7));
                    f.a.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
                    f.a.player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
                    shortcut(f.a.player, false);
                    test.assertTrue(f.a.player.getMainHandItem().is(Items.SHIELD) && f.a.player.getOffhandItem().is(Items.IRON_SWORD)
                            && !LobbyMenus.isMenu(f.a.player), "Unmodified swap still uses vanilla hand swapping");
                    shortcut(f.a.player, false);
                    var tab = new TabListRuntime();
                    tab.refresh(f.server, f.round(), 0, null, true);
                    test.assertTrue(latestFooter(f.a).contains("退出本轮并观战"), "Eligible player receives a persistent native TAB hint");
                    arrow[0] = new Arrow(f.a.player.level(), f.a.player, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
                    test.assertTrue(CombatRuntime.originOf(arrow[0]).isPresent(), "Real human arrow origin");
                    packet(f.a.player, "hardrush spectate");
                    test.assertTrue(LobbyMenus.isMenu(f.a.player), "Voluntary exit opens vanilla confirmation");
                    f.click(f.a, (short)3);
                    test.assertTrue(role(f, f.a.profile.id()) == ParticipantRole.HUMAN_ACTIVE, "Cancel preserves identity");
                    shortcut(f.a.player, true);
                    test.assertTrue(LobbyMenus.isMenu(f.a.player), "Sneak + native swap opens the same confirmation without a command");
                    var menu = f.a.player.containerMenu;
                    shortcut(f.a.player, true);
                    test.assertTrue(f.a.player.containerMenu == menu, "Repeated shortcut cannot replace an open menu");
                    test.assertTrue(f.a.player.getMainHandItem().is(Items.IRON_SWORD) && f.a.player.getOffhandItem().is(Items.SHIELD), "Shortcut preserves both held items");
                    f.a.player.connection.handleContainerClose(new ServerboundContainerClosePacket(menu.containerId));
                    test.assertTrue(role(f, f.a.profile.id()) == ParticipantRole.HUMAN_ACTIVE && f.idle(), "Closing shortcut confirmation does not withdraw");
                    shortcut(f.a.player, true); f.click(f.a, (short)2);
                    test.assertTrue(f.session.participationExitReserved(f.a.profile.id()), "Pending exit seals input before SQLite callback");
                    f.a.assertFrozen();
                })
                .thenWaitUntil(() -> f.await(role(f, f.a.profile.id()) == ParticipantRole.WITHDRAWN
                        && PlayerProfiles.isAdministrativeSpectator(f.a.player) && f.idle(), "Voluntary exit and empty spectator body ready"))
                .thenExecute(() -> {
                    new TabListRuntime().refresh(f.server, f.round(), 0, null, true);
                    test.assertFalse(latestFooter(f.a).contains("退出本轮并观战"), "Excluded observer no longer receives an actionable shortcut hint");
                    test.assertTrue(latestFooter(f.b).contains("退出本轮并观战"), "Still eligible peer retains the hint independently");
                    shortcut(f.a.player, true);
                    test.assertFalse(LobbyMenus.isMenu(f.a.player), "An excluded player cannot reopen withdrawal confirmation");
                    test.assertTrue(f.a.player.getInventory().getItem(4).isEmpty(), "Competition equipment is sealed");
                    test.assertValueEqual(f.round().onlineHumans(), 2L, "Observers are not surviving humans");
                    test.assertValueEqual(f.round().participants().get(f.a.profile.id()).lifeSequence(), 1, "No artificial death or new life");
                    test.assertFalse(f.round().timingUncertain(), "Voluntary exit is not a sanction");
                    var source = f.b.player.damageSources().arrow(arrow[0], f.a.player);
                    test.assertTrue(MonsterCombatRuntime.rejectDamage(f.b.player, source), "Old arrow loses permission");
                    test.assertFalse(f.b.player.hurtServer(f.b.player.level(), source, 4), "Native delayed damage is rejected");
                    f.a.player.connection.handleTeleportToEntityPacket(new net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket(f.b.profile.id()));
                    test.assertTrue(f.a.player.level() == f.b.player.level(), "A non-OP withdrawer can use the vanilla spectator menu to watch the match");
                    archive[0] = f.server.getWorldPath(LevelResource.ROOT).resolve("hardrush/profiles/" + f.a.profile.id()
                            + "/archives/exit-" + f.round().roundId() + ".bin");
                    try { sealed[0] = Files.readAllBytes(archive[0]); } catch (Exception e) { throw new IllegalStateException(e); }
                    f.b.disconnect();
                })
                .thenWaitUntil(() -> f.await(f.idle() && !f.round().participants().get(f.b.profile.id()).online(), "Offline target reconciled"))
                .thenExecute(() -> execute(f.server.createCommandSourceStack(), "hardrush admin disqualify " + f.b.profile.id() + " 查实作弊"))
                .thenWaitUntil(() -> f.await(role(f, f.b.profile.id()) == ParticipantRole.DISQUALIFIED && f.idle(), "Offline sanction committed"));
        f.b.login(sequence);
        sequence.thenWaitUntil(() -> f.await(f.b.player.gameMode() == GameType.ADVENTURE && PlayerProfiles.canMoveInLobby(f.b.player) && f.idle(), "Sanctioned reconnect stays in lobby"))
                .thenExecute(() -> { test.assertTrue(f.round().timingUncertain(), "Sanction marks round for review"); f.place(f.b, LobbyLayout.Landing.RESTORE_READY); })
                .thenIdle(3)
                .thenExecute(() -> {
                    test.assertTrue(role(f, f.b.profile.id()) == ParticipantRole.DISQUALIFIED && !f.ready(f.b), "Assembly cannot restore eligibility");
                    test.assertFalse(dev.hardrush.lobby.HumanEntry.gateAvailable(f.b.player), "Middle gate cannot restore eligibility");
                    execute(f.server.createCommandSourceStack(), "gamemode spectator CampHumanB");
                    test.assertTrue(PlayerProfiles.isAdministrativeSpectator(f.b.player), "Operator may explicitly grant observation");
                    execute(f.server.createCommandSourceStack(), "hardrush admin disqualify CampHumanA 退赛后追加处罚");
                })
                .thenWaitUntil(() -> f.await(role(f, f.a.profile.id()) == ParticipantRole.DISQUALIFIED
                        && PlayerProfiles.canMoveInLobby(f.a.player) && f.idle(), "Sanction upgrades withdrawal and revokes automatic observation"))
                .thenExecute(() -> {
                    try { test.assertTrue(java.util.Arrays.equals(sealed[0], Files.readAllBytes(archive[0])), "First equipment archive is immutable"); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                    c.player.hurtServer(c.player.level(), c.player.damageSources().genericKill(), Float.MAX_VALUE);
                })
                .thenWaitUntil(() -> f.await(role(f, c.profile.id()) == ParticipantRole.HUMAN_RESPAWNING && f.idle(), "Native death durable"))
                .thenExecute(() -> execute(f.server.createCommandSourceStack(), "hardrush admin disqualify CampHumanC 等待重生时取消资格"))
                .thenWaitUntil(() -> {
                    c.player = c.player.connection.player;
                    f.await(role(f, c.profile.id()) == ParticipantRole.DISQUALIFIED && c.player.isAlive()
                            && LobbyWorld.isLobby(c.player.level()) && f.idle(), "Death-screen target safely replaced");
                })
                .thenExecute(() -> {
                    test.assertTrue(f.round().phase() == RoundPhase.SETTLING && f.round().result().reason().equals("NO_ELIGIBLE_PARTICIPANTS"), "Last eligible human exit settles the round");
                    test.assertValueEqual(f.round().participants().get(c.profile.id()).lifeSequence(), 1, "No extra respawn life");
                    f.clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(f.round().rules().failureDisplayMillis() + 1000));
                })
                .thenWaitUntil(() -> f.await(f.round().sequence() == 2 && f.round().phase() == RoundPhase.WAITING
                        && PlayerProfiles.canMoveInLobby(f.a.player) && PlayerProfiles.canMoveInLobby(f.b.player)
                        && PlayerProfiles.canMoveInLobby(c.player), "Next round restores eligibility"))
                .thenExecute(() -> f.place(f.a, LobbyLayout.Landing.RESTORE_READY))
                .thenWaitUntil(() -> f.await(f.ready(f.a) && f.idle(), "Formerly excluded player can prepare next round"))
                .thenExecute(() -> { f.a.disconnect(); f.b.disconnect(); c.disconnect(); }).thenSucceed();
    }
    private static String latestFooter(HumanEntryGameTest.Fixture.Peer peer) {
        return peer.receivedPackets().stream().filter(ClientboundTabListPacket.class::isInstance)
                .map(ClientboundTabListPacket.class::cast).reduce((first, last) -> last).orElseThrow().footer().getString();
    }
    static void shortcut(net.minecraft.server.level.ServerPlayer player, boolean sneaking) {
        sneak(player, sneaking);
        player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                player.blockPosition(), Direction.DOWN, 0));
    }
    static void sneak(net.minecraft.server.level.ServerPlayer player, boolean sneaking) {
        player.connection.handlePlayerInput(new ServerboundPlayerInputPacket(new Input(false, false, false, false, false, sneaking, false)));
    }
    private static ParticipantRole role(HumanEntryGameTest.Fixture f, java.util.UUID id) { return f.round().participants().get(id).role(); }
    private static void packet(net.minecraft.server.level.ServerPlayer player, String command) {
        player.connection.handleChatCommand(new ServerboundChatCommandPacket(command));
    }
    private static int execute(CommandSourceStack source, String command) {
        try { return source.getServer().getCommands().getDispatcher().execute(command, source); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
