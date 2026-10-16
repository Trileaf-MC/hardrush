package dev.hardrush.p2.lobby;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.hardrush.core.hunt.HuntVotingService;
import dev.hardrush.core.round.*;
import dev.hardrush.p0.monster.MonsterRoles;
import dev.hardrush.p1.P1LobbySession;
import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p1.world.P1RoundWorldBundle;
import dev.hardrush.p2.hunt.P2SafeDeployment;
import dev.hardrush.p2.protection.P2ProtectionRuntime;
import dev.hardrush.p2.monster.P2MonsterRuntime;
import dev.hardrush.p2.combat.P2CombatRuntime;
import java.util.*;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Main-thread lobby requests. Native admission follows the durable sortie transition. */
public final class P2LobbyRuntime {
    private record Channel(ServerPlayer player, ServerLevel level, Vec3 origin, long started, int life, int sortie) {}
    private record Deployment(ServerPlayer player, UUID round, int life, int sortie, MonsterRole kind,
                              MonsterDimension dimension, UUID target, ServerLevel level, P2SafeDeployment.Search search,
                              long started, int spentCandidates) {}
    private static final class Binding {
        final P1LobbySession session;
        final P1PlayerProfiles profiles;
        final Supplier<P1RoundWorldBundle> worlds;
        HuntVotingService votes;
        Random searchRandom;
        UUID round;
        final Map<UUID, Deployment> searches = new HashMap<>();
        final Map<UUID, Deployment> resumptions = new HashMap<>();
        final Map<UUID, Channel> channels = new HashMap<>();
        final Map<UUID, Long> retryAt = new HashMap<>();
        final Set<UUID> committing = new HashSet<>();
        Binding(P1LobbySession session, P1PlayerProfiles profiles, Supplier<P1RoundWorldBundle> worlds) {
            this.session = session; this.profiles = profiles; this.worlds = worlds;
        }
    }
    private static final Map<MinecraftServer, Binding> BINDINGS = new IdentityHashMap<>();
    private static boolean initialized;
    private P2LobbyRuntime() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
            var root = Commands.literal("hardrush")
                    .then(Commands.literal("menu").executes(ctx -> {
                        var player = ctx.getSource().getPlayerOrException();
                        if (!P2LobbyUi.open(player)) tell(player, "大厅档案尚未就绪，或当前身份不能打开菜单。");
                        return 1;
                    }));
            for (String action : List.of("deploy", "return", "revive", "skill")) {
                root.then(Commands.literal(action).executes(ctx -> {
                    command(ctx.getSource().getPlayerOrException(), action, ""); return 1;
                }));
            }
            for (String action : List.of("role", "dimension", "vote")) {
                root.then(Commands.literal(action).then(Commands.argument("value", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            if (action.equals("role")) List.of("zombie", "skeleton", "creeper").forEach(builder::suggest);
                            if (action.equals("dimension")) List.of("overworld", "nether", "end").forEach(builder::suggest);
                            if (action.equals("vote")) candidates(ctx.getSource().getPlayer()).forEach(p -> builder.suggest(p.getScoreboardName()));
                            return builder.buildFuture();
                        }).executes(ctx -> { command(ctx.getSource().getPlayerOrException(), action,
                                StringArgumentType.getString(ctx, "value")); return 1; })));
            }
            dispatcher.register(root);
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (entity instanceof ServerPlayer player && taken > 0) interruptReturn(player);
        });
    }

    public static void configure(MinecraftServer server, P1LobbySession session, P1PlayerProfiles profiles,
                                 Supplier<P1RoundWorldBundle> worlds) {
        var binding = new Binding(session, profiles, worlds);
        BINDINGS.put(server, binding);
        P2MonsterRuntime.configure(server, session, profiles, worlds);
        P2CombatRuntime.configure(server, new P2CombatRuntime.Access() {
            @Override public Optional<RoundSnapshot> snapshot() { return session.snapshot(); }
            @Override public boolean competitionLocked() { return session.competitionLocked(); }
            @Override public boolean formalLevel(ServerLevel level) { return !dimension(binding, level).equals("lobby"); }
        });
        P2LobbyUi.configure(server, new P2LobbyUi.Access() {
            @Override public boolean available(ServerPlayer player) {
                if (lobbyAvailable(player, binding)) return true;
                return current(player, binding) && player.isAlive() && !P1PlayerProfiles.isLocked(player)
                        && session.snapshot().map(r -> r.participants().get(player.getUUID()))
                            .filter(p -> p.role() == ParticipantRole.MONSTER_DEPLOYED).isPresent();
            }
            @Override public UUID round(ServerPlayer player) { return session.snapshot().orElseThrow().roundId(); }
            @Override public List<P2LobbyUi.Button> buttons(ServerPlayer player) { return menuButtons(player, binding); }
            @Override public void act(ServerPlayer player, UUID round, String action, String value) {
                request(player, round, action, value);
            }
        });
    }
    public static void unconfigure(MinecraftServer server) {
        BINDINGS.remove(server); P2LobbyUi.unconfigure(server);
        P2MonsterRuntime.unconfigure(server); P2CombatRuntime.unconfigure(server);
    }
    public static void prepareStop(MinecraftServer server) {
        var b = BINDINGS.get(server); if (b == null) return;
        b.channels.clear(); b.searches.clear(); b.resumptions.clear();
        for (var player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (MonsterRoles.isMonster(player)) MonsterRoles.clear(player);
        }
    }
    private static boolean current(ServerPlayer player, Binding b) {
        return b != null && player.level().getServer().getPlayerList().getPlayer(player.getUUID()) == player
                && b.session.state() == P1LobbySession.State.OPEN
                && b.session.snapshot().filter(r -> b.profiles.isReady(player.getUUID(), r)).isPresent();
    }
    private static boolean lobbyAvailable(ServerPlayer player, Binding b) {
        return current(player, b) && dev.hardrush.p1.world.P1LobbyWorld.isLobby(player.level())
                && player.isAlive();
    }
    public static void command(ServerPlayer player, String action, String value) {
        var b = BINDINGS.get(player.level().getServer());
        var round = b == null ? null : b.session.snapshot().orElse(null);
        if (round == null) { tell(player, "正式大厅尚未就绪。"); return; }
        request(player, round.roundId(), action, value);
    }
    public static void request(ServerPlayer player, UUID expectedRound, String action, String value) {
        var b = BINDINGS.get(player.level().getServer());
        var round = b == null ? null : b.session.view().orElse(null);
        if (!current(player, b) || round == null || !round.roundId().equals(expectedRound)) {
            tell(player, "轮次或档案状态已改变，请重新打开大厅。"); return;
        }
        if (action.equals("info")) return;
        if (action.equals("join") || action.equals("ready") || action.equals("unready")) {
            if (!lobbyAvailable(player, b)) { tell(player, "请先回大厅。"); return; }
            if (action.equals("join")) b.session.requestJoin(player.getUUID(), player.getScoreboardName(), reply -> tell(player, reply.message()));
            else b.session.requestReady(player.getUUID(), action.equals("ready"), reply -> tell(player, reply.message()));
            return;
        }
        if (action.equals("history")) {
            player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), "hardrush history"); return;
        }
        var participant = round.participants().get(player.getUUID());
        if (participant == null || !participant.role().isMonster() || !participant.online()
                || round.phase() != RoundPhase.RUNNING && round.phase() != RoundPhase.RESCUE || b.session.competitionLocked()) {
            tell(player, "当前身份或轮次不允许怪物操作。"); return;
        }
        if (action.equals("skill")) {
            if (!P2MonsterRuntime.canAct(player) || !MonsterRoles.isMonster(player)) {
                tell(player, "请在正式出场后使用技能。"); return;
            }
            boolean accepted = switch (participant.monsterRole()) {
                case ZOMBIE -> MonsterRoles.summon(player);
                case SKELETON -> MonsterRoles.burst(player);
                case CREEPER -> MonsterRoles.ignite(player);
            };
            if (!accepted) tell(player, "技能尚在冷却、正在施放，或安全条件不足。");
            return;
        }
        if (action.equals("return")) { beginReturn(player, b, participant); return; }
        if (!lobbyAvailable(player, b) || participant.role() != ParticipantRole.MONSTER_LOBBY) {
            tell(player, "请在怪物大厅操作。"); return;
        }
        if (b.committing.contains(player.getUUID()) || b.searches.containsKey(player.getUUID())
                || b.resumptions.containsKey(player.getUUID())) {
            tell(player, "出场搜索／身份提交中，请稍候。"); return;
        }
        try {
            switch (action) {
                case "role", "dimension" -> {
                    var kind = action.equals("role") ? MonsterRole.valueOf(value.toUpperCase(Locale.ROOT)) : participant.monsterRole();
                    var dimension = action.equals("dimension") ? MonsterDimension.valueOf(value.toUpperCase(Locale.ROOT)) : participant.monsterDimension();
                    b.session.requestMonsterSelection(round.roundId(), participant.id(), participant.lifeSequence(),
                            participant.monsterDeploymentSequence(), kind, dimension, reply -> {
                                tell(player, reply.message()); synchronize(player.level().getServer(), b);
                            });
                }
                case "vote" -> {
                    synchronize(player.level().getServer(), b);
                    UUID target;
                    try { target = UUID.fromString(value); }
                    catch (IllegalArgumentException invalid) {
                        target = candidates(player).stream().filter(p -> p.getScoreboardName().equals(value))
                                .map(ServerPlayer::getUUID).findFirst().orElseThrow(() -> new IllegalArgumentException("目标已失效"));
                    }
                    var status = b.votes.castVote(player.getUUID(), target);
                    tell(player, status == HuntVotingService.VoteStatus.ACCEPTED
                            ? "已登记本维度追猎票；每 15 秒结算，不公开坐标。" : "目标或投票身份已失效，请刷新大厅。");
                }
                case "deploy" -> beginDeployment(player, b, round, participant);
                case "revive" -> {
                    var worlds = b.worlds.get();
                    if (worlds == null || !worlds.ready()
                            || !P1RoundWorldBundle.safeStanding(worlds.level(Level.OVERWORLD), worlds.spawn())) {
                        tell(player, "本轮出生点暂不安全，保留资格并留在大厅，请联系管理员恢复。");
                        break;
                    }
                    b.committing.add(player.getUUID());
                    b.session.requestRevival(round.roundId(), player.getUUID(), participant.lifeSequence(),
                            participant.monsterDeploymentSequence(), reply -> {
                                b.committing.remove(player.getUUID());
                                if (current(player, b)) tell(player, reply.message());
                            });
                }
                default -> tell(player, "未知大厅操作。");
            }
        } catch (IllegalArgumentException | IllegalStateException invalid) { tell(player, "操作未受理：" + invalid.getMessage()); }
    }
    private static void beginDeployment(ServerPlayer player, Binding b, RoundSnapshot round, Participant p) {
        long now = System.nanoTime();
        if (p.monsterCooldownNanos() > 0) { tell(player, "出场冷却剩余 " + seconds(p.monsterCooldownNanos()) + " 秒。"); return; }
        if (now < b.retryAt.getOrDefault(p.id(), Long.MIN_VALUE)) { tell(player, "安全搜索失败后请等待 3 秒重试。"); return; }
        synchronize(player.level().getServer(), b);
        var targetId = b.votes.deploymentTarget(p.id());
        if (targetId.isEmpty()) { tell(player, "该维度无人可追猎，请换维度；未消耗冷却。"); return; }
        var target = player.level().getServer().getPlayerList().getPlayer(targetId.orElseThrow());
        var level = level(b, p.monsterDimension());
        if (target == null || level == null || target.level() != level) { tell(player, "目标已失效，请重试。"); return; }
        var search = new P2SafeDeployment.Search(level, target.position(), humans(b, level),
                b.searchRandom, System::nanoTime);
        b.searches.put(p.id(), new Deployment(player, round.roundId(), p.lifeSequence(), p.monsterDeploymentSequence(),
                p.monsterRole(), p.monsterDimension(), target.getUUID(), level, search, now, 0));
        tell(player, "正在搜索安全出场点。");
    }
    public static void tick(MinecraftServer server) {
        var b = BINDINGS.get(server);
        if (b == null) return;
        // Only quarantine skips vanilla doTick(); normal lobby bodies now broadcast their own menus.
        for (var player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (P1PlayerProfiles.simulationFrozen(player) && P2LobbyUi.isMenu(player)) player.containerMenu.broadcastChanges();
        }
        synchronize(server, b);
        var round = b.session.view().orElse(null);
        if (round == null || round.phase() != RoundPhase.RUNNING && round.phase() != RoundPhase.RESCUE || b.session.competitionLocked()) {
            b.channels.clear(); b.searches.clear(); b.resumptions.clear(); return;
        }
        for (var d : List.copyOf(b.resumptions.values())) {
            var p = round.participants().get(d.player.getUUID());
            if (p == null || !round.roundId().equals(d.round) || p.role() != ParticipantRole.MONSTER_LOBBY
                    || p.monsterDeploymentSequence() != d.sortie + 1 || !p.online()) {
                b.resumptions.remove(d.player.getUUID()); continue;
            }
            if (!current(d.player, b)) continue;
            b.resumptions.remove(p.id()); restartSearch(b, d, p);
        }
        for (var entry : List.copyOf(b.channels.entrySet())) {
            var ch = entry.getValue(); var p = round.participants().get(entry.getKey());
            if (!current(ch.player, b) || p == null || p.role() != ParticipantRole.MONSTER_DEPLOYED
                    || p.lifeSequence() != ch.life || p.monsterDeploymentSequence() != ch.sortie
                    || ch.player.level() != ch.level || ch.player.position().distanceToSqr(ch.origin) > .000001
                    || MonsterRoles.isIgniting(ch.player)) { b.channels.remove(entry.getKey()); continue; }
            long duration = round.rules().combatValues().get("monster.returnChannelMillis").longValue() * 1_000_000L;
            if (System.nanoTime() - ch.started >= duration && !b.session.mutationPending()) {
                // A completed channel waits for an existing durable operation; ordinary
                // checkpoint contention must not consume it with a transient busy reply.
                b.channels.remove(entry.getKey());
                b.session.requestMonsterReturn(round.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(),
                        reply -> tell(ch.player, reply.message()));
            }
        }
        for (var d : List.copyOf(b.searches.values())) {
            var p = round.participants().get(d.player.getUUID());
            var target = server.getPlayerList().getPlayer(d.target);
            if (!current(d.player, b) || p == null || p.role() != ParticipantRole.MONSTER_LOBBY
                    || p.lifeSequence() != d.life || p.monsterDeploymentSequence() != d.sortie
                    || p.monsterRole() != d.kind || p.monsterDimension() != d.dimension
                    || !round.roundId().equals(d.round)) {
                searchFailed(b, d, "目标或身份已改变，请重试或换维度。"); continue;
            }
            if (!validTarget(b, target, d.level)) { restartSearch(b, d, p); continue; }
            var result = d.search.tick(target.position(), humans(b, d.level));
            if (result.status() == P2SafeDeployment.Status.PENDING) continue;
            if (result.status() == P2SafeDeployment.Status.FAILED) { searchFailed(b, d, "未找到安全站位；3 秒后可重试，未消耗冷却。"); continue; }
            if (b.session.mutationPending()) continue;
            b.searches.remove(p.id()); b.committing.add(p.id());
            b.session.requestMonsterDeploy(d.round, p.id(), d.life, d.sortie, reply -> {
                b.committing.remove(p.id());
                if (!reply.success()) { tell(d.player, reply.message()); return; }
                var committed = b.session.snapshot().orElseThrow();
                var latest = committed.participants().get(p.id());
                if (!current(d.player, b) || latest == null || latest.role() != ParticipantRole.MONSTER_DEPLOYED
                        || latest.monsterDeploymentSequence() != d.sortie + 1) return;
                var latestTarget = server.getPlayerList().getPlayer(d.target);
                if (!validTarget(b, latestTarget, d.level) || !d.search.revalidate(latestTarget.position(), humans(b, d.level))
                        || !b.profiles.deployMonster(d.player, committed, d.level, result.position())) {
                    b.session.requestMonsterDeployCancelled(d.round, p.id(), d.life, d.sortie + 1,
                            refunded -> {
                                if (!refunded.success() || server.getPlayerList().getPlayer(p.id()) != d.player) return;
                                var restored = b.session.snapshot().orElseThrow().participants().get(p.id());
                                if (restored != null && restored.role() == ParticipantRole.MONSTER_LOBBY
                                        && restored.monsterDeploymentSequence() == d.sortie + 1) {
                                    b.resumptions.put(p.id(), d);
                                    tell(d.player, "安全条件已改变，正在剩余预算内重新选择目标和站位。");
                                }
                            });
                    return;
                }
                MonsterRoles.select(d.player, d.kind.name().toLowerCase(Locale.ROOT));
                tell(d.player, "已安全出场；/hardrush return 可开始 5 秒回大厅引导。");
            });
        }
    }
    private static void searchFailed(Binding b, Deployment d, String message) {
        b.searches.remove(d.player.getUUID()); b.retryAt.put(d.player.getUUID(), System.nanoTime() + 3_000_000_000L);
        tell(d.player, message);
    }
    /** Retargeting is part of the same request, sharing its original time and candidate budgets. */
    private static void restartSearch(Binding b, Deployment previous, Participant p) {
        b.searches.remove(p.id());
        int spent = previous.spentCandidates + previous.search.result().candidates();
        if (spent >= P2SafeDeployment.MAX_CANDIDATES
                || System.nanoTime() - previous.started >= P2SafeDeployment.MAX_SEARCH_NANOS) {
            searchFailed(b, previous, "安全搜索预算已用完；3 秒后可重试，未消耗冷却。"); return;
        }
        synchronize(previous.level.getServer(), b);
        var targetId = b.votes.deploymentTarget(p.id());
        var target = targetId.map(id -> previous.level.getServer().getPlayerList().getPlayer(id)).orElse(null);
        if (!validTarget(b, target, previous.level)) {
            tell(previous.player, "该维度已无人可追猎，请换维度；未消耗冷却。"); return;
        }
        var search = new P2SafeDeployment.Search(previous.level, target.position(), humans(b, previous.level),
                b.searchRandom, System::nanoTime, P2SafeDeployment.MAX_CANDIDATES - spent, previous.started);
        b.searches.put(p.id(), new Deployment(previous.player, previous.round, p.lifeSequence(), p.monsterDeploymentSequence(),
                p.monsterRole(), p.monsterDimension(), target.getUUID(), previous.level, search, previous.started, spent));
    }
    private static void beginReturn(ServerPlayer player, Binding b, Participant p) {
        if (p.role() != ParticipantRole.MONSTER_DEPLOYED || MonsterRoles.isIgniting(player)) {
            tell(player, "只有已出场且未引爆的怪物可以引导回大厅。"); return;
        }
        if (b.channels.containsKey(p.id())) { tell(player, "正在引导；移动、攻击、技能或受伤会取消。"); return; }
        b.channels.put(p.id(), new Channel(player, player.level(), player.position(), System.nanoTime(),
                p.lifeSequence(), p.monsterDeploymentSequence()));
        tell(player, "开始 5 秒回大厅引导；移动、攻击、技能或受伤会取消。");
    }
    public static void interruptReturn(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        if (b != null && b.channels.remove(player.getUUID()) != null) tell(player, "回大厅引导已取消。");
    }
    public static boolean channeling(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer()); return b != null && b.channels.containsKey(player.getUUID());
    }
    public static boolean targetAllowed(UUID owner, ServerLevel level, net.minecraft.world.entity.Entity target) {
        var b = BINDINGS.get(level.getServer());
        if (b == null) return true;
        var round = b.session.snapshot().orElse(null);
        var origin = round == null ? null : round.participants().get(owner);
        if (origin == null) return true; // Operator-only P0 fixtures retain their independent scope.
        return round.phase() == RoundPhase.RUNNING && !b.session.competitionLocked()
                && origin.lifeSequence() > 0 && !owner.equals(target.getUUID())
                && target instanceof ServerPlayer player && !dimension(b, level).equals("lobby") && validTarget(b, player, level);
    }
    public static void onCommitted(MinecraftServer server, RoundSnapshot before, RoundSnapshot after) {
        var b = BINDINGS.get(server); if (b == null) return;
        if (!before.roundId().equals(after.roundId())) {
            for (var player : server.getPlayerList().getPlayers()) if (MonsterRoles.isMonster(player)) MonsterRoles.clear(player);
            b.searches.clear(); b.resumptions.clear(); b.channels.clear(); b.committing.clear(); b.retryAt.clear(); return;
        }
        for (var p : after.participants().values()) {
            var old = before.participants().get(p.id());
            if (old != null && old.role().isMonster() && p.role() == ParticipantRole.HUMAN_ACTIVE) {
                var revived = server.getPlayerList().getPlayer(p.id());
                if (revived != null && MonsterRoles.isMonster(revived)) MonsterRoles.clear(revived);
                b.channels.remove(p.id()); b.searches.remove(p.id()); b.resumptions.remove(p.id());
            }
            if (p.role() != ParticipantRole.MONSTER_LOBBY || old == null || old.role() != ParticipantRole.MONSTER_DEPLOYED) continue;
            var player = server.getPlayerList().getPlayer(p.id());
            if (player == null || !b.profiles.manages(player)) continue;
            b.channels.remove(p.id());
            MonsterRoles.clear(player);
            b.profiles.returnMonsterToLobby(player, after);
        }
    }
    public static void disconnected(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer()); if (b == null) return;
        b.channels.remove(player.getUUID()); b.searches.remove(player.getUUID()); b.resumptions.remove(player.getUUID());
        // Do not rebind a prototype body during the next native JOIN before the durable lobby gate.
        if (MonsterRoles.isMonster(player)) MonsterRoles.clear(player);
    }
    private static void synchronize(MinecraftServer server, Binding b) {
        var round = b.session.snapshot().orElse(null); if (round == null) return;
        if (!round.roundId().equals(b.round)) {
            b.round = round.roundId(); b.votes = new HuntVotingService(round.roundId(), List.of("overworld", "nether", "end"),
                    System.nanoTime(), new Random(round.seed()));
            b.searchRandom = new Random(round.seed() ^ 0x4841524452555348L);
        }
        var views = new ArrayList<HuntVotingService.ParticipantView>();
        for (var p : round.participants().values()) {
            var player = server.getPlayerList().getPlayer(p.id());
            String dimension = player == null ? "lobby" : dimension(b, player.level());
            views.add(new HuntVotingService.ParticipantView(p.id(), round.roundId(), p.role(),
                    p.online() && player != null && player.isAlive() && !P1PlayerProfiles.isLocked(player), dimension,
                    player == null || P2ProtectionRuntime.protectedEntity(player)));
        }
        // Isolated lobby monsters are eligible voters after their own profile barrier is ready.
        views.replaceAll(v -> {
            var p = round.participants().get(v.playerId()); var player = server.getPlayerList().getPlayer(v.playerId());
            return p.role().isMonster() && p.online() && player != null && b.profiles.isReady(p.id(), round)
                    ? new HuntVotingService.ParticipantView(p.id(), round.roundId(), p.role(), true,
                        p.monsterDimension().name().toLowerCase(Locale.ROOT), true) : v;
        });
        b.votes.synchronizeParticipants(views);
        for (var p : round.participants().values()) if (p.role().isMonster() && p.online()
                && server.getPlayerList().getPlayer(p.id()) != null && b.profiles.isReady(p.id(), round)) {
            b.votes.selectDimension(p.id(), p.monsterDimension().name().toLowerCase(Locale.ROOT));
        }
        b.votes.refresh(System.nanoTime());
    }
    private static ServerLevel level(Binding b, MonsterDimension dimension) {
        var worlds = b.worlds.get();
        return worlds == null || !worlds.ready() ? null : worlds.level(switch (dimension) {
            case OVERWORLD -> Level.OVERWORLD; case NETHER -> Level.NETHER; case END -> Level.END;
        });
    }
    private static String dimension(Binding b, ServerLevel level) {
        for (var kind : MonsterDimension.values()) if (level == level(b, kind)) return kind.name().toLowerCase(Locale.ROOT);
        return "lobby";
    }
    private static boolean validTarget(Binding b, ServerPlayer player, ServerLevel level) {
        if (player == null || player.level() != level || !player.isAlive() || P1PlayerProfiles.isLocked(player)
                || P2ProtectionRuntime.protectedEntity(player)) return false;
        return b.session.snapshot().map(r -> r.participants().get(player.getUUID()))
                .filter(p -> p.online() && p.role() == ParticipantRole.HUMAN_ACTIVE).isPresent();
    }
    private static List<Vec3> humans(Binding b, ServerLevel level) {
        var round = b.session.snapshot().orElseThrow();
        return level.getServer().getPlayerList().getPlayers().stream().filter(p -> p.level() == level && p.isAlive())
                .filter(p -> Optional.ofNullable(round.participants().get(p.getUUID()))
                        .filter(identity -> identity.online() && identity.role() == ParticipantRole.HUMAN_ACTIVE).isPresent())
                .map(ServerPlayer::position).toList();
    }
    private static List<ServerPlayer> candidates(ServerPlayer player) {
        if (player == null) return List.of(); var b = BINDINGS.get(player.level().getServer());
        if (b == null) return List.of();
        var p = b.session.snapshot().map(r -> r.participants().get(player.getUUID())).orElse(null);
        var level = p == null ? null : level(b, p.monsterDimension());
        return level == null ? List.of() : level.getServer().getPlayerList().getPlayers().stream().filter(target -> validTarget(b, target, level)).toList();
    }
    private static List<P2LobbyUi.Button> menuButtons(ServerPlayer player, Binding b) {
        var round = b.session.view().orElseThrow(); var p = round.participants().get(player.getUUID());
        var buttons = new ArrayList<P2LobbyUi.Button>();
        buttons.add(new P2LobbyUi.Button(Items.PAPER, "轮次 " + round.sequence() + " · " + phaseName(round.phase())
                + (p == null ? " · 未加入" : " · " + identityName(p.role())), "info", ""));
        buttons.add(new P2LobbyUi.Button(Items.DYE.pick(net.minecraft.world.item.DyeColor.LIME), "加入本轮", "join", ""));
        buttons.add(new P2LobbyUi.Button(Items.EMERALD, "就绪", "ready", ""));
        buttons.add(new P2LobbyUi.Button(Items.DYE.pick(net.minecraft.world.item.DyeColor.RED), "取消就绪", "unready", ""));
        buttons.add(new P2LobbyUi.Button(Items.BOOK, "查看历史", "history", ""));
        if (p != null && p.role().isMonster()) {
            buttons.add(new P2LobbyUi.Button(Items.BLAZE_POWDER, "施放当前角色技能", "skill", ""));
            buttons.add(new P2LobbyUi.Button(Items.OAK_DOOR, "引导返回大厅（5 秒）", "return", ""));
            synchronize(player.level().getServer(), b);
            var tally = b.votes.tally(p.monsterDimension().name().toLowerCase(Locale.ROOT));
            String targetName = tally.target().map(id -> round.participants().get(id))
                    .map(Participant::name).orElse("暂无有效人类");
            buttons.add(new P2LobbyUi.Button(Items.CLOCK, "本次追猎：" + targetName + " · "
                    + seconds(b.votes.remainingNanos(System.nanoTime())) + " 秒后结算", "info", ""));
            for (var role : MonsterRole.values()) buttons.add(new P2LobbyUi.Button(switch (role) {
                case ZOMBIE -> Items.ROTTEN_FLESH; case SKELETON -> Items.BONE; case CREEPER -> Items.GUNPOWDER;
            }, "选择 " + kindName(role) + (p.monsterRole() == role ? " ✓" : ""), "role", role.name()));
            for (var dimension : MonsterDimension.values()) buttons.add(new P2LobbyUi.Button(switch (dimension) {
                case OVERWORLD -> Items.GRASS_BLOCK; case NETHER -> Items.NETHERRACK; case END -> Items.END_STONE;
            }, "追猎维度 " + dimensionName(dimension) + (p.monsterDimension() == dimension ? " ✓" : ""), "dimension", dimension.name()));
            buttons.add(new P2LobbyUi.Button(Items.COMPASS, "安全出场 · 冷却 " + seconds(p.monsterCooldownNanos()) + " 秒", "deploy", ""));
            buttons.add(new P2LobbyUi.Button(Items.TOTEM_OF_UNDYING, "复生资格：" + (p.revivalEntitled() ? "有" : "无") + "（点击空装复生）", "revive", ""));
            for (var target : candidates(player)) buttons.add(new P2LobbyUi.Button(Items.PLAYER_HEAD,
                    "投票追猎 " + target.getScoreboardName(), "vote", target.getUUID().toString()));
        }
        return buttons;
    }
    private static String kindName(MonsterRole role) {
        return switch (role) { case ZOMBIE -> "僵尸"; case SKELETON -> "骷髅"; case CREEPER -> "苦力怕"; };
    }
    private static String dimensionName(MonsterDimension dimension) {
        return switch (dimension) { case OVERWORLD -> "主世界"; case NETHER -> "下界"; case END -> "末地"; };
    }
    private static String phaseName(RoundPhase phase) {
        return switch (phase) {
            case PREPARING -> "准备世界"; case WAITING -> "等待加入"; case COUNTDOWN -> "开局倒计时";
            case RUNNING -> "进行中"; case RESCUE -> "救场中"; case SETTLING -> "结算中";
            case CELEBRATING -> "庆祝中"; case ROTATING -> "换轮中"; case RECOVERY_REQUIRED -> "等待恢复";
        };
    }
    private static String identityName(ParticipantRole role) {
        return switch (role) {
            case LOBBY -> "大厅玩家"; case HUMAN_ACTIVE -> "人类"; case HUMAN_RESPAWNING -> "等待重生";
            case MONSTER_LOBBY -> "大厅怪物"; case MONSTER_DEPLOYED -> "外出怪物"; case TRANSITION_PENDING -> "转换中";
        };
    }
    private static long seconds(long nanos) { return nanos / 1_000_000_000L + (nanos % 1_000_000_000L == 0 ? 0 : 1); }
    private static void tell(ServerPlayer player, String message) {
        if (player.connection != null) player.sendSystemMessage(Component.literal(message));
    }
}
