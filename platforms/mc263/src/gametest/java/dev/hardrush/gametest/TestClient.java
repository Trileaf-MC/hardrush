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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;
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
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

/**
 * One fixture player whose client speaks the vanilla protocol back to the server.
 *
 * <p>A test describes who joins, what they do and what they should receive; this class owns what a real connection
 * does without the test asking.
 *
 * <p>Every server tick, the connection ticks where the server ticks its network connections (after the levels, before
 * the player list and the GameTests), which embedded connections are not part of. That tick runs the player's own
 * body ({@code ServerPlayer.doTick}: effects, hunger, air, fire, item and attack timers), resets the movement checks'
 * baseline, flushes block-change acknowledgements and checks keep-alives, spam throttles and floating. A test that
 * needs a player whose body stands still {@linkplain #freeze() freezes} the connection and steps it by hand with
 * {@link #tickConnection()}.
 *
 * <p>At the end of every server tick, and whenever a test calls {@link #acknowledge()}, the client handles what has
 * arrived:
 * <ul>
 *   <li>applies every teleport and rotation the server sent, in order, as the vanilla client does, and accepts each
 *   teleport at the destination it applied ({@link Reply#ACCEPT_TELEPORTS});</li>
 *   <li>reports once that it has loaded the level, after a login or respawn packet, once the server has started
 *   sending that level's chunks ({@code LEVEL_CHUNKS_LOAD_START}) and every teleport so far is accepted, as the vanilla
 *   loading screen does ({@link Reply#REPORT_LOADED});</li>
 *   <li>confirms every finished chunk batch, so the server keeps streaming its view ({@link Reply#CONFIRM_CHUNK_BATCHES});</li>
 *   <li>answers every keep-alive challenge, so the connection tick never times it out ({@link Reply#ANSWER_KEEP_ALIVE}).</li>
 * </ul>
 * The client never sees chunk contents, so unlike the vanilla loading screen it does not wait for the chunk it stands
 * in.
 *
 * <p>Then, at the end of every server tick only, it runs one client tick as {@code Minecraft.tick} does: once it has
 * reported the level loaded it sends its movement the way {@code LocalPlayer.sendChanges} does
 * ({@link Reply#REPORT_POSITION}), and it always ends with {@code ServerboundClientTickEndPacket}. The client keeps its own
 * view of where its player stands: from the teleports it applied, the rotations the server set and the moves a test
 * makes through {@link #move(Vec3, float, float, boolean)}. It sends a position when that view changed and at least once
 * every 20 client ticks, which makes the server look again at which entities this player can see, and it reports
 * whether it stands on something. It does not simulate physics: a player the server leaves in the air stays there, as
 * if hovering, and the server may kick it for flying when flight is not allowed (dedicated servers only; the headless
 * GameTest server allows it). Moving a fixture player without a packet ({@code setPos}, {@code snapTo}) does not reach
 * this view either, so the client's next position packet walks it back, as a real client's would; move players with a
 * teleport or through this client.
 *
 * <p>A client that misses any of these differs from a real one in ways that surface only as flaky timeouts: a position
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
    /** What a vanilla client sends on its own. */
    public enum Reply { ACCEPT_TELEPORTS, REPORT_LOADED, CONFIRM_CHUNK_BATCHES, ANSWER_KEEP_ALIVE, REPORT_POSITION }

    private enum Mode { LOGIN, PLACED, ATTACHED }

    /** A teleport as the client applies and acknowledges it: its id and absolute destination. */
    public record Teleport(int id, Vec3 position, float yRot, float xRot) {}

    /** In joining order, as the server's connection list ticks its connections. */
    private static final Set<TestClient> LIVE = new CopyOnWriteArraySet<>();
    private static final AtomicInteger NAMES = new AtomicInteger();

    private final MinecraftServer server;
    private final GameProfile profile;
    private final EnumSet<Reply> withheld = EnumSet.noneOf(Reply.class);
    private int viewDistance = TestClients.VIEW_DISTANCE;
    private boolean unmanaged;
    private volatile boolean frozen;

    private Mode mode;
    private PrepareSpawnTask prepare;
    private TestConnection connection;
    private EmbeddedChannel channel;
    private ServerGamePacketListenerImpl listener;
    private ServerGamePacketListenerImpl replaced;
    // Written by the connection as packets arrive, consumed by acknowledge().
    /** Position and rotation packets not yet applied, in the order they arrived. */
    private final ConcurrentLinkedQueue<Packet<?>> pendingPlacements = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> pendingKeepAlives = new ConcurrentLinkedQueue<>();
    private final List<Teleport> acceptedTeleports = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger pendingChunkBatches = new AtomicInteger();
    // The client's own player, written on the server thread only.
    /** Where the client sees its player; null until a teleport places it after a login or respawn. */
    private Vec3 position;
    private float yRot;
    private float xRot;
    private boolean onGround;
    /** What the client last sent, as {@code LocalPlayer} keeps it; a new player starts from vanilla's zero defaults. */
    private Vec3 sentPosition;
    private float sentYRot;
    private float sentXRot;
    private boolean sentOnGround;
    private int positionReminder;
    /** Between a login or respawn packet and the loaded report: the vanilla client's loading screen. */
    private volatile boolean levelLoading;
    private volatile boolean levelChunksStarted;
    /** A teleport arrived after the connection last ticked, so the server's movement baseline is still where it was. */
    private volatile boolean teleportedSinceConnectionTick;

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

    /**
     * Stops the per-tick connection tick, at any time: the player's own body ({@code ServerPlayer.doTick}: effects, hunger,
     * air, fire, attack and item timers) no longer ticks and nothing checks keep-alives. What the level ticks for every
     * player still runs (game mode, damage cooldown, client-load timeout, container sync), and the client still sends its
     * replies. {@link #tickConnection()} steps the connection by hand.
     */
    public TestClient freeze() { frozen = true; return this; }

    /** Resumes the per-tick connection tick. */
    public TestClient unfreeze() { frozen = false; return this; }

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
        UnmanagedProfileFixtures.run(() -> server.getPlayerList().placeNewPlayer(connection, player,
                TestClients.cookie(profile, viewDistance)));
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
        // The player already stands in its level, but this new connection has not reported it loaded. No teleport will
        // place it either: the client takes the player where it is when it first needs a position, so a fixture can
        // still set the player up within the step that attached it.
        levelLoading = true;
        levelChunksStarted = true;
        joined(player.connection);
        return player;
    }

    /** The client closes its connection; the server handles the disconnect as it would for a real socket. */
    public void disconnect() { disconnect(Component.literal("Fixture client disconnected")); }

    /**
     * As a real socket does: the channel closes on its network thread, so Netty's {@code channelInactive} (and Fabric's
     * {@code ServerPlayConnectionEvents.DISCONNECT}, which Fabric fires from it) runs there, not on the server thread;
     * the server thread then handles the disconnection on its next connection tick. Closing on the server thread
     * instead would hide any disconnect handling that only works there.
     */
    public void disconnect(Component reason) {
        if (prepare != null) { prepare.close(); prepare = null; }
        if (connection == null) return;
        if (mode == Mode.ATTACHED) throw new IllegalStateException("An attached client leaves through close()");
        var closing = connection;
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        // The embedded event loop runs its tasks on whichever thread drives it, as Netty's own loop thread would.
        var network = new Thread(() -> closing.disconnect(reason), "HardRush fixture network thread");
        network.setUncaughtExceptionHandler((thread, error) -> failure.set(error));
        network.start();
        try { network.join(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        if (failure.get() != null) throw new IllegalStateException("Closing the fixture connection failed", failure.get());
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
                    // This client stood in for it, so it sees the player wherever this one left it.
                    LIVE.stream().filter(other -> other != this && other.listener == original).forEach(other -> {
                        other.pendingPlacements.clear();
                        other.standWhere(player);
                    });
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

    /**
     * Where the client sees its player: placed by the teleports it applied and moved by the test through
     * {@link #move(Vec3, float, float, boolean)}. Null after a login or respawn until a teleport places the player.
     */
    public Vec3 position() { return position; }

    /** The connection of the current session, for exceptional paths that need it directly. */
    public TestConnection connection() { return connection; }

    /** Everything received since this session began or was last {@linkplain #clearPackets() cleared}. */
    public List<Packet<?>> packets() { return connection == null ? List.of() : connection.packets(); }

    public <T extends Packet<?>> List<T> packets(Class<T> type) { return connection == null ? List.of() : connection.packets(type); }

    public void clearPackets() { if (connection != null) connection.clear(); }

    /**
     * Whether the server has sent this client every chunk within {@code radius} chunks of {@code center}, in the level the
     * player is in. An entity that appears in a chunk before the client has it reaches the client only once the server
     * looks again, when the entity changes section or the player moves: the client's next position packet, within a
     * second.
     */
    public boolean receivedChunksAround(BlockPos center, int radius) {
        var player = player();
        if (player == null) return false;
        var chunkMap = player.level().getChunkSource().chunkMap;
        int chunkX = center.getX() >> 4, chunkZ = center.getZ() >> 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!chunkMap.isChunkTracked(player, chunkX + dx, chunkZ + dz)) return false;
            }
        }
        return true;
    }

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
        if (!withheld.contains(Reply.REPORT_LOADED) && levelLoading && levelChunksStarted && pendingPlacements.isEmpty()) reportLoaded();
    }

    /**
     * Applies, in order, the teleports and rotations the server had sent when this was called, as the vanilla client
     * does, and accepts each teleport at the destination it applied; {@code false} if nothing was pending. The server
     * moves the player only for the latest teleport, but HardRush recognises a level's arrival by the first one after it.
     * A teleport the server sends in answer to these acceptances waits for the next call, as it would wait for the next
     * packet a real client reads.
     */
    public boolean acceptTeleport() {
        if (!live() || pendingPlacements.isEmpty()) return false;
        for (int count = pendingPlacements.size(); count > 0; count--) {
            var packet = pendingPlacements.poll();
            if (packet == null) break;
            if (packet instanceof ClientboundPlayerPositionPacket teleport) accept(teleport);
            else if (packet instanceof ClientboundPlayerRotationPacket rotation) rotate(rotation);
        }
        return true;
    }

    /**
     * Accepts one particular teleport id, which no longer waits for the automatic reply. An id the client never received
     * (a stale or invented one, for tests of the server's checks) goes out where the client stands.
     */
    public void acceptTeleport(int id) {
        for (var packet : pendingPlacements) {
            if (packet instanceof ClientboundPlayerPositionPacket teleport && teleport.id() == id && pendingPlacements.remove(packet)) {
                accept(teleport);
                return;
            }
        }
        if (position == null) standWhere(listener.player);
        accept(new Teleport(id, position, yRot, xRot));
    }

    /**
     * Applies a position packet as {@code ClientPacketListener.handleMovePlayer} does: with
     * {@link PositionMoveRotation#calculateAbsolute}, absolute components from the packet and relative ones added to
     * where the client stands (pitch clamped to ±90°). A riding client applies none of it and acknowledges where it is.
     */
    private void accept(ClientboundPlayerPositionPacket packet) {
        var player = listener.player;
        if (player.isPassenger()) {
            standWhere(player);
        } else {
            // Only a relative teleport before any absolute one needs a start; vanilla's new player would be at the origin.
            if (position == null) standWhere(player);
            var arrival = PositionMoveRotation.calculateAbsolute(new PositionMoveRotation(position, Vec3.ZERO, yRot, xRot),
                    packet.change(), packet.relatives());
            position = arrival.position();
            yRot = arrival.yRot();
            xRot = arrival.xRot();
        }
        accept(new Teleport(packet.id(), position, yRot, xRot));
    }

    private void accept(Teleport teleport) {
        acceptedTeleports.add(teleport);
        var position = teleport.position();
        // The server checks an acceptance as a move from the baseline its connection tick last reset, and the teleport
        // moved the player away from it. A real client's acceptance travels for a while and normally arrives after the
        // next connection tick has reset the baseline to the destination. This client answers within the tick, so when
        // the teleport came after the connection ticked (or the connection is frozen), it applies that reset itself;
        // otherwise a long teleport would read as moving too quickly, and the server would teleport the player once more.
        if (teleportedSinceConnectionTick) listener.resetPosition();
        listener.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(teleport.id(), position.x, position.y, position.z,
                teleport.yRot(), teleport.xRot()));
    }

    /** As {@code ClientPacketListener.handleRotatePlayer}: turns where the server says and confirms the new rotation. */
    private void rotate(ClientboundPlayerRotationPacket packet) {
        var relatives = Relative.rotation(packet.relativeY(), packet.relativeX());
        var current = new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, yRot, xRot);
        var turned = PositionMoveRotation.calculateAbsolute(current, current.withRotation(packet.yRot(), packet.xRot()), relatives);
        yRot = turned.yRot();
        xRot = turned.xRot();
        listener.handleMovePlayer(new ServerboundMovePlayerPacket.Rot(yRot, xRot, false, false));
    }

    /**
     * Sends a loaded report now. It completes the current load only once that level's chunks have started and every
     * teleport is accepted; one sent earlier still belongs to the previous level, so the automatic report stays due.
     */
    public void reportLoaded() {
        if (levelChunksStarted && pendingPlacements.isEmpty()) levelLoading = false;
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
     * One client tick in which the player walks: the client first handles what has arrived ({@link #acknowledge()}), then
     * moves by {@code offset} from where it sees itself, faces {@code yRot}/{@code xRot}, sends that movement as
     * {@code LocalPlayer.sendPosition} does and ends the tick. {@code onGround} is what its physics would report there,
     * for example {@code false} at the start of a jump. The server may refuse the move and teleport the player back,
     * which the client applies with its next replies.
     */
    public void move(Vec3 offset, float yRot, float xRot, boolean onGround) {
        listener();
        acknowledge();
        if (!live()) throw new IllegalStateException(profile.name() + " does not control its player");
        if (position == null) throw new IllegalStateException(profile.name() + " has not been placed in its level yet");
        position = position.add(offset);
        this.yRot = yRot;
        this.xRot = xRot;
        this.onGround = onGround;
        sendPosition();
        endClientTick();
    }

    /**
     * Runs one connection tick now, besides the one every server tick runs: for a {@linkplain #freeze() frozen} client,
     * or a test that plays several ticks within one step.
     */
    public void tickConnection() {
        listener();
        runConnectionTick();
    }

    /** The Q key: drops one item, or with {@code wholeStack} (Ctrl+Q) the whole selected stack. */
    public void dropSelected(boolean wholeStack) {
        listener().handlePlayerAction(new ServerboundPlayerActionPacket(wholeStack
                ? ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS : ServerboundPlayerActionPacket.Action.DROP_ITEM,
                BlockPos.ZERO, Direction.DOWN));
    }

    // ---- Internals.

    /**
     * Ticks the connection of every live client of {@code server}, in joining order, as the server's connection list does;
     * called right after it. An exception propagates and crashes the server, as one in the automatic replies does. For a
     * remote connection vanilla would only log it and kick the player, which a test could mistake for the behaviour it
     * checks.
     */
    public static void tickConnections(MinecraftServer server) {
        for (var client : LIVE) {
            if (client.server == server && !client.frozen && client.live()) client.runConnectionTick();
        }
    }

    private void runConnectionTick() {
        // Cleared first: a teleport during this tick (by the player's own body) comes after the baseline reset.
        teleportedSinceConnectionTick = false;
        connection.tick();
    }

    /** Called by {@link TestClients} at the end of every server tick. */
    static void tick(MinecraftServer server) {
        for (var client : LIVE) {
            if (client.server != server) continue;
            if (client.ended()) { client.release(); continue; }
            client.acknowledge();
            client.clientTick();
        }
    }

    /**
     * {@code Minecraft.tick} for this client: {@code LocalPlayer.sendChanges} once the level is loaded, then the tick end
     * that lets the server take the next tick's position.
     */
    private void clientTick() {
        if (!live()) return;
        if (!withheld.contains(Reply.REPORT_POSITION) && !levelLoading) sendChanges();
        endClientTick();
    }

    private void endClientTick() {
        if (live()) listener.handleClientTickEnd(ServerboundClientTickEndPacket.INSTANCE);
    }

    private void sendChanges() {
        var player = listener.player;
        if (position == null) {
            // Only an attached client starts without a teleport placing it.
            if (mode != Mode.ATTACHED) return;
            standWhere(player);
        }
        if (player.isPassenger()) {
            // The client seats its player on the vehicle itself and reports only where it looks, every tick. It does not
            // steer: this client sends no vehicle movement.
            standWhere(player);
            onGround = false;
            listener.handleMovePlayer(new ServerboundMovePlayerPacket.Rot(yRot, xRot, onGround, false));
            return;
        }
        onGround = standsOnSomething(player);
        sendPosition();
    }

    /**
     * {@code LocalPlayer.sendPosition}: the position when it moved or 20 client ticks after the last one, the rotation
     * when it turned, and otherwise only a change of {@code onGround}. Nothing while the client watches through another
     * entity's eyes. The client never collides sideways.
     */
    private void sendPosition() {
        var player = listener.player;
        if (player.getCamera() != player) return;
        positionReminder++;
        boolean moved = position.distanceToSqr(sentPosition) > Mth.square(2.0E-4) || positionReminder >= 20;
        boolean turned = yRot != sentYRot || xRot != sentXRot;
        if (moved && turned) listener.handleMovePlayer(new ServerboundMovePlayerPacket.PosRot(position, yRot, xRot, onGround, false));
        else if (moved) listener.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(position, onGround, false));
        else if (turned) listener.handleMovePlayer(new ServerboundMovePlayerPacket.Rot(yRot, xRot, onGround, false));
        else if (onGround != sentOnGround) listener.handleMovePlayer(new ServerboundMovePlayerPacket.StatusOnly(onGround, false));
        if (moved) {
            sentPosition = position;
            positionReminder = 0;
        }
        if (turned) {
            sentYRot = yRot;
            sentXRot = xRot;
        }
        sentOnGround = onGround;
    }

    /**
     * What the vanilla client's physics would report for a player that has settled where this client sees it: whether a
     * block or solid entity is right below its feet. The client does not fall, so a player in the air is not on the
     * ground and stays in the air.
     */
    private boolean standsOnSomething(ServerPlayer player) {
        var feet = player.getBoundingBox().move(position.subtract(player.position())).move(0, -1.0E-5, 0);
        return !player.level().noCollision(player, feet);
    }

    /** Sees the player where the server has it, for a client that had no position packet to go by. */
    private void standWhere(ServerPlayer player) {
        position = player.position();
        yRot = player.getYRot();
        xRot = player.getXRot();
    }

    /** The client created a new local player: unplaced, and with nothing sent yet (vanilla's zero field defaults). */
    private void newPlayer() {
        position = null;
        yRot = 0;
        xRot = 0;
        onGround = false;
        sentPosition = Vec3.ZERO;
        sentYRot = 0;
        sentXRot = 0;
        sentOnGround = false;
        positionReminder = 0;
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
        pendingPlacements.clear();
        pendingKeepAlives.clear();
        acceptedTeleports.clear();
        pendingChunkBatches.set(0);
        newPlayer();
        levelLoading = false;
        levelChunksStarted = false;
        teleportedSinceConnectionTick = false;
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
            case ClientboundPlayerPositionPacket teleport -> {
                teleportedSinceConnectionTick = true;
                pendingPlacements.add(teleport);
            }
            case ClientboundPlayerRotationPacket rotation -> pendingPlacements.add(rotation);
            case ClientboundChunkBatchFinishedPacket ignored -> pendingChunkBatches.incrementAndGet();
            case ClientboundKeepAlivePacket keepAlive -> pendingKeepAlives.add(keepAlive.getId());
            case ClientboundLoginPacket ignored -> { levelLoading = true; levelChunksStarted = false; newPlayer(); }
            case ClientboundRespawnPacket ignored -> { levelLoading = true; levelChunksStarted = false; newPlayer(); }
            case ClientboundGameEventPacket event when event.getEvent() == ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START -> levelChunksStarted = true;
            default -> { }
        }
    }

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
