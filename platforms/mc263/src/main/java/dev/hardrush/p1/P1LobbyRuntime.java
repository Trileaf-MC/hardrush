package dev.hardrush.p1;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.api.OperationTag;
import dev.hardrush.core.api.ReplayPort;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p1.world.P1RoundWorldBundle;
import dev.hardrush.p1.combat.P1CombatRuntime;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.combat.CombatEventCodec;
import dev.hardrush.persistence.EvidenceWrite;
import net.minecraft.world.level.Level;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Explicitly enabled P1 normal-mode adapter; competition state stays separate from P0 probes. */
public final class P1LobbyRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("hardrush-p1");
    private static final Map<MinecraftServer, P1LobbySession> SESSIONS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, P1RoundWorldBundle> WORLDS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, P1PlayerProfiles> PROFILES = new IdentityHashMap<>();
    private static final Map<MinecraftServer, Map<java.util.UUID, String>> ADMITTED = new IdentityHashMap<>();
    private static final Map<MinecraftServer, CompletableFuture<Void>> RELEASES = new IdentityHashMap<>();
    private static final Map<MinecraftServer, ReplayPort> RECORDINGS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, CompletableFuture<Void>> REPLAY_FLUSHES = new IdentityHashMap<>();
    private static boolean initialized;

    private P1LobbyRuntime() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!Boolean.getBoolean("hardrush.p1.enabled")) return;
            var profiles = new P1PlayerProfiles(server, server.getWorldPath(LevelResource.ROOT).resolve("hardrush/profiles"),
                    server::overworld, () -> Vec3.atBottomCenterOf(server.overworld().getRespawnData().pos()));
            P1PlayerProfiles.install(server, profiles);
            PROFILES.put(server, profiles);
            ADMITTED.put(server, new java.util.HashMap<>());
            var reference = new P1LobbySession[1];
            var bootstrapCallbacks = new ArrayDeque<Runnable>();
            var session = new P1LobbySession(server.getWorldPath(LevelResource.ROOT).resolve("hardrush/p1.sqlite"),
                    MonotonicClock.system(), task -> server.execute(() -> {
                        // execute() may run inline on the server thread if
                        // bootstrap IO finished before constructor return.
                        if (reference[0] == null) bootstrapCallbacks.add(task);
                        else if (SESSIONS.get(server) == reference[0]) task.run();
                    }), (message, error) -> LOGGER.error(message, error));
            reference[0] = session;
            SESSIONS.put(server, session);
            session.onCommitted((before, after) -> applyPlayers(server, before, after));
            P1CombatRuntime.configure(server, new P1CombatRuntime.RoundAccess() {
                @Override public Optional<java.util.UUID> roundOf(net.minecraft.server.level.ServerLevel level) {
                    var round = session.snapshot().orElse(null);
                    var worlds = WORLDS.get(server);
                    return round != null && worlds != null && worlds.ready()
                            && P1RoundWorldBundle.sameRound(worlds.level(Level.OVERWORLD), level)
                            ? Optional.of(round.roundId()) : Optional.empty();
                }
                @Override public Optional<CombatActor> actor(java.util.UUID roundId, java.util.UUID playerId) {
                    if (session.competitionLocked()) return Optional.empty();
                    return session.snapshot().filter(round -> round.roundId().equals(roundId))
                            .map(round -> round.participants().get(playerId))
                            .filter(participant -> participant.role() == ParticipantRole.HUMAN_ACTIVE)
                            .map(participant -> new CombatActor(participant.id(), participant.role(), participant.lifeSequence(), 0));
                }
                @Override public boolean competitionFrozen(net.minecraft.server.level.ServerLevel level) {
                    return session.competitionLocked();
                }
                @Override public boolean mayDamage(net.minecraft.server.level.ServerLevel level, UUID batchId) {
                    return !session.competitionLocked() || session.terminalBatchId().filter(id -> id.equals(batchId)).isPresent();
                }
                @Override public void batchClosed(UUID roundId, UUID batchId) { session.finishDamageBatch(roundId, batchId); }
            }, event -> {
                var worlds = WORLDS.get(server);
                var dragon = verifiedFirstDragon(worlds, event);
                if (dragon.isPresent()) {
                    session.captureDragonDeath(event, dragon.orElseThrow());
                    return;
                }
                session.recordEvidence(new EvidenceWrite(event.roundId(), event.eventId(), "COMBAT", CombatEventCodec.encode(event)));
            });
            while (!bootstrapCallbacks.isEmpty()) bootstrapCallbacks.remove().run();
            LOGGER.info("P1 enabled; independent world preparation and player profile isolation gate participation");
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var session = SESSIONS.get(server);
            if (session == null) return;
            try {
                if (session.state() == P1LobbySession.State.FAILED) {
                    var round = session.snapshot().orElse(null);
                    if (round != null) for (var player : server.getPlayerList().getPlayers()) {
                        var profiles = PROFILES.get(server);
                        if (profiles.manages(player)) profiles.returnToLobby(player);
                    }
                    return;
                }
                var round = session.snapshot().orElse(null);
                if (round == null) return;
                var worlds = WORLDS.get(server);
                if (worlds == null && round.phase() != RoundPhase.RECOVERY_REQUIRED) {
                    worlds = P1RoundWorldBundle.open(server, round);
                    WORLDS.put(server, worlds);
                }
                if (worlds != null) worlds.tick();
                var profiles = PROFILES.get(server);
                var activeWorlds = worlds;
                profiles.tick(round);
                if (worlds != null && worlds.ready()) session.adaptersReady(round.roundId());
                if (round.phase() == RoundPhase.ROTATING) {
                    rotate(server, session, round, worlds, profiles);
                    session.tick();
                    return;
                }
                for (var player : server.getPlayerList().getPlayers()) {
                    var profileFailure = profiles.failure(player.getUUID());
                    if (profileFailure.isPresent()) {
                        throw new IllegalStateException("玩家 " + player.getUUID() + " 档案核验失败：" + profileFailure.orElseThrow());
                    }
                    if (!profiles.isReady(player.getUUID(), round)) continue;
                    var names = ADMITTED.get(server);
                    var participant = round.participants().get(player.getUUID());
                    if (round.phase() == RoundPhase.SETTLING || round.phase() == RoundPhase.CELEBRATING) {
                        if (participant != null && participant.role() == ParticipantRole.HUMAN_ACTIVE
                                && worlds != null && worlds.ready() && P1PlayerProfiles.isLocked(player)) {
                            profiles.admitCelebration(player, round, worlds.level(Level.OVERWORLD),
                                    Vec3.atBottomCenterOf(worlds.spawn()),
                                    level -> P1RoundWorldBundle.sameRound(activeWorlds.level(Level.OVERWORLD), level));
                        }
                        continue;
                    }
                    if (participant != null && participant.role() == ParticipantRole.HUMAN_RESPAWNING
                            && worlds != null && worlds.ready()) {
                        profiles.permitRespawn(player, round,
                                level -> P1RoundWorldBundle.sameRound(activeWorlds.level(Level.OVERWORLD), level));
                        if (profiles.respawnCommitPending(player, round)) {
                            session.normalRespawn(player.getUUID(), participant.lifeSequence());
                        }
                    }
                    if (participant != null && participant.role() == ParticipantRole.HUMAN_ACTIVE
                            && !participant.online() && worlds != null && worlds.ready() && P1PlayerProfiles.isLocked(player)) {
                        if (!profiles.admit(player, round, worlds.level(Level.OVERWORLD), Vec3.atBottomCenterOf(worlds.spawn()),
                                level -> P1RoundWorldBundle.sameRound(activeWorlds.level(Level.OVERWORLD), level))) continue;
                        names.put(player.getUUID(), player.getScoreboardName());
                        session.onOnline(player.getUUID(), player.getScoreboardName(), true);
                        continue;
                    }
                    if (!player.getScoreboardName().equals(names.get(player.getUUID()))) {
                        names.put(player.getUUID(), player.getScoreboardName());
                        session.onOnline(player.getUUID(), player.getScoreboardName(), true);
                    }
                }
                session.tick();
            } catch (RuntimeException error) { session.adapterFailure("世界／档案适配失败", error); }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var session = SESSIONS.get(server);
            if (session != null) session.onOnline(handler.player.getUUID(), handler.player.getScoreboardName(), false);
            var admitted = ADMITTED.get(server);
            if (admitted != null) admitted.remove(handler.player.getUUID());
            var profiles = PROFILES.get(server);
            if (profiles != null) profiles.onDisconnect(handler.player);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayer player)) return;
            var session = SESSIONS.get(player.level().getServer());
            var round = session == null ? null : session.snapshot().orElse(null);
            var participant = round == null ? null : round.participants().get(player.getUUID());
            if (participant != null && participant.role() == ParticipantRole.HUMAN_ACTIVE
                    && P1RoundWorldBundle.owns(player.level())) session.normalDeath(player.getUUID(), participant.lifeSequence(),
                            P1CombatRuntime.currentBatch(player.level()).orElse(null));
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (alive) return;
            var session = SESSIONS.get(newPlayer.level().getServer());
            var round = session == null ? null : session.snapshot().orElse(null);
            var participant = round == null ? null : round.participants().get(newPlayer.getUUID());
            if (participant != null && P1RoundWorldBundle.owns(newPlayer.level())) {
                var profiles = PROFILES.get(newPlayer.level().getServer());
                if (profiles != null) profiles.onRespawn(oldPlayer, newPlayer, round);
                session.normalRespawn(newPlayer.getUUID(), participant.lifeSequence());
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            var session = SESSIONS.get(server);
            if (session == null) return;
            session.prepareStop();
            var profiles = PROFILES.get(server);
            if (profiles != null) for (var player : server.getPlayerList().getPlayers()) {
                if (!profiles.manages(player)) continue;
                try { profiles.returnToLobby(player); }
                catch (RuntimeException error) { session.adapterFailure("正常停服档案封存失败", error); }
            }
            var recorder = RECORDINGS.get(server);
            if (recorder != null) try {
                var barrier = REPLAY_FLUSHES.get(server);
                if (barrier == null) {
                    var round = session.snapshot().orElseThrow();
                    barrier = P1RoundRelease.stopRecordings(recorder,
                            new OperationTag(round.roundId(), UUID.randomUUID(), round.version())).toCompletableFuture();
                }
                barrier.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception error) { session.adapterFailure("正常停服回放保存未核验", error); }
            var worlds = WORLDS.remove(server);
            if (worlds != null) {
                try { worlds.shutdown(); }
                catch (RuntimeException error) { session.adapterFailure("正常停服世界保存失败", error); }
            }
            PROFILES.remove(server);
            if (profiles != null) {
                try { profiles.close(); }
                catch (Exception error) { session.adapterFailure("正常停服档案排空失败", error); }
                P1PlayerProfiles.uninstall(server);
            }
            ADMITTED.remove(server);
            RELEASES.remove(server);
            RECORDINGS.remove(server);
            REPLAY_FLUSHES.remove(server);
            try { session.close(); }
            catch (Exception error) { LOGGER.error("P1 stop flush failed; retain database and inspect diagnostics", error); }
            SESSIONS.remove(server);
            P1CombatRuntime.unconfigure(server);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("hardrush")
                        .executes(context -> status(context.getSource()))
                        .then(Commands.literal("status").executes(context -> status(context.getSource())))
                        .then(Commands.literal("join").executes(context -> {
                            var source = context.getSource();
                            var player = source.getPlayerOrException();
                            var session = SESSIONS.get(source.getServer());
                            if (session == null) return reply(source, disabled());
                            if (!profileReady(player)) return reply(source, new P1LobbySession.Reply(false, "玩家档案仍在隔离／转换，请稍后重试。"));
                            session.requestJoin(player.getUUID(), player.getScoreboardName(), result -> reply(source, result));
                            return 0;
                        }))
                        .then(Commands.literal("ready").executes(context -> ready(context.getSource(), true)))
                        .then(Commands.literal("unready").executes(context -> ready(context.getSource(), false)))
                        .then(Commands.literal("retry-world")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(context -> {
                                    var worlds = WORLDS.get(context.getSource().getServer());
                                    if (worlds == null || worlds.failure().isEmpty()) {
                                        context.getSource().sendFailure(Component.literal("当前没有可重试的世界准备失败。"));
                                        return 0;
                                    }
                                    worlds.retry();
                                    context.getSource().sendSuccess(() -> Component.literal("已重试原轮次世界准备，轮次与种子不变。"), false);
                                    return 1;
                                }))
                        .then(Commands.literal("config")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.literal("next-mode")
                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                .suggests((context, builder) -> builder.suggest("normal").suggest("hardcore").buildFuture())
                                                .executes(context -> nextMode(context.getSource(),
                                                        StringArgumentType.getString(context, "mode"))))))));
    }

    private static int status(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(status(source.getServer())), false);
        return 1;
    }

    /** Main-thread, read-only health access; no participation or world-ready bypass. */
    public static String status(MinecraftServer server) {
        var session = SESSIONS.get(server);
        var worlds = WORLDS.get(server);
        return session == null ? disabled().message() : session.status()
                + (worlds == null ? "" : " 世界准备：" + (worlds.ready() ? "已完成" : worlds.failure().orElse("进行中")));
    }

    public static Optional<P1LobbySession.State> sessionState(MinecraftServer server) {
        var session = SESSIONS.get(server);
        return session == null ? Optional.empty() : Optional.of(session.state());
    }

    public static Optional<RoundSnapshot> snapshot(MinecraftServer server) {
        var session = SESSIONS.get(server);
        return session == null ? Optional.empty() : session.snapshot();
    }

    public static Optional<RulesConfig> nextRules(MinecraftServer server) {
        var session = SESSIONS.get(server);
        return session == null ? Optional.empty() : session.nextRules();
    }

    public static boolean mutationPending(MinecraftServer server) {
        var session = SESSIONS.get(server);
        return session != null && session.mutationPending();
    }

    /** Native fight identity, actual END entity and first-kill state must all agree. */
    public static Optional<UUID> verifiedFirstDragon(P1RoundWorldBundle worlds, CombatEvent event) {
        if (!event.lethal() || worlds == null || !worlds.ready() || !worlds.roundId().equals(event.roundId())) return Optional.empty();
        var end = worlds.level(Level.END);
        var fight = end.getDragonFight();
        if (fight == null || fight.hasPreviouslyKilledDragon() || !event.victimId().equals(fight.dragonUUID())
                || !(end.getEntity(event.victimId()) instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon)) {
            return Optional.empty();
        }
        return Optional.of(event.victimId());
    }

    private static int ready(CommandSourceStack source, boolean ready) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        var session = SESSIONS.get(source.getServer());
        if (session == null) return reply(source, disabled());
        if (!profileReady(player)) return reply(source, new P1LobbySession.Reply(false, "玩家档案仍在隔离／转换，请稍后重试。"));
        session.requestReady(player.getUUID(), ready, result -> reply(source, result));
        return 0;
    }

    private static boolean profileReady(ServerPlayer player) {
        var profiles = PROFILES.get(player.level().getServer());
        var session = SESSIONS.get(player.level().getServer());
        var round = session == null ? null : session.snapshot().orElse(null);
        return profiles != null && round != null && profiles.isReady(player.getUUID(), round);
    }

    private static void applyPlayers(MinecraftServer server, RoundSnapshot before, RoundSnapshot after) {
        if (!before.roundId().equals(after.roundId())) {
            WORLDS.remove(server);
            RELEASES.remove(server);
            REPLAY_FLUSHES.remove(server);
            ADMITTED.get(server).clear();
            return;
        }
        if (before.result() == null && after.result() != null) {
            var winner = after.result().winner() == null ? "胜者待核验" : after.participants().get(after.result().winner()).name();
            server.getPlayerList().broadcastSystemMessage(Component.literal("本轮已通关：" + winner
                    + "，有效挑战 " + after.challengeMillis() + " ms。庆祝结束后返回大厅并轮换。"), false);
        }
        if (after.phase() != RoundPhase.RUNNING) return;
        var worlds = WORLDS.get(server);
        var profiles = PROFILES.get(server);
        if (worlds == null || profiles == null || !worlds.ready()) return;
        for (var participant : after.participants().values()) {
            var player = server.getPlayerList().getPlayer(participant.id());
            if (player == null || !profiles.isReady(participant.id(), after)) continue;
            var previous = before.participants().get(participant.id());
            if (participant.role() == ParticipantRole.HUMAN_ACTIVE && P1PlayerProfiles.isLocked(player)
                    && (previous == null || previous.role() == ParticipantRole.LOBBY
                        || previous.role() == ParticipantRole.HUMAN_RESPAWNING || !previous.online())) {
                boolean admitted = previous != null && previous.role() == ParticipantRole.HUMAN_RESPAWNING
                        ? profiles.confirmRespawn(player, after)
                        : profiles.admit(player, after, worlds.level(Level.OVERWORLD), Vec3.atBottomCenterOf(worlds.spawn()),
                            level -> P1RoundWorldBundle.sameRound(worlds.level(Level.OVERWORLD), level));
                if (!admitted) {
                    throw new IllegalStateException("已提交参与者的档案无法入场：" + participant.id());
                }
                if (!participant.online()) SESSIONS.get(server).onOnline(player.getUUID(), player.getScoreboardName(), true);
            }
        }
    }

    /** Integration seam: an installed recorder must verify its final writes before world release. */
    public static void installReplay(MinecraftServer server, ReplayPort recordings) {
        if (!server.isSameThread()) throw new IllegalStateException("回放适配器必须在服务端主线程安装");
        if (RELEASES.containsKey(server)) throw new IllegalStateException("轮换已开始，不能更换回放适配器");
        RECORDINGS.put(server, java.util.Objects.requireNonNull(recordings));
    }

    private static void rotate(MinecraftServer server, P1LobbySession session, RoundSnapshot round,
            P1RoundWorldBundle worlds, P1PlayerProfiles profiles) {
        var release = RELEASES.get(server);
        if (release != null) {
            if (!release.isDone()) return;
            release.join(); // Any failed recorder/save retains this round and enters the failure gate.
            session.rotationReady(round.roundId());
            return;
        }
        if (worlds == null || !worlds.ready() || session.mutationPending()) return;
        for (var player : server.getPlayerList().getPlayers()) {
            if (profiles.manages(player) && profiles.failure(player.getUUID()).isPresent()) {
                throw new IllegalStateException("轮换档案封存失败：" + profiles.failure(player.getUUID()).orElseThrow());
            }
            if (profiles.manages(player)) profiles.returnToLobby(player);
            else if (P1RoundWorldBundle.sameRound(worlds.level(Level.OVERWORLD), player.level())) {
                player.stopRiding(); player.ejectPassengers(); player.closeContainer();
                var spawn = server.overworld().getRespawnData().pos();
                player.teleportTo(server.overworld(), spawn.getX() + .5, spawn.getY(), spawn.getZ() + .5,
                        java.util.Set.of(), 0, 0, true);
            }
        }
        // Do not replace the old profile generation while its survival suspension is still being written.
        if (server.getPlayerList().getPlayers().stream().anyMatch(player -> profiles.manages(player)
                && !profiles.isReady(player.getUUID(), round))) return;
        var completion = new CompletableFuture<Void>();
        RELEASES.put(server, completion);
        var recorder = RECORDINGS.get(server);
        if (recorder == null) LOGGER.info("P1 round {}: replay integration is disabled; no recording is claimed", round.roundId());
        var tag = new OperationTag(round.roundId(), UUID.randomUUID(), round.version());
        var recordingBarrier = recorder == null ? CompletableFuture.<Void>completedFuture(null)
                : P1RoundRelease.stopRecordings(recorder, tag).toCompletableFuture();
        REPLAY_FLUSHES.put(server, recordingBarrier);
        P1RoundRelease.releaseAfterRecordings(recordingBarrier, server::execute,
                () -> SESSIONS.get(server) == session && WORLDS.get(server) == worlds
                        && session.state() == P1LobbySession.State.OPEN
                        && session.snapshot().orElseThrow().roundId().equals(round.roundId()), worlds::saveAndClose)
                .whenComplete((ignored, error) -> {
                    if (error == null) completion.complete(null); else completion.completeExceptionally(error);
                });
    }

    private static int nextMode(CommandSourceStack source, String argument) {
        var session = SESSIONS.get(source.getServer());
        if (session == null) return reply(source, disabled());
        GameMode mode = switch (argument.toLowerCase(Locale.ROOT)) {
            case "normal" -> GameMode.NORMAL;
            case "hardcore" -> GameMode.HARDCORE;
            default -> null;
        };
        if (mode == null) return reply(source, new P1LobbySession.Reply(false, "模式只接受 normal 或 hardcore。"));
        session.updateNextMode(mode, source.getTextName(), result -> reply(source, result));
        // Do not report synchronous command success while the durable write is pending.
        return 0;
    }

    private static int reply(CommandSourceStack source, P1LobbySession.Reply result) {
        if (result.success()) source.sendSuccess(() -> Component.literal(result.message()), false);
        else source.sendFailure(Component.literal(result.message()));
        return result.success() ? 1 : 0;
    }

    private static P1LobbySession.Reply disabled() {
        return new P1LobbySession.Reply(false,
                "P1 默认关闭；服务端启动参数 -Dhardrush.p1.enabled=true 可启用独立世界／档案与普通模式开发入口。");
    }
}
