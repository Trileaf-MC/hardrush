package dev.hardrush.gametest;

import com.mojang.authlib.GameProfile;
import dev.hardrush.gametest.mixin.AwaitingTeleportAccessor;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

/**
 * One fixture player whose client speaks the vanilla protocol back to the server.
 *
 * <p>A test describes who joins, what they do and what they should receive; this class owns the protocol a vanilla
 * client runs on its own. At the end of every server tick, and whenever a test calls {@link #acknowledge()}, the
 * client:
 * <ul>
 *   <li>accepts every teleport the server sent, in order, at its destination ({@link Reply#ACCEPT_TELEPORTS});</li>
 *   <li>reports once that it has loaded the level, after a login or respawn packet, once the server has started
 *   sending that level's chunks ({@code LEVEL_CHUNKS_LOAD_START}) and every teleport so far is accepted, as the vanilla
 *   loading screen does ({@link Reply#REPORT_LOADED});</li>
 *   <li>confirms every finished chunk batch, so the server keeps streaming its view ({@link Reply#CONFIRM_CHUNK_BATCHES});</li>
 *   <li>answers every keep-alive challenge, which matters to tests that {@linkplain #tickConnection() tick the
 *   connection} ({@link Reply#ANSWER_KEEP_ALIVE}).</li>
 * </ul>
 * The client never sees chunk contents, so unlike the vanilla loading screen it does not wait for the chunk it stands
 * in.
 * A client that misses any of these differs from a real one in ways that surface only as flaky timeouts: a position
 * that never settles, or chunks and entities that stop arriving. A test of exactly such an abnormal client
 * {@linkplain #withhold(Reply...) withholds} the reply it is about and sends it by hand when it chooses.
 *
 * <p>A client joins in one of three ways, and can rejoin through the same object after {@link #disconnect()}:
 * <ul>
 *   <li>{@link #beginLogin()} / {@link #finishLogin()} (or {@link #login(GameTestHelper, GameTestSequence)}): the native
 *   configuration-phase login, so HardRush manages the player exactly as on a real server;</li>
 *   <li>{@link #place(ServerLevel)}: a fixture-only player placed straight into a level, outside HardRush's player
 *   profiles; {@link #place(ServerPlayer)} places one a fixture prepared itself, for tests of what happens before
 *   registration;</li>
 *   <li>{@link #attach(ServerPlayer)}: a client connection for a player some other fixture already created.</li>
 * </ul>
 *
 * <p>Nothing here depends on the GameTest server, so dedicated-server regressions drive the same client from their
 * own step runners.
 */
public final class TestClient implements AutoCloseable {
    /** What a vanilla client answers on its own. */
    public enum Reply { ACCEPT_TELEPORTS, REPORT_LOADED, CONFIRM_CHUNK_BATCHES, ANSWER_KEEP_ALIVE }

    private enum Mode { LOGIN, PLACED, ATTACHED }

    /** A teleport as the client applies and acknowledges it: its id and absolute destination. */
    public record Teleport(int id, Vec3 position, float yRot, float xRot) {}

    private static final Set<TestClient> LIVE = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger NAMES = new AtomicInteger();

    private final MinecraftServer server;
    private final GameProfile profile;
    private final EnumSet<Reply> withheld = EnumSet.noneOf(Reply.class);
    private int viewDistance = TestClients.VIEW_DISTANCE;
    private boolean unmanaged;

    private Mode mode;
    private PrepareSpawnTask prepare;
    private TestConnection connection;
    private EmbeddedChannel channel;
    private ServerGamePacketListenerImpl listener;
    private ServerGamePacketListenerImpl replaced;
    /** The player being placed, before its listener exists. */
    private ServerPlayer placing;
    // Written by the connection as packets arrive, consumed by acknowledge().
    private final ConcurrentLinkedQueue<Teleport> pendingTeleports = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> pendingKeepAlives = new ConcurrentLinkedQueue<>();
    private final List<Teleport> acceptedTeleports = Collections.synchronizedList(new ArrayList<>());
    /** The newest teleport received; while any is unaccepted, it is where the client stands. */
    private volatile Teleport lastReceived;
    private final AtomicInteger pendingChunkBatches = new AtomicInteger();
    /** Between a login or respawn packet and the loaded report: the vanilla client's loading screen. */
    private volatile boolean levelLoading;
    private volatile boolean levelChunksStarted;

