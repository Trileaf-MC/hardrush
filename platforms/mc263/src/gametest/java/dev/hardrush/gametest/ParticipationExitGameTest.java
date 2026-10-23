package dev.hardrush.gametest;

import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.lobby.LobbyLayout;
import dev.hardrush.p1.P1LobbyRuntime;
import dev.hardrush.p1.P1LobbySession;
import dev.hardrush.p1.combat.P1CombatRuntime;
import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p1.world.P1LobbyWorld;
import dev.hardrush.p2.combat.P2CombatRuntime;
import dev.hardrush.p2.lobby.P2LobbyUi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

/** Native command/menu packets, profile archives, death-screen replacement and a complete rotation. */
public final class ParticipationExitGameTest {
    @GameTest(maxTicks = 60000)
    public void durableExitsKeepObservationSeparateFromEligibility(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.lobby.testParticipationExit")) { test.succeed(); return; }
        var f = new P1HumanEntryGameTest.Fixture(test); var c = f.new Peer("CampHumanC");
        var arrow = new Arrow[1]; var archive = new Path[1]; var sealed = new byte[1][];
        test.onEachTick(() -> { f.drain(); f.a.tickConnection(); f.b.tickConnection(); c.tickConnection(); });
        var sequence = test.startSequence().thenWaitUntil(() -> f.await(P1LobbyWorld.open(f.server).ready()
                && P1LobbyRuntime.snapshot(f.server).filter(r -> r.phase() == RoundPhase.WAITING).isPresent()
                && !((P1LobbySession) f.service("SESSIONS")).mutationPending(), "Participation exits camp ready"))
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
                .thenExecute(() -> f.place(f.b, LobbyLayout.Landing.RESTORE_READY))
                .thenWaitUntil(() -> f.await(f.ready(f.b) && f.idle(), "Second ready"))
                .thenExecute(() -> f.place(c, LobbyLayout.Landing.RESTORE_READY))
                .thenWaitUntil(() -> f.await(f.ready(c) && f.idle(), "Third ready"))
                .thenExecute(() -> f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30)))
                .thenWaitUntil(() -> f.await(f.round().onlineHumans() == 3 && !P1PlayerProfiles.isLocked(f.a.player)
                        && !P1PlayerProfiles.isLocked(f.b.player) && !P1PlayerProfiles.isLocked(c.player) && f.idle(), "Three humans admitted"))
                .thenExecute(() -> {
                    f.a.player.getInventory().setItem(4, new ItemStack(Items.DIAMOND, 7));
                    arrow[0] = new Arrow(f.a.player.level(), f.a.player, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
                    test.assertTrue(P1CombatRuntime.originOf(arrow[0]).isPresent(), "Real human arrow origin");
                    packet(f.a.player, "hardrush spectate");
                    test.assertTrue(P2LobbyUi.isMenu(f.a.player), "Voluntary exit opens vanilla confirmation");
                    f.click(f.a, (short)3);
                    test.assertTrue(role(f, f.a.profile.id()) == ParticipantRole.HUMAN_ACTIVE, "Cancel preserves identity");
                    packet(f.a.player, "hardrush spectate"); f.click(f.a, (short)2);
                    test.assertTrue(f.session.participationExitReserved(f.a.profile.id()), "Pending exit seals input before SQLite callback");
                    f.a.assertFrozen();
                })
                .thenWaitUntil(() -> f.await(role(f, f.a.profile.id()) == ParticipantRole.WITHDRAWN
                        && P1PlayerProfiles.isAdministrativeSpectator(f.a.player) && f.idle(), "Voluntary exit and empty spectator body ready"))
                .thenExecute(() -> {
                    test.assertTrue(f.a.player.getInventory().getItem(4).isEmpty(), "Competition equipment is sealed");
                    test.assertValueEqual(f.round().onlineHumans(), 2L, "Observers are not surviving humans");
                    test.assertValueEqual(f.round().participants().get(f.a.profile.id()).lifeSequence(), 1, "No artificial death or new life");
                    test.assertFalse(f.round().timingUncertain(), "Voluntary exit is not a sanction");
                    var source = f.b.player.damageSources().arrow(arrow[0], f.a.player);
                    test.assertTrue(P2CombatRuntime.rejectDamage(f.b.player, source), "Old arrow loses permission");
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
        sequence.thenWaitUntil(() -> f.await(f.b.player.gameMode() == GameType.ADVENTURE && P1PlayerProfiles.canMoveInLobby(f.b.player) && f.idle(), "Sanctioned reconnect stays in lobby"))
                .thenExecute(() -> { test.assertTrue(f.round().timingUncertain(), "Sanction marks round for review"); f.place(f.b, LobbyLayout.Landing.RESTORE_READY); })
                .thenIdle(3)
                .thenExecute(() -> {
                    test.assertTrue(role(f, f.b.profile.id()) == ParticipantRole.DISQUALIFIED && !f.ready(f.b), "Assembly cannot restore eligibility");
                    test.assertFalse(dev.hardrush.p1.lobby.P1HumanEntry.gateAvailable(f.b.player), "Middle gate cannot restore eligibility");
                    execute(f.server.createCommandSourceStack(), "gamemode spectator CampHumanB");
                    test.assertTrue(P1PlayerProfiles.isAdministrativeSpectator(f.b.player), "Operator may explicitly grant observation");
                    execute(f.server.createCommandSourceStack(), "hardrush admin disqualify CampHumanA 退赛后追加处罚");
                })
                .thenWaitUntil(() -> f.await(role(f, f.a.profile.id()) == ParticipantRole.DISQUALIFIED
                        && P1PlayerProfiles.canMoveInLobby(f.a.player) && f.idle(), "Sanction upgrades withdrawal and revokes automatic observation"))
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
                            && P1LobbyWorld.isLobby(c.player.level()) && f.idle(), "Death-screen target safely replaced");
                })
                .thenExecute(() -> {
                    test.assertTrue(f.round().phase() == RoundPhase.SETTLING && f.round().result().reason().equals("NO_ELIGIBLE_PARTICIPANTS"), "Last eligible human exit settles the round");
                    test.assertValueEqual(f.round().participants().get(c.profile.id()).lifeSequence(), 1, "No extra respawn life");
                    f.clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(f.round().rules().failureDisplayMillis() + 1000));
                })
                .thenWaitUntil(() -> f.await(f.round().sequence() == 2 && f.round().phase() == RoundPhase.WAITING
                        && P1PlayerProfiles.canMoveInLobby(f.a.player) && P1PlayerProfiles.canMoveInLobby(f.b.player)
                        && P1PlayerProfiles.canMoveInLobby(c.player), "Next round restores eligibility"))
                .thenExecute(() -> f.place(f.a, LobbyLayout.Landing.RESTORE_READY))
                .thenWaitUntil(() -> f.await(f.ready(f.a) && f.idle(), "Formerly excluded player can prepare next round"))
                .thenExecute(() -> { f.a.disconnect(); f.b.disconnect(); c.disconnect(); }).thenSucceed();
    }
    private static ParticipantRole role(P1HumanEntryGameTest.Fixture f, java.util.UUID id) { return f.round().participants().get(id).role(); }
    private static void packet(net.minecraft.server.level.ServerPlayer player, String command) {
        player.connection.handleChatCommand(new ServerboundChatCommandPacket(command));
    }
    private static int execute(CommandSourceStack source, String command) {
        try { return source.getServer().getCommands().getDispatcher().execute(command, source); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
