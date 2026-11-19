package dev.hardrush.revival;

import com.mojang.authlib.GameProfile;
import dev.hardrush.HardRushProperties;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.gametest.TestClients;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.ChatFormatting;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * Revival charges in the player list (docs/plan/复生图腾掉落.md 1.7, 2.8) as the packets real fixture connections
 * receive: the objective right after vanilla's scoreboard at login, one packet per change, resets for whoever stops
 * showing, a rebuilt objective for a new round. The round runtime is scripted through {@link TotemTab.Access}.
 * {@code -PhardrushTotemTab=true} runs it alone.
 */
public final class TotemTabGameTest {
    private static final AtomicInteger RUNS = new AtomicInteger();
    private static final String SERVER_OBJECTIVE = "hardrush_tab_owner";

    @GameTest(maxTicks = 100)
    public void chargesShowBesideNamesOnlyWhileTheyCount(GameTestHelper test) {
        if (HardRushProperties.competitionEnabled()) { test.succeed(); return; }
        var server = test.getLevel().getServer();
        var s = new Script();
        var clients = new ArrayList<Client>();
        int run = RUNS.getAndIncrement();
        // The server owner's own list objective, which vanilla sends at login before ours.
        var owner = server.getScoreboard().addObjective(SERVER_OBJECTIVE, ObjectiveCriteria.DUMMY, Component.literal("owner"),
                ObjectiveCriteria.RenderType.INTEGER, false, null);
        server.getScoreboard().setDisplayObjective(DisplaySlot.LIST, owner);
        TotemTab.configure(server, s);
        try {
            var human = client(server, clients, "H" + run); var monster = client(server, clients, "M" + run);
            s.put(human, ParticipantRole.HUMAN_ACTIVE, 2);
            s.put(monster, ParticipantRole.MONSTER_LOBBY, 1);
            var empty = client(server, clients, "E" + run); s.put(empty, ParticipantRole.HUMAN_ACTIVE, 0);
            var withdrawn = client(server, clients, "W" + run); s.put(withdrawn, ParticipantRole.WITHDRAWN, 3);
            var offline = UUID.randomUUID();
            s.players.put(offline, participant(offline, "Offline", ParticipantRole.HUMAN_ACTIVE, 4));
            var outsider = client(server, clients, "N" + run);

            // Login: the objective and every current score follow vanilla's own list objective.
            for (var c : clients) {
                test.assertValueEqual((int) c.adds(), 1, "Each connection gets the objective exactly once: " + c.name());
                test.assertTrue(c.lastListObjective().equals(TotemTab.OBJECTIVE), "Ours is the list objective after vanilla's: " + c.name());
            }
            test.assertTrue(outsider.packets.stream().anyMatch(p -> p instanceof ClientboundSetDisplayObjectivePacket d
                    && SERVER_OBJECTIVE.equals(d.getObjectiveName())), "Vanilla sent the server's list objective first");
            var objective = outsider.packets.stream().filter(ClientboundSetObjectivePacket.class::isInstance)
                    .map(ClientboundSetObjectivePacket.class::cast).filter(p -> TotemTab.OBJECTIVE.equals(p.getObjectiveName())).findFirst().orElseThrow();
            test.assertTrue(objective.getRenderType() == ObjectiveCriteria.RenderType.INTEGER, "Shown as integers");
            test.assertValueEqual(outsider.score(human.name()).orElseThrow(), "✦2", "A newcomer gets the human's count at login");
            test.assertValueEqual(outsider.score(monster.name()).orElseThrow(), "✦1", "Monsters show theirs too");
            test.assertTrue(outsider.score(empty.name()).isEmpty() && outsider.score(withdrawn.name()).isEmpty()
                    && outsider.score("Offline").isEmpty() && outsider.score(outsider.name()).isEmpty(),
                    "No score for none left, a withdrawn player, an offline one or an outsider");
            var badge = outsider.scores().filter(p -> p.owner().equals(human.name())).findFirst().orElseThrow()
                    .numberFormat().map(FixedFormat.class::cast).orElseThrow().value();
            test.assertTrue(net.minecraft.network.chat.TextColor.fromLegacyFormat(ChatFormatting.GOLD).equals(badge.getStyle().getColor()),
                    "The count is gold");
            test.assertValueEqual(human.score(monster.name()).orElseThrow(), "✦1", "An earlier player gets a later one's count");
            test.assertTrue(server.getScoreboard().getObjective(TotemTab.OBJECTIVE) == null, "The server scoreboard is never written");

            // Unchanged: nothing more. A change: one packet per connection, once.
            clear(clients);
            TotemTab.refresh(server);
            for (var c : clients) test.assertValueEqual(c.packets.size(), 0, "Nothing unchanged is resent: " + c.name());
            s.put(human, ParticipantRole.HUMAN_ACTIVE, 3);
            TotemTab.tick(server);
            for (var c : clients) test.assertValueEqual(c.packets.size(), 0, "Within the same second the change waits: " + c.name());
            TotemTab.refresh(server);
            TotemTab.refresh(server);
            for (var c : clients) {
                test.assertValueEqual(c.packets.size(), 1, "One packet for one change: " + c.name());
                test.assertValueEqual(c.score(human.name()).orElseThrow(), "✦3", "The new count: " + c.name());
            }

            // A save in flight already counts, as the held stack does.
            clear(clients);
            s.inFlight.put(human.id(), 1);
            TotemTab.refresh(server);
            test.assertValueEqual(outsider.score(human.name()).orElseThrow(), "✦2", "The count follows the charges left");
            s.inFlight.clear();
            s.put(human, ParticipantRole.HUMAN_ACTIVE, 2);

            // None left, or out of the round: a reset, and nothing more afterwards.
            clear(clients);
            s.put(human, ParticipantRole.HUMAN_ACTIVE, 0);
            s.put(monster, ParticipantRole.DISQUALIFIED, 1);
            TotemTab.refresh(server);
            for (var c : clients) {
                test.assertTrue(c.reset(human.name()) && c.reset(monster.name()) && c.scores().findAny().isEmpty(),
                        "No charges or a disqualification resets the score: " + c.name());
                test.assertValueEqual(c.packets.size(), 2, "Only the two resets: " + c.name());
            }
            clear(clients);
            TotemTab.refresh(server);
            for (var c : clients) test.assertValueEqual(c.packets.size(), 0, "A reset is sent once: " + c.name());

            // Normal mode, or a round past its race, shows nothing.
            s.put(human, ParticipantRole.HUMAN_ACTIVE, 2);
            TotemTab.refresh(server);
            clear(clients);
            s.rules = RulesConfig.defaults().withMode(GameMode.NORMAL);
            TotemTab.refresh(server);
            test.assertTrue(outsider.reset(human.name()), "Normal mode clears the list");
            s.rules = RulesConfig.defaults();
            TotemTab.refresh(server);
            clear(clients);
            s.phase = RoundPhase.SETTLING;
            TotemTab.refresh(server);
            test.assertTrue(outsider.reset(human.name()), "A settling round clears the list");

            // A new round rebuilds the objective at once, without waiting for the second.
            s.phase = RoundPhase.RUNNING;
            s.roundId = UUID.randomUUID();
            clear(clients);
            TotemTab.tick(server);
            for (var c : clients) {
                test.assertTrue(c.packets.size() >= 3 && c.packets.get(0) instanceof ClientboundSetObjectivePacket remove
                        && remove.getMethod() == ClientboundSetObjectivePacket.METHOD_REMOVE
                        && c.packets.get(1) instanceof ClientboundSetObjectivePacket add && add.getMethod() == ClientboundSetObjectivePacket.METHOD_ADD
                        && c.lastListObjective().equals(TotemTab.OBJECTIVE), "A new round rebuilds the objective: " + c.name());
                test.assertValueEqual(c.score(human.name()).orElseThrow(), "✦2", "The new round's counts follow: " + c.name());
            }

            // A disconnected player's cache goes; a reconnecting one starts from a fresh objective.
            TotemTab.disconnected(outsider.player);
            server.getPlayerList().remove(outsider.player);
            clear(clients);
            TotemTab.refresh(server);
            test.assertValueEqual(outsider.packets.size(), 0, "A disconnected connection is not sent anything");
        } finally {
            TotemTab.unconfigure(server);
            server.getScoreboard().removeObjective(owner);
            for (var c : clients) {
                var current = server.getPlayerList().getPlayer(c.id());
                if (current != null) server.getPlayerList().remove(current);
                c.channel.finishAndReleaseAll();
            }
        }
        test.succeed();
    }

