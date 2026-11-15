package dev.hardrush.lobby;

import dev.hardrush.core.lobby.LobbyContext;
import dev.hardrush.core.lobby.LobbyHints;
import dev.hardrush.world.LobbyWorld;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Both network guards consume through this router. Dedicated interactions NEVER reach vanilla. */
public final class LobbyInteractions {
    public enum Result { UNREGISTERED, ACCEPTED, STALE, IDENTITY, DISTANCE, INPUT, UNAVAILABLE }
    public interface Access {
        UUID round();
        boolean allowed(ServerPlayer player, LobbyContext context);
    }
    private record BlockEntry(UUID round, Block expected, LobbyContext context, Consumer<ServerPlayer> action) {}
    private record Dispatch(Object packet, ServerGamePacketListenerImpl connection, Result result) {}
    private static final Map<MinecraftServer, LobbyInteractions> SERVICES = new IdentityHashMap<>();
    private final MinecraftServer server;
    private final Access access;
    private final Map<BlockPos, BlockEntry> blocks = new HashMap<>();
    private final Map<UUID, Dispatch> dispatches = new HashMap<>();
    private record Hints(ServerPlayer owner, ServerGamePacketListenerImpl connection, LobbyHints values) {}
    private final Map<UUID, Hints> hints = new HashMap<>();
    private final LobbyProjections projections;
    private final LobbyRoleModels roleModels;
    private UUID round;
    /** The lobby plan generation registrations were made against; a new lobby invalidates them like a new round. */
    private int planGeneration;
    private boolean closed;

