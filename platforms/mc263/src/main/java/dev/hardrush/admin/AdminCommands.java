package dev.hardrush.admin;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.hardrush.core.admin.*;
import dev.hardrush.core.book.SafeBookText;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.hunt.LateShelter;
import dev.hardrush.core.record.*;
import dev.hardrush.core.round.MonsterCoins;
import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.Refusal;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundResult;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.notice.Notices;
import dev.hardrush.round.RoundSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Operator commands (docs/plan/管理员命令与面板.md). Each submits directly with permission, state and transactional
 * audit checks; the reason is always optional. The panel ({@link AdminPanel}) calls the same operations, so a button
 * is never more powerful than its command. Every operation takes an {@code after} callback that runs on the main
 * thread once the outcome has been told to the source, for the panel to refresh.
 */
public final class AdminCommands {
    /** Audit reason when the operator gives none. */
    public static final String DEFAULT_REASON = "管理员操作";
    /** How many times a batch step waits for the previous step's commit before giving up. */
    private static final int BATCH_RETRIES = 200;

    public enum Item { TOTEM, COINS }

    private final Function<MinecraftServer, RoundSession> sessions;
    private final Function<MinecraftServer, Optional<String>> worldFailure;
    private final BiConsumer<MinecraftServer, String> retryWorld;

    public AdminCommands(Function<MinecraftServer, RoundSession> sessions,
            Function<MinecraftServer, Optional<String>> worldFailure, BiConsumer<MinecraftServer, String> retryWorld) {
        this.sessions = sessions; this.worldFailure = worldFailure; this.retryWorld = retryWorld;
    }

    @FunctionalInterface
    private interface Reasoned { int run(CommandContext<CommandSourceStack> context, String reason) throws CommandSyntaxException; }