    private static void clear(List<Client> clients) { clients.forEach(c -> c.packets.clear()); }

    private static Participant participant(UUID id, String name, ParticipantRole role, int charges) {
        return new Participant(id, name, role, true, false, 0, 0, 1, charges,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0, Map.of(), MonsterWallet.EMPTY, 0, false);
    }

    private static Client client(MinecraftServer server, List<Client> clients, String tag) {
        var profile = new GameProfile(UUID.randomUUID(), "HRTab" + tag);
        var connection = new Recorder();
        var player = new ServerPlayer(server, server.overworld(), profile, TestClients.information());
        var channel = new EmbeddedChannel(connection);
        dev.hardrush.gametest.UnmanagedProfileFixtures.run(() -> server.getPlayerList().placeNewPlayer(connection, player,
                TestClients.cookie(profile)));
        var client = new Client(player, profile, connection.packets, channel);
        clients.add(client);
        return client;
    }

    /** The round runtime as the player list sees it: a scripted committed round and saves in flight. */
    private static final class Script implements TotemTab.Access {
        UUID roundId = UUID.randomUUID();
        RulesConfig rules = RulesConfig.defaults();
        RoundPhase phase = RoundPhase.RUNNING;
        final Map<UUID, Participant> players = new LinkedHashMap<>();
        final Map<UUID, Integer> inFlight = new HashMap<>();

