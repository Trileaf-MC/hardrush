package dev.hardrush.gametest;

import dev.hardrush.monster.MonsterRoles;
import dev.hardrush.locator.PlayerLocator;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.waypoints.WaypointTransmitter;

/** Native connections and outgoing packets, without starting a graphical client. */
public final class PlayerLocatorGameTest {
    @GameTest(maxTicks = 100)
    public void everySpeciesIsHiddenFromHumansForAllThreeNativeConnectionTypes(GameTestHelper test) {
        try (var human = new Actor(test, 1000); var source = new Actor(test, 1010);
                var monster = new Actor(test, 1020)) {
            MonsterRoles.select(monster.player, "zombie");
            for (String kind : List.of("zombie", "skeleton", "creeper")) {
                for (int distance : new int[]{10, 160, 400}) {
                    source.player.snapTo(1000 + distance, 150, 1000);
                    var previous = source.player.makeWaypointConnectionWith(human.player).orElseThrow();
                    test.assertTrue(switch (distance) {
                        case 10 -> previous instanceof WaypointTransmitter.EntityBlockConnection;
                        case 160 -> previous instanceof WaypointTransmitter.EntityChunkConnection;
                        default -> previous instanceof WaypointTransmitter.EntityAzimuthConnection;
                    }, "The control uses the expected native precise/chunk/direction connection");
                    test.assertFalse(previous.isBroken(), "Human-to-human control is initially connected");
                    MonsterRoles.select(source.player, kind);
                    test.assertTrue(previous.isBroken(), "Existing human connection breaks after becoming " + kind);
                    test.assertTrue(source.player.makeWaypointConnectionWith(human.player).isEmpty(),
                            "No new monster position can be transmitted to a human");
                    test.assertTrue(human.player.makeWaypointConnectionWith(source.player).isPresent(),
                            "A monster can still receive a human waypoint");
                    test.assertTrue(source.player.makeWaypointConnectionWith(monster.player).isPresent(),
                            "Monster-to-monster visibility retains native behavior");
                    human.player.setGameMode(GameType.SPECTATOR);
                    test.assertTrue(source.player.makeWaypointConnectionWith(human.player).isEmpty(),
                            "Changing Minecraft game mode cannot bypass the human identity filter");
                    human.player.setGameMode(GameType.SURVIVAL);
                    MonsterRoles.clear(source.player);
                    test.assertTrue(source.player.makeWaypointConnectionWith(human.player).isPresent(),
                            "Returning to human restores visibility");
                }
            }
        }
        test.succeed();
    }

    @GameTest(maxTicks = 100)
    public void identityChangesRemoveAndRestoreActualPacketsWithoutMovement(GameTestHelper test) {
        var server = test.getLevel().getServer();
        try (var human = new Actor(test, 2000); var source = new Actor(test, 2010);
                var monster = new Actor(test, 2020)) {
            MonsterRoles.select(monster.player, "skeleton");
            var manager = test.getLevel().getWaypointManager();
            for (var actor : List.of(human, source, monster)) {
                manager.addPlayer(actor.player);
                manager.trackWaypoint(actor.player);
            }
            PlayerLocator.tick(server);
            test.assertTrue(human.saw(source.player, "TRACK"), "Human control receives the original human marker");
            test.assertTrue(monster.saw(source.player, "TRACK"), "Monster control receives the original human marker");
            human.clear(); monster.clear();

            MonsterRoles.select(source.player, "creeper");
            PlayerLocator.tick(server);
            test.assertTrue(human.saw(source.player, "UNTRACK"), "Identity refresh sends removal of the old human marker");
            test.assertFalse(human.saw(source.player, "TRACK") || human.saw(source.player, "UPDATE"),
                    "Human receives no replacement position for the monster");
            test.assertFalse(monster.saw(source.player, "UNTRACK"), "Monster observer keeps its existing connection");
            test.assertTrue(source.saw(human.player, "TRACK"), "The new monster keeps receiving the human marker");
            human.clear();

            MonsterRoles.select(human.player, "zombie");
            PlayerLocator.tick(server);
            test.assertTrue(human.saw(source.player, "TRACK"), "A receiver becoming a monster gains the permitted connection");
            human.clear();
            MonsterRoles.clear(human.player);
            PlayerLocator.tick(server);
            test.assertTrue(human.saw(source.player, "UNTRACK"), "A receiver returning to human loses monster markers");
            human.clear();

            MonsterRoles.clear(source.player);
            PlayerLocator.tick(server);
            test.assertTrue(human.saw(source.player, "TRACK"), "A source returning to human is broadcast again");
            try (var reconnected = new Capture(human.player)) {
                manager.removePlayer(human.player);
                manager.addPlayer(human.player);
                test.assertTrue(reconnected.saw(source.player.getUUID(), "TRACK"), "A fresh connection receives the human source");
                test.assertFalse(reconnected.saw(monster.player.getUUID(), "TRACK"), "A fresh human connection never receives monsters");
            }
        }
        test.succeed();
    }

