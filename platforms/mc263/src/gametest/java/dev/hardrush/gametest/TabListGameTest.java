package dev.hardrush.gametest;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.PauseReason;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundResult;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.record.TabListRuntime;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Headless packet capture: TAB presentation never replaces native online-player entries. */
public final class TabListGameTest {
    @GameTest(maxTicks = 100)
    public void nativeHeaderFooterShowsRaceChangesAndNewConnectionGetsItsFirstPacket(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var first = test.makeMockServerPlayerInLevel();
        var second = test.makeMockServerPlayerInLevel();
        var runtime = new TabListRuntime();
        var id = UUID.randomUUID();
        try (var a = new Capture(first); var b = new Capture(second)) {
            var running = round(id, first, second, RoundPhase.RUNNING, PauseReason.NONE, 0, null);
            runtime.refresh(server, running, 7, null, true);
            var packet = latest(test, a);
            test.assertTrue(packet.header().getString().contains("第 42 轮") && packet.header().getString().contains("普通")
                    && packet.header().getString().contains("01:02:03"), "Native TAB header contains round, mode and second-granularity challenge time");
            test.assertTrue(packet.footer().getString().contains("进入过末地 7 人") && packet.footer().getString().contains("人类在线 2"),
                    "Native TAB footer contains UUID-deduplicated End entrants and online human count");
            test.assertValueEqual(latest(test, b).header().getString(), packet.header().getString(), "Both native connections receive the same race header");
            runtime.refresh(server, running, 7, null, false);
            runtime.refresh(server, running, 7, null, true);
            test.assertValueEqual(a.packets.size(), 1, "Unchanged header/footer does not resend to an existing connection");
            test.assertValueEqual(b.packets.size(), 1, "Periodic and forced refresh both deduplicate unchanged text");

            var paused = round(id, first, second, RoundPhase.RUNNING, PauseReason.NO_ONLINE_HUMANS, 0, null);
            runtime.refresh(server, paused, 8, null, true);
            packet = latest(test, a);
            test.assertTrue(packet.header().getString().contains("暂停（无人类在线）") && packet.footer().getString().contains("进入过末地 8 人"),
                    "A state-event refresh immediately publishes pause status and new End entrant count");
            var countdown = round(id, first, second, RoundPhase.COUNTDOWN, PauseReason.NOT_RUNNING, 2_001_000_000L, null);
            runtime.refresh(server, countdown, 8, null, true);
            test.assertTrue(latest(test, a).footer().getString().contains("开局倒计时 3 秒"), "Nonzero partial seconds round up in countdown text");
            var celebration = round(id, first, second, RoundPhase.CELEBRATING, PauseReason.SETTLED, 600_000_000_000L,
                    new RoundResult(RoundResult.Kind.COMPLETED, first.getUUID(), "trusted-first-dragon"));
            runtime.refresh(server, celebration, 8, null, true);
            packet = latest(test, a);
            test.assertTrue(packet.footer().getString().contains("尾刀：" + first.getScoreboardName())
                    && packet.footer().getString().contains("轮换倒计时 600 秒"), "Terminal TAB displays the committed winner and celebration countdown");
            int unchangedSecond = b.packets.size();
            try (var reconnected = new Capture(first)) {
                runtime.refresh(server, celebration, 8, null, true);
                test.assertValueEqual(reconnected.packets.size(), 1, "A replacement native connection receives its first packet despite unchanged round text");
                test.assertValueEqual(latest(test, reconnected).footer().getString(), packet.footer().getString(), "Reconnect begins with the complete current footer");
                test.assertValueEqual(b.packets.size(), unchangedSecond, "One reconnect does not resend to unchanged peer connections");
                onlyTabPackets(test, reconnected);
            }
            onlyTabPackets(test, a);
            onlyTabPackets(test, b);
        } finally {
            server.getPlayerList().remove(first);
            server.getPlayerList().remove(second);
        }
        test.succeed();
    }

    private static RoundSnapshot round(UUID id, ServerPlayer first, ServerPlayer second, RoundPhase phase,
            PauseReason pause, long remaining, RoundResult result) {
        var players = new LinkedHashMap<UUID, Participant>();
        for (var player : new ServerPlayer[]{first, second}) {
            players.put(player.getUUID(), new Participant(player.getUUID(), player.getScoreboardName(),
                    ParticipantRole.HUMAN_ACTIVE, true, false, 0, 0, 1, false));
        }
        return new RoundSnapshot(id, 42, 12345, 1, RulesConfig.defaults().withMode(GameMode.NORMAL), phase, pause,
                3_723_999_000_000L, remaining, players, false, false, result);
    }
    private static ClientboundTabListPacket latest(GameTestHelper test, Capture capture) {
        var packet = capture.packets.stream().filter(ClientboundTabListPacket.class::isInstance)
                .map(ClientboundTabListPacket.class::cast).reduce((previous, current) -> current).orElseThrow();
        test.assertTrue(packet.header().getContents() instanceof PlainTextContents && packet.footer().getContents() instanceof PlainTextContents,
                "TAB uses literal plain-text Components, never interpreted names or translated command content");
        test.assertTrue(packet.header().getSiblings().isEmpty() && packet.footer().getSiblings().isEmpty(), "The TAB text has no embedded interactive Components");
        return packet;
    }
    private static void onlyTabPackets(GameTestHelper test, Capture capture) {
        test.assertTrue(capture.packets.stream().noneMatch(packet -> packet instanceof ClientboundPlayerInfoUpdatePacket
                        || packet instanceof ClientboundPlayerInfoRemovePacket), "TAB never removes or replaces native online-player list entries");
        test.assertTrue(capture.packets.stream().allMatch(ClientboundTabListPacket.class::isInstance), "The TAB adapter sends only native header/footer packets");
    }
    private static final class Capture implements AutoCloseable {
        final ServerPlayer player;
        final ServerGamePacketListenerImpl previous;
        final ArrayList<Packet<?>> packets;
        final EmbeddedChannel channel;
        Capture(ServerPlayer player) {
            this.player = player;
            previous = player.connection;
            var connection = new RecordingConnection();
            packets = connection.packets;
            channel = new EmbeddedChannel(connection);
            player.connection = new ServerGamePacketListenerImpl(player.level().getServer(), connection, player,
                    CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }
        @Override public void close() { player.connection = previous; channel.finishAndReleaseAll(); }
    }
    private static final class RecordingConnection extends Connection {
        final ArrayList<Packet<?>> packets = new ArrayList<>();
        RecordingConnection() { super(PacketFlow.SERVERBOUND); }
        private void record(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) bundle.subPackets().forEach(this::record);
            else packets.add(packet);
        }
        @Override public void send(Packet<?> packet) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { record(packet); }
    }
}