        void put(Client c, ParticipantRole role, int charges) { players.put(c.id(), participant(c.id(), c.name(), role, charges)); }

        @Override public Optional<RoundSnapshot> committed() {
            return Optional.of(new RoundSnapshot(roundId, 1, 1, 1, rules, phase, PauseReason.NONE, 0, 0,
                    new LinkedHashMap<>(players), false, false, phase == RoundPhase.SETTLING
                            ? new RoundResult(RoundResult.Kind.FAILED, null, "all-humans-lost") : null));
        }
        @Override public int chargesLeft(UUID player) {
            var p = players.get(player);
            return p == null ? 0 : Math.max(0, p.revivalCharges() - inFlight.getOrDefault(player, 0));
        }
    }

    private record Client(ServerPlayer player, GameProfile profile, List<Packet<?>> packets, EmbeddedChannel channel) {
        UUID id() { return profile.id(); }
        String name() { return profile.name(); }
        long adds() {
            return packets.stream().filter(p -> p instanceof ClientboundSetObjectivePacket o && TotemTab.OBJECTIVE.equals(o.getObjectiveName())
                    && o.getMethod() == ClientboundSetObjectivePacket.METHOD_ADD).count();
        }
        String lastListObjective() {
            return packets.stream().filter(p -> p instanceof ClientboundSetDisplayObjectivePacket d && d.getSlot() == DisplaySlot.LIST)
                    .map(p -> String.valueOf(((ClientboundSetDisplayObjectivePacket) p).getObjectiveName())).reduce((a, b) -> b).orElse("");
        }
        java.util.stream.Stream<ClientboundSetScorePacket> scores() {
            return packets.stream().filter(p -> p instanceof ClientboundSetScorePacket s && TotemTab.OBJECTIVE.equals(s.objectiveName()))
                    .map(ClientboundSetScorePacket.class::cast);
        }
        /** The latest fixed text sent for {@code owner}. */
        Optional<String> score(String owner) {
            return scores().filter(p -> p.owner().equals(owner)).reduce((a, b) -> b)
                    .map(p -> p.numberFormat().map(FixedFormat.class::cast).orElseThrow().value().getString());
        }
        boolean reset(String owner) {
            return packets.stream().anyMatch(p -> p instanceof ClientboundResetScorePacket r && r.owner().equals(owner)
                    && TotemTab.OBJECTIVE.equals(r.objectiveName()));
        }
    }

    /** Keeps the scoreboard packets a fixture connection receives. */
    private static final class Recorder extends Connection {
        final List<Packet<?>> packets = new ArrayList<>();
        Recorder() { super(PacketFlow.SERVERBOUND); }
        private void record(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) bundle.subPackets().forEach(this::record);
            else if (packet instanceof ClientboundSetObjectivePacket || packet instanceof ClientboundSetDisplayObjectivePacket
                    || packet instanceof ClientboundSetScorePacket || packet instanceof ClientboundResetScorePacket) packets.add(packet);
        }
        @Override public void send(Packet<?> packet) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { record(packet); }
    }
}
