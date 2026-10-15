package dev.hardrush.p1;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.api.OperationTag;
import dev.hardrush.core.api.ReplayPort;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p2.protection.P2ProtectionRuntime;
import dev.hardrush.p2.lobby.P2LobbyRuntime;
import dev.hardrush.p1.world.P1RoundWorldBundle;
import dev.hardrush.p1.world.P1ClosedWorlds;
import dev.hardrush.p1.world.P1WorldPaths;
import dev.hardrush.p1.combat.P1CombatRuntime;
import dev.hardrush.p1.record.P1ProgressRuntime;
import dev.hardrush.p1.record.P1TabRuntime;
import dev.hardrush.p1.record.P1HistoryCommands;
import dev.hardrush.p1.admin.P1AdminCommands;
import dev.hardrush.core.admin.AdminAction;
import dev.hardrush.core.book.HistoryBookService;
import dev.hardrush.core.record.*;
import dev.hardrush.p1.book.P1BookPort;
import dev.hardrush.p1.book.P1BookInventorySaver;
import dev.hardrush.p1.book.P1BookReader;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.combat.CombatEventCodec;
import dev.hardrush.persistence.EvidenceWrite;
import net.minecraft.world.level.Level;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
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

/** Explicitly enabled formal round adapter; competition state stays separate from P0 probes. */
public final class P1LobbyRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("hardrush-p1");
    private static final Map<MinecraftServer, P1LobbySession> SESSIONS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, P1RoundWorldBundle> WORLDS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, P1PlayerProfiles> PROFILES = new IdentityHashMap<>();
    private static final Map<MinecraftServer, Map<java.util.UUID, String>> ADMITTED = new IdentityHashMap<>();
    private static final Map<MinecraftServer, CompletableFuture<Void>> RELEASES = new IdentityHashMap<>();
    private static final Map<MinecraftServer, ReplayPort> RECORDINGS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, CompletableFuture<Void>> REPLAY_FLUSHES = new IdentityHashMap<>();
    private static final Map<MinecraftServer, P1TabRuntime> TABS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, HistoryBookService> BOOKS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, BookAttempt>> BOOK_ATTEMPTS = new IdentityHashMap<>();
    private static boolean initialized;
    private static final P1AdminCommands ADMIN = new P1AdminCommands(SESSIONS::get,
            server -> Optional.ofNullable(WORLDS.get(server)).flatMap(P1RoundWorldBundle::failure),
            (server, expectedFailure) -> {
                var worlds = WORLDS.get(server);
                if (worlds == null || !worlds.failure().filter(expectedFailure::equals).isPresent()) {
                    throw new IllegalStateException("世界准备目标已改变，请重新核对。");
                }
                worlds.retry();
            });

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
                        // After halt(), vanilla execute() may run inline on the submitting IO worker.
                        // close() drains the accepted futures itself; never mutate the engine or
                        // apply native player/TAB effects from that worker during normal shutdown.
                        if (!server.isSameThread()) return;
                        // execute() may run inline on the server thread if
                        // bootstrap IO finished before constructor return.
                        if (reference[0] == null) bootstrapCallbacks.add(task);
                        else if (SESSIONS.get(server) == reference[0]) task.run();
                    }), (message, error) -> LOGGER.error(message, error));
            reference[0] = session;
            SESSIONS.put(server, session);
            P2LobbyRuntime.configure(server, session, profiles, () -> WORLDS.get(server));
            P2ProtectionRuntime.configure(server, new P2ProtectionRuntime.Access() {
                @Override public Optional<P2ProtectionRuntime.Zone> zone(net.minecraft.server.level.ServerLevel level) {
                    var round = session.snapshot().orElse(null);
                    if (round == null) return Optional.empty();
                    if (level == server.overworld()) return Optional.of(new P2ProtectionRuntime.Zone(round.roundId(),
                            Vec3.atBottomCenterOf(level.getRespawnData().pos()), round.rules().protectionRadius(), true));
                    var worlds = WORLDS.get(server);
                    return worlds != null && worlds.ready() && level == worlds.level(Level.OVERWORLD)
                            ? Optional.of(new P2ProtectionRuntime.Zone(round.roundId(), Vec3.atBottomCenterOf(worlds.spawn()),
                                round.rules().protectionRadius(), false)) : Optional.empty();
                }
                @Override public Optional<dev.hardrush.core.round.Participant> participant(UUID playerId) {
                    return session.snapshot().map(round -> round.participants().get(playerId));
                }
                @Override public boolean leaveProtection(UUID roundId, UUID playerId, int lifeSequence) {
                    return session.leaveProtection(roundId, playerId, lifeSequence);
                }
                @Override public void quarantine(ServerPlayer player) {
                    profiles.returnToLobby(player);
                    session.adapterFailure("保护边界缺少安全站位，已隔离并保留身份",
                            new IllegalStateException("玩家 " + player.getUUID() + " 无已加载且安全的区外回退位置"));
                }
            });
            TABS.put(server, new P1TabRuntime());
            BOOK_ATTEMPTS.put(server, new java.util.HashMap<>());
            BOOKS.put(server, new HistoryBookService(new HistoryBookService.HistorySource() {
                @Override public java.util.concurrent.CompletionStage<Optional<RoundHistory>> latestHistoryBefore(long sequence) { return session.latestHistoryBefore(sequence); }
                @Override public java.util.concurrent.CompletionStage<Optional<RoundHistory>> roundHistory(UUID roundId) { return session.history(roundId); }
                @Override public java.util.concurrent.CompletionStage<Long> participantCount(UUID roundId) { return session.participantCount(roundId); }
                @Override public java.util.concurrent.CompletionStage<java.util.List<ParticipantRecord>> participantRecords(UUID roundId, int limit, int offset) { return session.participants(roundId, limit, offset); }
                @Override public java.util.concurrent.CompletionStage<Optional<ParticipantRecord>> participantRecord(UUID roundId, UUID playerId) { return session.participant(roundId, playerId); }
                @Override public java.util.concurrent.CompletionStage<java.util.List<LeaderboardEntry>> leaderboard(String fingerprint, int limit, int offset) {
                    return offset == 0 ? session.leaderboard(fingerprint, limit)
                            : CompletableFuture.failedFuture(new IllegalArgumentException("仅提供榜单首页"));
                }
            }, new HistoryBookService.Claims() {
                @Override public java.util.concurrent.CompletionStage<BookClaim> ensureAutomaticBookClaim(UUID playerId, UUID roundId, int edition, int volume) { return session.ensureBook(playerId, roundId, edition, volume); }
                @Override public java.util.concurrent.CompletionStage<Boolean> markBookDelivered(BookClaim claim) { return session.markBookDelivered(claim.playerId(), claim.roundId(), claim.edition(), claim.volume()); }
            }, new P1BookPort(server, (tag, playerId) -> {
                var player = server.getPlayerList().getPlayer(playerId);
                return player != null && session.snapshot().filter(round -> round.roundId().equals(tag.roundId())).isPresent()
                        && canReceiveBook(player);
            }, new P1BookInventorySaver(server)), MonotonicClock.system()));
            session.onCommitted((before, after) -> applyPlayers(server, before, after));
            session.onRecordsChanged(() -> refreshTab(server, true));
            P1ProgressRuntime.configure(server, player -> {
                var round = session.view().orElse(null);
                var worlds = WORLDS.get(server);
                var participant = round == null ? null : round.participants().get(player.getUUID());
                if (round == null || round.phase() != RoundPhase.RUNNING || session.competitionLocked() || !player.isAlive() || participant == null || !participant.online()
                        || participant.role() != ParticipantRole.HUMAN_ACTIVE || worlds == null || !worlds.ready()
                        || !profiles.isReady(player.getUUID(), round)
                        || !P1RoundWorldBundle.sameRound(worlds.level(Level.OVERWORLD), player.level())) return Optional.empty();
                return Optional.of(new P1ProgressRuntime.Context(round.roundId(), round.challengeNanos()));
            }, session::recordProgress);
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
                session.captureLethalEvidence(event);
                session.recordEvidence(new EvidenceWrite(event.roundId(), event.eventId(), "COMBAT", CombatEventCodec.encode(event)));
            });
            while (!bootstrapCallbacks.isEmpty()) bootstrapCallbacks.remove().run();
            LOGGER.info("P1 enabled; independent world preparation and player profile isolation gate participation");
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var session = SESSIONS.get(server);
            if (session == null) return;
            try {
                if (session.state() == P1LobbySession.State.FAILED || session.state() == P1LobbySession.State.RECHECKING
                        || session.snapshot().filter(r -> r.phase() == RoundPhase.RECOVERY_REQUIRED).isPresent()) {
                    var round = session.snapshot().orElse(null);
                    if (round != null) for (var player : server.getPlayerList().getPlayers()) {
                        var profiles = PROFILES.get(server);
                        if (profiles.manages(player)) profiles.returnToLobby(player);
                    }
                    refreshTab(server, false);
                    return;
                }
                var round = session.snapshot().orElse(null);
                if (round == null) return;
                var worlds = WORLDS.get(server);
                if (worlds == null && !(round.phase() == RoundPhase.ROTATING && round.timingUncertain())) {
                    worlds = P1RoundWorldBundle.open(server, round);
                    WORLDS.put(server, worlds);
                }
                if (worlds != null) worlds.tick();
                var profiles = PROFILES.get(server);
                var activeWorlds = worlds;
                profiles.tick(round);
                refreshTab(server, false);
                if (worlds != null && worlds.ready()) session.adaptersReady(round.roundId());
                if (round.phase() == RoundPhase.ROTATING) {
                    rotate(server, session, round, worlds, profiles);
                    session.tick();
                    return;
                }
                for (var player : java.util.List.copyOf(server.getPlayerList().getPlayers())) {
                    var profileFailure = profiles.failure(player.getUUID());
                    if (profileFailure.isPresent()) {
                        throw new IllegalStateException("玩家 " + player.getUUID() + " 档案核验失败：" + profileFailure.orElseThrow());
                    }
                    if (!profiles.isReady(player.getUUID(), round)) continue;
                    var names = ADMITTED.get(server);
                    var participant = round.participants().get(player.getUUID());
                    if (participant != null && participant.role().isMonster()) {
                        if (participant.role() == ParticipantRole.MONSTER_LOBBY) profiles.enterMonsterLobby(player, round);
                        if (!player.getScoreboardName().equals(names.get(player.getUUID()))) {
                            names.put(player.getUUID(), player.getScoreboardName());
                            session.onOnline(player.getUUID(), player.getScoreboardName(), true);
                        }
                        continue;
                    }
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
                    if (!player.getScoreboardName().equals(names.get(player.getUUID()))
                            || participant != null && participant.role() == ParticipantRole.HUMAN_ACTIVE
                                && !participant.online() && !P1PlayerProfiles.isLocked(player)) {
                        names.put(player.getUUID(), player.getScoreboardName());
                        session.onOnline(player.getUUID(), player.getScoreboardName(), true);
                    }
                }
                P1ProgressRuntime.tick(server);
                P2ProtectionRuntime.tick(server);
                P2LobbyRuntime.tick(server);
                automaticBooks(server);
                session.tick();
            } catch (RuntimeException error) { session.adapterFailure("世界／档案适配失败", error); }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            P2LobbyRuntime.disconnected(handler.player);
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
                    && P1RoundWorldBundle.owns(player.level())) {
                var profiles = PROFILES.get(player.level().getServer());
                if (profiles != null) profiles.holdDeath(player);
                session.captureDeath(round.roundId(), player.getUUID(), participant.lifeSequence(), participant.monsterDeploymentSequence(),
                        P1CombatRuntime.currentBatch(player.level()).orElse(null));
            } else if (participant != null && participant.role() == ParticipantRole.MONSTER_DEPLOYED
                    && P1RoundWorldBundle.owns(player.level())) {
                var profiles = PROFILES.get(player.level().getServer());
                if (profiles != null) profiles.holdDeath(player);
                session.captureDeath(round.roundId(), player.getUUID(), participant.lifeSequence(), participant.monsterDeploymentSequence(),
                        P1CombatRuntime.currentBatch(player.level()).orElse(null));
            }
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (alive) return;
            var session = SESSIONS.get(newPlayer.level().getServer());
            var round = session == null ? null : session.snapshot().orElse(null);
            var participant = round == null ? null : round.participants().get(newPlayer.getUUID());
            if (participant != null && participant.role() == ParticipantRole.MONSTER_LOBBY) {
                var profiles = PROFILES.get(newPlayer.level().getServer());
                if (profiles != null) profiles.onMonsterRespawn(oldPlayer, newPlayer, round);
            } else if (participant != null && P1RoundWorldBundle.owns(newPlayer.level())) {
                var profiles = PROFILES.get(newPlayer.level().getServer());
                if (profiles != null) profiles.onRespawn(oldPlayer, newPlayer, round);
                session.normalRespawn(newPlayer.getUUID(), participant.lifeSequence());
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            var session = SESSIONS.get(server);
            if (session == null) return;
            session.prepareStop();
            P2LobbyRuntime.prepareStop(server);
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
            P2ProtectionRuntime.unconfigure(server);
            P2LobbyRuntime.unconfigure(server);
            TABS.remove(server);
            BOOKS.remove(server);
            BOOK_ATTEMPTS.remove(server);
            ADMIN.forget(server);
            P1ProgressRuntime.unconfigure(server);
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
                                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> ADMIN.current(context.getSource(), AdminAction.RETRY,
                                                StringArgumentType.getString(context, "reason")))))
                        .then(Commands.literal("config")
                                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                                .then(Commands.literal("next-mode")
                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                .suggests((context, builder) -> builder.suggest("normal").suggest("hardcore").buildFuture())
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(context -> ADMIN.nextMode(context.getSource(),
                                                                StringArgumentType.getString(context, "mode"),
                                                                StringArgumentType.getString(context, "reason")))))))));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(ADMIN.attach(Commands.literal("hardrush"))));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                P1HistoryCommands.attach(Commands.literal("hardrush")
                        .then(Commands.literal("read-book")
                                .executes(context -> readBook(context.getSource(), -1))
                                .then(Commands.argument("slot", IntegerArgumentType.integer(1, 36))
                                        .executes(context -> readBook(context.getSource(), IntegerArgumentType.getInteger(context, "slot") - 1)))),
                        SESSIONS::get, P1LobbyRuntime::manualBook)));
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
        refreshTab(server, true);
        P2LobbyRuntime.onCommitted(server, before, after);
        if (!before.roundId().equals(after.roundId())) {
            WORLDS.remove(server);
            RELEASES.remove(server);
            REPLAY_FLUSHES.remove(server);
            ADMITTED.get(server).clear();
            BOOK_ATTEMPTS.get(server).clear();
            return;
        }
        if (before.result() == null && after.result() != null) {
            if (after.result().kind() != dev.hardrush.core.round.RoundResult.Kind.COMPLETED) {
                var profiles = PROFILES.get(server);
                if (profiles != null) for (var player : server.getPlayerList().getPlayers()) {
                    if (profiles.manages(player)) profiles.returnToLobby(player);
                }
                server.getPlayerList().broadcastSystemMessage(Component.literal("本轮" + (after.result().kind() == dev.hardrush.core.round.RoundResult.Kind.FAILED ? "挑战失败" : "已中止") + "，原因："
                        + dev.hardrush.core.book.SafeBookText.literal(after.result().reason(), 512)
                        + "。已返回大厅，安全释放旧世界后轮换。"), false);
                return;
            }
            var winner = after.result().winner() == null ? "胜者待核验"
                    : dev.hardrush.core.book.SafeBookText.literal(after.participants().get(after.result().winner()).name(), 64);
            server.getPlayerList().broadcastSystemMessage(Component.literal("本轮已通关：" + winner
                    + "，有效挑战 " + after.challengeMillis() + " ms。庆祝结束后返回大厅并轮换。"), false);
        }
        if (after.phase() != RoundPhase.RUNNING && after.phase() != RoundPhase.RESCUE) return;
        var worlds = WORLDS.get(server);
        var profiles = PROFILES.get(server);
        if (worlds == null || profiles == null || !worlds.ready()) return;
        for (var participant : after.participants().values()) {
            var player = server.getPlayerList().getPlayer(participant.id());
            if (player == null || !profiles.isReady(participant.id(), after)) continue;
            var previous = before.participants().get(participant.id());
            if (participant.role() == ParticipantRole.HUMAN_RESPAWNING) {
                // Durable death admission and native respawn permission share the callback;
                // an immediate respawn packet must not race the next END_SERVER_TICK.
                profiles.permitRespawn(player, after,
                        level -> P1RoundWorldBundle.sameRound(worlds.level(Level.OVERWORLD), level));
            }
            if (participant.role() == ParticipantRole.HUMAN_ACTIVE && P1PlayerProfiles.isLocked(player)
                    && (previous == null || previous.role() == ParticipantRole.MONSTER_LOBBY || previous.role() == ParticipantRole.LOBBY
                        || previous.role() == ParticipantRole.HUMAN_RESPAWNING || !previous.online())) {
                boolean revived = previous != null && previous.role() == ParticipantRole.MONSTER_LOBBY;
                boolean admitted = revived
                        ? profiles.revive(player, after, worlds.level(Level.OVERWORLD), Vec3.atBottomCenterOf(worlds.spawn()))
                        : previous != null && previous.role() == ParticipantRole.HUMAN_RESPAWNING
                        ? profiles.confirmRespawn(player, after)
                        : profiles.admit(player, after, worlds.level(Level.OVERWORLD), Vec3.atBottomCenterOf(worlds.spawn()),
                            level -> P1RoundWorldBundle.sameRound(worlds.level(Level.OVERWORLD), level));
                if (!admitted) {
                    throw new IllegalStateException("已提交参与者的档案无法入场：" + participant.id());
                }
                if (!participant.online() && profiles.isReady(player.getUUID(), after) && !P1PlayerProfiles.isLocked(player))
                    SESSIONS.get(server).onOnline(player.getUUID(), player.getScoreboardName(), true);
            }
        }
    }

    private static void refreshTab(MinecraftServer server, boolean immediate) {
        var session = SESSIONS.get(server);
        var tab = TABS.get(server);
        if (session != null && tab != null) session.view().ifPresent(round ->
                tab.refresh(server, round, session.endEntrants(), session.problem(), immediate));
    }

    private static void manualBook(CommandSourceStack source, UUID roundId, int volume) {
        var player = source.getPlayer();
        var service = BOOKS.get(source.getServer());
        var session = SESSIONS.get(source.getServer());
        if (player == null || service == null || session == null || !canReceiveBook(player)) {
            source.sendFailure(Component.literal("档案核验完成后可在大厅或本轮人类比赛中领书。")); return;
        }
        var round = session.snapshot().orElseThrow();
        service.manual(player.getUUID(), roundId, volume, round.rules().fingerprint(),
                new OperationTag(round.roundId(), UUID.randomUUID(), round.version())).whenComplete((result, error) -> {
            if (SESSIONS.get(source.getServer()) != session || source.getServer().getPlayerList().getPlayer(player.getUUID()) != player) return;
            player.sendSystemMessage(Component.literal(error == null ? bookMessage(result.status()) : "历史书暂未领到，请稍后重试。"));
        });
    }

    private static int readBook(CommandSourceStack source, int slot) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        if (!canReceiveBook(player)) {
            source.sendFailure(Component.literal("档案核验完成后可读取历史书。")); return 0;
        }
        if (!P1BookReader.open(player, slot)) {
            source.sendFailure(Component.literal("指定格位没有历史书。可用 /hardrush book <轮次> 领取。")); return 0;
        }
        return 1;
    }

    public static boolean lobbyBookReady(ServerPlayer player) {
        var server = player.level().getServer();
        var session = SESSIONS.get(server);
        var round = session == null ? null : session.snapshot().orElse(null);
        if (round == null || session.state() != P1LobbySession.State.OPEN || !profileReady(player)
                || player.level() != server.overworld()) return false;
        var participant = round.participants().get(player.getUUID());
        return (round.phase() == RoundPhase.PREPARING || round.phase() == RoundPhase.WAITING
                || round.phase() == RoundPhase.COUNTDOWN || round.phase() == RoundPhase.RUNNING)
                && (participant == null || participant.lifeSequence() == 0);
    }

    private static boolean canReceiveBook(ServerPlayer player) {
        if (lobbyBookReady(player)) return true;
        var session = SESSIONS.get(player.level().getServer());
        return session != null && !session.competitionLocked() && profileReady(player) && !P1PlayerProfiles.isLocked(player);
    }

    private static void automaticBooks(MinecraftServer server) {
        var session = SESSIONS.get(server);
        var service = BOOKS.get(server);
        var attempts = BOOK_ATTEMPTS.get(server);
        if (session == null || service == null || attempts == null) return;
        var round = session.snapshot().orElseThrow();
        long now = System.nanoTime();
        attempts.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        long active = attempts.values().stream().filter(attempt -> attempt.inFlight).count();
        for (var player : server.getPlayerList().getPlayers()) {
            if (active >= 4 || !lobbyBookReady(player)) continue;
            var attempt = attempts.computeIfAbsent(player.getUUID(), id -> new BookAttempt());
            if (attempt.done || attempt.inFlight || now < attempt.retryAt) continue;
            attempt.inFlight = true;
            active++;
            service.automatic(player.getUUID(), round.sequence(), round.rules().fingerprint(),
                    new OperationTag(round.roundId(), UUID.randomUUID(), round.version())).whenComplete((result, error) -> {
                if (BOOK_ATTEMPTS.get(server) != attempts || attempts.get(player.getUUID()) != attempt) return;
                attempt.inFlight = false;
                attempt.retryAt = System.nanoTime() + 5_000_000_000L;
                if (error != null) return;
                attempt.done = result.status() == HistoryBookService.Status.DELIVERED
                        || result.status() == HistoryBookService.Status.ALREADY_DELIVERED
                        || result.status() == HistoryBookService.Status.NO_HISTORY;
                if (server.getPlayerList().getPlayer(player.getUUID()) == player
                        && (result.status() == HistoryBookService.Status.DELIVERED
                            || result.status() == HistoryBookService.Status.INVENTORY_FULL && !attempt.fullNotified)) {
                    player.sendSystemMessage(Component.literal(bookMessage(result.status())));
                    if (result.status() == HistoryBookService.Status.INVENTORY_FULL) attempt.fullNotified = true;
                }
            });
        }
    }

    private static String bookMessage(HistoryBookService.Status status) {
        return switch (status) {
            case DELIVERED -> "历史成书已放入背包；/hardrush read-book 可读取，主册含历史查询与分卷入口。";
            case ALREADY_DELIVERED -> "本版主册已自动发放。可用 /hardrush book <轮次> 手工补领。";
            case INVENTORY_FULL -> "背包已满，历史主册保留待领；空出格位后自动重试。";
            case RATE_LIMITED -> "手工领书间隔为5秒，请稍后重试。";
            case INVALID_VOLUME -> "该卷不存在，请查看主册列出的卷数。";
            case NO_HISTORY -> "暂无该轮已结算历史。";
            case OFFLINE -> "玩家离线，主册仍保留待领。";
            case BUSY -> "领书请求正在处理，请稍后重试。";
            case STALE_SCOPE -> "轮次或档案状态已变化，请重新领书。";
            case RETRYABLE_FAILURE -> "历史书发放或库存保存未完成，自动主册保留待领。";
        };
    }
    private static final class BookAttempt { boolean inFlight; boolean done; boolean fullNotified; long retryAt; }

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
        if (session.mutationPending()) return;
        if (worlds != null && !worlds.ready()) {
            if (worlds.failure().isPresent()) throw new IllegalStateException("原轮世界仍有故障，禁止跳过安全释放：" + worlds.failure().orElseThrow());
            return;
        }
        for (var player : server.getPlayerList().getPlayers()) {
            if (profiles.manages(player) && profiles.failure(player.getUUID()).isPresent()) {
                throw new IllegalStateException("轮换档案封存失败：" + profiles.failure(player.getUUID()).orElseThrow());
            }
            if (profiles.manages(player)) profiles.returnToLobby(player);
            else if (worlds != null && P1RoundWorldBundle.sameRound(worlds.level(Level.OVERWORLD), player.level())) {
                player.stopRiding(); player.ejectPassengers(); player.closeContainer();
                var spawn = server.overworld().getRespawnData().pos();
                player.teleportTo(server.overworld(), spawn.getX() + .5, spawn.getY(), spawn.getZ() + .5,
                        java.util.Set.of(), 0, 0, true);
            }
        }
        // Do not replace the old profile generation while its survival suspension is still being written.
        if (server.getPlayerList().getPlayers().stream().anyMatch(player -> profiles.manages(player)
                && !profiles.isReady(player.getUUID(), round))) return;
        if (worlds == null) {
            // Do not recreate terrain or simulate an uncertain world merely to retire it.
            for (var level : server.getAllLevels()) {
                for (String dimension : java.util.List.of("overworld", "nether", "end")) {
                    if (level.dimension().identifier().toString().equals(P1WorldPaths.identifier(round.roundId(), dimension))) {
                        throw new IllegalStateException("恢复轮次仍有已加载原生维度，拒绝当作关闭世界轮换");
                    }
                }
            }
        }
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
                        && session.snapshot().orElseThrow().roundId().equals(round.roundId()),
                () -> worlds != null ? worlds.saveAndClose() : CompletableFuture.runAsync(() -> {
                    try { P1ClosedWorlds.verifyRetained(server.getWorldPath(LevelResource.ROOT), round); }
                    catch (java.io.IOException error) { throw new java.util.concurrent.CompletionException(error); }
                }))
                .whenComplete((ignored, error) -> {
                    if (error == null) completion.complete(null); else completion.completeExceptionally(error);
                });
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