    public TestClient(MinecraftServer server, GameProfile profile) {
        this.server = server;
        this.profile = profile;
    }

    /** A client with a fresh random UUID; {@code name} gets a unique suffix within the player name limit. */
    public static TestClient named(MinecraftServer server, String name) {
        var suffix = String.valueOf(NAMES.incrementAndGet());
        var base = name.length() + suffix.length() > 16 ? name.substring(0, 16 - suffix.length()) : name;
        return new TestClient(server, new GameProfile(UUID.randomUUID(), base + suffix));
    }

    // ---- Options, before joining.

    /** The render distance this client requests; defaults to the vanilla client's. */
    public TestClient viewDistance(int chunks) { viewDistance = chunks; return this; }

    /** Stops answering on its own; the test sends these replies itself through the explicit methods below. */
    public TestClient withhold(Reply... replies) { withheld.addAll(List.of(replies)); return this; }

    /** Resumes answering on its own. */
    public TestClient resume(Reply... replies) { List.of(replies).forEach(withheld::remove); return this; }

    /** Native logins of this client stay outside HardRush's player profiles, for fixtures that seed vanilla data. */
    public TestClient unmanaged() { unmanaged = true; return this; }

    // ---- Joining and leaving.

    /** Starts the native login: the configuration phase begins loading this UUID's player data. */
    public void beginLogin() {
        requireOffline();
        prepare = new PrepareSpawnTask(server, new NameAndId(profile));
        prepare.start(packet -> {});
    }

    /**
     * Advances the native login; once the player data has loaded, spawns the player through the real login path and
     * returns {@code true}. Call it each tick until it does.
     */
    public boolean finishLogin() {
        if (prepare == null) throw new IllegalStateException("Native login of " + profile.name() + " was not begun");
        if (!prepare.tick()) return false;
        open(Mode.LOGIN);
        ServerPlayer[] spawned = new ServerPlayer[1];
        scoped(() -> spawned[0] = prepare.spawnPlayer(connection, TestClients.cookie(profile, viewDistance)));
        prepare.close();
        prepare = null;
        joined(spawned[0].connection);
        return true;
    }

    /** The native login as two steps of a GameTest sequence. */
    public GameTestSequence login(GameTestHelper test, GameTestSequence sequence) {
        return sequence.thenExecute(this::beginLogin)
                .thenWaitUntil(() -> test.assertTrue(finishLogin(), "Native login of " + profile.name() + " loads its player data"));
    }

    /** Places a fixture-only player into {@code level}, outside HardRush's player profiles. */
    public ServerPlayer place(ServerLevel level) {
        return place(new ServerPlayer(server, level, profile, TestClients.information(viewDistance)));
    }

    /**
     * Places a player object the fixture constructed and prepared itself (loaded data, login hooks), outside HardRush's
     * player profiles. It must carry this client's profile.
     */
    public ServerPlayer place(ServerPlayer player) {
        requireOffline();
        if (!player.getUUID().equals(profile.id())) throw new IllegalArgumentException("Player " + player.getUUID() + " is not " + profile.name());
        open(Mode.PLACED);
        placing = player;
        try {
            UnmanagedProfileFixtures.run(() -> server.getPlayerList().placeNewPlayer(connection, player,
                    TestClients.cookie(profile, viewDistance)));
        } finally { placing = null; }
        joined(player.connection);
        return player;
    }