    public LiteralArgumentBuilder<CommandSourceStack> attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        return root.then(Commands.literal("admin").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .executes(c -> panelOrStatus(c.getSource()))
            .then(Commands.literal("status").executes(c -> inspect(c.getSource(), false)))
            .then(Commands.literal("rules").executes(c -> inspect(c.getSource(), true)))
            .then(reasoned(Commands.literal("start").requires(Commands.hasPermission(Commands.LEVEL_ADMINS)), (c, r) -> current(c.getSource(), AdminAction.START, r)))
            .then(reasoned(Commands.literal("abort").requires(Commands.hasPermission(Commands.LEVEL_ADMINS)), (c, r) -> current(c.getSource(), AdminAction.ABORT, r)))
            .then(reasoned(Commands.literal("retry-world").requires(Commands.hasPermission(Commands.LEVEL_ADMINS)), (c, r) -> current(c.getSource(), AdminAction.RETRY, r)))
            .then(reasoned(Commands.literal("recheck-storage").requires(Commands.hasPermission(Commands.LEVEL_ADMINS)),
                (c, r) -> current(c.getSource(), AdminAction.RECHECK_STORAGE, r)))
            .then(reasoned(Commands.literal("skip").requires(Commands.hasPermission(Commands.LEVEL_ADMINS)), (c, r) -> skip(c.getSource(), r, null)))
            .then(Commands.literal("timer").requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(reasoned(Commands.argument("seconds", IntegerArgumentType.integer(0, 3600)),
                    (c, r) -> timer(c.getSource(), IntegerArgumentType.getInteger(c, "seconds"), r, null))))
            .then(reasoned(Commands.literal("pause").requires(Commands.hasPermission(Commands.LEVEL_ADMINS)), (c, r) -> pause(c.getSource(), true, r, null)))
            .then(reasoned(Commands.literal("resume").requires(Commands.hasPermission(Commands.LEVEL_ADMINS)), (c, r) -> pause(c.getSource(), false, r, null)))
            .then(adjustment("give", 1)).then(adjustment("take", -1))
            .then(Commands.literal("cooldown").requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("target", StringArgumentType.word()).suggests(targets(p -> p.role().isMonster()))
                    .then(reasoned(Commands.literal("clear"),
                        (c, r) -> cooldown(c.getSource(), StringArgumentType.getString(c, "target"), 0, r, null)))
                    .then(reasoned(Commands.argument("seconds", IntegerArgumentType.integer(0, 3600)),
                        (c, r) -> cooldown(c.getSource(), StringArgumentType.getString(c, "target"),
                            IntegerArgumentType.getInteger(c, "seconds"), r, null)))))
            .then(Commands.literal("unlock").requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("target", StringArgumentType.word()).suggests(targets(p -> p.role().isMonster()))
                    .then(reasoned(Commands.literal("all"),
                        (c, r) -> unlock(c.getSource(), StringArgumentType.getString(c, "target"), null, r, null)))
                    .then(reasoned(Commands.argument("human", StringArgumentType.word()).suggests(players(p -> p.role().isActiveHuman())),
                        (c, r) -> unlock(c.getSource(), StringArgumentType.getString(c, "target"),
                            StringArgumentType.getString(c, "human"), r, null)))))
            .then(Commands.literal("shelter").requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("target", StringArgumentType.word()).suggests(targets(p -> p.role().isActiveHuman()))
                    .then(reasoned(Commands.literal("end"),
                        (c, r) -> shelter(c.getSource(), StringArgumentType.getString(c, "target"), r, null)))))
            .then(Commands.literal("pull").requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests((c, b) -> {
                        c.getSource().getServer().getPlayerList().getPlayers().stream().map(p -> p.getScoreboardName())
                                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(b.getRemainingLowerCase())).forEach(b::suggest);
                        return b.buildFuture();
                    })
                    .executes(c -> pull(c.getSource(), StringArgumentType.getString(c, "player")))))
            .then(Commands.literal("recovery").requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .executes(c -> dev.hardrush.storage.RecoveryRuntime.request(c.getSource()))
                .then(Commands.literal("yes").then(Commands.argument("token", StringArgumentType.word())
                    .executes(c -> dev.hardrush.storage.RecoveryRuntime.decide(c.getSource(), "yes", StringArgumentType.getString(c, "token")))))
                .then(Commands.literal("no").then(Commands.argument("token", StringArgumentType.word())
                    .executes(c -> dev.hardrush.storage.RecoveryRuntime.decide(c.getSource(), "no", StringArgumentType.getString(c, "token"))))))
            .then(Commands.literal("close-recovery").requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .executes(c -> current(c.getSource(), AdminAction.CLOSE_RECOVERY, "管理员放弃故障轮并开始新轮"))
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                    .executes(c -> current(c.getSource(), AdminAction.CLOSE_RECOVERY, StringArgumentType.getString(c, "reason")))))
            .then(Commands.literal("next-mode").requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(reasoned(Commands.argument("mode", StringArgumentType.word())
                        .suggests((c, b) -> b.suggest("normal").suggest("hardcore").buildFuture()),
                    (c, r) -> nextMode(c.getSource(), StringArgumentType.getString(c, "mode"), r))))
            .then(Commands.literal("correct").requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .then(Commands.argument("round", StringArgumentType.word())
                    .then(Commands.argument("millis", LongArgumentType.longArg(0, Long.MAX_VALUE / 1_000_000))
                        .then(Commands.argument("winner", StringArgumentType.word())
                            .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(c -> revise(c.getSource(), StringArgumentType.getString(c, "round"),
                                    LongArgumentType.getLong(c, "millis"), StringArgumentType.getString(c, "winner"),
                                    StringArgumentType.getString(c, "reason"), false, null)))))))
            .then(Commands.literal("validate").requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .then(reasoned(Commands.argument("round", StringArgumentType.word()),
                    (c, r) -> revise(c.getSource(), StringArgumentType.getString(c, "round"), 0, "none", r, true, null))))
            .then(Commands.literal("audit")
                .then(Commands.argument("round", StringArgumentType.word())
                    .executes(c -> audit(c.getSource(), StringArgumentType.getString(c, "round"), 1))
                    .then(Commands.argument("page", IntegerArgumentType.integer(1, 10_000))
                        .executes(c -> audit(c.getSource(), StringArgumentType.getString(c, "round"),
                            IntegerArgumentType.getInteger(c, "page")))))));
    }

    /** {@code give|take <target> totem|coins <n> [reason]}. */
    private LiteralArgumentBuilder<CommandSourceStack> adjustment(String name, int sign) {
        var target = Commands.argument("target", StringArgumentType.word())
                .suggests(targets(p -> p.role().isMonster() || p.role().isActiveHuman()));
        for (var item : Item.values()) {
            target.then(Commands.literal(item == Item.TOTEM ? "totem" : "coins")
                .then(reasoned(Commands.argument("count", IntegerArgumentType.integer(1, 1_000_000)),
                    (c, r) -> adjust(c.getSource(), StringArgumentType.getString(c, "target"), item,
                        sign * IntegerArgumentType.getInteger(c, "count"), r, null))));
        }
        return Commands.literal(name).requires(Commands.hasPermission(Commands.LEVEL_ADMINS)).then(target);
    }

    /** Runs with {@link #DEFAULT_REASON}, or with a trailing free-text reason. */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T reasoned(T builder, Reasoned command) {
        return builder.executes(c -> command.run(c, DEFAULT_REASON))
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(c -> command.run(c, StringArgumentType.getString(c, "reason"))));
    }

    /** This round's participants matching {@code filter}, plus the group keywords. */
    private SuggestionProvider<CommandSourceStack> targets(Predicate<Participant> filter) {
        return (c, b) -> {
            for (var group : List.of("all-humans", "all-monsters", "all-players")) if (group.startsWith(b.getRemaining())) b.suggest(group);
            return players(filter).getSuggestions(c, b);
        };
    }

    private SuggestionProvider<CommandSourceStack> players(Predicate<Participant> filter) {
        return (c, b) -> {
            var session = sessions.apply(c.getSource().getServer());
            if (session != null) session.snapshot().ifPresent(r -> r.participants().values().stream().filter(filter)
                    .map(Participant::name).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(b.getRemainingLowerCase()))
                    .forEach(b::suggest));
            return b.buildFuture();
        };
    }

    // ---- Panel ---------------------------------------------------------------------------------------------------

    private int panelOrStatus(CommandSourceStack source) {
        var player = source.getPlayer();
        if (player != null && AdminPanel.open(player)) return 1;
        return inspect(source, false);
    }

    // ---- Round flow ----------------------------------------------------------------------------------------------

    private int inspect(CommandSourceStack source, boolean rules) {
        var session = session(source);
        if (session == null) return 0;
        say(source, session.status());
        if (rules) {
            session.snapshot().ifPresent(r -> say(source, "当轮不可变规则：\n" + r.rules().encode()));
            session.nextRules().ifPresent(r -> say(source, "下轮待生效规则：\n" + r.encode()));
        } else worldFailure.apply(source.getServer()).ifPresent(f -> say(source, "世界准备故障：" + safe(f)));
        return 1;
    }

    public int current(CommandSourceStack source, AdminAction action, String reason) {
        return current(source, action, reason, null);
    }

    public int current(CommandSourceStack source, AdminAction action, String reason, Runnable after) {
        if (!permitted(source, action)) return failure(source, "当前权限不足，管理操作未受理。", after);
        try { AdminText.reason(reason); }
        catch (IllegalArgumentException error) { return failure(source, error.getMessage(), after); }
        var session = session(source);
        if (session == null || session.snapshot().isEmpty()) return done(after, 0);
        String actor = actor(source).identity();
        switch (action) {
            case START -> session.adminStart(actor, reason, r -> reply(source, r, after));
            case ABORT -> session.adminAbort(actor, reason, r -> reply(source, r, after));
            case RECHECK_STORAGE -> session.recheckStorage(actor, reason, r -> reply(source, r, after));
            case CLOSE_RECOVERY -> { return done(after, dev.hardrush.storage.RecoveryRuntime.closeRecovery(source, session, reason)); }
            case RETRY -> {
                String failure = worldFailure.apply(source.getServer()).orElse("");
                if (failure.isEmpty()) return failure(source, "当前没有世界准备失败。日常配置不能清除状态故障。", after);
                var round = session.snapshot().orElseThrow();
                var audit = new AdminAudit(UUID.randomUUID(), round.roundId(), actor, "RETRY_WORLD", reason,
                        failure, "retry-requested;round=" + round.roundId() + ";seed-preserved", System.currentTimeMillis());
                session.auditWorldRetry(audit, () -> retryWorld.accept(source.getServer(), failure), r -> reply(source, r, after));
            }
            default -> throw new IllegalArgumentException("Unsupported current-round action");
        }
        return 1;
    }

    /** Skips whatever the round is waiting for, if anything can be skipped. */
    public int skip(CommandSourceStack source, String reason, Runnable after) {
        var round = liveSession(source, after);
        if (round == null) return 0;
        return switch (round.phase()) {
            case COUNTDOWN -> current(source, AdminAction.START, reason, after);
            case SETTLING, CELEBRATING -> timer(source, 0, reason, after);
            case WAITING -> failure(source, "还没有人准备，没有可以跳过的倒计时；可以用 /hardrush admin pull <玩家> 把人送进集结区。", after);
            case RUNNING, RESCUE -> failure(source, "挑战进行中，没有可以跳过的等待；要中止本轮请用 /hardrush admin abort。", after);
            case PREPARING, ROTATING -> failure(source, "正在准备世界，这段时间不能跳过。", after);
            case RECOVERY_REQUIRED -> failure(source, "恢复隔离中，请用 /hardrush admin recovery 处理。", after);
        };
    }

    public int timer(CommandSourceStack source, int seconds, String reason, Runnable after) {
        var round = liveSession(source, after);
        if (round == null || !checked(source, reason, after)) return 0;
        boolean rescue = round.phase() == RoundPhase.RESCUE;
        session(source).adminPhaseTimer(round.roundId(), seconds * 1_000_000_000L, actor(source).identity(), reason, r -> {
            reply(source, r, null);
            if (r.success() && rescue) announce(source.getServer(), null,
                    "管理员把救场剩余时间调整为 " + Notices.key(seconds) + " 秒");
            done(after, 0);
        });
        return 1;
    }

    public int pause(CommandSourceStack source, boolean paused, String reason, Runnable after) {
        var round = liveSession(source, after);
        if (round == null || !checked(source, reason, after)) return 0;
        session(source).adminPause(round.roundId(), paused, actor(source).identity(), reason, r -> {
            reply(source, r, null);
            if (r.success()) {
                var server = source.getServer();
                Notices.broadcast(server, paused ? "**比赛已暂停** · 管理员暂停了比赛，计时已冻结" : "**比赛继续** · 计时恢复");
                for (var player : server.getPlayerList().getPlayers()) {
                    Notices.title(player, paused ? "比赛已暂停" : "比赛继续", paused ? "计时已冻结" : "计时恢复");
                }
            }
            done(after, 0);
        });
        return 1;
    }

    public int pull(CommandSourceStack source, String name) {
        if (!Commands.hasPermission(Commands.LEVEL_ADMINS).test(source)) return failure(source, "当前权限不足，管理操作未受理。", null);
        var player = source.getServer().getPlayerList().getPlayerByName(name);
        if (player == null) return failure(source, "玩家不在线。", null);
        var refusal = dev.hardrush.lobby.HumanEntry.pull(player);
        if (refusal.isPresent()) return failure(source, refusal.get(), null);
        say(source, player.getScoreboardName() + " 已送进集结区；之后按正常流程准备或中途加入。");
        Notices.operators(source.getServer(), actor(source).displayName() + " 把 " + player.getScoreboardName() + " 送进了集结区");
        return 1;
    }

    // ---- Per-participant adjustments -----------------------------------------------------------------------------

    /** Gives ({@code delta > 0}) or takes back revival totems or monster coins. */
    public int adjust(CommandSourceStack source, String input, Item item, int delta, String reason, Runnable after) {
        var round = liveSession(source, after);
        if (round == null || !checked(source, reason, after)) return 0;
        Predicate<Participant> eligible = item == Item.TOTEM
                ? p -> (p.role().isMonster() || p.role().isActiveHuman()) && p.lifeSequence() > 0 && p.joinedAtChallengeNanos() >= 0
                : p -> p.role().isMonster();
        if (item == Item.TOTEM && round.rules().mode() != GameMode.HARDCORE) return failure(source, "普通模式没有复生图腾。", after);
        if (item == Item.COINS && !MonsterCoins.enabled(round.rules())) return failure(source, "本轮没有怪物币。", after);
        var targets = resolve(source, round, input, eligible,
                item == Item.TOTEM ? "只能调整本轮已参赛的人类或怪物的复生图腾。" : "只有怪物持有怪物币。", after);
        if (targets == null) return 0;
        var session = session(source);
        String actor = actor(source).identity();
        batch(source, targets, (p, done) -> {
            var now = session.snapshot().map(r -> r.participants().get(p.id())).orElse(p);
            if (item == Item.TOTEM) session.adminRevival(round.roundId(), p.id(), now.lifeSequence(), now.revivalCharges(), delta, actor, reason, done);
            else session.adminCoins(round.roundId(), p.id(), now.lifeSequence(), now.wallet().coins(), delta, actor, reason, done);
        }, p -> {
            int n = Math.abs(delta);
            String what = item == Item.TOTEM ? " 个复生图腾" : " 枚怪物币";
            if (delta < 0) {
                var left = session.snapshot().map(r -> r.participants().get(p.id())).orElse(p);
                tell(source.getServer(), p, item == Item.TOTEM
                        ? "管理员收回了 " + Notices.key(n) + " 个复生图腾 · 还剩 " + Notices.key(left.revivalCharges()) + " 个"
                        : Notices.key("-" + n) + " " + Notices.COINS + "（管理员收回） · 共 " + Notices.key(left.wallet().coins()));
            }
            announce(source.getServer(), p.id(), "管理员" + (delta > 0 ? "给 " : "收回了 ") + Notices.name(p.name())
                    + (delta > 0 ? " 发放了 " : " 的 ") + Notices.key(n) + what);
        }, after);
        return 1;
    }

    public int cooldown(CommandSourceStack source, String input, int seconds, String reason, Runnable after) {
        var round = liveSession(source, after);
        if (round == null || !checked(source, reason, after)) return 0;
        var targets = resolve(source, round, input, p -> p.role().isMonster(), "只有怪物有出击冷却。", after);
        if (targets == null) return 0;
        var session = session(source);
        String actor = actor(source).identity();
        batch(source, targets, (p, done) -> session.adminDeployCooldown(round.roundId(), p.id(),
                p.lifeSequence(), seconds * 1_000_000_000L, actor, reason, done), p -> {
            tell(source.getServer(), p, seconds == 0 ? "管理员清除了你的出击冷却" : "管理员把你的出击冷却设为 " + Notices.key(seconds) + " 秒");
            announce(source.getServer(), p.id(), "管理员" + (seconds == 0 ? "清除了 " + Notices.name(p.name()) + " 的出击冷却"
                    : "把 " + Notices.name(p.name()) + " 的出击冷却设为 " + Notices.key(seconds) + " 秒"));
        }, after);
        return 1;
    }

    /** {@code humanInput} null lifts every lock; otherwise only that human's. */
    public int unlock(CommandSourceStack source, String input, String humanInput, String reason, Runnable after) {
        var round = liveSession(source, after);
        if (round == null || !checked(source, reason, after)) return 0;
        UUID human = null;
        if (humanInput != null) {
            var humans = round.participants().values().stream().filter(p -> matches(p, humanInput)).toList();
            if (humans.size() != 1) return failure(source, "人类玩家不存在或名称不唯一，请填写本轮玩家名称或 UUID。", after);
            human = humans.getFirst().id();
        }
        UUID only = human;
        var targets = resolve(source, round, input,
                p -> p.role().isMonster() && (only == null ? !p.huntLocks().isEmpty() : p.huntLockNanos(only) > 0),
                "这名怪物没有对应的击败锁定。", after);
        if (targets == null) return 0;
        var session = session(source);
        String actor = actor(source).identity();
        batch(source, targets, (p, done) -> session.adminHuntUnlock(round.roundId(), p.id(), only, actor, reason, done), p -> {
            tell(source.getServer(), p, "管理员清除了你的击败锁定");
            announce(source.getServer(), p.id(), "管理员清除了 " + Notices.name(p.name()) + " 的击败锁定");
        }, after);
        return 1;
    }

    /** Ends 初入荒野 and the altar's blessing; at least one of them must be there to end. */
    public int shelter(CommandSourceStack source, String input, String reason, Runnable after) {
        var round = liveSession(source, after);
        if (round == null || !checked(source, reason, after)) return 0;
        var server = source.getServer();
        var view = session(source).view().orElse(round);
        Predicate<Participant> sheltered = p -> {
            if (!p.role().isActiveHuman()) return false;
            var player = server.getPlayerList().getPlayer(p.id());
            var live = view.participants().getOrDefault(p.id(), p);
            return LateShelter.sheltered(round.rules(), live) || player != null && dev.hardrush.lobby.RevivalAltar.blessed(player);
        };
        var targets = resolve(source, round, input, sheltered, "这名玩家现在没有初入荒野或图腾之力庇护。", after);
        if (targets == null) return 0;
        var session = session(source);
        String actor = actor(source).identity();
        batch(source, targets, (p, done) -> session.adminEndShelter(round.roundId(), p.id(), p.lifeSequence(), actor, reason, done), p -> {
            var player = server.getPlayerList().getPlayer(p.id());
            if (player != null) dev.hardrush.lobby.RevivalAltar.liftBlessing(player);
            tell(server, p, "管理员结束了你的庇护，现在可以被追猎");
            announce(server, p.id(), "管理员结束了 " + Notices.name(p.name()) + " 的庇护");
        }, after);
        return 1;
    }

    // ---- Next round and records ----------------------------------------------------------------------------------

    public int nextMode(CommandSourceStack source, String value, String reason) {
        if (!permitted(source, AdminAction.SET_RULES)) return failure(source, "当前权限不足，管理操作未受理。", null);
        try { AdminText.reason(reason); }
        catch (IllegalArgumentException error) { return failure(source, error.getMessage(), null); }
        var session = session(source);
        if (session == null || session.snapshot().isEmpty()) return 0;
        GameMode mode;
        if (value.equals("normal")) mode = GameMode.NORMAL;
        else if (value.equals("hardcore")) mode = GameMode.HARDCORE;
        else return failure(source, "模式只接受 normal 或 hardcore。", null);
        session.updateNextMode(mode, actor(source).identity(), reason, r -> reply(source, r, null));
        return 1;
    }

    /** {@code verify} re-checks a trusted first-dragon winner; otherwise the owner corrects time and winner. */
    public int revise(CommandSourceStack source, String input, long millis, String winnerInput, String reason, boolean verify,
            Runnable after) {
        var action = verify ? AdminAction.VERIFY_RECORD : AdminAction.CORRECT_RECORD;
        if (!permitted(source, action)) return failure(source, "当前权限不足，管理操作未受理。", after);
        var session = session(source);
        if (session == null) return done(after, 0);
        UUID winner;
        try { winner = winnerInput.equals("none") ? null : UUID.fromString(winnerInput); AdminText.reason(reason); }
        catch (IllegalArgumentException e) { return failure(source, "胜者必须为UUID或none；原因必须为安全的非空文本。", after); }
        resolve(session, input).whenComplete((found, error) -> {
            if (error != null || found.isEmpty()) { failure(source, "目标历史轮次不存在或查询失败。", after); return; }
            if (!permitted(source, action)) { failure(source, "当前权限不足，管理操作未受理。", after); return; }
            var before = found.orElseThrow();
            if (!verify && before.result().kind() != RoundResult.Kind.COMPLETED) {
                failure(source, "失败／中止记录保留原结果，不能修成通关成绩。", after); return;
            }
            RoundResult result = verify ? before.result() : new RoundResult(before.result().kind(), winner, reason);
            long nanos = verify ? before.challengeNanos() : Math.multiplyExact(millis, 1_000_000);
            var operation = UUID.randomUUID();
            var revision = new HistoryRevision(operation, before.roundId(), before.edition(), result, nanos,
                    !verify, verify ? HistoryRevision.Action.VERIFY : HistoryRevision.Action.CORRECT);
            var audit = new AdminAudit(operation, before.roundId(), actor(source).identity(),
                    verify ? "VERIFY_RECORD" : "CORRECT_RECORD", reason, historyValue(before),
                    "edition=" + (before.edition() + 1) + ",result=" + result + ",challengeNanos=" + nanos + ",uncertain=" + !verify,
                    System.currentTimeMillis());
            session.reviseHistory(revision, audit, r -> reply(source, r, after));
        });
        return 0;
    }

    private int audit(CommandSourceStack source, String input, int page) {
        var session = session(source);
        if (session == null) return 0;
        CompletableFuture<Optional<UUID>> target = input.equals("all") ? CompletableFuture.completedFuture(Optional.empty())
                : input.equals("current") ? CompletableFuture.completedFuture(session.snapshot().map(RoundSnapshot::roundId))
                : resolve(session, input).thenApply(found -> found.map(RoundHistory::roundId));
        target.thenCompose(id -> {
            if (id.isEmpty() && !input.equals("all")) return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown round"));
            return session.audits(id.orElse(null), 20, (page - 1) * 20);
        }).whenComplete((rows, error) -> {
            if (error != null) { failure(source, "审计查询失败，请核对轮次。可用 all 或 current。", null); return; }
            say(source, "管理审计 · 第 " + page + " 页（最多20项）");
            for (var row : rows) {
                say(source, row.operationId() + " · " + row.action() + " · 操作者 " + safe(row.actor())
                        + " · 轮次 " + row.roundId() + " · UTC " + java.time.Instant.ofEpochMilli(row.occurredAtEpochMillis())
                        + " · 原因 " + safe(row.reason()));
                say(source, "前：" + safe(row.beforeValue()) + "\n后：" + safe(row.afterValue()));
            }
            if (rows.isEmpty()) say(source, "暂无管理审计。");
        });
        return 0;
    }

    // ---- Shared plumbing -----------------------------------------------------------------------------------------

    /** The committed round, once the operator may act on it at all; reports and returns null otherwise. */
    private RoundSnapshot liveSession(CommandSourceStack source, Runnable after) {
        if (!Commands.hasPermission(Commands.LEVEL_ADMINS).test(source)) { failure(source, "当前权限不足，管理操作未受理。", after); return null; }
        var session = session(source);
        if (session == null || session.snapshot().isEmpty()) { done(after, 0); return null; }
        return session.snapshot().orElseThrow();
    }

    private static boolean checked(CommandSourceStack source, String reason, Runnable after) {
        try { AdminText.reason(reason); return true; }
        catch (IllegalArgumentException error) { failure(source, error.getMessage(), after); return false; }
    }

    private static boolean matches(Participant p, String input) {
        return p.id().toString().equalsIgnoreCase(input) || p.name().equalsIgnoreCase(input);
    }

    /**
     * One named participant (name or UUID) or a group keyword, narrowed to those {@code eligible}. A named player who
     * is not eligible, or a group with nobody eligible, is refused as a whole.
     */
    private static List<Participant> resolve(CommandSourceStack source, RoundSnapshot round, String input,
            Predicate<Participant> eligible, String ineligible, Runnable after) {
        Predicate<Participant> group = switch (input.toLowerCase(Locale.ROOT)) {
            case "all-humans" -> p -> p.role().isActiveHuman();
            case "all-monsters" -> p -> p.role().isMonster();
            case "all-players" -> p -> p.role().isActiveHuman() || p.role().isMonster();
            default -> null;
        };
        if (group != null) {
            var found = round.participants().values().stream().filter(group.and(eligible)).toList();
            if (found.isEmpty()) { failure(source, "分组里没有符合条件的玩家：" + ineligible, after); return null; }
            return found;
        }
        var named = round.participants().values().stream().filter(p -> matches(p, input)).toList();
        if (named.size() != 1) { failure(source, "玩家不存在或名称不唯一，请填写本轮玩家名称或 UUID。", after); return null; }
        if (!eligible.test(named.getFirst())) { failure(source, ineligible, after); return null; }
        return named;
    }

    /**
     * Submits one step per participant, each after the previous one committed (a later step waits on the commit queue
     * for up to {@link #BATCH_RETRIES} ticks). Every outcome is reported with the participant's name;
     * {@code succeeded} tells the players about each success.
     */
    private static void batch(CommandSourceStack source, List<Participant> targets,
            BiConsumer<Participant, Consumer<RoundSession.Reply>> step, Consumer<Participant> succeeded, Runnable after) {
        batch(source, new ArrayList<>(targets), 0, step, succeeded, after);
    }

    private static void batch(CommandSourceStack source, List<Participant> left, int attempt,
            BiConsumer<Participant, Consumer<RoundSession.Reply>> step, Consumer<Participant> succeeded, Runnable after) {
        if (left.isEmpty()) { done(after, 0); return; }
        var p = left.getFirst();
        // Only a later step waits; the first answers at once, like any single command.
        boolean later = attempt > 0;
        step.accept(p, reply -> {
            if (!reply.success() && reply.refusal() == Refusal.BUSY && attempt < BATCH_RETRIES && later) {
                NextTick.schedule(source.getServer(), () -> batch(source, left, attempt + 1, step, succeeded, after));
                return;
            }
            if (reply.success()) { say(source, p.name() + "：" + reply.message()); succeeded.accept(p); }
            else failure(source, p.name() + "：" + reply.message(), null);
            left.removeFirst();
            if (left.isEmpty()) { done(after, 0); return; }
            // The next step waits for this commit's queued events, so it starts on the next tick.
            NextTick.schedule(source.getServer(), () -> batch(source, left, 1, step, succeeded, after));
        });
    }

    /** T1 to the participant concerned, if online. */
    private static void tell(MinecraftServer server, Participant p, String markup) {
        var player = server.getPlayerList().getPlayer(p.id());
        if (player != null) Notices.hint(player, markup);
    }

    /** T1 to everyone else racing in this round, so no adjustment is ever made in secret. */
    private void announce(MinecraftServer server, UUID except, String markup) {
        var session = sessions.apply(server);
        var round = session == null ? null : session.snapshot().orElse(null);
        if (round == null) return;
        for (var p : round.participants().values()) {
            if (p.id().equals(except) || !(p.role().isActiveHuman() || p.role().isMonster()
                    || p.role() == ParticipantRole.HUMAN_RESPAWNING)) continue;
            var player = server.getPlayerList().getPlayer(p.id());
            if (player != null) Notices.hint(player, markup);
        }
    }

    private static boolean permitted(CommandSourceStack source, AdminAction action) {
        return Commands.hasPermission(action == AdminAction.VERIFY_RECORD || action == AdminAction.CORRECT_RECORD || action == AdminAction.CLOSE_RECOVERY
                ? Commands.LEVEL_OWNERS : Commands.LEVEL_ADMINS).test(source);
    }
    private RoundSession session(CommandSourceStack source) {
        var session = sessions.apply(source.getServer());
        if (session == null) failure(source, "比赛模式尚未启用。", null);
        return session;
    }
    static AdminActor actor(CommandSourceStack source) {
        return new AdminActor(source.getEntity() == null ? "console:" + source.getTextName()
                : "entity:" + source.getEntity().getUUID(), SafeBookText.literal(source.getTextName(), 64));
    }
    private static String historyValue(RoundHistory r) { return "edition=" + r.edition() + ",result=" + r.result() + ",challengeNanos=" + r.challengeNanos() + ",uncertain=" + r.timingUncertain() + ",trusted=" + r.trustedWinner(); }
    private static CompletableFuture<Optional<RoundHistory>> resolve(RoundSession session, String input) {
        try { return input.matches("[1-9][0-9]{0,18}") ? session.historyBySequence(Long.parseLong(input)) : session.history(UUID.fromString(input)); }
        catch (IllegalArgumentException error) { return CompletableFuture.failedFuture(error); }
    }
    private static String safe(String text) { return SafeBookText.literal(text, 2048); }
    private static int done(Runnable after, int result) { if (after != null) after.run(); return result; }
    private static void say(CommandSourceStack source, String text) { source.sendSuccess(() -> Component.literal(text), false); }
    private static int failure(CommandSourceStack source, String text, Runnable after) {
        source.sendFailure(Component.literal(safe(text)));
        return done(after, 0);
    }
    private static void reply(CommandSourceStack source, RoundSession.Reply reply, Runnable after) {
        if (reply.success()) say(source, reply.message()); else source.sendFailure(Component.literal(safe(reply.message())));
        done(after, 0);
    }
}
