package dev.hardrush.p1.celebration;

import dev.hardrush.core.round.*;
import dev.hardrush.p1.P1LobbySession;
import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p1.world.P1LobbyWorld;
import dev.hardrush.p1.world.P1RoundWorldBundle;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;

/** Main-thread ceremony coordination; every destructive kit/return step follows a durable profile write. */
public final class P1CelebrationRuntime {
    private static final Map<MinecraftServer, P1CelebrationRuntime> SERVICES = new IdentityHashMap<>();
    private final MinecraftServer server;
    private final P1LobbySession session;
    private final P1PlayerProfiles profiles;
    private final Supplier<P1RoundWorldBundle> worlds;
    private final Map<UUID, PlayerState> players = new HashMap<>();
    private UUID roundId;
    private Set<UUID> roster;
    private boolean loadingRoster;
    private boolean stopping;

    private static final class PlayerState {
        ServerPlayer player;
        CompoundTag saved;
        boolean busy;
        boolean applied;
        boolean returning;
        boolean lobbyApplied;
        PlayerState(ServerPlayer player) { this.player = player; }
    }

    private P1CelebrationRuntime(MinecraftServer server, P1LobbySession session, P1PlayerProfiles profiles,
            Supplier<P1RoundWorldBundle> worlds) {
        this.server = server; this.session = session; this.profiles = profiles; this.worlds = worlds;
    }

    public static void configure(MinecraftServer server, P1LobbySession session, P1PlayerProfiles profiles,
            Supplier<P1RoundWorldBundle> worlds) {
        SERVICES.put(server, new P1CelebrationRuntime(server, session, profiles, worlds));
    }
    public static void unconfigure(MinecraftServer server) { SERVICES.remove(server); }
    public static void nextRound(MinecraftServer server) {
        var service = SERVICES.get(server);
        if (service == null) return;
        service.players.clear(); service.roundId = null; service.roster = null; service.loadingRoster = false;
    }
    public static boolean successful(RoundSnapshot round) {
        return !round.timingUncertain() && round.result() != null && round.result().kind() == RoundResult.Kind.COMPLETED
                && (round.phase() == RoundPhase.SETTLING || round.phase() == RoundPhase.CELEBRATING || round.phase() == RoundPhase.ROTATING);
    }

    public static void start(MinecraftServer server, RoundSnapshot round) {
        var service = SERVICES.get(server);
        if (service != null && successful(round)) service.initialize(round);
    }

    private void initialize(RoundSnapshot round) {
        if (!round.roundId().equals(roundId)) {
            roundId = round.roundId(); roster = null; loadingRoster = false; players.clear();
        }
        if (roster != null || loadingRoster) return;
        var bundle = worlds.get();
        if (bundle == null || !bundle.ready()) return;
        var initial = new HashSet<UUID>();
        for (var player : server.getPlayerList().getPlayers()) {
            if (player.level() == bundle.level(Level.END) || round.cleanShutdown()
                    && profiles.savedInCelebrationEnd(player, round, bundle.level(Level.END))) initial.add(player.getUUID());
        }
        loadingRoster = true;
        after(profiles.celebrationRoster(round, initial), round, null, saved -> { roster = saved; loadingRoster = false; });
    }

    /** Returns true only after every connected body is durably returned and all profile work has completed. */
    public static boolean tick(MinecraftServer server, RoundSnapshot round, boolean evacuate) {
        var service = SERVICES.get(server);
        return service == null || !successful(round) || service.tick(round, evacuate);
    }