    /** Gives a player another fixture created a client connection of its own; {@link #close()} restores the original. */
    public ServerPlayer attach(ServerPlayer player) {
        requireOffline();
        open(Mode.ATTACHED);
        replaced = player.connection;
        var attachedListener = new ServerGamePacketListenerImpl(server, connection, player, TestClients.cookie(player.getGameProfile(), viewDistance));
        // As PlayerList.placeNewPlayer does, so a kick reaches this listener's onDisconnect and the player leaves.
        connection.setupInboundProtocol(GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess()),
                attachedListener), attachedListener);
        player.connection = attachedListener;
        // The player already stands in its level, but this new connection has not reported it loaded.
        levelLoading = true;
        levelChunksStarted = true;
        joined(player.connection);
        return player;
    }

    /** The client closes its connection; the server handles the disconnect as it would for a real socket. */
    public void disconnect() { disconnect(Component.literal("Fixture client disconnected")); }

    public void disconnect(Component reason) {
        if (prepare != null) { prepare.close(); prepare = null; }
        if (connection == null) return;
        if (mode == Mode.ATTACHED) throw new IllegalStateException("An attached client leaves through close()");
        connection.disconnect(reason);
        connection.handleDisconnection();
        release();
    }

    /**
     * Leaves the way each kind of client always has: a native login disconnects, a placed player is removed from the
     * player list, and an attached client gives the player back its original connection.
     */
    @Override public void close() {
        if (prepare != null) { prepare.close(); prepare = null; }
        if (connection == null) return;
        switch (mode) {
            case LOGIN -> disconnect();
            case PLACED -> {
                var player = player();
                if (player != null && server.getPlayerList().getPlayer(player.getUUID()) == player) server.getPlayerList().remove(player);
                release();
            }
            case ATTACHED -> {
                // A respawn while attached moved this listener to a new entity; the original listener takes that one back.
                var player = listener.player;
                if (player.connection == listener) {
                    replaced.player = player;
                    player.connection = replaced;
                    // A teleport the original connection still awaited from before is overtaken: the player has moved on
                    // through this one. Neither side waits for it any more, and no packet says so (see the accessor).
                    var original = replaced;
                    ((AwaitingTeleportAccessor) original).hardrush$awaitingPositionFromClient(null);
                    LIVE.stream().filter(other -> other != this && other.listener == original).forEach(other -> other.pendingTeleports.clear());
                }
                release();
            }
        }
    }

    // ---- State.

    public GameProfile profile() { return profile; }
    public UUID id() { return profile.id(); }
    public String name() { return profile.name(); }

    /** The player this client currently controls; a respawn replaces it. Null before joining and after leaving. */
    public ServerPlayer player() { settle(); return listener == null ? null : listener.player; }

    /** Where the test sends serverbound packets, for inputs this class has no shorthand for. */
    public ServerGamePacketListenerImpl listener() {
        settle();
        if (listener == null) throw new IllegalStateException(profile.name() + " is not connected");
        return listener;
    }

    public boolean connected() { settle(); return connection != null; }

    /** Every acknowledgement sent in this session, in order, automatic and manual alike. */
    public List<Teleport> acceptedTeleports() {
        synchronized (acceptedTeleports) { return List.copyOf(acceptedTeleports); }
    }

    /** The connection of the current session, for exceptional paths that need it directly. */
    public TestConnection connection() { return connection; }

    /** Everything received since this session began or was last {@linkplain #clearPackets() cleared}. */
    public List<Packet<?>> packets() { return connection == null ? List.of() : connection.packets(); }

    public <T extends Packet<?>> List<T> packets(Class<T> type) { return connection == null ? List.of() : connection.packets(type); }

    public void clearPackets() { if (connection != null) connection.clear(); }

    /** Whether the server has sent this client the entity, as a real client would need to render it. */
    public boolean sawEntity(Entity entity) {
        return packets(ClientboundAddEntityPacket.class).stream().anyMatch(add -> add.getId() == entity.getId());
    }

    // ---- What the client sends.

    /** Sends now whatever replies the client has not withheld, instead of waiting for the end of the tick. */
    public void acknowledge() {
        if (!live()) return;
        if (!withheld.contains(Reply.ANSWER_KEEP_ALIVE)) answerKeepAlives();
        if (!withheld.contains(Reply.CONFIRM_CHUNK_BATCHES)) confirmChunkBatches();
        if (!withheld.contains(Reply.ACCEPT_TELEPORTS)) acceptTeleport();
        if (!withheld.contains(Reply.REPORT_LOADED) && levelLoading && levelChunksStarted && pendingTeleports.isEmpty()) reportLoaded();
    }

    /**
     * Accepts, in order, the teleports the server had sent when this was called, each at the destination the vanilla
     * client applies; {@code false} if none was unaccepted. The server moves the player only for the latest, but
     * HardRush recognises a level's arrival by the first one after it. A teleport the server sends in answer to these
     * acceptances waits for the next call, as it would wait for the next packet a real client reads.
     */
    public boolean acceptTeleport() {
        if (!live() || pendingTeleports.isEmpty()) return false;
        for (int count = pendingTeleports.size(); count > 0; count--) {
            var teleport = pendingTeleports.poll();
            if (teleport == null) break;
            accept(teleport);
        }
        return true;
    }

    /**
     * Accepts one particular teleport id, which no longer waits for the automatic reply. An id the client never received
     * (a stale or invented one, for tests of the server's checks) goes out at the player's current position.
     */
    public void acceptTeleport(int id) {
        for (var teleport : pendingTeleports) {
            if (teleport.id() == id && pendingTeleports.remove(teleport)) { accept(teleport); return; }
        }
        var player = listener.player;
        accept(new Teleport(id, player.position(), player.getYRot(), player.getXRot()));
    }

    private void accept(Teleport teleport) {
        acceptedTeleports.add(teleport);
        var position = teleport.position();
        // A real acknowledgement arrives after the server's connection tick has reset the movement baseline to where the
        // teleport put the player. Embedded connections get no such tick, so their baseline would stay wherever the
        // player joined, every acceptance would read as moving too quickly, and the server would teleport back forever.
        listener.resetPosition();
        listener.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(teleport.id(), position.x, position.y, position.z,
                teleport.yRot(), teleport.xRot()));
    }

    /**
     * Sends a loaded report now. It completes the current load only once that level's chunks have started and every
     * teleport is accepted; one sent earlier still belongs to the previous level, so the automatic report stays due.
     */
    public void reportLoaded() {
        if (levelChunksStarted && pendingTeleports.isEmpty()) levelLoading = false;
        listener.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
    }

    public void answerKeepAlives() {
        if (!live()) return;
        for (Long challenge; (challenge = pendingKeepAlives.poll()) != null; ) listener.handleKeepAlive(new ServerboundKeepAlivePacket(challenge));
    }

    public void confirmChunkBatches() {
        if (!live()) return;
        while (pendingChunkBatches.getAndUpdate(count -> Math.max(0, count - 1)) > 0) {
            listener.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(64));
        }
    }

    /**
     * Runs the connection tick a network socket would get from the server's connection list, which embedded
     * connections are not part of. Only for tests about per-connection timers; it also starts keep-alive checks.
     */
    public void tickConnection() { listener().tick(); }

    /** The Q key: drops one item, or with {@code wholeStack} (Ctrl+Q) the whole selected stack. */
    public void dropSelected(boolean wholeStack) {
        listener().handlePlayerAction(new ServerboundPlayerActionPacket(wholeStack
                ? ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS : ServerboundPlayerActionPacket.Action.DROP_ITEM,
                BlockPos.ZERO, Direction.DOWN));
    }

    // ---- Internals.

    /** Called by {@link TestClients} at the end of every server tick. */
    static void tick(MinecraftServer server) {
        for (var client : LIVE) {
            if (client.server != server) continue;
            if (client.ended()) { client.release(); continue; }
            client.acknowledge();
        }
    }

    /**
     * The session is over without the test closing it: the server closed the connection (a kick), or a placed or
     * logged-in player left the player list and no entity of this UUID is on this connection any more. A test client
     * {@linkplain #attach(ServerPlayer) attached} over this one only borrows the player and does not end it.
     */
    private boolean ended() {
        if (connection == null) return false;
        if (!connection.isConnected()) return true;
        if (mode == Mode.ATTACHED || listener == null) return false;
        var online = server.getPlayerList().getPlayer(profile.id());
        if (online == null) return true;
        return online.connection != listener && LIVE.stream().noneMatch(other -> other.mode == Mode.ATTACHED && other.replaced == listener);
    }

    private boolean live() {
        if (listener == null || !connection.isConnected()) return false;
        // A placed or logged-in player the player list has dropped no longer has a client to answer for.
        if (mode == Mode.ATTACHED) return listener.player.connection == listener && !listener.player.isRemoved();
        // Only the connection the player is on answers; one another client is attached over stays quiet.
        return server.getPlayerList().getPlayer(profile.id()) == listener.player && listener.player.connection == listener;
    }

    private void open(Mode mode) {
        this.mode = mode;
        pendingTeleports.clear();
        pendingKeepAlives.clear();
        acceptedTeleports.clear();
        lastReceived = null;
        pendingChunkBatches.set(0);
        levelLoading = false;
        levelChunksStarted = false;
        connection = new TestConnection(this::observe);
        channel = new EmbeddedChannel(connection);
    }

    private void joined(ServerGamePacketListenerImpl listener) {
        this.listener = listener;
        LIVE.add(this);
        acknowledge();
    }

    private void observe(Packet<?> packet) {
        switch (packet) {
            case ClientboundPlayerPositionPacket position -> {
                var teleport = arrival(position);
                lastReceived = teleport;
                pendingTeleports.add(teleport);
            }
            case ClientboundChunkBatchFinishedPacket ignored -> pendingChunkBatches.incrementAndGet();
            case ClientboundKeepAlivePacket keepAlive -> pendingKeepAlives.add(keepAlive.getId());
            case ClientboundLoginPacket ignored -> { levelLoading = true; levelChunksStarted = false; }
            case ClientboundRespawnPacket ignored -> { levelLoading = true; levelChunksStarted = false; }
            case ClientboundGameEventPacket event when event.getEvent() == ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START -> levelChunksStarted = true;
            default -> { }
        }
    }

    /**
     * The destination the client applies, computed as the vanilla client does with
     * {@link PositionMoveRotation#calculateAbsolute}: absolute components from the packet, relative ones added to where
     * the client stands (pitch clamped to ±90°). While an earlier teleport is unaccepted the client stands at that one's
     * destination. Otherwise it stands where the server had the player before this teleport: every position packet
     * comes from {@code ServerGamePacketListenerImpl.teleport}, which moves the player before sending, so that is the
     * player's position now less the packet's offset. A riding client applies no position packet and acknowledges
     * where it is.
     * TODO: [Refactor] tracking the client's own position (#52) would also cover position packets that do not come from
     * {@code teleport()}, which only tests construct, and two relative teleports bundled after both moved the player.
     */
    private Teleport arrival(ClientboundPlayerPositionPacket packet) {
        var change = packet.change();
        var relatives = packet.relatives();
        var player = listener != null ? listener.player : placing;
        if (player != null && player.isPassenger()) return new Teleport(packet.id(), player.position(), player.getYRot(), player.getXRot());
        PositionMoveRotation from;
        var standing = pendingTeleports.isEmpty() ? null : lastReceived;
        if (standing != null) {
            from = new PositionMoveRotation(standing.position(), Vec3.ZERO, standing.yRot(), standing.xRot());
        } else if (relatives.stream().noneMatch(PLACEMENT_FLAGS::contains)) {
            from = new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, 0, 0);
        } else {
            if (player == null) throw new IllegalStateException("Relative teleport " + packet.id() + " before " + profile.name() + " has a player");
            var offset = change.position();
            from = new PositionMoveRotation(new Vec3(
                    player.getX() - (relatives.contains(Relative.X) ? offset.x : 0),
                    player.getY() - (relatives.contains(Relative.Y) ? offset.y : 0),
                    player.getZ() - (relatives.contains(Relative.Z) ? offset.z : 0)), Vec3.ZERO,
                    player.getYRot() - (relatives.contains(Relative.Y_ROT) ? change.yRot() : 0),
                    player.getXRot() - (relatives.contains(Relative.X_ROT) ? change.xRot() : 0));
        }
        var arrival = PositionMoveRotation.calculateAbsolute(from, change, relatives);
        return new Teleport(packet.id(), arrival.position(), arrival.yRot(), arrival.xRot());
    }

    /** The relative flags that change where the player ends up or faces, as opposed to its velocity. */
    private static final Set<Relative> PLACEMENT_FLAGS = Set.of(Relative.X, Relative.Y, Relative.Z, Relative.Y_ROT, Relative.X_ROT);

    /** A session the server closed (a kick) is over as soon as anyone looks, not only at the end of the tick. */
    private void settle() {
        if (connection != null && !connection.isConnected()) release();
    }

    private void release() {
        LIVE.remove(this);
        if (channel == null) return;
        channel.finishAndReleaseAll();
        connection = null;
        channel = null;
        listener = null;
        replaced = null;
        mode = null;
    }

    private void requireOffline() {
        settle();
        if (prepare != null) throw new IllegalStateException(profile.name() + " is preparing a native login");
        if (connection != null) throw new IllegalStateException(profile.name() + " is already connected");
    }

    private void scoped(Runnable task) {
        if (unmanaged) UnmanagedProfileFixtures.run(task); else task.run();
    }
}
