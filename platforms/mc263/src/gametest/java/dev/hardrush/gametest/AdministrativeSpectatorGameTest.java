package dev.hardrush.gametest;

import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.p1.P1LobbyRuntime;
import dev.hardrush.p1.P1LobbySession;
import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p1.world.P1LobbyWorld;
import dev.hardrush.p1.world.P1RoundWorldBundle;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Native command packets, OP permissions, profiles, movement and SQLite; no graphical client. */
public final class AdministrativeSpectatorGameTest {
    @GameTest(maxTicks = 60000)
    public void operatorsAuthorizeNonParticipantsWithoutOpeningOrdinaryGameplay(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.lobby.testAdminSpectator")) { test.succeed(); return; }
        var f = new P1HumanEntryGameTest.Fixture(test);
        var ordinaryPosition = new Vec3[1];
        test.onEachTick(() -> { f.drain(); f.a.tickConnection(); f.b.tickConnection(); });
        var sequence = test.startSequence().thenWaitUntil(() -> f.await(P1LobbyWorld.open(f.server).ready()
                && P1LobbyRuntime.snapshot(f.server).filter(r -> r.phase() == RoundPhase.WAITING).isPresent()
                && !((P1LobbySession) f.service("SESSIONS")).mutationPending(), "Admin spectator camp ready"))
                .thenExecute(() -> { f.bind(); f.callbacks(); });
        f.a.login(sequence); f.b.login(sequence);
        sequence.thenWaitUntil(() -> f.await(f.idle(), "Both non-participant profiles ready"))
                .thenExecute(() -> {
                    packet(f.b.player, "gamemode spectator");
                    test.assertFalse(P1PlayerProfiles.administrativeCommandAllowed(f.b.player, "tp @s CampHumanA"), "Non-OP commands stay sealed");
                    f.server.getPlayerList().op(new NameAndId(f.a.profile),
                            Optional.of(LevelBasedPermissionSet.forLevel(PermissionLevel.MODERATORS)), Optional.empty());
                    test.assertFalse(P1PlayerProfiles.administrativeCommandAllowed(f.a.player, "gamemode spectator"), "OP 1 cannot open travel commands");
                    f.server.getPlayerList().op(new NameAndId(f.a.profile),
                            Optional.of(LevelBasedPermissionSet.forLevel(PermissionLevel.GAMEMASTERS)), Optional.empty());
                    packet(f.a.player, "gamemode spectator");
                })
                .thenWaitUntil(() -> f.await(P1PlayerProfiles.isAdministrativeSpectator(f.a.player), "OP 2 opens spectator mode through the actual command packet"))
                .thenExecute(() -> {
                    test.assertTrue(f.b.player.gameMode() == GameType.ADVENTURE, "Ordinary player could not self-authorize spectator mode");
                    var worlds = (P1RoundWorldBundle) f.service("WORLDS");
                    command(f.server.createCommandSourceStack().withLevel(worlds.level(Level.OVERWORLD)), "tp CampHumanB 0 150 0");
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    test.assertTrue(P1LobbyWorld.isLobby(f.b.player.level()), "An ordinary unadmitted TP still returns to the lobby");
                    packet(f.a.player, "gamemode spectator CampHumanB");
                })
                .thenWaitUntil(() -> f.await(P1PlayerProfiles.isAdministrativeSpectator(f.b.player), "Operator grants a non-OP spectator mode"))
                .thenExecute(() -> {
                    assertObserver(test, f.a.player); assertObserver(test, f.b.player);
                    var worlds = (P1RoundWorldBundle) f.service("WORLDS");
                    command(f.server.createCommandSourceStack().withLevel(worlds.level(Level.OVERWORLD)), "tp CampHumanB 0 150 0");
                    packet(f.a.player, "teleport CampHumanA CampHumanB");
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    test.assertTrue(f.a.player.level() == f.b.player.level() && !P1LobbyWorld.isLobby(f.a.player.level()), "Native teleport alias crosses into the current round");
                    assertObserver(test, f.a.player); assertObserver(test, f.b.player);
                    f.a.move(.2, .42, 45, 15, false);
                    ordinaryPosition[0] = f.b.player.position();
                    packet(f.b.player, "tp 100 180 100");
                    packet(f.b.player, "gamemode adventure");
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    test.assertTrue(f.b.player.position().distanceToSqr(ordinaryPosition[0]) < .000001 && f.b.player.isSpectator(), "A granted non-OP gets movement but no command permissions");
                    test.assertFalse(f.b.player.teleportTo(f.server.overworld(), .5, 150, .5, Set.of(), 0, 0, true), "Spectators cannot visit unrelated worlds");
                    test.assertValueEqual(f.round().onlineHumans(), 0L, "Observers do not count as surviving humans");
                    for (var p : f.round().participants().values()) {
                        test.assertTrue(p.role() == ParticipantRole.LOBBY && p.lifeSequence() == 0 && p.humanNanos() == 0 && !p.ready(), "Travel never grants roster admission or time");
                    }
                    command(f.server.createCommandSourceStack().withLevel(P1LobbyWorld.open(f.server).holdingLevel()), "tp CampHumanB 0 100 0");
                    packet(f.a.player, "tp CampHumanA CampHumanB");
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    test.assertTrue(P1LobbyWorld.isLobby(f.a.player.level()) && P1LobbyWorld.isLobby(f.b.player.level()), "Operator and target can return to the lobby without losing spectator mode");
                    packet(f.a.player, "gamemode survival CampHumanB");
                    test.assertTrue(f.b.player.isSpectator(), "A spectator cannot be turned into an unadmitted survival body");
                    packet(f.a.player, "gamemode adventure CampHumanB");
                    packet(f.a.player, "gamemode adventure");
                })
                .thenWaitUntil(() -> f.await(P1PlayerProfiles.canMoveInLobby(f.a.player) && P1PlayerProfiles.canMoveInLobby(f.b.player), "Adventure ends observation and restores normal lobby bodies"))
                .thenExecute(() -> f.place(f.a, dev.hardrush.core.lobby.LobbyLayout.Landing.RESTORE_READY))
                .thenWaitUntil(() -> f.await(f.ready(f.a) && f.idle(), "Operator prepares through the ordinary assembly"))
                .thenExecute(() -> {
                    test.assertValueEqual(command(f.server.createCommandSourceStack(), "gamemode spectator CampHumanA"), 0, "A prepared player must leave assembly before spectating");
                    f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
                })
                .thenWaitUntil(() -> f.await(f.round().phase() == RoundPhase.RUNNING && !P1PlayerProfiles.isLocked(f.a.player) && f.idle(), "Operator enters as an ordinary participant"))
                .thenExecute(() -> {
                    test.assertValueEqual(command(f.server.createCommandSourceStack(), "gamemode spectator CampHumanA"), 0, "A participating OP cannot become an administrative spectator");
                    test.assertTrue(f.a.player.gameMode() == GameType.SURVIVAL && f.round().onlineHumans() == 1, "Denied spectator command preserves the active identity");
                    packet(f.a.player, "gamemode spectator CampHumanB");
                })
                .thenWaitUntil(() -> f.await(P1PlayerProfiles.isAdministrativeSpectator(f.b.player), "Participating operator can authorize a different unadmitted player"))
                .thenExecute(() -> packet(f.a.player, "tp CampHumanB CampHumanA"))
                .thenIdle(3)
                .thenExecute(() -> {
                    test.assertTrue(f.b.player.level() == f.a.player.level(), "Operator moves another player into the live match");
                    assertObserver(test, f.b.player);
                    f.b.disconnect();
                });
        f.b.login(sequence);
        sequence.thenExecute(() -> {
                    test.assertFalse(P1PlayerProfiles.isAdministrativeSpectator(f.b.player), "A reconnect needs a new explicit grant");
                    test.assertTrue(P1LobbyWorld.isLobby(f.b.player.level()) && f.b.player.gameMode() == GameType.ADVENTURE, "Reconnect cannot restore a spectator competition location");
                    packet(f.a.player, "gamemode spectator CampHumanB");
                })
                .thenWaitUntil(() -> f.await(P1PlayerProfiles.isAdministrativeSpectator(f.b.player), "Regrant after reconnect"));
        for (var dimension : java.util.List.of(Level.NETHER, Level.END)) {
            sequence.thenExecute(() -> {
                var worlds = (P1RoundWorldBundle) f.service("WORLDS");
                ServerLevel destination = worlds.level(dimension);
                command(f.server.createCommandSourceStack().withLevel(destination), "tp CampHumanB 0 150 0");
            }).thenIdle(3).thenExecute(() -> {
                assertObserver(test, f.b.player);
                test.assertTrue(f.b.player.level().dimension().identifier().toString().endsWith(dimension == Level.NETHER ? "/nether" : "/end"), "Observers can visit every current-round dimension");
            });
        }
        sequence.thenExecute(() -> f.session.adapterFailure("管理旁观隔离回归", new IllegalStateException("intentional fixture")))
                .thenWaitUntil(() -> f.await(P1LobbyWorld.isLobby(f.b.player.level()) && f.b.player.gameMode() == GameType.ADVENTURE
                        && !P1PlayerProfiles.isAdministrativeSpectator(f.b.player), "A runtime failure revokes observation and evacuates the round"))
                .thenExecute(() -> {
                    test.assertTrue(P1PlayerProfiles.simulationFrozen(f.b.player), "Spectating cannot bypass recovery isolation");
                    f.a.disconnect(); f.b.disconnect();
                }).thenSucceed();
    }

    private static void assertObserver(GameTestHelper test, ServerPlayer player) {
        test.assertTrue(P1PlayerProfiles.isAdministrativeSpectator(player) && !P1PlayerProfiles.simulationFrozen(player), "Authorized spectator can move");
        test.assertTrue(P1PlayerProfiles.isLocked(player) && !P1PlayerProfiles.verifiedLobbyBody(player), "Movement leaves admission, items and lobby interactions sealed");
    }
    private static void packet(ServerPlayer player, String command) {
        player.connection.handleChatCommand(new ServerboundChatCommandPacket(command));
    }
    private static int command(CommandSourceStack source, String command) {
        try { return source.getServer().getCommands().getDispatcher().execute(command, source); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