    private boolean tick(RoundSnapshot round, boolean evacuate) {
        initialize(round);
        if (stopping || roster == null || !P1LobbyWorld.open(server).ready()) return false;
        var bundle = worlds.get();
        if (!evacuate && (bundle == null || !bundle.ready())) return false;
        boolean allReturned = true;
        for (var player : List.copyOf(server.getPlayerList().getPlayers())) {
            profiles.failure(player.getUUID()).ifPresent(problem -> { throw new IllegalStateException(problem); });
            if (!profiles.isReady(player.getUUID(), round)) { allReturned = false; continue; }
            var state = players.get(player.getUUID());
            if (state == null || state.player != player) {
                state = new PlayerState(player); players.put(player.getUUID(), state);
            }
            if (state.busy) { allReturned = false; continue; }
            if (state.saved == null) {
                var pending = state;
                pending.busy = true;
                after(profiles.celebrationState(player.getUUID(), round), round, pending, loaded -> {
                    pending.busy = false;
                    if (loaded.isPresent()) pending.saved = loaded.orElseThrow();
                    else {
                        pending.saved = new CompoundTag();
                        pending.saved.put("original", profiles.celebrationSnapshot(pending.player, true));
                        pending.saved.putBoolean("returned", !roster.contains(pending.player.getUUID()));
                        write(round, pending, () -> {});
                    }
                });
                allReturned = false; continue;
            }
            if (state.saved.getBooleanOr("returned", false)) {
                if (!state.lobbyApplied) {
                    state.player = profiles.finishCelebration(player, round);
                    state.lobbyApplied = true;
                    state.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("已返回独立大厅；全员返回或庆祝到期后安全换轮。下一轮需重新主动进入集结区。"));
                }
                if (!profiles.isReady(state.player.getUUID(), round) || !P1LobbyWorld.isLobby(state.player.level())) allReturned = false;
                continue;
            }
            allReturned = false;
            if (evacuate || state.returning) {
                profiles.freezeCelebration(player);
                if (state.applied) state.saved.put("current", profiles.celebrationSnapshot(player, false));
                state.saved.putBoolean("returned", true);
                write(round, state, () -> {});
            } else if (!state.applied) {
                boolean fresh = !state.saved.contains("current");
                var snapshot = state.saved.getCompoundOrEmpty(fresh ? "original" : "current");
                state.player = profiles.prepareCelebrationBody(player, round, snapshot, bundle.level(Level.END), fresh);
                state.saved.put("current", profiles.celebrationSnapshot(state.player, false));
                var pending = state;
                write(round, pending, () -> {
                    pending.applied = true;
                    profiles.unlockCelebration(pending.player, round);
                });
            }
        }
        // A disconnected player never blocks, but accepted journal writes must finish before world release.
        return allReturned && players.values().stream().noneMatch(state -> state.busy);
    }

    private void write(RoundSnapshot round, PlayerState state, Runnable complete) {
        state.busy = true;
        after(profiles.celebrationState(state.player.getUUID(), round, state.saved), round, state, ignored -> {
            state.busy = false;
            complete.run();
        });
    }

    private <T> void after(CompletableFuture<T> future, RoundSnapshot round, PlayerState state,
            java.util.function.Consumer<T> complete) {
        future.whenComplete((value, error) -> dev.hardrush.p1.P1MainThreadExecutor.forServer(server).execute(() -> {
            if (!server.isSameThread() || stopping || SERVICES.get(server) != this || !round.roundId().equals(roundId)) return;
            if (error != null) { session.adapterFailure("庆祝档案保存／恢复失败", error); return; }
            if (state != null && players.get(state.player.getUUID()) != state) return;
            try { complete.accept(value); }
            catch (RuntimeException failure) { session.adapterFailure("庆祝玩家状态应用失败", failure); }
        }));
    }

    public static boolean active(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        var state = service == null ? null : service.players.get(player.getUUID());
        var round = service == null ? null : service.session.snapshot().orElse(null);
        return state != null && state.player == player && state.applied && !state.busy && !state.returning
                && !state.saved.getBooleanOr("returned", false) && !service.stopping
                && service.session.state() == P1LobbySession.State.OPEN && round != null
                && round.roundId().equals(service.roundId) && successful(round) && round.phase() != RoundPhase.ROTATING;
    }

    public static boolean allowsRocket(ServerPlayer player, InteractionHand hand) {
        return active(player) && CelebrationKit.isRocket(player.getItemInHand(hand));
    }

    /** Called by native end portal routing before bed/anchor lookup. The next tick performs the durable return. */
    public static boolean returnThroughPortal(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        var round = service == null ? null : service.session.snapshot().orElse(null);
        var bundle = service == null ? null : service.worlds.get();
        if (round == null || !successful(round) || bundle == null || !bundle.ready() || player.level() != bundle.level(Level.END)) return false;
        var state = service.players.get(player.getUUID());
        if (state != null) state.returning = true;
        return true;
    }

    public static void disconnected(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        if (service != null) service.checkpoint(player);
    }

    private void checkpoint(ServerPlayer player) {
        var state = players.get(player.getUUID());
        var round = session.snapshot().orElse(null);
        if (state == null || state.player != player || !state.applied || state.saved == null
                || state.saved.getBooleanOr("returned", false) || round == null || !round.roundId().equals(roundId)) return;
        state.saved.put("current", profiles.celebrationSnapshot(player, false));
        write(round, state, () -> {});
    }

    public static void prepareStop(MinecraftServer server) {
        var service = SERVICES.get(server);
        if (service == null) return;
        var round = service.session.snapshot().orElse(null);
        // A stop immediately after native lethal capture drains FINISH only later in Session.close().
        // Seal the actual online End roster before profile quarantine changes every visible dimension.
        if (round != null && (successful(round) || service.session.terminalBatchId().isPresent())) service.initialize(round);
        for (var player : List.copyOf(server.getPlayerList().getPlayers())) service.checkpoint(player);
        service.stopping = true;
    }
}
