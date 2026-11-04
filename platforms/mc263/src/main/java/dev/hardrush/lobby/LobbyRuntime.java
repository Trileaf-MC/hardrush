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
import dev.hardrush.hunt.HuntRetracks;
import dev.hardrush.hunt.SafeDeployment;
import dev.hardrush.protection.ProtectionRuntime;
import dev.hardrush.monster.MonsterRoundRuntime;
import dev.hardrush.combat.MonsterCombatRuntime;
import dev.hardrush.notice.Notices;
import dev.hardrush.notice.Replies;
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
    /** A role click that would discard a paused sortie, waiting for a separate second click; times are server ticks. */
    private record AbandonAsk(MonsterRole role, long asked, long lastClick) {}
    /** A five-second standing channel: back to camp, or (retrack) onto the quarry's trail again. */
    private record Channel(ServerPlayer player, ServerLevel level, Vec3 origin, long started, int life, int sortie, boolean retrack) {}
    /** The in-place search after a completed retrack channel; the body must keep standing still. */
    private static final class Retrack {
        final ServerPlayer player, targetPlayer;
        final Vec3 origin; final ServerLevel origLevel;
        final int life, sortie, targetLife;
        final long started;
        SafeDeployment.Search search;
        Retrack(ServerPlayer player, ServerPlayer target, int life, int sortie, int targetLife, long started) {
            this.player = player; targetPlayer = target; origin = player.position(); origLevel = (ServerLevel) player.level();
            this.life = life; this.sortie = sortie; this.targetLife = targetLife; this.started = started;
        }
    }
    private static final class Deployment {
        final ServerPlayer player, targetPlayer;
        final net.minecraft.server.network.ServerGamePacketListenerImpl connection, targetConnection;
        final PersonalHuntRequest identity;
        final long started;
        int sortie;
        ServerLevel level;
        SafeDeployment.Search search;
        boolean cancelled, seaTipShown;
        Deployment(ServerPlayer player, ServerPlayer target, PersonalHuntRequest identity, long started) {
            this.player = player; targetPlayer = target; connection = player.connection; targetConnection = target == null ? null : target.connection;
            this.identity = identity; this.started = started; sortie = identity.initialSortie();
        }
    }
    private record Refund(Deployment deployment, boolean resume) {}
    private record Display(ServerPlayer player, net.minecraft.server.network.ServerGamePacketListenerImpl connection,
                           List<LobbyProjections.Target> targets) {}
    private enum Arrival { HUMAN_DIED, MONSTER_DIED }
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
        final Map<UUID, Retrack> retracks = new HashMap<>();
        final Map<UUID, Long> retryAt = new HashMap<>();
        final Set<UUID> committing = new HashSet<>();
        final Map<UUID, AbandonAsk> abandoning = new HashMap<>();
        /** Deaths waiting for the respawned body to stand in camp before they are told. */
        final Map<UUID, Arrival> arrivals = new HashMap<>();
        /** Player → round in which the return-to-camp rule was last explained; taught once per round. */
        final Map<UUID, UUID> returnTaught = new HashMap<>();
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
                        if (!LobbyMenus.open(player)) tell(player, "现在不能打开菜单");
                        return 1;
                    }));
            for (String action : List.of("deploy", "return", "skill")) {
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
            if (entity instanceof ServerPlayer player && taken > 0) interruptReturn(player, "受到伤害");
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
        var camp = new RevivalTotems.Access() {
            @Override public Optional<RoundSnapshot> committed() { return session.snapshot(); }
            @Override public boolean eligible(ServerPlayer player) {
                var r = session.snapshot().orElse(null);
                var p = r == null ? null : r.participants().get(player.getUUID());
                return p != null && p.online() && p.role() == ParticipantRole.MONSTER_LOBBY
                        && r.rules().mode() == dev.hardrush.core.config.GameMode.HARDCORE
                        && (r.phase() == RoundPhase.RUNNING || r.phase() == RoundPhase.RESCUE)
                        && !session.competitionLocked() && current(player, binding)
                        && PlayerProfiles.verifiedLobbyBody(player) && player.isAlive();
            }
            /** A hunt still waiting for a landing is not busy: the camp stays usable while it waits. */
            @Override public boolean busy(UUID player) {
                return binding.committing.contains(player) || binding.resumptions.containsKey(player) || binding.pending.containsKey(player);
            }
            @Override public RoundSession session() { return session; }
        };
        RevivalTotems.configure(server, camp);
        CampTransfers.configure(server, camp);
        CampKit.configure(server, camp);
        MonsterShop.configure(server, camp);
        MonsterVaults.configure(server, camp);
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
                if (context == LobbyContext.HISTORY) return dev.hardrush.record.HistoryLibrary.buttons(player);
                var round = session.snapshot().orElseThrow();
                return List.of(new LobbyMenus.Button(Items.BOOK, "站入集结区参加，离开取消准备", "info", ""),
                        new LobbyMenus.Button(Items.PAPER, "追猎规则：" + (round.rules().huntRule() == RulesConfig.HuntRule.PERSONAL_TARGET ? "右键庭院模型，循着目标足迹伺机出场；再次右键取消" : "旧版投票（升级恢复隔离）"), "info", ""),
                        new LobbyMenus.Button(Items.CLOCK, "右键快捷栏技能；回响碎片归返营地，追迹罗盘发光时可循迹回到目标附近；引导中移动、攻击、用物或受伤会中断", "info", ""),
                        new LobbyMenus.Button(Items.TOTEM_OF_UNDYING, "极限：人类击杀在赛人类每人 +1 次复生；击杀怪物不奖励", "info", ""),
                        new LobbyMenus.Button(Items.ROTTEN_FLESH, "怪物击杀在赛人类：每人 +1 次复生，可累计", "info", ""),
                        new LobbyMenus.Button(Items.TOTEM_OF_UNDYING, "死亡后走上营地北侧的复生祭坛，站在金块上 4 秒消耗一次空装复生，冷却中可用", "info", ""),
                        new LobbyMenus.Button(Items.TOTEM_OF_UNDYING, "营地背包的复生图腾数量即剩余次数；丢出可转赠营地怪物，落地5分钟作废", "info", ""),
                        new LobbyMenus.Button(Items.EMERALD, "怪物币：伤害人类、近期受玩家伤害后阵亡都能赚，领先者有悬赏倍率；找营地女巫购买下次出场的增强", "info", ""),
                        new LobbyMenus.Button(Items.TOTEM_OF_UNDYING, "持有复生次数的怪物不能追猎、选角或交易，须先到祭坛复生；击杀人类后立即被召回祭坛前；复生时怪物币清空", "info", ""),
                        new LobbyMenus.Button(Items.TRIAL_KEY, "试炼钥匙插入营地宝库抽一件一次性装备；穿戴的装备被玩家击杀时按原版概率掉落", "info", ""),
                        new LobbyMenus.Button(Items.EXPERIENCE_BOTTLE, "出场时经验条就是你的身价：从 10 级起，伤害人类会上涨，最高 30 级；近期受玩家伤害后阵亡会喷出经验，满 15 级再掉一本同等级附魔台的署名附魔书", "info", ""),
                        new LobbyMenus.Button(Items.GRASS_BLOCK, "挑战中站入集结区 4 秒空装参赛；普通死亡原版重生，极限死亡转怪物", "info", ""),
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
        BINDINGS.remove(server); LobbyInteractions.close(server); LobbyMenus.unconfigure(server); RevivalTotems.unconfigure(server); CampTransfers.unconfigure(server);
        CampKit.unconfigure(server); MonsterShop.unconfigure(server); MonsterVaults.unconfigure(server);
        MonsterRoundRuntime.unconfigure(server); MonsterCombatRuntime.unconfigure(server);
    }
    public static void prepareStop(MinecraftServer server) {
        var b = BINDINGS.get(server); if (b == null) return;
        b.pending.values().forEach(d -> d.cancelled = true);
        b.channels.clear(); b.retracks.clear(); b.searches.clear(); b.resumptions.clear(); b.displays.clear(); b.cooling.clear(); b.abandoning.clear();
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
            case HISTORY -> verified;
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
        if (round == null) { tell(player, "营地还在准备中"); return; }
        request(player, round.roundId(), action, value);
    }
    public static void request(ServerPlayer player, UUID expectedRound, String action, String value) {
        var b = BINDINGS.get(player.level().getServer());
        var round = b == null ? null : b.session.view().orElse(null);
        if (!current(player, b) || round == null || !round.roundId().equals(expectedRound)) {
            tell(player, "情况有变，请重新打开菜单"); return;
        }
        if (action.equals("info")) return;
        if (round.rules().huntRule() == RulesConfig.HuntRule.PERSONAL_TARGET
                && List.of("vote", "dimension", "deploy").contains(action)) {
            tell(player, "到庭院右键目标模型发起追猎"); return;
        }
        if (action.equals("join") || action.equals("ready") || action.equals("unready")) {
            tell(player, "站进集结区准备；挑战中站满 4 秒中途加入"); return;
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
            tell(player, "现在不能这样做"); return;
        }
        if (action.equals("skill")) {
            interruptReturn(player, "施放技能");
            if (!MonsterRoundRuntime.canAct(player) || !MonsterRoles.isMonster(player)) {
                tell(player, "出击后才能使用技能"); return;
            }
            boolean accepted = switch (participant.monsterRole()) {
                case ZOMBIE -> false;
                case SKELETON -> MonsterRoles.burst(player);
                case CREEPER -> false;
            };
            if (!accepted) refuse(player, participant.monsterRole() == MonsterRole.SKELETON
                    ? MonsterRoundRuntime.skillRefusal(player, MonsterSkill.SKELETON_BURST) : "当前角色没有可施放的技能");
            return;
        }
        if (action.equals("return")) { beginReturn(player, b, participant); return; }
        if (!lobbyAvailable(player, b) || participant.role() != ParticipantRole.MONSTER_LOBBY) {
            tell(player, "在庭院里才能这样做"); return;
        }
        var waiting = b.searches.get(player.getUUID());
        if (waiting != null && action.equals("revive")) cancelSearch(b, waiting, "前往复生，追猎取消");
        if (b.committing.contains(player.getUUID()) || b.searches.containsKey(player.getUUID())
                || b.resumptions.containsKey(player.getUUID())) {
            tell(player, "正在出击，稍等片刻"); return;
        }
        try {
            switch (action) {
                case "role" -> tell(player, "右键庭院里的角色展位选择角色");
                case "revive" -> {
                    var worlds = b.worlds.get();
                    if (worlds == null || !worlds.ready()
                            || !RoundWorldBundle.safeStanding(worlds.level(Level.OVERWORLD), worlds.spawn())) {
                        refuse(player, "现在无法复生，复生图腾还在，请联系管理员");
                        break;
                    }
                    b.committing.add(player.getUUID());
                    b.session.requestRevival(round.roundId(), player.getUUID(), participant.lifeSequence(),
                            participant.monsterDeploymentSequence(), reply -> {
                                b.committing.remove(player.getUUID());
                                if (current(player, b) && !reply.success()) Replies.refuse(player, reply, "现在无法复生");
                            });
                }
                default -> { }
            }
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            org.slf4j.LoggerFactory.getLogger(LobbyRuntime.class).debug("Lobby action {} rejected", action, invalid);
            refuse(player, "现在不能这样做");
        }
    }
    /** Only called with a server-owned projection selection after BOTH packet guards. */
    private static void beginDeployment(ServerPlayer player, Binding b, LobbyProjections.Selection selected) {
        var round = b.session.view().orElse(null);
        if (round == null || !selected.round().equals(round.roundId()) || !personalOpen(b, round)
                || !contextAvailable(player, b, LobbyContext.DISPLAY)) return;
        hunt(player, b, round, selected.target(), selected.life(), false);
    }
    /** Holding jump while riding a human's camera is the same request as right-clicking its model. */
    public static void huntObserved(ServerPlayer player, UUID target) {
        var b = BINDINGS.get(player.level().getServer());
        var round = b == null ? null : b.session.view().orElse(null);
        if (round == null || !personalOpen(b, round) || !current(player, b) || !dev.hardrush.observe.Observation.observing(player)) return;
        var quarry = round.participants().get(target);
        if (quarry == null || quarry.role() != ParticipantRole.HUMAN_ACTIVE) { refuse(player, Notices.key(quarry == null ? "此人" : quarry.name()) + " 目前不适合再被追猎了，换个人吧"); return; }
        hunt(player, b, round, target, quarry.lifeSequence(), true);
    }
    private static void hunt(ServerPlayer player, Binding b, RoundSnapshot round, UUID targetId, int targetLife, boolean observed) {
        var p = round.participants().get(player.getUUID());
        if (p == null || p.role() != ParticipantRole.MONSTER_LOBBY || !p.online()) {
            refuse(player, "只有庭院里待命的怪物可以追猎"); return;
        }
        var waiting = b.searches.get(p.id());
        if (waiting != null && waiting.identity.target().equals(targetId)) {
            // Holding right-click repeats the interaction every few ticks; only a deliberate second click cancels.
            if (b.searchClock.getAsLong() - waiting.started < 1_000_000_000L) return;
            cancelSearch(b, waiting, "已取消对 " + Notices.key(waiting.targetPlayer.getScoreboardName()) + " 的追猎"); sound(player, false); return;
        }
        if (b.committing.contains(p.id()) || b.resumptions.containsKey(p.id())) {
            tell(player, "正在出击……"); return;
        }
        if (revivalFirst(player, round, p)) return;
        long now = b.searchClock.getAsLong();
        if (p.monsterCooldownNanos() > 0) { refuse(player, "出击冷却中 · " + Notices.key(seconds(p.monsterCooldownNanos())) + " 秒"); return; }
        Long retry = b.retryAt.get(p.id());
        if (retry != null && now - retry < 0) { refuse(player, Notices.key(seconds(retry - now)) + " 秒后才能再次追猎"); return; }
        var identity = new PersonalHuntRequest(round.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(),
                p.monsterRole(), targetId, targetLife);
        var target = player.level().getServer().getPlayerList().getPlayer(identity.target());
        var d = new Deployment(player, target, identity, now);
        if (target == null || !requestTarget(b, d)) { searchFailed(b, d, Notices.key(targetName(round, targetId)) + " 已离线或阵亡，追猎取消"); return; }
        if (waiting != null) cancelSearch(b, waiting, null);
        restartSearch(b, d);
        // The progress hint below names the target and how to cancel; no separate announcement is needed.
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
        CampTransfers.tick(server);
        RevivalTotems.tick(server);
        CampKit.tick(server);
        MonsterVaults.tick(server);
        var round = b.session.view().orElse(null);
        updateDisplays(server, b);
        tellArrivals(server, b);
        if (round == null || round.phase() != RoundPhase.RUNNING && round.phase() != RoundPhase.RESCUE || b.session.competitionLocked()) {
            for (var d : List.copyOf(b.searches.values())) searchFailed(b, d, "本轮即将结束，追猎取消");
            for (var d : b.pending.values()) d.cancelled = true;
            for (var channel : List.copyOf(b.channels.values())) interruptReturn(channel.player, "比赛状态变化");
            for (var retrack : List.copyOf(b.retracks.values())) interruptReturn(retrack.player, "比赛状态变化");
            b.resumptions.clear(); return;
        }
        HuntRetracks.tick(server, round);
        for (var refund : List.copyOf(b.refunds.values())) compensate(b, refund);
        for (var d : List.copyOf(b.resumptions.values())) {
            if (!current(d.player, b)) {
                if (server.getPlayerList().getPlayer(d.player.getUUID()) != d.player || b.profiles.failure(d.player.getUUID()).isPresent()) {
                    b.resumptions.remove(d.player.getUUID()); searchFailed(b, d, "情况有变，追猎取消");
                }
                continue;
            }
            b.resumptions.remove(d.player.getUUID());
            if (!requestHunter(b, d, ParticipantRole.MONSTER_LOBBY, d.sortie) || !requestTarget(b, d)) {
                searchFailed(b, d, Notices.key(d.targetPlayer.getScoreboardName()) + " 已离线或阵亡，追猎取消"); continue;
            }
            restartSearch(b, d);
        }
        for (var entry : List.copyOf(b.channels.entrySet())) {
            var ch = entry.getValue(); var p = round.participants().get(entry.getKey());
            if (current(ch.player, b) && ChannelEffects.moving(ch.player)) { interruptReturn(ch.player, "移动"); continue; }
            if (!current(ch.player, b) || p == null || p.role() != ParticipantRole.MONSTER_DEPLOYED
                    || p.lifeSequence() != ch.life || p.monsterDeploymentSequence() != ch.sortie
                    || ch.player.level() != ch.level || MonsterRoles.isIgniting(ch.player)) {
                interruptReturn(ch.player, "比赛状态变化"); continue;
            }
            long duration = channelNanos(round);
            long elapsed = System.nanoTime() - ch.started;
            ChannelEffects.tick(ch.player, ch.retrack, elapsed, duration);
            hint(ch.player, "return", dev.hardrush.core.lobby.LobbyHints.Priority.PROGRESS,
                    (ch.retrack ? "正在循迹" : "正在返回营地") + " · 还需 " + Math.max(0, (duration - elapsed + 999_999_999L) / 1_000_000_000L) + " 秒");
            if (ch.retrack && elapsed >= duration) {
                b.channels.remove(entry.getKey());
                ChannelEffects.complete(ch.player, true);
                beginRetrackSearch(b, ch, p);
            } else if (elapsed >= duration && !b.session.mutationPending()) {
                b.channels.remove(entry.getKey());
                ChannelEffects.complete(ch.player, false);
                // Only the sortie's own bound items are parked; world items settle here when the return commits.
                var from = ch.player.position(); var level = (ServerLevel) ch.player.level();
                String parked = dev.hardrush.monster.MonsterParking.capture(ch.player);
                b.session.requestMonsterReturn(round.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(),
                        parked, reply -> {
                            if (reply.success()) {
                                ChannelEffects.departed(ch.player, level, from, false);
                                tell(ch.player, returned(round, !parked.isEmpty()));
                            } else { Replies.refuse(ch.player, reply, "没能返回营地"); }
                        });
            }
        }
        for (var r : List.copyOf(b.retracks.values())) tickRetrack(b, round, r);
        for (var d : List.copyOf(b.searches.values())) {
            if (!requestHunter(b, d, ParticipantRole.MONSTER_LOBBY, d.sortie) || !requestTarget(b, d)) {
                searchFailed(b, d, Notices.key(d.targetPlayer.getScoreboardName()) + " 已离线或阵亡，追猎取消"); continue;
            }
            // Watching the quarry from its camera keeps the hunt; the body returns to the courtyard it left.
            if (!inCamp(d.player) && !dev.hardrush.observe.Observation.observing(d.player)) { cancelSearch(b, d, "你离开了庭院，追猎取消"); continue; }
            var target = d.targetPlayer;
            if (target.level() != d.level) { restartSearch(b, d); continue; }
            var result = d.search.tick(SafeDeployment.Quarry.of(target), humans(b, d.level));
            if (result.status() == SafeDeployment.Status.PENDING) continue;
            if (result.status() == SafeDeployment.Status.FAILED) {
                searchFailed(b, d, NO_DEPLOYMENT); continue;
            }
            if (!d.search.revalidate(target.position(), humans(b, d.level))) { restartSearch(b, d); continue; }
            if (b.session.mutationPending()) continue;
            b.searches.remove(d.player.getUUID()); b.committing.add(d.player.getUUID()); b.pending.put(d.player.getUUID(), d);
            b.session.requestPersonalHunt(d.identity, d.sortie, reply -> completeDeployment(b, d, reply));
        }
    }
    private static void completeDeployment(Binding b, Deployment d, RoundSession.Reply reply) {
        UUID id = d.identity.hunter();
        if (!reply.success()) {
            b.pending.remove(id, d); b.committing.remove(id); searchFailed(b, d, Replies.text(reply, Notices.key(d.targetPlayer.getScoreboardName()) + " 情况有变，临时去掉了出击 · 可再次尝试")); return;
        }
        boolean hunter = requestHunter(b, d, ParticipantRole.MONSTER_DEPLOYED, d.sortie + 1);
        boolean target = requestTarget(b, d);
        boolean safe = hunter && target && d.targetPlayer.level() == d.level
                && d.search.revalidate(d.targetPlayer.position(), humans(b, d.level));
        if (safe) {
            try {
                var committed = b.session.snapshot().orElseThrow();
                var landing = d.search.result().position();
                if (b.profiles.deployMonster(d.player, committed, d.level, landing, d.search.yaw(d.targetPlayer.position()))) {
                    MonsterRoles.select(d.player, d.identity.kind().name().toLowerCase(Locale.ROOT));
                    if (!MonsterRoles.isMonster(d.player) || MonsterRoles.nativeModel(d.player) == null)
                        throw new IllegalStateException("已提交出场未能应用原生怪物角色");
                    boolean risen = d.search.result().submerged() && d.identity.kind() == MonsterRole.ZOMBIE;
                    if (risen) MonsterRoles.riseFromSea(d.player);
                    b.pending.remove(id, d); b.committing.remove(id);
                    dev.hardrush.hunt.HuntBossBars.deployed(d.player, committed, d.identity);
                    HuntRetracks.arrived(d.player, d.targetPlayer);
                    sound(d.player, true);
                    String quarry = Notices.key(d.targetPlayer.getScoreboardName());
                    tell(d.player, risen ? "已化身溺尸，从水下出击"
                            : d.search.result().fallback()
                            ? "已出击：去 " + quarry + " 的路被切断，这是能到的最近处" : "已出击，" + quarry + " 就在附近"); return;
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
            searchFailed(b, d, "情况有变，追猎取消"); return;
        }
        b.session.requestMonsterDeployCancelled(d.identity.round(), id, d.identity.hunterLife(), d.sortie + 1, refunded -> {
            if (!refunded.success()) {
                if (b.session.state() == RoundSession.State.FAILED || b.session.competitionLocked()) {
                    refuse(d.player, "出击出错，请联系管理员");
                    Notices.operators(d.player.level().getServer(), d.player.getScoreboardName() + " 出击退回失败：" + refunded.message());
                }
                return; // Transient queued death/connection write: retain the exact refund and retry after it drains.
            }
            b.refunds.remove(id, refund); b.pending.remove(id, d); b.committing.remove(id);
            d.sortie++;
            if (refund.resume && requestTarget(b, d)) {
                b.resumptions.put(id, d);
                tell(d.player, "出击位置失效，正在重新寻找");
            } else searchFailed(b, d, Notices.key(d.targetPlayer.getScoreboardName()) + " 情况有变，临时去掉了出击 · 可再次尝试");
        });
    }
    /** A deliberate cancel or target switch: no failure sound and no retry wait. */
    private static void cancelSearch(Binding b, Deployment d, String message) {
        b.searches.remove(d.identity.hunter(), d); b.resumptions.remove(d.identity.hunter(), d);
        if (message != null && d.player.level().getServer().getPlayerList().getPlayer(d.player.getUUID()) == d.player) tell(d.player, message);
    }
    private static boolean inCamp(ServerPlayer player) {
        var camp = dev.hardrush.world.LobbyWorld.open(player.level().getServer());
        if (!camp.ready() || player.level() != camp.holdingLevel()) return false;
        var origin = camp.layoutPosition(new LobbyLayout.Point(0, 0, 0));
        return LobbyLayout.MONSTER_CAMP.contains(player.getX() - origin.x, player.getZ() - origin.z);
    }
    private static void searchFailed(Binding b, Deployment d, String message) {
        b.searches.remove(d.identity.hunter(), d); b.resumptions.remove(d.identity.hunter(), d);
        b.retryAt.put(d.identity.hunter(), b.searchClock.getAsLong() + 3_000_000_000L);
        if (d.player.level().getServer().getPlayerList().getPlayer(d.player.getUUID()) == d.player) {
            tell(d.player, message); sound(d.player, false);
        }
    }
    /** Same target life only; dimension changes and compensation share the original start and 60-second wait. */
    private static void restartSearch(Binding b, Deployment d) {
        b.searches.remove(d.identity.hunter(), d);
        d.search = null;
        if (!requestHunter(b, d, ParticipantRole.MONSTER_LOBBY, d.sortie) || !requestTarget(b, d)) {
            searchFailed(b, d, Notices.key(d.targetPlayer.getScoreboardName()) + " 已离线或阵亡，追猎取消"); return;
        }
        if (b.searchClock.getAsLong() - d.started >= SafeDeployment.DEPLOY_WAIT_NANOS) {
            searchFailed(b, d, NO_DEPLOYMENT); return;
        }
        d.level = d.targetPlayer.level();
        d.search = new SafeDeployment.Search(d.level, d.player, d.identity.kind(), SafeDeployment.Quarry.of(d.targetPlayer),
                humans(b, d.level), b.searchRandom, b.searchClock, d.started, SafeDeployment.DEPLOY_WAIT_NANOS);
        b.searches.put(d.identity.hunter(), d);
    }
    private static final String CELEBRATION_RETURN = "庆祝期间不能返回营地";
    private static final String NO_DEPLOYMENT = "暂时没找到合适的出击位置 · " + Notices.key(3) + " 秒后可重试";

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
            refuse(player, "现在不能返回营地"); return;
        }
        // Holding the item, or right-clicking again, neither restarts nor repeats a running channel.
        if (b.channels.containsKey(p.id()) || b.retracks.containsKey(p.id())) return;
        if (ChannelEffects.moving(player)) { tell(player, "站稳后才能返回营地"); sound(player, false); return; }
        var round = b.session.view().orElseThrow();
        b.channels.put(p.id(), new Channel(player, (ServerLevel) player.level(), player.position(), System.nanoTime(),
                p.lifeSequence(), p.monsterDeploymentSequence(), false));
        ChannelEffects.start(player, false, channelNanos(round));
        // The progress hint shows the countdown; the interruption rule is explained once per round.
        if (!round.roundId().equals(b.returnTaught.put(p.id(), round.roundId())))
            tell(player, "原地不动 " + Notices.key(channelSeconds(round)) + " 秒返回营地，移动或受伤会打断");
    }
    private static String returned(RoundSnapshot round, boolean parked) {
        long wait = seconds(RoundEngine.returnCooldownNanos(round.rules()));
        return (parked ? "已返回营地，本次出击已暂存" : "已返回营地") + (wait == 0 ? "" : " · " + Notices.key(wait) + " 秒后可再出击");
    }
    private static String targetName(RoundSnapshot round, UUID target) {
        var p = round.participants().get(target);
        return p == null ? "目标" : p.name();
    }
    /** The bound quarry's name, highlighted; a neutral word when the compass has lost it entirely. */
    private static String quarryName(ServerPlayer hunter) {
        return HuntRetracks.quarry(hunter).map(target -> Notices.key(target.getScoreboardName())).orElse("目标");
    }
    private static long channelNanos(RoundSnapshot round) {
        return round.rules().combatValues().get("monster.returnChannelMillis").longValue() * 1_000_000L;
    }
    private static long channelSeconds(RoundSnapshot round) { return Math.max(1, (channelNanos(round) + 999_999_999L) / 1_000_000_000L); }
    private static final String CELEBRATION_RETRACK = "庆祝期间不能循迹";

    /** Right-click on the bound tracking compass: a five-second channel, then the quarry's trail again. */
    public static boolean useRetrack(ServerPlayer player, net.minecraft.world.item.ItemStack compass) {
        if (dev.hardrush.celebration.CelebrationRuntime.arena(player)) { tell(player, CELEBRATION_RETRACK); return false; }
        var b = BINDINGS.get(player.level().getServer());
        var r = b == null ? null : b.session.view().orElse(null);
        var p = r == null ? null : r.participants().get(player.getUUID());
        if (p == null || !current(player, b) || !player.isAlive() || PlayerProfiles.isLocked(player)
                || b.session.competitionLocked() || r.phase() != RoundPhase.RUNNING && r.phase() != RoundPhase.RESCUE) return false;
        if (p.role() != ParticipantRole.MONSTER_DEPLOYED || MonsterRoles.isIgniting(player) || MonsterRoles.performingSkill(player)) {
            refuse(player, "现在不能循迹"); return false;
        }
        if (b.channels.containsKey(p.id()) || b.retracks.containsKey(p.id())) return true;
        // The native cooldown overlay on the compass already says it is not ready.
        if (player.getCooldowns().isOnCooldown(compass)) return false;
        var target = HuntRetracks.quarry(player).orElse(null);
        if (target == null || !validTarget(b, target, (ServerLevel) target.level()) || dimension(b, (ServerLevel) target.level()).equals("lobby")) {
            refuse(player, "罗盘失去了指向：" + quarryName(player) + " 已离线、阵亡或不在挑战世界"); return false;
        }
        if (!HuntRetracks.available(player)) {
            refuse(player, "罗盘未亮：离 " + Notices.key(target.getScoreboardName()) + " " + Notices.key(HuntRetracks.FAR_DISTANCE)
                    + " 格以上持续 " + Notices.key(HuntRetracks.FAR_SECONDS) + " 秒才会亮"); return false;
        }
        if (ChannelEffects.moving(player)) { tell(player, "站稳后才能循迹"); sound(player, false); return false; }
        b.channels.put(p.id(), new Channel(player, (ServerLevel) player.level(), player.position(), System.nanoTime(),
                p.lifeSequence(), p.monsterDeploymentSequence(), true));
        ChannelEffects.start(player, true, channelNanos(r));
        return true;
    }
    private static void beginRetrackSearch(Binding b, Channel ch, dev.hardrush.core.round.Participant p) {
        var target = HuntRetracks.quarry(ch.player).orElse(null);
        var life = HuntRetracks.quarryLife(ch.player);
        if (target == null || life.isEmpty() || !validTarget(b, target, (ServerLevel) target.level())) {
            tell(ch.player, quarryName(ch.player) + " 已离线或阵亡，循迹取消，罗盘不进入冷却"); sound(ch.player, false); return;
        }
        var r = new Retrack(ch.player, target, p.lifeSequence(), p.monsterDeploymentSequence(), life.getAsInt(), b.searchClock.getAsLong());
        retrackSearch(b, r);
        b.retracks.put(p.id(), r);
        hint(ch.player, "return", dev.hardrush.core.lobby.LobbyHints.Priority.PROGRESS, "正在寻找 " + target.getScoreboardName() + " 附近的落点");
    }
    private static void retrackSearch(Binding b, Retrack r) {
        var level = (ServerLevel) r.targetPlayer.level();
        var kind = b.session.snapshot().map(s -> s.participants().get(r.player.getUUID()))
                .map(dev.hardrush.core.round.Participant::monsterRole).orElse(MonsterRole.ZOMBIE);
        r.search = new SafeDeployment.Search(level, r.player, kind, SafeDeployment.Quarry.of(r.targetPlayer), humans(b, level),
                b.searchRandom, b.searchClock, r.started, SafeDeployment.RETRACK_WAIT_NANOS);
    }
    private static void tickRetrack(Binding b, RoundSnapshot round, Retrack r) {
        var p = round.participants().get(r.player.getUUID());
        var target = r.targetPlayer;
        var targetIdentity = round.participants().get(target.getUUID());
        if (current(r.player, b) && ChannelEffects.moving(r.player)) { interruptReturn(r.player, "移动"); return; }
        if (!current(r.player, b) || p == null || p.role() != ParticipantRole.MONSTER_DEPLOYED || !r.player.isAlive()
                || p.lifeSequence() != r.life || p.monsterDeploymentSequence() != r.sortie
                || r.player.level() != r.origLevel || MonsterRoles.isIgniting(r.player)) { interruptReturn(r.player, "比赛状态变化"); return; }
        if (target.level().getServer().getPlayerList().getPlayer(target.getUUID()) != target || targetIdentity == null
                || targetIdentity.lifeSequence() != r.targetLife || !validTarget(b, target, (ServerLevel) target.level())
                || dimension(b, (ServerLevel) target.level()).equals("lobby")) {
            b.retracks.remove(r.player.getUUID());
            tell(r.player, Notices.key(target.getScoreboardName()) + " 已离线或阵亡，循迹取消，罗盘不进入冷却"); sound(r.player, false); return;
        }
        if (r.search == null || target.level() != r.search.level()) retrackSearch(b, r);
        var level = r.search.level();
        var result = r.search.tick(SafeDeployment.Quarry.of(target), humans(b, level));
        if (result.status() == SafeDeployment.Status.PENDING) return;
        b.retracks.remove(r.player.getUUID());
        LobbyInteractions.get(r.player.level().getServer()).ifPresent(router -> router.hints(r.player).clear("return"));
        if (result.status() == SafeDeployment.Status.FAILED || !r.search.revalidate(target.position(), humans(b, level))) {
            tell(r.player, SafeDeployment.atSea(target) ? Notices.key(target.getScoreboardName()) + " 在海上，没有落脚点，罗盘不进入冷却"
                    : "暂没找到能循迹过去的位置"); sound(r.player, false); return;
        }
        var landing = result.position();
        var from = r.player.position(); var fromLevel = (ServerLevel) r.player.level();
        if (!r.player.teleportTo(level, landing.x, landing.y, landing.z, java.util.Set.of(), r.search.yaw(target.position()), 0, true)) {
            tell(r.player, "落点失效，循迹取消"); sound(r.player, false); return;
        }
        HuntRetracks.retracked(r.player, target);
        ChannelEffects.departed(r.player, fromLevel, from, true);
        sound(r.player, true);
        if (result.fallback()) tell(r.player, "去 " + Notices.key(target.getScoreboardName()) + " 的路被切断，这是能到的最近处");
    }
    /** @param reason one of the plain words players see: 移动、受到伤害、攻击、互动、使用物品、施放技能、敲门、比赛状态变化 */
    public static void interruptReturn(ServerPlayer player, String reason) {
        var b = BINDINGS.get(player.level().getServer());
        if (b == null) return;
        var channel = b.channels.remove(player.getUUID());
        var retrack = b.retracks.remove(player.getUUID());
        if (channel == null && retrack == null) return;
        boolean tracking = channel == null || channel.retrack();
        String what = tracking ? "循迹" : "返回营地";
        ChannelEffects.interrupted(player, tracking);
        LobbyInteractions.get(player.level().getServer()).ifPresent(router -> router.hints(player).clear("return"));
        tell(player, what + "被打断（" + Notices.key(reason) + "）");
        sound(player, false);
    }
    public static boolean channeling(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        return b != null && (b.channels.containsKey(player.getUUID()) || b.retracks.containsKey(player.getUUID()));
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
            b.searches.clear(); b.resumptions.clear(); b.pending.clear(); b.refunds.clear(); b.channels.clear(); b.retracks.clear(); b.committing.clear(); b.retryAt.clear(); b.displays.clear(); return;
        }
        if (dev.hardrush.celebration.CelebrationRuntime.successful(after)) {
            b.searches.clear(); b.resumptions.clear(); b.channels.clear(); b.retracks.clear(); return;
        }
        if (before.phase() != after.phase() && after.phase() != RoundPhase.RUNNING && after.phase() != RoundPhase.RESCUE)
            for (var ch : List.copyOf(b.channels.values())) interruptReturn(ch.player, "比赛状态变化");
        if (before.phase() != after.phase() && after.phase() != RoundPhase.RUNNING && after.phase() != RoundPhase.RESCUE)
            for (var r : List.copyOf(b.retracks.values())) interruptReturn(r.player, "比赛状态变化");
        for (var p : after.participants().values()) {
            var old = before.participants().get(p.id());
            if (old != null && old.role().isMonster() && p.role() == ParticipantRole.HUMAN_ACTIVE) {
                b.reviving.add(p.id());
                RevivalAltar.revived(server, p.id());
                var revived = server.getPlayerList().getPlayer(p.id());
                if (revived != null && MonsterRoles.isMonster(revived)) MonsterRoles.clear(revived);
                b.channels.remove(p.id()); b.retracks.remove(p.id()); b.searches.remove(p.id()); b.resumptions.remove(p.id());
            }
            if (old != null && p.wallet().coins() > old.wallet().coins()) {
                var owner = server.getPlayerList().getPlayer(p.id());
                if (owner != null) coinFeedback(owner, b.session.committedEventType(), old, p);
            }
            if (old != null && p.revivalCharges() > old.revivalCharges()) {
                var owner = server.getPlayerList().getPlayer(p.id());
                if (owner != null) {
                    boolean recalled = old.role() == ParticipantRole.MONSTER_DEPLOYED && p.role() == ParticipantRole.MONSTER_LOBBY
                            && dev.hardrush.core.round.MonsterCoins.enabled(after.rules());
                    tell(owner, recalled ? "获得复生图腾，可在营地祭坛复生"
                            : "获得 " + Notices.key(p.revivalCharges() - old.revivalCharges()) + " 个复生图腾 · 可在营地祭坛复生");
                    sound(owner, true);
                }
            }
            // Both deaths are told after the respawn lands in camp; a death screen would hide them.
            if (old != null && old.role() == ParticipantRole.HUMAN_ACTIVE && p.role() == ParticipantRole.MONSTER_LOBBY) b.arrivals.put(p.id(), Arrival.HUMAN_DIED);
            if (old != null && old.role() == ParticipantRole.MONSTER_DEPLOYED && p.role() == ParticipantRole.MONSTER_LOBBY
                    && "MONSTER_DEATH".equals(b.session.committedEventType())) b.arrivals.put(p.id(), Arrival.MONSTER_DIED);
            if (p.role() != ParticipantRole.MONSTER_LOBBY || old == null || old.role() != ParticipantRole.MONSTER_DEPLOYED) continue;
            var player = server.getPlayerList().getPlayer(p.id());
            if (player == null || !b.profiles.manages(player)) continue;
            b.channels.remove(p.id()); b.retracks.remove(p.id());
            MonsterRoles.clear(player);
            b.profiles.returnMonsterToLobby(player, after);
            // A kill that earned a revival in a coin round is summoned to the altar's steps, facing the beam.
            // A dead body was replaced by its native respawn, so look the player up again.
            var summoned = server.getPlayerList().getPlayer(p.id());
            if (dev.hardrush.core.round.MonsterCoins.enabled(after.rules()) && p.revivalCharges() > old.revivalCharges()
                    && summoned != null && dev.hardrush.world.LobbyWorld.isLobby(summoned.level())
                    && dev.hardrush.world.LobbyWorld.open(server).safeLanding(LobbyLayout.Landing.ALTAR))
                b.profiles.placeInLobby(summoned, LobbyLayout.Landing.ALTAR);
        }
    }
    /** A camp monster holding a revival is pointed to the altar instead of hunting, choosing or shopping. */
    public static boolean revivalFirst(ServerPlayer player, RoundSnapshot round, dev.hardrush.core.round.Participant p) {
        if (!dev.hardrush.core.round.MonsterCoins.enabled(round.rules()) || !p.revivalEntitled()) return false;
        refuse(player, Replies.REVIVAL_FIRST);
        sound(player, false);
        return true;
    }
    /** Only the earner hears the native experience chime; the reason follows the committed event. */
    private static void coinFeedback(ServerPlayer player, String event, dev.hardrush.core.round.Participant before,
                                     dev.hardrush.core.round.Participant after) {
        int gained = after.wallet().coins() - before.wallet().coins();
        String reason = switch (event) {
            case "MONSTER_DAMAGE_COINS" -> "伤害";
            case "HARDCORE_DEATH" -> "化身礼包";
            case "DEATH_BATCH" -> before.role().isActiveHuman() ? "化身礼包" : "阵亡奖励";
            case "MONSTER_DEPLOY", "MONSTER_SELECTION" -> "退款";
            default -> "奖励";
        };
        player.sendSystemMessage(Component.literal("+" + gained + " 怪物币（" + reason + "）· 共 " + after.wallet().coins())
                .withStyle(net.minecraft.ChatFormatting.GREEN), true);
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP),
                net.minecraft.sounds.SoundSource.PLAYERS, player.getX(), player.getY(), player.getZ(), 0.3f, 1.2f,
                player.getRandom().nextLong()));
    }
    /** The courtyard's append-only slot order; observers cycle through players in the same order. */
    public static List<UUID> courtyardOrder(MinecraftServer server) {
        var b = BINDINGS.get(server);
        return b == null ? List.of() : List.copyOf(b.slots.keySet());
    }
    /** A sortie being committed or compensated; a camp body must not leave for a camera meanwhile. */
    public static boolean submitting(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        UUID id = player.getUUID();
        return b != null && (b.committing.contains(id) || b.pending.containsKey(id) || b.resumptions.containsKey(id));
    }
    /** Whom this camp monster is currently waiting to hunt, if anyone. */
    public static Optional<UUID> huntTarget(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        if (b == null) return Optional.empty();
        var d = b.searches.get(player.getUUID());
        if (d == null) d = b.resumptions.get(player.getUUID());
        if (d == null) d = b.pending.get(player.getUUID());
        return d == null ? Optional.empty() : Optional.of(d.identity.target());
    }
    public static void disconnected(ServerPlayer player) {
        LobbyInteractions.disconnected(player);
        MonsterVaults.disconnected(player);
        var b = BINDINGS.get(player.level().getServer()); if (b == null) return;
        b.channels.remove(player.getUUID()); b.retracks.remove(player.getUUID()); b.displays.remove(player.getUUID());
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
            b.slots.clear(); b.deadAt.clear(); b.displays.clear(); b.cooling.clear(); b.abandoning.clear(); b.reviving.clear();
        }
    }
    /** A death is told once the respawned body stands in camp, so the death screen cannot swallow it. */
    private static void tellArrivals(MinecraftServer server, Binding b) {
        b.arrivals.entrySet().removeIf(entry -> {
            var player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) return true;
            if (!player.isAlive() || !dev.hardrush.world.LobbyWorld.isLobby(player.level()) || PlayerProfiles.isLocked(player)
                    || player.connection == null || !player.connection.hasClientLoaded()) return false;
            if (entry.getValue() == Arrival.HUMAN_DIED) Notices.title(player, "你阵亡了", "已回到营地 · 可以在展位化身怪物出击");
            else tell(player, "你在出击中倒下，回到了庭院");
            return true;
        });
    }
    /** Role models and paging blocks use the same guarded facility action. */
    public static void facility(ServerPlayer player, LobbyLayout.Facility facility) {
        var b = BINDINGS.get(player.level().getServer());
        var r = b == null ? null : b.session.snapshot().orElse(null);
        if (r == null || !contextAvailable(player, b, LobbyContext.DISPLAY) || !personalOpen(b, r)) {
            refuse(player, "庭院只在极限挑战或救场期间开放"); return;
        }
        if (facility == LobbyLayout.Facility.MERCHANT) { MonsterShop.open(player); return; }
        if (facility == LobbyLayout.Facility.PREVIOUS || facility == LobbyLayout.Facility.NEXT) {
            var projections = LobbyInteractions.get(player.level().getServer()).orElseThrow().projections();
            projections.turn(player, facility == LobbyLayout.Facility.NEXT ? 1 : -1);
            return;
        }
        var p = r.participants().get(player.getUUID());
        MonsterRole role = switch (facility) {
            case ZOMBIE -> MonsterRole.ZOMBIE; case SKELETON -> MonsterRole.SKELETON; case CREEPER -> MonsterRole.CREEPER;
            default -> null;
        };
        // Picking another role ends a waiting hunt; the new body would not match the pending identity.
        var waiting = p == null ? null : b.searches.get(p.id());
        if (waiting != null && role != null) cancelSearch(b, waiting, "已换角色，对 " + Notices.key(waiting.targetPlayer.getScoreboardName()) + " 的追猎已取消");
        if (p == null || p.role() != ParticipantRole.MONSTER_LOBBY) { refuse(player, "阵亡后才能在这里选择怪物角色"); return; }
        if (b.committing.contains(p.id()) || b.searches.containsKey(p.id()) || b.resumptions.containsKey(p.id())) {
            refuse(player, "正在出击，稍等片刻"); return;
        }
        if (role != null && revivalFirst(player, r, p)) return;
        if (role == null) return;
        if (p.wallet().hasParkedBody() && role != p.monsterRole() && !confirmAbandon(b, player, p, role)) return;
        b.committing.add(p.id());
        b.session.requestMonsterRole(r.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(), role, reply -> {
            b.committing.remove(p.id());
            if (!current(player, b)) return;
            // Success shows itself: the role's items arrive. How each item works is in its description.
            if (reply.success()) sound(player, true);
            else Replies.refuse(player, reply, "现在不能选择角色");
        });
    }
    /**
     * Another role discards the paused sortie, so it takes a second click within a few seconds.
     * A native held right-click repeats every 4 ticks; such repeats count as the same click.
     */
    private static boolean confirmAbandon(Binding b, ServerPlayer player, Participant p, MonsterRole role) {
        long now = player.level().getServer().getTickCount();
        var ask = b.abandoning.get(p.id());
        if (ask != null && ask.role() == role && now - ask.lastClick() <= 5) {
            b.abandoning.put(p.id(), new AbandonAsk(role, ask.asked(), now)); return false;
        }
        if (ask != null && ask.role() == role && now - ask.asked() <= 120) { b.abandoning.remove(p.id()); return true; }
        b.abandoning.put(p.id(), new AbandonAsk(role, now, now));
        String parked = dev.hardrush.monster.MonsterParking.describe(p.wallet().parked()).orElse(kindName(p.monsterRole()));
        tell(player, "再点一次改选 " + Notices.key(kindName(role)) + "：暂存的 " + Notices.key(parked) + " 连同装备和身价作废");
        sound(player, false);
        return false;
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
                // The revival title and broadcast come from RevivalAltar when the body lands.
                if (state == LobbyProjections.State.AVAILABLE) b.reviving.remove(p.id());
            }
            targets.add(new LobbyProjections.Target(slot.getValue(), p.lifeSequence(), state,
                    state == LobbyProjections.State.AVAILABLE ? bounty(b, r, p, player) : 1000,
                    state == LobbyProjections.State.AVAILABLE ? targetLocation(b, player) : null));
        }
        for (var observer : server.getPlayerList().getPlayers()) {
            if (!contextAvailable(observer, b, LobbyContext.DISPLAY)) { b.displays.remove(observer.getUUID()); continue; }
            var old = b.displays.get(observer.getUUID()); var view = projections.view(observer);
            var identity = r.participants().get(observer.getUUID());
            if (identity != null && identity.role() == ParticipantRole.MONSTER_LOBBY) {
                boolean cooling = identity.monsterCooldownNanos() > 0;
                if (Boolean.TRUE.equals(b.cooling.put(identity.id(), cooling)) && !cooling) {
                    tell(observer, "可以再次出击了"); sound(observer, true);
                }
            }
            if (old == null || old.player != observer || old.connection != observer.connection || !old.targets.equals(targets) || view.isEmpty()) {
                int page = view.map(LobbyProjections.View::page).orElse(0);
                projections.show(observer, r.roundId(), targets, page, (actor, selected) -> beginDeployment(actor, b, selected),
                        (actor, selected) -> dev.hardrush.observe.Observation.watch(actor, selected.target()));
                b.displays.put(observer.getUUID(), new Display(observer, observer.connection, List.copyOf(targets)));
            }
            if (server.getTickCount() % 5 == 0) {
                var p = r.participants().get(observer.getUUID());
                if (p != null && p.role().isExcluded()) projections.aimed(observer).ifPresent(selected -> LobbyInteractions.get(server).orElseThrow()
                        .hints(observer).offer("watch-target", dev.hardrush.core.lobby.LobbyHints.Priority.TARGET,
                                "目标：" + r.participants().get(selected.target()).name() + " · 左键观察", System.nanoTime() / 1_000_000 + 300));
                if (p != null && p.role() == ParticipantRole.MONSTER_LOBBY) {
                    var hints = LobbyInteractions.get(server).orElseThrow().hints(observer);
                    long now = System.nanoTime() / 1_000_000;
                    var attempt = b.searches.get(p.id());
                    if (attempt != null && attempt.search != null) {
                        var quarry = attempt.targetPlayer;
                        String name = Notices.key(quarry.getScoreboardName());
                        long waited = Math.max(0, b.searchClock.getAsLong() - attempt.started) / 1_000_000_000L;
                        String cancel = dev.hardrush.observe.Observation.observing(observer) ? " · 长按空格取消" : " · 再次右键取消";
                        String state;
                        if (SafeDeployment.atSea(quarry)) {
                            if (attempt.identity.kind() != MonsterRole.ZOMBIE) {
                                state = name + " 在海上，等靠岸后出击" + cancel;
                                if (!attempt.seaTipShown) {
                                    attempt.seaTipShown = true;
                                    Notices.hint(observer, "换成僵尸可以化身溺尸，从水下出击");
                                }
                            } else state = name + " 在海上，正在寻找水下出击位置";
                        } else state = quarry.onGround()
                                ? "正在寻找 " + name + " 附近的最佳出击位置 · " + Notices.key(waited) + " 秒" + cancel
                                : name + " 不在地面，等落地后出击" + cancel;
                        hints.offer("hunt", dev.hardrush.core.lobby.LobbyHints.Priority.PROGRESS, state, now + 300);
                    } else if (attempt != null || b.committing.contains(p.id()) || b.resumptions.containsKey(p.id()))
                        hints.offer("hunt", dev.hardrush.core.lobby.LobbyHints.Priority.PROGRESS, "正在出击……", now + 300);
                    else if (!dev.hardrush.core.round.MonsterCoins.enabled(r.rules()) || !p.revivalEntitled()) {
                        if (inCamp(observer)) {
                            String guidance = targets.stream().anyMatch(target -> target.state() == LobbyProjections.State.AVAILABLE)
                                    ? "当前角色：" + kindName(p.monsterRole()) + (p.monsterCooldownNanos() > 0
                                    ? " · 冷却 " + seconds(p.monsterCooldownNanos()) + " 秒 · 结束后右键目标模型追猎"
                                    : " · 右键目标模型追猎") : "当前暂无可追猎目标";
                            hints.offer("hunt-guide", dev.hardrush.core.lobby.LobbyHints.Priority.GUIDANCE, guidance, now + 300);
                        }
                        projections.aimed(observer).ifPresent(selected -> hints.offer("hunt-target", dev.hardrush.core.lobby.LobbyHints.Priority.TARGET,
                                "目标：" + r.participants().get(selected.target()).name() + " · 当前角色：" + kindName(p.monsterRole())
                                        + (p.monsterCooldownNanos() > 0 ? " · 冷却 " + seconds(p.monsterCooldownNanos()) + " 秒" : " · 右键追猎") + " · 左键观察", now + 300));
                        dev.hardrush.monster.MonsterParking.describe(p.wallet().parked()).ifPresent(parked -> hints.offer("parked",
                                dev.hardrush.core.lobby.LobbyHints.Priority.FACILITY, "上次出场已暂存：" + parked
                                        + (dev.hardrush.core.round.MonsterWorth.enabled(r.rules()) ? " · 身价 "
                                        + dev.hardrush.core.round.MonsterWorth.level(dev.hardrush.core.round.MonsterWorth.milliLevels(r.rules(), p.wallet())) + " 级" : "")
                                        + "；右键目标继续追猎", now + 300));
                    }
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
    /** The same multiplier damage pays, from the human's live life time and current dimension. */
    private static int bounty(Binding b, RoundSnapshot r, dev.hardrush.core.round.Participant p, ServerPlayer player) {
        if (player == null || !dev.hardrush.core.round.MonsterCoins.enabled(r.rules())) return 1000;
        var live = b.session.view().map(view -> view.participants().get(p.id())).orElse(p);
        var where = dimension(b, player.level());
        var kind = where.equals("lobby") ? MonsterDimension.OVERWORLD : MonsterDimension.valueOf(where.toUpperCase(Locale.ROOT));
        return dev.hardrush.core.round.MonsterCoins.bountyPermille(r.rules(), kind, live.lifeHumanNanos());
    }
    private static Component targetLocation(Binding b, ServerPlayer player) {
        String dimensionName = switch (dimension(b, player.level())) {
            case "overworld" -> "主世界"; case "nether" -> "下界"; case "end" -> "末地";
            default -> "未知维度";
        };
        var biome = player.level().getBiome(player.blockPosition()).unwrapKey()
                .map(key -> Component.translatableWithFallback(key.identifier().toLanguageKey("biome"), key.identifier().toString()))
                .orElseGet(() -> Component.literal("未知群系"));
        return Component.literal(dimensionName + " · ").append(biome);
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
            buttons.add(new LobbyMenus.Button(Items.TOTEM_OF_UNDYING, "剩余复生次数：" + p.revivalCharges() + " · 走上营地北侧复生祭坛站立 4 秒空装复生", "info", ""));

        }
        return buttons;
    }
    /** Why the altar cannot begin a rite for this camp body now; null when it can. */
    static String altarRefusal(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        var r = b == null ? null : b.session.snapshot().orElse(null);
        if (r == null || b.session.state() != RoundSession.State.OPEN || r.phase() == RoundPhase.RECOVERY_REQUIRED) return "恢复核验中，祭坛暂停";
        if (r.rules().mode() == dev.hardrush.core.config.GameMode.NORMAL) return "普通模式死亡后按原版重生，祭坛不开放";
        var p = r.participants().get(player.getUUID());
        if (p != null && p.role() == ParticipantRole.WITHDRAWN) return "你已退出本轮，下一轮恢复资格";
        if (p != null && p.role() == ParticipantRole.DISQUALIFIED) return "你已被取消本轮资格，下一轮恢复资格";
        if (r.phase() != RoundPhase.RUNNING && r.phase() != RoundPhase.RESCUE) return "祭坛沉寂中 · 极限挑战中死去的玩家会在这里复生";
        if (p == null || !p.role().isMonster()) return "祭坛只回应持有复生图腾的怪物 · 参赛请到集结区";
        if (p.role() != ParticipantRole.MONSTER_LOBBY || !p.online()) return "身份转换中，请稍候";
        if (b.session.competitionLocked() || !lobbyAvailable(player, b)) return "本轮正在保存 · 稍候即可复生";
        if (p.revivalCharges() - CampTransfers.pending(player, CampTransfers.Kind.REVIVAL) <= 0)
            return "尚无复生图腾 · 击杀在赛人类可获得，也可接住同伴丢来的图腾";
        var worlds = b.worlds.get();
        if (worlds == null || !worlds.ready() || !RoundWorldBundle.safeStanding(worlds.level(Level.OVERWORLD), worlds.spawn()))
            return "本轮出生点暂不安全，保留资格，请联系管理员";
        UUID id = player.getUUID();
        if (b.committing.contains(id) || b.resumptions.containsKey(id) || b.pending.containsKey(id)) return "身份提交中，请稍候";
        return null;
    }
    /** A completed altar rite: the same durable revival request; its reply is told to the player. */
    static void beginRevival(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        var round = b == null ? null : b.session.snapshot().orElse(null);
        if (round != null) request(player, round.roundId(), "revive", "");
    }
    static boolean reviveCommitting(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        return b != null && b.committing.contains(player.getUUID());
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
    /** T1 action bar; see {@link Notices}. */
    private static void tell(ServerPlayer player, String message) { Notices.hint(player, message); }
    /** T1 with the rejection sound. */
    private static void refuse(ServerPlayer player, String message) {
        Notices.refuse(player, message);
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
