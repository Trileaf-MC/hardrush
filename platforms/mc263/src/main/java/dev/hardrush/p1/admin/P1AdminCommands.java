package dev.hardrush.p1.admin;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.hardrush.core.admin.*;
import dev.hardrush.core.book.SafeBookText;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.record.*;
import dev.hardrush.core.round.RoundResult;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.p1.P1LobbySession;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** Operator commands submit directly with permission, state and transactional audit checks. */
public final class P1AdminCommands {
    private final Function<MinecraftServer, P1LobbySession> sessions;
    private final Function<MinecraftServer, Optional<String>> worldFailure;
    private final BiConsumer<MinecraftServer, String> retryWorld;

    public P1AdminCommands(Function<MinecraftServer, P1LobbySession> sessions,
            Function<MinecraftServer, Optional<String>> worldFailure, BiConsumer<MinecraftServer, String> retryWorld) {
        this.sessions = sessions; this.worldFailure = worldFailure; this.retryWorld = retryWorld;
    }

    public LiteralArgumentBuilder<CommandSourceStack> attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        return root.then(Commands.literal("admin").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(Commands.literal("status").executes(c -> inspect(c.getSource(), false)))
            .then(Commands.literal("rules").executes(c -> inspect(c.getSource(), true)))
            .then(withReason("start", AdminAction.START))
            .then(withReason("abort", AdminAction.ABORT))
            .then(withReason("retry-world", AdminAction.RETRY))
            .then(withReason("recheck-storage", AdminAction.RECHECK_STORAGE))
            .then(Commands.literal("recovery").requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .executes(c -> dev.hardrush.p1.storage.P1RecoveryRuntime.request(c.getSource()))
                .then(Commands.literal("yes").then(Commands.argument("token", StringArgumentType.word())
                    .executes(c -> dev.hardrush.p1.storage.P1RecoveryRuntime.decide(c.getSource(), "yes", StringArgumentType.getString(c, "token")))))
                .then(Commands.literal("no").then(Commands.argument("token", StringArgumentType.word())
                    .executes(c -> dev.hardrush.p1.storage.P1RecoveryRuntime.decide(c.getSource(), "no", StringArgumentType.getString(c, "token"))))))
            .then(Commands.literal("grant-revival").requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests((c, b) -> {
                        var session = sessions.apply(c.getSource().getServer());
                        if (session != null) session.snapshot().ifPresent(r -> r.participants().values().stream()
                                .filter(p -> p.role().isMonster() || p.role().isActiveHuman())
                                .map(p -> p.name()).filter(n -> n.toLowerCase(java.util.Locale.ROOT)
                                        .startsWith(b.getRemainingLowerCase())).forEach(b::suggest));
                        return b.buildFuture();
                    })
                    .then(Commands.argument("count", IntegerArgumentType.integer(1))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                            .executes(c -> grantRevival(c.getSource(), StringArgumentType.getString(c, "player"),
                                IntegerArgumentType.getInteger(c, "count"), StringArgumentType.getString(c, "reason")))))))
            .then(Commands.literal("close-recovery").requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .executes(c -> current(c.getSource(), AdminAction.CLOSE_RECOVERY, "管理员放弃故障轮并开始新轮"))
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                    .executes(c -> current(c.getSource(), AdminAction.CLOSE_RECOVERY, StringArgumentType.getString(c, "reason")))))
            .then(Commands.literal("next-mode").requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("mode", StringArgumentType.word())
                    .suggests((c, b) -> b.suggest("normal").suggest("hardcore").buildFuture())
                    .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(c -> nextMode(c.getSource(), StringArgumentType.getString(c, "mode"),
                            StringArgumentType.getString(c, "reason"))))))
            .then(Commands.literal("correct").requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .then(Commands.argument("round", StringArgumentType.word())
                    .then(Commands.argument("millis", LongArgumentType.longArg(0, Long.MAX_VALUE / 1_000_000))
                        .then(Commands.argument("winner", StringArgumentType.word())
                            .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(c -> revise(c.getSource(), StringArgumentType.getString(c, "round"),
                                    LongArgumentType.getLong(c, "millis"), StringArgumentType.getString(c, "winner"),
                                    StringArgumentType.getString(c, "reason"), false)))))))
            .then(Commands.literal("validate").requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .then(Commands.argument("round", StringArgumentType.word())
                    .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(c -> revise(c.getSource(), StringArgumentType.getString(c, "round"), 0, "none",
                            StringArgumentType.getString(c, "reason"), true)))))
            .then(Commands.literal("audit")
                .then(Commands.argument("round", StringArgumentType.word())
                    .executes(c -> audit(c.getSource(), StringArgumentType.getString(c, "round"), 1))
                    .then(Commands.argument("page", IntegerArgumentType.integer(1, 10_000))
                        .executes(c -> audit(c.getSource(), StringArgumentType.getString(c, "round"),
                            IntegerArgumentType.getInteger(c, "page")))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> withReason(String name, AdminAction action) {
        return Commands.literal(name).requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(c -> current(c.getSource(), action, StringArgumentType.getString(c, "reason"))));
    }

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
        if (!permitted(source, action)) return failure(source, "当前权限不足，管理操作未受理。");
        try { AdminText.reason(reason); }
        catch (IllegalArgumentException error) { return failure(source, error.getMessage()); }
        var session = session(source);
        if (session == null || session.snapshot().isEmpty()) return 0;
        String actor = actor(source).identity();
        switch (action) {
            case START -> session.adminStart(actor, reason, r -> reply(source, r));
            case ABORT -> session.adminAbort(actor, reason, r -> reply(source, r));
            case RECHECK_STORAGE -> session.recheckStorage(actor, reason, r -> reply(source, r));
            case CLOSE_RECOVERY -> { return dev.hardrush.p1.storage.P1RecoveryRuntime.closeRecovery(source, session, reason); }
            case RETRY -> {
                String failure = worldFailure.apply(source.getServer()).orElse("");
                if (failure.isEmpty()) return failure(source, "当前没有世界准备失败。日常配置不能清除状态故障。");
                var round = session.snapshot().orElseThrow();
                var audit = new AdminAudit(UUID.randomUUID(), round.roundId(), actor, "RETRY_WORLD", reason,
                        failure, "retry-requested;round=" + round.roundId() + ";seed-preserved", System.currentTimeMillis());
                session.auditWorldRetry(audit, () -> retryWorld.accept(source.getServer(), failure), r -> reply(source, r));
            }
            default -> throw new IllegalArgumentException("Unsupported current-round action");
        }
        return 1;
    }

    public int nextMode(CommandSourceStack source, String value, String reason) {
        if (!permitted(source, AdminAction.SET_RULES)) return failure(source, "当前权限不足，管理操作未受理。");
        try { AdminText.reason(reason); }
        catch (IllegalArgumentException error) { return failure(source, error.getMessage()); }
        var session = session(source);
        if (session == null || session.snapshot().isEmpty()) return 0;
        GameMode mode;
        if (value.equals("normal")) mode = GameMode.NORMAL;
        else if (value.equals("hardcore")) mode = GameMode.HARDCORE;
        else return failure(source, "模式只接受 normal 或 hardcore。");
        session.updateNextMode(mode, actor(source).identity(), reason, r -> reply(source, r));
        return 1;
    }

    private int grantRevival(CommandSourceStack source, String input, int count, String reason) {
        if (!permitted(source, AdminAction.GRANT_REVIVAL)) return failure(source, "当前权限不足，不能发放复生次数。");
        var session = session(source);
        if (session == null || session.snapshot().isEmpty()) return 0;
        var round = session.snapshot().orElseThrow();
        if (session.state() != P1LobbySession.State.OPEN || session.competitionLocked()
                || round.rules().mode() != GameMode.HARDCORE
                || round.phase() != dev.hardrush.core.round.RoundPhase.RUNNING
                    && round.phase() != dev.hardrush.core.round.RoundPhase.RESCUE) {
            return failure(source, "仅可在正常开放的极限挑战或救场期间发放复生次数。");
        }
        var matches = round.participants().values().stream()
                .filter(p -> p.id().toString().equalsIgnoreCase(input) || p.name().equalsIgnoreCase(input)).toList();
        if (matches.size() != 1) return failure(source, "玩家不存在或名称不唯一，请填写本轮玩家名称或 UUID。");
        var target = matches.getFirst();
        if ((!target.role().isMonster() && !target.role().isActiveHuman()) || target.lifeSequence() == 0
                || target.joinedAtChallengeNanos() < 0) return failure(source, "只能向本轮已参赛的人类或怪物发放复生次数。");
        if (count <= 0 || count > Integer.MAX_VALUE - target.revivalCharges()) {
            return failure(source, "累计复生次数不能超过 " + Integer.MAX_VALUE + "。");
        }
        session.adminGrantRevival(round.roundId(), target.id(), target.lifeSequence(), target.revivalCharges(), count,
                actor(source).identity(), reason, result -> {
                    if (result.success()) say(source, target.name() + "（" + target.id() + "）：" + result.message());
                    else failure(source, result.message());
                });
        return 1;
    }

    private int revise(CommandSourceStack source, String input, long millis, String winnerInput, String reason, boolean verify) {
        var action = verify ? AdminAction.VERIFY_RECORD : AdminAction.CORRECT_RECORD;
        if (!permitted(source, action)) return failure(source, "当前权限不足，管理操作未受理。");
        var session = session(source);
        if (session == null) return 0;
        UUID winner;
        try { winner = winnerInput.equals("none") ? null : UUID.fromString(winnerInput); AdminText.reason(reason); }
        catch (IllegalArgumentException e) { return failure(source, "胜者必须为UUID或none；原因必须为安全的非空文本。"); }
        resolve(session, input).whenComplete((found, error) -> {
            if (error != null || found.isEmpty()) { failure(source, "目标历史轮次不存在或查询失败。"); return; }
            if (!permitted(source, action)) { failure(source, "当前权限不足，管理操作未受理。"); return; }
            var before = found.orElseThrow();
            if (!verify && before.result().kind() != RoundResult.Kind.COMPLETED) {
                failure(source, "失败／中止记录保留原结果，不能修成通关成绩。"); return;
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
            session.reviseHistory(revision, audit, r -> reply(source, r));
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
            if (error != null) { failure(source, "审计查询失败，请核对轮次。可用 all 或 current。"); return; }
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

    private static boolean permitted(CommandSourceStack source, AdminAction action) {
        return Commands.hasPermission(action == AdminAction.VERIFY_RECORD || action == AdminAction.CORRECT_RECORD || action == AdminAction.CLOSE_RECOVERY
                ? Commands.LEVEL_OWNERS : Commands.LEVEL_ADMINS).test(source);
    }
    private P1LobbySession session(CommandSourceStack source) {
        var session = sessions.apply(source.getServer());
        if (session == null) failure(source, "P1 尚未启用。");
        return session;
    }
    private static AdminActor actor(CommandSourceStack source) {
        return new AdminActor(source.getEntity() == null ? "console:" + source.getTextName()
                : "entity:" + source.getEntity().getUUID(), SafeBookText.literal(source.getTextName(), 64));
    }
    private static String historyValue(RoundHistory r) { return "edition=" + r.edition() + ",result=" + r.result() + ",challengeNanos=" + r.challengeNanos() + ",uncertain=" + r.timingUncertain() + ",trusted=" + r.trustedWinner(); }
    private static CompletableFuture<Optional<RoundHistory>> resolve(P1LobbySession session, String input) {
        try { return input.matches("[1-9][0-9]{0,18}") ? session.historyBySequence(Long.parseLong(input)) : session.history(UUID.fromString(input)); }
        catch (IllegalArgumentException error) { return CompletableFuture.failedFuture(error); }
    }
    private static String safe(String text) { return SafeBookText.literal(text, 2048); }
    private static void say(CommandSourceStack source, String text) { source.sendSuccess(() -> Component.literal(text), false); }
    private static int failure(CommandSourceStack source, String text) { source.sendFailure(Component.literal(safe(text))); return 0; }
    private static void reply(CommandSourceStack source, P1LobbySession.Reply reply) { if (reply.success()) say(source, reply.message()); else failure(source, reply.message()); }
}