    private LobbyInteractions(MinecraftServer server, Access access) {
        this.server = server; this.access = access; round = access.round(); planGeneration = LobbyWorld.planGeneration(server);
        projections = new LobbyProjections(this);
        roleModels = new LobbyRoleModels(this);
    }
    public static LobbyInteractions configure(MinecraftServer server, Access access) {
        if (!server.isSameThread()) throw new IllegalStateException("Lobby configure requires server thread");
        close(server);
        var service = new LobbyInteractions(server, Objects.requireNonNull(access));
        SERVICES.put(server, service); return service;
    }
    public static Optional<LobbyInteractions> get(MinecraftServer server) { return Optional.ofNullable(SERVICES.get(server)); }
    public LobbyProjections projections() { return projections; }
    public LobbyRoleModels roleModels() { return roleModels; }
    public UUID round() { return access.round(); }
    public LobbyHints hints(ServerPlayer player) {
        requireThread(); synchronizeRound();
        if (server.getPlayerList().getPlayer(player.getUUID()) != player || player.connection == null)
            throw new IllegalArgumentException("Not a current connection");
        var previous = hints.get(player.getUUID());
        if (previous == null || previous.owner != player || previous.connection != player.connection) {
            previous = new Hints(player, player.connection, new LobbyHints()); hints.put(player.getUUID(), previous);
        }
        return previous.values;
    }
    public void requireThread() {
        if (!server.isSameThread()) throw new IllegalStateException("Lobby interaction requires server thread");
        if (closed) throw new IllegalStateException("Lobby interaction service is closed");
    }
    public void registerBlock(BlockPos pos, Block expected, LobbyContext context, Consumer<ServerPlayer> action) {
        requireThread(); synchronizeRound();
        // Any block can be a facility; the plan names the block expected there, and a different one is STALE.
        if (expected == Blocks.AIR) throw new IllegalArgumentException("A facility needs a block");
        blocks.put(pos.immutable(), new BlockEntry(Objects.requireNonNull(round()), expected,
                Objects.requireNonNull(context), Objects.requireNonNull(action)));
    }
    public void unregisterBlock(BlockPos pos) { requireThread(); blocks.remove(pos); }
    Result validate(ServerPlayer player, UUID expectedRound, LobbyContext context, Vec3 position) {
        requireThread(); synchronizeRound();
        if (expectedRound == null || !expectedRound.equals(round())) return Result.STALE;
        if (server.getPlayerList().getPlayer(player.getUUID()) != player || player.connection == null
                || !player.connection.isAcceptingMessages() || !player.isAlive() || !LobbyWorld.isLobby(player.level())) return Result.IDENTITY;
        if (!access.allowed(player, context)) return Result.UNAVAILABLE;
        // Fixed server range independent of client attributes or supplied hit vector.
        if (player.getEyePosition().distanceToSqr(position) > 25) return Result.DISTANCE;
        return Result.ACCEPTED;
    }
    public static Result block(ServerPlayer player, ServerboundUseItemOnPacket packet) {
        var service = SERVICES.get(player.level().getServer());
        if (service == null || !player.level().getServer().isSameThread()) return Result.UNREGISTERED;
        return service.dispatch(player, packet, () -> {
            var pos = packet.hitResult().getBlockPos(); var entry = service.blocks.get(pos);
            if (entry == null || !LobbyWorld.isLobby(player.level())) return Result.UNREGISTERED;
            Result result = service.validate(player, entry.round, entry.context, Vec3.atCenterOf(pos));
            if (result == Result.ACCEPTED && (packet.hand() != InteractionHand.MAIN_HAND || packet.hitResult().getType() == net.minecraft.world.phys.HitResult.Type.MISS
                    || !Double.isFinite(packet.hitResult().getLocation().lengthSqr())
                    || packet.hitResult().getLocation().distanceToSqr(Vec3.atCenterOf(pos)) > 3)) result = Result.INPUT;
            if (result == Result.ACCEPTED && !player.level().getBlockState(pos).is(entry.expected)) result = Result.STALE;
            // Ack here even when one guard cancels before the other, independent of mixin order.
            player.connection.ackBlockChangesUpTo(packet.sequence());
            for (var corrected : List.of(pos, pos.relative(packet.hitResult().getDirection()))) {
                if (player.level().getChunkSource().getChunkNow(corrected.getX() >> 4, corrected.getZ() >> 4) != null)
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(player.level(), corrected));
            }
            if (result == Result.ACCEPTED) entry.action.accept(player);
            return result;
        });
    }
    public static Result entity(ServerPlayer player, ServerboundInteractPacket packet) {
        var service = SERVICES.get(player.level().getServer());
        if (service == null || !player.level().getServer().isSameThread()) return Result.UNREGISTERED;
        return service.dispatch(player, packet, () -> {
            var result = service.roleModels.click(player, packet);
            if (consumed(result)) return result;
            result = service.projections.click(player, packet);
            return consumed(result) ? result : service.taggedEntity(player, packet);
        });
    }
    /**
     * A real lobby entity tagged {@code hardrush.fn.<id>} with a clickable function (design 4.3): an interaction box, a
     * statue's armor stand, an item frame. Right clicks only; left clicks arrive as attacks and protection drops them.
     */
    private Result taggedEntity(ServerPlayer player, ServerboundInteractPacket packet) {
        if (!LobbyWorld.isLobby(player.level())) return Result.UNREGISTERED;
        var entity = player.level().getEntity(packet.entityId());
        if (entity == null || entity instanceof ServerPlayer) return Result.UNREGISTERED;
        if (!(dev.hardrush.core.lobby.plan.MarkerTags.parseTrigger(entity.entityTags()).orElse(null)
                instanceof dev.hardrush.core.lobby.plan.MarkerTags.Ok ok)
                || ok.spec().binding() != dev.hardrush.core.lobby.plan.Binding.CLICK) return Result.UNREGISTERED;
        var function = ok.spec().function();
        var box = entity.getBoundingBox();
        var eye = player.getEyePosition();
        // The point of the box nearest the eye, so a large interaction box is reachable from its edge.
        var nearest = new Vec3(Math.clamp(eye.x, box.minX, box.maxX), Math.clamp(eye.y, box.minY, box.maxY), Math.clamp(eye.z, box.minZ, box.maxZ));
        var result = validate(player, round(), LobbyRuntime.context(function), nearest);
        var hit = packet.location();
        double reach = box.getSize() + 1;
        if (result == Result.ACCEPTED && (packet.hand() != InteractionHand.MAIN_HAND
                || hit != null && (!Double.isFinite(hit.lengthSqr()) || hit.lengthSqr() > reach * reach))) result = Result.INPUT;
        if (result == Result.ACCEPTED) LobbyRuntime.activate(player, function, null);
        return result;
    }
    /** Left-clicking a packet-only courtyard model; the model never exists in the world. */
    public static Result attack(ServerPlayer player, net.minecraft.network.protocol.game.ServerboundAttackPacket packet) {
        var service = SERVICES.get(player.level().getServer());
        if (service == null || !player.level().getServer().isSameThread()) return Result.UNREGISTERED;
        return service.dispatch(player, packet, () -> service.projections.attack(player, packet.entityId()));
    }
    private Result dispatch(ServerPlayer player, Object packet, java.util.function.Supplier<Result> action) {
        var previous = dispatches.get(player.getUUID());
        if (previous != null && previous.packet == packet && previous.connection == player.connection) return previous.result;
        // Install before callback so a reentrant guard also cannot execute the action twice.
        dispatches.put(player.getUUID(), new Dispatch(packet, player.connection, Result.UNAVAILABLE));
        Result result = action.get();
        dispatches.put(player.getUUID(), new Dispatch(packet, player.connection, result)); return result;
    }
    public static boolean consumed(Result result) { return result != Result.UNREGISTERED; }
    /** A new round or a newly installed lobby drops every registration, dispatch, projection and model. */
    private void synchronizeRound() {
        UUID current = round();
        int generation = LobbyWorld.planGeneration(server);
        if (!Objects.equals(round, current) || generation != planGeneration) {
            blocks.clear(); dispatches.clear(); projections.clear(); roleModels.clear(); hints.clear();
            round = current; planGeneration = generation;
        }
    }
    public Optional<Result> lastResult(ServerPlayer player) {
        var dispatch = dispatches.get(player.getUUID());
        return dispatch == null || dispatch.connection != player.connection ? Optional.empty() : Optional.of(dispatch.result);
    }
    public static void tick(MinecraftServer server) {
        var service = SERVICES.get(server); if (service == null) return;
        service.synchronizeRound(); service.projections.tick(); service.roleModels.tick(server);
        dev.hardrush.lobby.LobbyMenus.tick(server);
        dev.hardrush.record.HistoryLibrary.tick(server);
        // Quarantine skips vanilla player doTick, including container invalidation. Still enforce contexts.
        for (var player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (dev.hardrush.player.PlayerProfiles.simulationFrozen(player)
                    && dev.hardrush.lobby.LobbyMenus.isMenu(player)) player.containerMenu.broadcastChanges();
        }
        service.hints.values().removeIf(h -> server.getPlayerList().getPlayer(h.owner.getUUID()) != h.owner
                || h.owner.connection != h.connection || !h.connection.isAcceptingMessages());
    }
    public static void disconnected(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer()); if (service == null) return;
        service.projections.remove(player); service.roleModels.remove(player);
        service.dispatches.remove(player.getUUID()); service.hints.remove(player.getUUID());
    }
    public static void close(MinecraftServer server) {
        var service = SERVICES.remove(server);
        if (service != null) {
            service.requireThread(); service.projections.clear(); service.roleModels.clear();
            service.blocks.clear(); service.dispatches.clear(); service.hints.clear(); service.closed = true;
        }
    }
}
