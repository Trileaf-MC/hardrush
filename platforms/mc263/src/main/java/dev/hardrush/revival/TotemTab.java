package dev.hardrush.revival;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Revival charges beside the names in the player list (docs/plan/复生图腾掉落.md 1.7, 2.8): a gold {@code ✦n} for every
 * online participant of a round who holds at least one charge, humans and monsters alike, read from
 * {@link Access#chargesLeft} so it always matches the projected stack.
 *
 * <p>Packets only: while it {@linkplain #takesOver takes over}, every connection gets its own objective
 * {@value #OBJECTIVE} in the {@code list} slot, and the server scoreboard is never written, so nothing reaches the save
 * or outlives the round. The name holds a colon, which {@code /scoreboard objectives add} (an unquoted
 * {@code StringArgumentType.word()}) cannot type, so the server owner's objectives never collide with it; an objective of
 * that name another mod adds through the API suspends the list instead, since a second ADD of a name the client already
 * has disconnects it. Outside a running or rescuing round nothing of ours is shown: the objective is removed
 * and the {@code list} slot goes back to whatever the server scoreboard shows there. Each score carries its own
 * {@link FixedFormat}; a player without a score is shown blank by the native {@code PlayerTabOverlay}, so a cleared count
 * is a reset.
 *
 * <p>A new connection gets the objective and every score right after vanilla's {@code updateEntireScoreboard} in
 * {@code PlayerList.placeNewPlayer} ({@link #joined}); afterwards the scores are compared at most once a second and a
 * connection is sent only what changed. A new round rebuilds the objective, which drops every old score on the client.
 */
public final class TotemTab {
    /** Not a name a command can create (see the class comment). */
    public static final String OBJECTIVE = "hardrush:totems";
    private static final int INTERVAL_TICKS = 20;
    private static final Logger LOGGER = LoggerFactory.getLogger("hardrush-revival");

    /** The round runtime as the player list sees it. */
    public interface Access {
        /** The committed round. */
        Optional<RoundSnapshot> committed();
        /** {@code RoundSession.totemChargesLeft}: the committed charges not already spent by a save in flight. */
        int chargesLeft(UUID player);
    }

    /**
     * What one connection's client holds. {@code added}: it has our objective (added once and not removed since, so
     * another ADD would disconnect it), belonging to {@code round}, with these scores. Without it, {@code round} is only
     * the last round compared, so a new round is noticed at once either way.
     */
    private static final class View {
        boolean added;
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
    /** Whether the last comparison took over the list slot: a change is applied at once, not within the second. */
    private boolean shown;
    private boolean warned;

    private TotemTab(Access access) { this.access = Objects.requireNonNull(access); }

    public static void configure(MinecraftServer server, Access access) { INSTANCES.put(server, new TotemTab(access)); }
    public static void unconfigure(MinecraftServer server) { INSTANCES.remove(server); }

    /** The fixed text a count is shown as. */
    static Component badge(int count) { return Component.literal("✦" + count).withStyle(ChatFormatting.GOLD); }

    /** Whether {@code round} owns the list slot: a round that is running or rescuing. */
    static boolean takesOver(RoundSnapshot round) {
        return round != null
                && (round.phase() == RoundPhase.RUNNING || round.phase() == RoundPhase.RESCUE);
    }

    /**
     * Who shows which count: online participants of a round that is running or rescuing, not withdrawn or
     * disqualified, with at least one charge left; keyed by the name the player list shows. Empty while the round does
     * not {@linkplain #takesOver take over} the list slot.
     */
    static Map<String, Integer> scores(MinecraftServer server, RoundSnapshot round, ToIntFunction<UUID> chargesLeft) {
        var scores = new HashMap<String, Integer>();
        if (!takesOver(round)) return scores;
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

    /**
     * Every tick: a new round, a new connection, or the list slot taken over or given back at once, other changes at
     * most once a second.
     */
    public static void tick(MinecraftServer server) {
        var tab = INSTANCES.get(server);
        if (tab == null) return;
        var committed = tab.access.committed().orElse(null);
        var round = committed == null ? null : committed.roundId();
        boolean due = tab.lastCompare == Long.MIN_VALUE || server.getTickCount() - tab.lastCompare >= INTERVAL_TICKS
                || server.getTickCount() < tab.lastCompare || takesOver(committed) != tab.shown;
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
        var clash = server.getScoreboard().getObjective(OBJECTIVE);
        if (clash != null) {
            // Only another mod can add this name; our ADD would disconnect whoever already has its objective.
            if (!warned) LOGGER.warn("The server scoreboard has an objective named {}; revival charges stay out of the player list", OBJECTIVE);
            warned = true;
            // Take ours back while the other one is not shown yet: showing it later sends vanilla's ADD, which a client
            // still holding ours would refuse by disconnecting. Once shown, the client already holds theirs: leave it.
            boolean theirsShown = server.getScoreboard().getObjectiveDisplaySlotCount(clash) > 0;
            for (var entry : views.entrySet()) {
                var view = entry.getValue();
                if (!view.added) continue;
                view.added = false;
                view.scores.clear();
                if (theirsShown) continue;
                entry.getKey().send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_REMOVE));
                entry.getKey().send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.LIST,
                        server.getScoreboard().getDisplayObjective(DisplaySlot.LIST)));
            }
            shown = false;
            return;
        }
        var round = access.committed().orElse(null);
        var roundId = round == null ? null : round.roundId();
        shown = takesOver(round);
        var wanted = scores(server, round, access::chargesLeft);
        var players = server.getPlayerList().getPlayers();
        views.keySet().removeIf(connection -> players.stream().noneMatch(player -> player.connection == connection));
        for (var player : players) {
            var connection = player.connection;
            var view = views.computeIfAbsent(connection, c -> new View());
            if (!shown) {
                if (view.added) {
                    // Give the slot back: whatever the server shows there, which the client already has, or nothing.
                    view.added = false;
                    view.scores.clear();
                    connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_REMOVE));
                    connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.LIST,
                            server.getScoreboard().getDisplayObjective(DisplaySlot.LIST)));
                }
                view.round = roundId;
                continue;
            }
            if (view.added && !Objects.equals(view.round, roundId)) {
                // A new round starts from an empty objective: removing it drops every old score on the client.
                view.added = false;
                view.scores.clear();
                connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_REMOVE));
            }
            if (!view.added) {
                // Adding an objective the client already has would disconnect it, so only while it has none.
                view.added = true;
                view.round = roundId;
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
