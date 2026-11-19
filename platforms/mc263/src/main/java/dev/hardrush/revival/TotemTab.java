package dev.hardrush.revival;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundSnapshot;
import java.util.*;
import java.util.function.ToIntFunction;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * Revival charges beside the names in the player list (docs/plan/复生图腾掉落.md 1.7, 2.8): a gold {@code ✦n} for every
 * online participant of a hardcore round who holds at least one charge, humans and monsters alike, read from
 * {@link Access#chargesLeft} so it always matches the projected stack.
 *
 * <p>Packets only: every connection gets its own objective {@value #OBJECTIVE} in the {@code list} slot, and the server
 * scoreboard is never written, so nothing reaches the save or outlives the round. The cost is that an objective the
 * server owner shows in the list slot is covered while this runs. Each score carries its own {@link FixedFormat}; a
 * player without a score is shown blank by the native {@code PlayerTabOverlay}, so a cleared count is a reset.
 *
 * <p>A new connection gets the objective and every score right after vanilla's {@code updateEntireScoreboard} in
 * {@code PlayerList.placeNewPlayer} ({@link #joined}); afterwards the scores are compared at most once a second and a
 * connection is sent only what changed. A new round rebuilds the objective, which drops every old score on the client.
 */
public final class TotemTab {
    public static final String OBJECTIVE = "hardrush_totems";
    private static final int INTERVAL_TICKS = 20;

    /** The round runtime as the player list sees it. */
    public interface Access {
        /** The committed round. */
        Optional<RoundSnapshot> committed();
        /** {@code RoundSession.totemChargesLeft}: the committed charges not already spent by a save in flight. */
        int chargesLeft(UUID player);
    }

    /** What one connection's client holds: its objective belongs to {@code round}, with these scores. */
    private static final class View {
        UUID round;
        final Map<String, Integer> scores = new HashMap<>();
    }

    private static final Map<MinecraftServer, TotemTab> INSTANCES = new IdentityHashMap<>();
    private final Access access;
    private final Scoreboard scoreboard = new Scoreboard();
    private final Objective objective = scoreboard.addObjective(OBJECTIVE, ObjectiveCriteria.DUMMY,
            Component.literal("复生图腾"), ObjectiveCriteria.RenderType.INTEGER, false, null);
    private final Map<ServerGamePacketListenerImpl, View> views = new IdentityHashMap<>();
    private long lastCompare = Long.MIN_VALUE;

    private TotemTab(Access access) { this.access = Objects.requireNonNull(access); }

    public static void configure(MinecraftServer server, Access access) { INSTANCES.put(server, new TotemTab(access)); }
    public static void unconfigure(MinecraftServer server) { INSTANCES.remove(server); }

    /** The fixed text a count is shown as. */
    static Component badge(int count) { return Component.literal("✦" + count).withStyle(ChatFormatting.GOLD); }

    /**
     * Who shows which count: online participants of a hardcore round that is running or rescuing, not withdrawn or
     * disqualified, with at least one charge left; keyed by the name the player list shows. Empty otherwise, which
     * clears the list when a round settles.
     */
    static Map<String, Integer> scores(MinecraftServer server, RoundSnapshot round, ToIntFunction<UUID> chargesLeft) {
        var scores = new HashMap<String, Integer>();
        if (round == null || round.rules().mode() != GameMode.HARDCORE
                || round.phase() != RoundPhase.RUNNING && round.phase() != RoundPhase.RESCUE) return scores;
        for (var participant : round.participants().values()) {
            if (participant.role().isExcluded()) continue;
            var player = server.getPlayerList().getPlayer(participant.id());
            int count = player == null ? 0 : chargesLeft.applyAsInt(participant.id());
            if (count > 0) scores.put(player.getScoreboardName(), count);
        }
        return scores;
    }

    /** {@code PlayerList.placeNewPlayer}, after vanilla sent the server scoreboard: the newcomer gets everything now. */
    public static void joined(ServerPlayer player) {
        var server = player.level().getServer();
        var tab = INSTANCES.get(server);
        if (tab != null) tab.compare(server);
    }

    public static void disconnected(ServerPlayer player) {
        var tab = INSTANCES.get(player.level().getServer());
        if (tab != null) tab.views.remove(player.connection);
    }

    /** Every tick: a new round or a new connection at once, other changes at most once a second. */
    public static void tick(MinecraftServer server) {
        var tab = INSTANCES.get(server);
        if (tab == null) return;
        var round = tab.access.committed().map(RoundSnapshot::roundId).orElse(null);
        boolean due = tab.lastCompare == Long.MIN_VALUE || server.getTickCount() - tab.lastCompare >= INTERVAL_TICKS
                || server.getTickCount() < tab.lastCompare;
        if (due || server.getPlayerList().getPlayers().stream().anyMatch(player -> {
            var view = tab.views.get(player.connection);
            return view == null || !Objects.equals(view.round, round);
        })) tab.compare(server);
    }

    /** Compares now, whatever the cadence. */
    static void refresh(MinecraftServer server) {
        var tab = INSTANCES.get(server);
        if (tab != null) tab.compare(server);
    }

    private void compare(MinecraftServer server) {
        lastCompare = server.getTickCount();
        var round = access.committed().orElse(null);
        var roundId = round == null ? null : round.roundId();
        var wanted = scores(server, round, access::chargesLeft);
        var players = server.getPlayerList().getPlayers();
        views.keySet().removeIf(connection -> players.stream().noneMatch(player -> player.connection == connection));
        for (var player : players) {
            var connection = player.connection;
            var view = views.get(connection);
            if (view == null) {
                // A fresh client scoreboard: adding an objective it already has would disconnect it, so only once.
                view = new View();
                view.round = roundId;
                views.put(connection, view);
                connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
                connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.LIST, objective));
            } else if (!Objects.equals(view.round, roundId)) {
                // A new round starts from an empty objective: removing it drops every old score on the client.
                view.round = roundId;
                view.scores.clear();
                connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_REMOVE));
                connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
                connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.LIST, objective));
            }
            for (var it = view.scores.keySet().iterator(); it.hasNext(); ) {
                var owner = it.next();
                if (wanted.containsKey(owner)) continue;
                connection.send(new ClientboundResetScorePacket(owner, OBJECTIVE));
                it.remove();
            }
            for (var entry : wanted.entrySet()) {
                if (entry.getValue().equals(view.scores.get(entry.getKey()))) continue;
                connection.send(new ClientboundSetScorePacket(entry.getKey(), OBJECTIVE, entry.getValue(), Optional.empty(),
                        Optional.of(new FixedFormat(badge(entry.getValue())))));
                view.scores.put(entry.getKey(), entry.getValue());
            }
        }
    }
}
