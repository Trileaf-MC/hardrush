package dev.hardrush.lobby;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.hardrush.core.hunt.PersonalHuntRequest;
import dev.hardrush.core.lobby.LobbyLayout;
import dev.hardrush.core.record.RoundResultText;
import com.mojang.authlib.GameProfile;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.lobby.LobbyContext;
import dev.hardrush.core.round.*;
import dev.hardrush.monster.MonsterRoles;
import dev.hardrush.round.RoundSession;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.world.RoundWorldBundle;
import dev.hardrush.hunt.SafeDeployment;
import dev.hardrush.protection.ProtectionRuntime;
import dev.hardrush.monster.MonsterRoundRuntime;
import dev.hardrush.combat.MonsterCombatRuntime;
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
public final class LobbyRuntime {
    private record Channel(ServerPlayer player, ServerLevel level, Vec3 origin, long started, int life, int sortie) {}
    private static final class Deployment {
        final ServerPlayer player, targetPlayer;
        final net.minecraft.server.network.ServerGamePacketListenerImpl connection, targetConnection;
        final PersonalHuntRequest identity;
        final long started;
        int sortie, spentCandidates;
        ServerLevel level;
        SafeDeployment.Search search;
        boolean cancelled;
        Deployment(ServerPlayer player, ServerPlayer target, PersonalHuntRequest identity, long started) {
            this.player = player; targetPlayer = target; connection = player.connection; targetConnection = target == null ? null : target.connection;
            this.identity = identity; this.started = started; sortie = identity.initialSortie();
        }
    }
    private record Refund(Deployment deployment, boolean resume) {}
    private record Display(ServerPlayer player, net.minecraft.server.network.ServerGamePacketListenerImpl connection,
                           List<LobbyProjections.Target> targets) {}
    private static final class Binding {
        final RoundSession session;
        final PlayerProfiles profiles;
        final Supplier<RoundWorldBundle> worlds;
        java.util.function.LongSupplier searchClock = System::nanoTime;
        Random searchRandom;
        UUID round;
        final Map<UUID, Deployment> searches = new HashMap<>();
        final Map<UUID, Deployment> resumptions = new HashMap<>();
        final Map<UUID, Deployment> pending = new HashMap<>();
        final Map<UUID, Refund> refunds = new HashMap<>();
        final LinkedHashMap<UUID, GameProfile> slots = new LinkedHashMap<>();
        final Map<UUID, Integer> deadAt = new HashMap<>();
        final Map<UUID, Display> displays = new HashMap<>();
        final Map<UUID, Boolean> cooling = new HashMap<>();
        final Set<UUID> reviving = new HashSet<>();
        final Map<UUID, Channel> channels = new HashMap<>();
        final Map<UUID, Long> retryAt = new HashMap<>();
        final Set<UUID> committing = new HashSet<>();
        Binding(RoundSession session, PlayerProfiles profiles, Supplier<RoundWorldBundle> worlds) {
            this.session = session; this.profiles = profiles; this.worlds = worlds;
        }
    }
    private static final Map<MinecraftServer, Binding> BINDINGS = new IdentityHashMap<>();
    private static boolean initialized;
    private LobbyRuntime() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
            var root = Commands.literal("hardrush")
                    .then(Commands.literal("menu").executes(ctx -> {
                        var player = ctx.getSource().getPlayerOrException();
                        if (!LobbyMenus.open(player)) tell(player, "大厅档案尚未就绪，或当前身份不能打开菜单。");
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

                            return builder.buildFuture();
                        }).executes(ctx -> { command(ctx.getSource().getPlayerOrException(), action,
                                StringArgumentType.getString(ctx, "value")); return 1; })));
            }
            dispatcher.register(root);
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (entity instanceof ServerPlayer player && taken > 0) interruptReturn(player, "受伤");
        });
    }

    public static void configure(MinecraftServer server, RoundSession session, PlayerProfiles profiles,
                                 Supplier<RoundWorldBundle> worlds) {
        var binding = new Binding(session, profiles, worlds);
        BINDINGS.put(server, binding);
        LobbyInteractions.configure(server, new LobbyInteractions.Access() {
            public UUID round() { return session.snapshot().map(RoundSnapshot::roundId).orElse(null); }
            public boolean allowed(ServerPlayer player, LobbyContext context) {
                return contextAvailable(player, binding, context);
            }
        });
        MonsterRoundRuntime.configure(server, session, profiles, worlds);
        MonsterCombatRuntime.configure(server, new MonsterCombatRuntime.Access() {
            @Override public Optional<RoundSnapshot> snapshot() { return session.snapshot(); }
            @Override public boolean competitionLocked() { return session.competitionLocked(); }
            @Override public boolean formalLevel(ServerLevel level) { return !dimension(binding, level).equals("lobby"); }
        });
        LobbyMenus.configure(server, new LobbyMenus.Access() {
            @Override public boolean available(ServerPlayer player) {
                if (contextAvailable(player, binding, LobbyContext.COMPETITION)) return true;
                return !session.competitionLocked() && current(player, binding) && player.isAlive() && !PlayerProfiles.isLocked(player)
                        && session.snapshot().map(r -> r.participants().get(player.getUUID()))
                            .filter(p -> p.role() == ParticipantRole.MONSTER_DEPLOYED).isPresent();
            }
            @Override public boolean available(ServerPlayer player, LobbyContext context) {
                return context == LobbyContext.COMPETITION ? available(player) : contextAvailable(player, binding, context);
            }
            @Override public List<LobbyMenus.Button> buttons(ServerPlayer player, LobbyContext context) {
                if (context == LobbyContext.WITHDRAWAL) return dev.hardrush.participation.ParticipationControl.withdrawalButtons(player);
                if (context == LobbyContext.COMPETITION) return buttons(player);
                if (context == LobbyContext.ENTRY) return dev.hardrush.lobby.HumanEntry.gateButtons(player);
                if (context == LobbyContext.REVIVAL) return altarButtons(player, binding);
                if (context == LobbyContext.HISTORY) return dev.hardrush.record.HistoryLibrary.buttons(player);
                var round = session.snapshot().orElseThrow();
                return List.of(new LobbyMenus.Button(Items.BOOK, "站入集结区参加，离开取消准备", "info", ""),
                        new LobbyMenus.Button(Items.PAPER, "追猎规则：" + (round.rules().huntRule() == RulesConfig.HuntRule.PERSONAL_TARGET ? "右键庭院模型，个人定向追猎" : "旧版投票（升级恢复隔离）"), "info", ""),
                        new LobbyMenus.Button(Items.CLOCK, "右键快捷栏技能；回响碎片归返营地，移动、攻击、用物或受伤中断", "info", ""),
                        new LobbyMenus.Button(Items.TOTEM_OF_UNDYING, "极限：人类击杀在赛人类每人 +1 次复生；击杀怪物不奖励", "info", ""),
                        new LobbyMenus.Button(Items.ROTTEN_FLESH, "怪物击杀在赛人类：没有复生次数时获得一次", "info", ""),
                        new LobbyMenus.Button(Items.TOTEM_OF_UNDYING, "死亡后到营地祭坛消耗一次空装复生，冷却中可用", "info", ""),
                        new LobbyMenus.Button(Items.GRASS_BLOCK, "中途门确认空装参赛；普通死亡原版重生，极限死亡转怪物", "info", ""),
                        new LobbyMenus.Button(Items.DRAGON_EGG, "人类尾刀首龙通关；全员集结末地为胜者庆祝，胜者跳龙池结束，最长"
                                + (RoundEngine.successCelebrationMillis(round.rules()) + 999) / 1000
                                + "秒（现实时间）；书屋查战绩领书", "info", ""),
                        new LobbyMenus.Button(Items.PAPER, "离线人类保留资格并暂停计时；无人类有资格时60秒救场", "info", ""));
            }
            @Override public void act(ServerPlayer player, UUID round, LobbyContext context, String action, String value) {
                if (context == LobbyContext.WITHDRAWAL) {
                    if (action.equals("withdraw-confirm")) dev.hardrush.participation.ParticipationControl.confirmWithdrawal(player, round, value);
                    else if (action.equals("withdraw-cancel")) player.closeContainer();
                }
                else if (context == LobbyContext.ENTRY && action.equals("enter")) dev.hardrush.lobby.HumanEntry.confirm(player, round);
                else if (context == LobbyContext.REVIVAL && action.equals("revive")) {
                    var p = session.snapshot().orElseThrow().participants().get(player.getUUID());
                    if (p != null && value.equals(p.lifeSequence() + ":" + p.monsterDeploymentSequence())) request(player, round, action, value);
                }
                else if (context == LobbyContext.HISTORY) dev.hardrush.record.HistoryLibrary.act(player, action, value);
                else if (context == LobbyContext.COMPETITION) request(player, round, action, value);
            }
            @Override public UUID round(ServerPlayer player) { return session.snapshot().orElseThrow().roundId(); }
            @Override public List<LobbyMenus.Button> buttons(ServerPlayer player) { return menuButtons(player, binding); }
            @Override public void act(ServerPlayer player, UUID round, String action, String value) {
                request(player, round, action, value);
            }
        });
    }
    public static void unconfigure(MinecraftServer server) {
        BINDINGS.remove(server); LobbyInteractions.close(server); LobbyMenus.unconfigure(server);
        MonsterRoundRuntime.unconfigure(server); MonsterCombatRuntime.unconfigure(server);
    }
    public static void prepareStop(MinecraftServer server) {
        var b = BINDINGS.get(server); if (b == null) return;
        b.pending.values().forEach(d -> d.cancelled = true);
        b.channels.clear(); b.searches.clear(); b.resumptions.clear(); b.displays.clear(); b.cooling.clear();
        LobbyInteractions.get(server).ifPresent(r -> r.projections().clear());
        for (var player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (MonsterRoles.isMonster(player)) MonsterRoles.clear(player);
        }
    }
    private static boolean contextAvailable(ServerPlayer player, Binding b, LobbyContext context) {
        boolean verified = current(player, b) && PlayerProfiles.verifiedLobbyBody(player);
        return switch (context) {
            case WITHDRAWAL -> dev.hardrush.participation.ParticipationControl.canWithdraw(player);
            case RULES -> verified;
            case ENTRY -> dev.hardrush.lobby.HumanEntry.gateAvailable(player);
            case HISTORY -> verified;
            case REVIVAL -> verified && revivalAvailable(player, b);
            case DISPLAY -> verified && dev.hardrush.round.RoundRuntime.lobbyActivityAllowed(player);
            case COMPETITION -> verified && dev.hardrush.round.RoundRuntime.lobbyActivityAllowed(player)
                    && b.worlds.get() != null && b.worlds.get().ready()
                    && b.session.snapshot().filter(r -> switch (r.phase()) {
                        case WAITING, COUNTDOWN, RUNNING, RESCUE -> true;
                        default -> false;
                    }).isPresent();
        };
    }

    private static boolean current(ServerPlayer player, Binding b) {
        return b != null && player.level().getServer().getPlayerList().getPlayer(player.getUUID()) == player
                && b.session.state() == RoundSession.State.OPEN
                && b.session.snapshot().filter(r -> b.profiles.isReady(player.getUUID(), r)).isPresent();
    }
    private static boolean lobbyAvailable(ServerPlayer player, Binding b) {
        return current(player, b) && dev.hardrush.world.LobbyWorld.isLobby(player.level())
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
        if (round.rules().huntRule() == RulesConfig.HuntRule.PERSONAL_TARGET
                && List.of("vote", "dimension", "deploy").contains(action)) {
            tell(player, "本轮采用个人追猎规则；请到追猎庭院右键目标模型，系统自动决定维度。"); return;
        }
        if (action.equals("join") || action.equals("ready") || action.equals("unready")) {
            tell(player, "开局请站入集结区，离开取消准备；挑战中请到中途入场门确认。"); return;
        }
        if (action.equals("history")) {
            dev.hardrush.record.HistoryLibrary.open(player); return;
        }
        var participant = round.participants().get(player.getUUID());
        boolean celebrating = dev.hardrush.celebration.CelebrationRuntime.arena(player);
        if (celebrating && action.equals("return")) { tell(player, CELEBRATION_RETURN); return; }
        if (participant == null || !participant.role().isMonster()
                || !celebrating && (!participant.online() || round.phase() != RoundPhase.RUNNING && round.phase() != RoundPhase.RESCUE
                    || b.session.competitionLocked())
                || celebrating && !action.equals("skill")) {
            tell(player, "当前身份或轮次不允许怪物操作。"); return;
        }
        if (action.equals("skill")) {
            interruptReturn(player, "施放技能");
            if (!MonsterRoundRuntime.canAct(player) || !MonsterRoles.isMonster(player)) {
                tell(player, "请在正式出场后使用技能。"); return;
            }
            boolean accepted = switch (participant.monsterRole()) {
                case ZOMBIE -> false;
                case SKELETON -> MonsterRoles.burst(player);
                case CREEPER -> false;
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
                case "role" -> tell(player, "请右键营地的僵尸、骷髅或苦力怕模型。");
                case "revive" -> {
                    var worlds = b.worlds.get();
                    if (worlds == null || !worlds.ready()
                            || !RoundWorldBundle.safeStanding(worlds.level(Level.OVERWORLD), worlds.spawn())) {
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
    /** Only called with a server-owned projection selection after BOTH packet guards. */
    private static void beginDeployment(ServerPlayer player, Binding b, LobbyProjections.Selection selected) {
        var round = b.session.view().orElse(null);
        if (round == null || !selected.round().equals(round.roundId()) || !personalOpen(b, round)
                || !contextAvailable(player, b, LobbyContext.DISPLAY)) return;
        var p = round.participants().get(player.getUUID());
        if (p == null || p.role() != ParticipantRole.MONSTER_LOBBY || !p.online()) {
            tell(player, "只有大厅怪物可以追猎；极限死亡后前往角色展位。"); return;
        }
        if (b.committing.contains(p.id()) || b.searches.containsKey(p.id()) || b.resumptions.containsKey(p.id())) {
            tell(player, "正在搜索或提交本人的追猎，请稍候。"); return;
        }
        long now = b.searchClock.getAsLong();
        if (p.monsterCooldownNanos() > 0) { tell(player, "出场冷却剩余 " + seconds(p.monsterCooldownNanos()) + " 秒；结束后请再次右键。"); return; }
        Long retry = b.retryAt.get(p.id());
        if (retry != null && now - retry < 0) { tell(player, "搜索失败后请等待 3 秒；换目标不能跳过等待。"); return; }
        var identity = new PersonalHuntRequest(round.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(),
                p.monsterRole(), selected.target(), selected.life());
        var target = player.level().getServer().getPlayerList().getPlayer(identity.target());
        var d = new Deployment(player, target, identity, now);
        if (target == null || !requestTarget(b, d)) { searchFailed(b, d, "目标已离线、死亡或生命改变，请重新选择。"); return; }
        restartSearch(b, d);
        if (b.searches.containsKey(p.id())) tell(player, "正在寻找 " + target.getScoreboardName() + " 附近的安全落点。");
    }
    private static boolean personalOpen(Binding b, RoundSnapshot r) {
        return r.rules().mode() == dev.hardrush.core.config.GameMode.HARDCORE
                && r.rules().huntRule() == RulesConfig.HuntRule.PERSONAL_TARGET
                && (r.phase() == RoundPhase.RUNNING || r.phase() == RoundPhase.RESCUE)
                && !b.session.competitionLocked();
    }
    private static boolean requestTarget(Binding b, Deployment d) {
        var r = b.session.snapshot().orElse(null);
        var target = d.player.level().getServer().getPlayerList().getPlayer(d.identity.target());
        return !d.cancelled && r != null && personalOpen(b, r) && d.identity.targetMatches(r)
                && target != null && target == d.targetPlayer && target.connection == d.targetConnection
                && b.profiles.isReady(target.getUUID(), r) && !dimension(b, target.level()).equals("lobby")
                && validTarget(b, target, target.level());
    }
    private static boolean requestHunter(Binding b, Deployment d, ParticipantRole role, int sortie) {
        var r = b.session.snapshot().orElse(null);
        return !d.cancelled && r != null && personalOpen(b, r) && current(d.player, b)
                && d.player.connection == d.connection && d.identity.hunterMatches(r, role, sortie);
    }
    public static void tick(MinecraftServer server) {
        var b = BINDINGS.get(server); if (b == null) return;
        synchronize(server, b);
        var round = b.session.view().orElse(null);
        updateDisplays(server, b);
        if (round == null || round.phase() != RoundPhase.RUNNING && round.phase() != RoundPhase.RESCUE || b.session.competitionLocked()) {
            for (var d : List.copyOf(b.searches.values())) searchFailed(b, d, "轮次已冻结，追猎已取消。");
            for (var d : b.pending.values()) d.cancelled = true;
            for (var channel : List.copyOf(b.channels.values())) interruptReturn(channel.player, "阶段变化／恢复隔离");
            b.resumptions.clear(); return;
        }
        for (var refund : List.copyOf(b.refunds.values())) compensate(b, refund);
        for (var d : List.copyOf(b.resumptions.values())) {
            if (!current(d.player, b)) {
                if (server.getPlayerList().getPlayer(d.player.getUUID()) != d.player || b.profiles.failure(d.player.getUUID()).isPresent()) {
                    b.resumptions.remove(d.player.getUUID()); searchFailed(b, d, "档案或连接已改变，追猎已取消。");
                }
                continue;
            }
            b.resumptions.remove(d.player.getUUID());
            if (!requestHunter(b, d, ParticipantRole.MONSTER_LOBBY, d.sortie) || !requestTarget(b, d)) {
                searchFailed(b, d, "目标或生命已改变，追猎已取消。"); continue;
            }
            restartSearch(b, d);
        }
        for (var entry : List.copyOf(b.channels.entrySet())) {
            var ch = entry.getValue(); var p = round.participants().get(entry.getKey());
            if (!current(ch.player, b) || p == null || p.role() != ParticipantRole.MONSTER_DEPLOYED
                    || p.lifeSequence() != ch.life || p.monsterDeploymentSequence() != ch.sortie
                    || ch.player.level() != ch.level || ch.player.position().distanceToSqr(ch.origin) > .000001
                    || MonsterRoles.isIgniting(ch.player)) { interruptReturn(ch.player, "移动或身份／阶段变化"); continue; }
            long duration = round.rules().combatValues().get("monster.returnChannelMillis").longValue() * 1_000_000L;
            long elapsed = System.nanoTime() - ch.started;
            hint(ch.player, "return", dev.hardrush.core.lobby.LobbyHints.Priority.PROGRESS,
                    "正在返回营地 · 还需 " + Math.max(0, (duration - elapsed + 999_999_999L) / 1_000_000_000L) + " 秒");
            if (elapsed >= duration && !b.session.mutationPending()) {
                b.channels.remove(entry.getKey());
                b.session.requestMonsterReturn(round.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(),
                        reply -> { tell(ch.player, reply.message()); sound(ch.player, reply.success()); });
            }
        }
        for (var d : List.copyOf(b.searches.values())) {
            if (!requestHunter(b, d, ParticipantRole.MONSTER_LOBBY, d.sortie) || !requestTarget(b, d)) {
                searchFailed(b, d, "目标离线、死亡或身份／生命已改变，本次追猎已取消。"); continue;
            }
            var target = d.targetPlayer;
            if (target.level() != d.level) { restartSearch(b, d); continue; }
            var result = d.search.tick(target.position(), humans(b, d.level));
            if (result.status() == SafeDeployment.Status.PENDING) continue;
            if (result.status() == SafeDeployment.Status.FAILED) { searchFailed(b, d, "未找到安全站位；3 秒后可重试，保留出场机会。"); continue; }
            if (!d.search.revalidate(target.position(), humans(b, d.level))) { restartSearch(b, d); continue; }
            if (b.session.mutationPending()) continue;
            b.searches.remove(d.player.getUUID()); b.committing.add(d.player.getUUID()); b.pending.put(d.player.getUUID(), d);
            b.session.requestPersonalHunt(d.identity, d.sortie, reply -> completeDeployment(b, d, reply));
        }
    }
    private static void completeDeployment(Binding b, Deployment d, RoundSession.Reply reply) {
        UUID id = d.identity.hunter();
        if (!reply.success()) {
            b.pending.remove(id, d); b.committing.remove(id); searchFailed(b, d, reply.message()); return;
        }
        boolean hunter = requestHunter(b, d, ParticipantRole.MONSTER_DEPLOYED, d.sortie + 1);
        boolean target = requestTarget(b, d);
        boolean safe = hunter && target && d.targetPlayer.level() == d.level
                && d.search.revalidate(d.targetPlayer.position(), humans(b, d.level));
        if (safe) {
            try {
                var committed = b.session.snapshot().orElseThrow();
                if (b.profiles.deployMonster(d.player, committed, d.level, d.search.result().position())) {
                    MonsterRoles.select(d.player, d.identity.kind().name().toLowerCase(Locale.ROOT));
                    if (!MonsterRoles.isMonster(d.player) || MonsterRoles.nativeModel(d.player) == null)
                        throw new IllegalStateException("已提交出场未能应用原生怪物角色");
                    b.pending.remove(id, d); b.committing.remove(id);
                    sound(d.player, true);
                    tell(d.player, "已安全出场。"); return;
                }
                if (b.profiles.failure(id).isPresent()) {
                    b.session.adapterFailure("追猎档案应用失败", new IllegalStateException(b.profiles.failure(id).orElseThrow()));
                    b.pending.remove(id, d); b.committing.remove(id); return;
                }
            } catch (RuntimeException fault) {
                b.pending.remove(id, d); b.committing.remove(id); b.session.adapterFailure("追猎世界或档案应用失败", fault); return;
            }
        }
        // Refund only the exact committed sortie. A replacement life/round is never touched.
        var r = b.session.snapshot().orElse(null);
        if (r == null || !d.identity.round().equals(r.roundId())) { b.pending.remove(id, d); b.committing.remove(id); return; }
        var p = r.participants().get(id);
        if (p == null || p.lifeSequence() != d.identity.hunterLife() || p.role() != ParticipantRole.MONSTER_DEPLOYED
                || p.monsterDeploymentSequence() != d.sortie + 1) { b.pending.remove(id, d); b.committing.remove(id); return; }
        b.refunds.put(id, new Refund(d, hunter && target));
        compensate(b, b.refunds.get(id));
    }
    private static void compensate(Binding b, Refund refund) {
        var d = refund.deployment; var id = d.identity.hunter();
        if (b.session.mutationPending()) return;
        var r = b.session.snapshot().orElse(null);
        var p = r == null ? null : r.participants().get(id);
        if (r == null || !d.identity.round().equals(r.roundId()) || p == null || p.lifeSequence() != d.identity.hunterLife()
                || p.role() != ParticipantRole.MONSTER_DEPLOYED || p.monsterDeploymentSequence() != d.sortie + 1) {
            b.refunds.remove(id, refund); b.pending.remove(id, d); b.committing.remove(id);
            searchFailed(b, d, "身份已改变，本次追猎已取消。"); return;
        }
        b.session.requestMonsterDeployCancelled(d.identity.round(), id, d.identity.hunterLife(), d.sortie + 1, refunded -> {
            if (!refunded.success()) {
                if (b.session.state() == RoundSession.State.FAILED || b.session.competitionLocked())
                    tell(d.player, "出场补偿处于恢复隔离：" + refunded.message());
                return; // Transient queued death/connection write: retain the exact refund and retry after it drains.
            }
            b.refunds.remove(id, refund); b.pending.remove(id, d); b.committing.remove(id);
            d.sortie++;
            if (refund.resume && requestTarget(b, d)) {
                b.resumptions.put(id, d);
                tell(d.player, "安全条件已改变，已退回出场机会，在原预算内重搜同一目标。");
            } else searchFailed(b, d, "目标或生命已改变，已退回出场机会，3 秒后可重新选择。");
        });
    }
    private static void searchFailed(Binding b, Deployment d, String message) {
        b.searches.remove(d.identity.hunter(), d); b.resumptions.remove(d.identity.hunter(), d);
        b.retryAt.put(d.identity.hunter(), b.searchClock.getAsLong() + 3_000_000_000L);
        if (d.player.level().getServer().getPlayerList().getPlayer(d.player.getUUID()) == d.player) {
            tell(d.player, message); sound(d.player, false);
        }
    }
    /** Same target life only; dimension changes and compensation share the original deadline and candidates. */
    private static void restartSearch(Binding b, Deployment d) {
        b.searches.remove(d.identity.hunter(), d);
        if (d.search != null) { d.spentCandidates += d.search.result().candidates(); d.search = null; }
        if (!requestHunter(b, d, ParticipantRole.MONSTER_LOBBY, d.sortie) || !requestTarget(b, d)) {
            searchFailed(b, d, "目标离线、死亡或生命已改变，追猎已取消。"); return;
        }
        if (d.spentCandidates >= SafeDeployment.MAX_CANDIDATES
                || b.searchClock.getAsLong() - d.started >= SafeDeployment.MAX_SEARCH_NANOS) {
            searchFailed(b, d, "安全搜索预算已用完；3 秒后可重试，保留出场机会。"); return;
        }
        d.level = d.targetPlayer.level();
        d.search = new SafeDeployment.Search(d.level, d.player, d.targetPlayer.position(), humans(b, d.level),
                b.searchRandom, b.searchClock, SafeDeployment.MAX_CANDIDATES - d.spentCandidates, d.started);
        b.searches.put(d.identity.hunter(), d);
    }
    private static final String CELEBRATION_RETURN = "庆祝期间归返无效；胜者离场或计时结束后全员统一回大厅。";

    public static boolean useReturn(ServerPlayer player) {
        if (dev.hardrush.celebration.CelebrationRuntime.arena(player)) { tell(player, CELEBRATION_RETURN); return false; }
        var b = BINDINGS.get(player.level().getServer());
        var r = b == null ? null : b.session.view().orElse(null);
        var p = r == null ? null : r.participants().get(player.getUUID());
        if (p == null || !current(player, b) || !player.isAlive() || PlayerProfiles.isLocked(player)
                || b.session.competitionLocked() || r.phase() != RoundPhase.RUNNING && r.phase() != RoundPhase.RESCUE) return false;
        beginReturn(player, b, p);
        return channeling(player);
    }
    private static void beginReturn(ServerPlayer player, Binding b, Participant p) {
        if (p.role() != ParticipantRole.MONSTER_DEPLOYED || MonsterRoles.isIgniting(player) || MonsterRoles.performingSkill(player)) {
            tell(player, "只有已出场、未引爆且未在施放技能的怪物可以引导回大厅。"); return;
        }
        if (b.channels.containsKey(p.id())) { tell(player, "正在引导；移动、攻击、技能或受伤会取消。"); return; }
        b.channels.put(p.id(), new Channel(player, player.level(), player.position(), System.nanoTime(),
                p.lifeSequence(), p.monsterDeploymentSequence()));
        tell(player, "开始 5 秒回大厅引导；移动、攻击、使用物品／技能或受伤会取消。");
    }
    public static void interruptReturn(ServerPlayer player) { interruptReturn(player, "施放技能／攻击"); }
    public static void interruptReturn(ServerPlayer player, String reason) {
        var b = BINDINGS.get(player.level().getServer());
        if (b != null && b.channels.remove(player.getUUID()) != null) {
            tell(player, "归返已中断：" + reason + "。");
            LobbyInteractions.get(player.level().getServer()).ifPresent(router -> router.hints(player).clear("return"));
            hint(player, "return-result", dev.hardrush.core.lobby.LobbyHints.Priority.PROGRESS, "归返已中断：" + reason);
            sound(player, false);
        }
    }
    public static boolean channeling(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer()); return b != null && b.channels.containsKey(player.getUUID());
    }
    public static boolean targetAllowed(UUID owner, ServerLevel level, net.minecraft.world.entity.Entity target) {
        var b = BINDINGS.get(level.getServer());
        if (b == null) return true;
        var round = b.session.snapshot().orElse(null);
        var origin = round == null ? null : round.participants().get(owner);
        if (origin == null) return true; // Operator-only development fixtures retain their independent scope.
        if (dev.hardrush.celebration.CelebrationRuntime.arena(level)) {
            // Celebration is native survival: anyone standing in the End may be fought, never recorded.
            return !owner.equals(target.getUUID()) && target instanceof ServerPlayer player && player.level() == level
                    && player.isAlive() && !player.isSpectator() && !PlayerProfiles.isLocked(player);
        }
        return round.phase() == RoundPhase.RUNNING && !b.session.competitionLocked()
                && origin.lifeSequence() > 0 && !owner.equals(target.getUUID())
                && target instanceof ServerPlayer player && !dimension(b, level).equals("lobby")
                && player.level() == level && player.isAlive() && !PlayerProfiles.isLocked(player)
                && !ProtectionRuntime.protectedEntity(player)
                && Optional.ofNullable(round.participants().get(player.getUUID()))
                    .filter(p -> p.online() && (p.role() == ParticipantRole.HUMAN_ACTIVE || p.role() == ParticipantRole.MONSTER_DEPLOYED)).isPresent();
    }
    public static void onCommitted(MinecraftServer server, RoundSnapshot before, RoundSnapshot after) {
        var b = BINDINGS.get(server); if (b == null) return;
        if (!before.roundId().equals(after.roundId())) {
            for (var player : server.getPlayerList().getPlayers()) if (MonsterRoles.isMonster(player)) MonsterRoles.clear(player);
            b.pending.values().forEach(d -> d.cancelled = true);
            b.searches.clear(); b.resumptions.clear(); b.pending.clear(); b.refunds.clear(); b.channels.clear(); b.committing.clear(); b.retryAt.clear(); b.displays.clear(); return;
        }
        if (dev.hardrush.celebration.CelebrationRuntime.successful(after)) {
            b.searches.clear(); b.resumptions.clear(); b.channels.clear(); return;
        }
        if (before.phase() != after.phase() && after.phase() != RoundPhase.RUNNING && after.phase() != RoundPhase.RESCUE)
            for (var ch : List.copyOf(b.channels.values())) interruptReturn(ch.player, "阶段变化");
        for (var p : after.participants().values()) {
            var old = before.participants().get(p.id());
            if (old != null && old.role().isMonster() && p.role() == ParticipantRole.HUMAN_ACTIVE) {
                b.reviving.add(p.id());
                var revived = server.getPlayerList().getPlayer(p.id());
                if (revived != null && MonsterRoles.isMonster(revived)) MonsterRoles.clear(revived);
                b.channels.remove(p.id()); b.searches.remove(p.id()); b.resumptions.remove(p.id());
            }
            if (old != null && p.revivalCharges() > old.revivalCharges()) {
                var owner = server.getPlayerList().getPlayer(p.id());
                if (owner != null) {
                    tell(owner, "复生次数增加 " + (p.revivalCharges() - old.revivalCharges()) + "，剩余 " + p.revivalCharges()
                            + " 次；成为大厅怪物后可在祭坛确认空装复生。");
                    sound(owner, true);
                }
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
        LobbyInteractions.disconnected(player);
        var b = BINDINGS.get(player.level().getServer()); if (b == null) return;
        b.channels.remove(player.getUUID()); b.displays.remove(player.getUUID());
        var attempts = new HashSet<Deployment>(b.searches.values()); attempts.addAll(b.resumptions.values()); attempts.addAll(b.pending.values());
        for (var d : attempts) if (d.identity.hunter().equals(player.getUUID()) || d.identity.target().equals(player.getUUID())) {
            d.cancelled = true;
            if (!b.pending.containsValue(d)) searchFailed(b, d, "连接已断开，本次追猎已取消。");
        }
        // Do not rebind a prototype body during the next native JOIN before the durable lobby gate.
        if (MonsterRoles.isMonster(player)) MonsterRoles.clear(player);
    }
    private static void synchronize(MinecraftServer server, Binding b) {
        var round = b.session.snapshot().orElse(null); if (round == null) return;
        if (!round.roundId().equals(b.round)) {
            b.round = round.roundId(); b.searchRandom = new Random(round.seed() ^ 0x4841524452555348L);
            b.slots.clear(); b.deadAt.clear(); b.displays.clear(); b.cooling.clear(); b.reviving.clear();
        }
    }
    /** Role models and paging blocks use the same guarded facility action. */
    public static void facility(ServerPlayer player, LobbyLayout.Facility facility) {
        var b = BINDINGS.get(player.level().getServer());
        var r = b == null ? null : b.session.snapshot().orElse(null);
        if (r == null || !contextAvailable(player, b, LobbyContext.DISPLAY) || !personalOpen(b, r)) {
            tell(player, "怪物庭院仅在极限挑战或救场期间开放。"); return;
        }
        if (facility == LobbyLayout.Facility.PREVIOUS || facility == LobbyLayout.Facility.NEXT) {
            var projections = LobbyInteractions.get(player.level().getServer()).orElseThrow().projections();
            projections.turn(player, facility == LobbyLayout.Facility.NEXT ? 1 : -1);
            projections.view(player).ifPresent(v -> tell(player, "庭院第 " + (v.page() + 1) + "/" + v.pages() + " 页 · 本轮展位 " + b.slots.size() + " 人"));
            return;
        }
        var p = r.participants().get(player.getUUID());
        if (p == null || p.role() != ParticipantRole.MONSTER_LOBBY || b.committing.contains(p.id())
                || b.searches.containsKey(p.id()) || b.resumptions.containsKey(p.id())) {
            tell(player, "只有未搜索／提交的大厅怪物可以选角。"); return;
        }
        MonsterRole role = switch (facility) {
            case ZOMBIE -> MonsterRole.ZOMBIE; case SKELETON -> MonsterRole.SKELETON; case CREEPER -> MonsterRole.CREEPER;
            default -> null;
        };
        if (role == null) return;
        b.committing.add(p.id());
        b.session.requestMonsterRole(r.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(), role, reply -> {
            b.committing.remove(p.id());
            if (current(player, b)) tell(player, reply.success() ? "已选择 " + kindName(role) + " ✓ · " + switch (role) {
                case ZOMBIE -> "补给：成年／幼年僵尸蛋各一枚；浸水可转化"; case SKELETON -> "三连射；粉雪可转化为流浪者"; case CREEPER -> "引爆器：按住膨胀，松开消退";
            } : reply.message());
        });
    }
    private static void updateDisplays(MinecraftServer server, Binding b) {
        var r = b.session.snapshot().orElse(null); if (r == null) return;
        var projections = LobbyInteractions.get(server).orElseThrow().projections();
        if (!personalOpen(b, r) || !dev.hardrush.world.LobbyWorld.open(server).ready()) {
            if (!b.displays.isEmpty()) projections.clear();
            b.displays.clear(); return;
        }
        // Append only. Dead/offline slots are never recycled, including after a revival.
        r.participants().values().stream().filter(p -> p.lifeSequence() > 0).forEach(p -> {
            var player = server.getPlayerList().getPlayer(p.id());
            if (player != null) b.slots.put(p.id(), player.getGameProfile());
            else b.slots.putIfAbsent(p.id(), new GameProfile(p.id(), p.name()));
        });
        var targets = new ArrayList<LobbyProjections.Target>();
        for (var slot : b.slots.entrySet()) {
            var p = r.participants().get(slot.getKey()); if (p == null) continue;
            var player = server.getPlayerList().getPlayer(p.id());
            LobbyProjections.State state;
            if (p.role() != ParticipantRole.HUMAN_ACTIVE) {
                int died = b.deadAt.computeIfAbsent(p.id(), ignored -> server.getTickCount());
                state = server.getTickCount() - died < 40 ? LobbyProjections.State.DEAD : LobbyProjections.State.EMPTY;
            } else {
                b.deadAt.remove(p.id());
                state = player == null ? LobbyProjections.State.AWAY
                        : b.reviving.contains(p.id()) && (!p.online() || !b.profiles.isReady(p.id(), r) || PlayerProfiles.isLocked(player))
                        ? LobbyProjections.State.RESTORING
                        : !b.profiles.isReady(p.id(), r) || PlayerProfiles.isLocked(player) ? LobbyProjections.State.CLOSED
                        : !p.online() ? LobbyProjections.State.AWAY
                        : validTarget(b, player, player.level()) && !dimension(b, player.level()).equals("lobby")
                        ? LobbyProjections.State.AVAILABLE : LobbyProjections.State.CLOSED;
                if (state == LobbyProjections.State.AVAILABLE && b.reviving.remove(p.id()) && player != null) {
                    tell(player, "复生完成：已空装恢复人类，位于本轮自然出生点，无出生保护。"); sound(player, true);
                }
            }
            targets.add(new LobbyProjections.Target(slot.getValue(), p.lifeSequence(), state));
        }
        for (var observer : server.getPlayerList().getPlayers()) {
            if (!contextAvailable(observer, b, LobbyContext.DISPLAY)) { b.displays.remove(observer.getUUID()); continue; }
            var old = b.displays.get(observer.getUUID()); var view = projections.view(observer);
            var identity = r.participants().get(observer.getUUID());
            if (identity != null && identity.role() == ParticipantRole.MONSTER_LOBBY) {
                boolean cooling = identity.monsterCooldownNanos() > 0;
                if (Boolean.TRUE.equals(b.cooling.put(identity.id(), cooling)) && !cooling) {
                    tell(observer, "出场冷却结束，右键庭院目标模型发起追猎。"); sound(observer, true);
                }
            }
            if (old == null || old.player != observer || old.connection != observer.connection || !old.targets.equals(targets) || view.isEmpty()) {
                int page = view.map(LobbyProjections.View::page).orElse(0);
                projections.show(observer, r.roundId(), targets, page, (actor, selected) -> beginDeployment(actor, b, selected));
                b.displays.put(observer.getUUID(), new Display(observer, observer.connection, List.copyOf(targets)));
            }
            if (server.getTickCount() % 5 == 0) {
                var p = r.participants().get(observer.getUUID());
                if (p != null && p.role() == ParticipantRole.MONSTER_LOBBY) {
                    var hints = LobbyInteractions.get(server).orElseThrow().hints(observer);
                    long now = System.nanoTime() / 1_000_000;
                    var attempt = b.searches.get(p.id());
                    if (attempt != null || b.committing.contains(p.id()) || b.resumptions.containsKey(p.id()))
                        hints.offer("hunt", dev.hardrush.core.lobby.LobbyHints.Priority.PROGRESS, "正在搜索／提交个人操作", now + 300);
                    else projections.aimed(observer).ifPresent(selected -> hints.offer("hunt-target", dev.hardrush.core.lobby.LobbyHints.Priority.TARGET,
                            "目标：" + r.participants().get(selected.target()).name() + " · 当前角色：" + kindName(p.monsterRole())
                                    + (p.monsterCooldownNanos() > 0 ? " · 冷却 " + seconds(p.monsterCooldownNanos()) + " 秒" : " · 右键追猎"), now + 300));
                }
            }
        }
        b.displays.entrySet().removeIf(e -> server.getPlayerList().getPlayer(e.getKey()) != e.getValue().player);
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
        if (player == null || player.level() != level || !player.isAlive() || PlayerProfiles.isLocked(player)
                || ProtectionRuntime.protectedEntity(player)) return false;
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
    private static List<LobbyMenus.Button> menuButtons(ServerPlayer player, Binding b) {
        var round = b.session.view().orElseThrow(); var p = round.participants().get(player.getUUID());
        var buttons = new ArrayList<LobbyMenus.Button>();
        buttons.add(new LobbyMenus.Button(Items.PAPER, "轮次 " + round.sequence() + " · " + phaseName(round)
                + (p == null ? " · 未加入" : " · " + identityName(p.role())), "info", ""));
        buttons.add(new LobbyMenus.Button(Items.EMERALD, "开局请站入集结区；中途加入请使用侧门", "info", ""));
        buttons.add(new LobbyMenus.Button(Items.BOOK, "查看历史", "history", ""));
        if (p != null && p.role().isMonster()) {
            buttons.add(new LobbyMenus.Button(Items.BLAZE_POWDER, "施放当前角色技能", "skill", ""));
            buttons.add(new LobbyMenus.Button(Items.OAK_DOOR, "引导返回大厅（5 秒）", "return", ""));
            buttons.add(new LobbyMenus.Button(Items.PLAYER_HEAD, "右键庭院模型直接追猎 · 当前 " + kindName(p.monsterRole())
                    + " · 出场冷却 " + seconds(p.monsterCooldownNanos()) + " 秒", "info", ""));
            buttons.add(new LobbyMenus.Button(Items.TOTEM_OF_UNDYING, "剩余复生次数：" + p.revivalCharges() + " · 请到祭坛确认空装复生", "info", ""));

        }
        return buttons;
    }
    private static boolean revivalAvailable(ServerPlayer player, Binding b) {
        var r = b.session.snapshot().orElse(null);
        var p = r == null ? null : r.participants().get(player.getUUID());
        return r != null && !b.session.competitionLocked() && (r.phase() == RoundPhase.RUNNING || r.phase() == RoundPhase.RESCUE)
                && p != null && p.online() && p.role() == ParticipantRole.MONSTER_LOBBY
                && player.position().distanceToSqr(dev.hardrush.world.LobbyWorld.open(player.level().getServer())
                    .layoutPosition(LobbyLayout.facility(LobbyLayout.Facility.ALTAR))) <= 25;
    }
    public static void altar(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        if (b == null || !contextAvailable(player, b, LobbyContext.REVIVAL)) { tell(player, "祭坛仅供极限挑战／救场中的大厅怪物使用。"); return; }
        LobbyMenus.open(player, LobbyContext.REVIVAL);
    }
    private static List<LobbyMenus.Button> altarButtons(ServerPlayer player, Binding b) {
        var p = b.session.snapshot().orElseThrow().participants().get(player.getUUID());
        var buttons = new ArrayList<LobbyMenus.Button>();
        buttons.add(new LobbyMenus.Button(Items.TOTEM_OF_UNDYING, p.revivalEntitled() ? "剩余复生次数：" + p.revivalCharges() : "尚无复生资格 · 击杀在赛人类后获得", "info", ""));
        buttons.add(new LobbyMenus.Button(Items.GRASS_BLOCK, "消耗一次复生，空装恢复人类 · 主世界自然出生点，无出生保护", "info", ""));
        buttons.add(new LobbyMenus.Button(Items.PAPER, "保留本轮进度战绩；旧掉落不找回；出场冷却期间也可复生", "info", ""));
        if (p.revivalEntitled()) buttons.add(new LobbyMenus.Button(Items.EMERALD, b.committing.contains(p.id()) ? "正在复生" : "确认复生", b.committing.contains(p.id()) ? "info" : "revive", p.lifeSequence() + ":" + p.monsterDeploymentSequence()));
        return buttons;
    }
    private static void hint(ServerPlayer player, String source, dev.hardrush.core.lobby.LobbyHints.Priority priority, String text) {
        LobbyInteractions.get(player.level().getServer()).ifPresent(router -> router.hints(player).offer(source, priority, text, System.nanoTime() / 1_000_000 + 300));
    }
    private static String kindName(MonsterRole role) {
        return switch (role) { case ZOMBIE -> "僵尸"; case SKELETON -> "骷髅"; case CREEPER -> "苦力怕"; };
    }
    private static String phaseName(RoundSnapshot round) {
        return switch (round.phase()) {
            case PREPARING -> "准备世界"; case WAITING -> "等待加入"; case COUNTDOWN -> "开局倒计时";
            case RUNNING -> "进行中"; case RESCUE -> "救场中";
            case SETTLING -> RoundResultText.stageLabel(round.result(), "结算中");
            case CELEBRATING -> RoundResultText.stageLabel(round.result(), "庆祝中");
            case ROTATING -> "换轮中"; case RECOVERY_REQUIRED -> "等待恢复";
        };
    }
    private static String identityName(ParticipantRole role) {
        return switch (role) {
            case WITHDRAWN -> "已主动退出"; case DISQUALIFIED -> "本轮资格已取消";
            case LOBBY -> "大厅玩家"; case HUMAN_ACTIVE -> "人类"; case HUMAN_RESPAWNING -> "等待重生";
            case MONSTER_LOBBY -> "大厅怪物"; case MONSTER_DEPLOYED -> "外出怪物"; case TRANSITION_PENDING -> "转换中";
        };
    }
    private static long seconds(long nanos) { return nanos / 1_000_000_000L + (nanos % 1_000_000_000L == 0 ? 0 : 1); }
    private static void tell(ServerPlayer player, String message) {
        if (player.connection != null) player.sendSystemMessage(Component.literal(message));
    }
    private static void sound(ServerPlayer player, boolean success) {
        if (player.connection != null && player.connection.isAcceptingMessages()) player.connection.send(
                new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                        success ? net.minecraft.core.Holder.direct(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP)
                                : net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BASS,
                        net.minecraft.sounds.SoundSource.PLAYERS, player.getX(), player.getY(), player.getZ(),
                        .5f, success ? 1.2f : .8f, 0));
    }
}