    @GameTest(maxTicks = 100)
    public void nativeRangeAndTransmissionRestrictionsStillApplyToMonsterReceivers(GameTestHelper test) {
        try (var source = new Actor(test, 3000); var monster = new Actor(test, 3040)) {
            MonsterRoles.select(monster.player, "zombie");
            test.assertTrue(source.player.makeWaypointConnectionWith(monster.player).isPresent(), "Normal human location is available");
            source.player.getAttribute(Attributes.WAYPOINT_TRANSMIT_RANGE).setBaseValue(20);
            test.assertTrue(source.player.makeWaypointConnectionWith(monster.player).isEmpty(), "Native source range is respected");
            source.player.getAttribute(Attributes.WAYPOINT_TRANSMIT_RANGE).setBaseValue(1000);
            monster.player.getAttribute(Attributes.WAYPOINT_RECEIVE_RANGE).setBaseValue(20);
            test.assertTrue(source.player.makeWaypointConnectionWith(monster.player).isEmpty(), "Native receiving range is respected");
            monster.player.getAttribute(Attributes.WAYPOINT_RECEIVE_RANGE).setBaseValue(1000);
            source.player.getAttribute(Attributes.WAYPOINT_TRANSMIT_RANGE).setBaseValue(0);
            test.assertTrue(source.player.makeWaypointConnectionWith(monster.player).isEmpty(), "Native hiding is not overridden");
        }
        test.succeed();
    }

    private static final class Actor implements AutoCloseable {
        final ServerPlayer player;
        final Capture capture;
        Actor(GameTestHelper test, int x) {
            player = test.makeMockServerPlayerInLevel();
            capture = new Capture(player);
            player.snapTo(x, 150, x / 1000 * 1000);
            player.setChunkTrackingView(ChunkTrackingView.of(player.chunkPosition(), 4));
            player.setGameMode(GameType.SURVIVAL);
            player.baseTick();
        }
        boolean saw(ServerPlayer source, String operation) { return capture.saw(source.getUUID(), operation); }
        void clear() { capture.packets.clear(); }
        @Override public void close() {
            MonsterRoles.clear(player);
            player.level().getServer().getPlayerList().remove(player);
            capture.close();
        }
    }

    private static final class Capture implements AutoCloseable {
        final ServerPlayer player;
        final ServerGamePacketListenerImpl previous;
        final ArrayList<ClientboundTrackedWaypointPacket> packets = new ArrayList<>();
        final EmbeddedChannel channel;
        Capture(ServerPlayer player) {
            this.player = player;
            previous = player.connection;
            var connection = new Connection(PacketFlow.SERVERBOUND) {
                private void record(Packet<?> packet) {
                    if (packet instanceof ClientboundBundlePacket bundle) bundle.subPackets().forEach(this::record);
                    else if (packet instanceof ClientboundTrackedWaypointPacket waypoint) packets.add(waypoint);
                }
                @Override public void send(Packet<?> packet) { record(packet); }
                @Override public void send(Packet<?> packet, ChannelFutureListener listener) { record(packet); }
                @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { record(packet); }
            };
            channel = new EmbeddedChannel(connection);
            player.connection = new ServerGamePacketListenerImpl(player.level().getServer(), connection, player,
                    CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }
        boolean saw(UUID source, String operation) {
            return packets.stream().anyMatch(packet -> packet.waypoint().id().left().filter(source::equals).isPresent()
                    && String.valueOf(packet.operation()).equals(operation));
        }
        @Override public void close() { player.connection = previous; channel.finishAndReleaseAll(); }
    }
}
