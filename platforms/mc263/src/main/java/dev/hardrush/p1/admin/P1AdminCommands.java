package dev.hardrush.p1.admin;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.hardrush.core.admin.*;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.book.SafeBookText;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.record.*;
import dev.hardrush.core.round.RoundResult;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.p1.P1LobbySession;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** Operator commands recheck both permission and immutable target context at confirmation. */
public final class P1AdminCommands {
    private final Function<MinecraftServer, P1LobbySession> sessions;
    private final Function<MinecraftServer, Optional<String>> worldFailure;
    private final BiConsumer<MinecraftServer, String> retryWorld;
    private final Map<MinecraftServer, Controller> controllers = new IdentityHashMap<>();

    public P1AdminCommands(Function<MinecraftServer, P1LobbySession> sessions,
            Function<MinecraftServer, Optional<String>> worldFailure, BiConsumer<MinecraftServer, String> retryWorld) {
        this.sessions = sessions; this.worldFailure = worldFailure; this.retryWorld = retryWorld;
    }
    public void forget(MinecraftServer server) { controllers.remove(server); }

    public LiteralArgumentBuilder<CommandSourceStack> attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        return root.then(Commands.literal("admin").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(Commands.literal("status").executes(c -> inspect(c.getSource(), false)))
            .then(Commands.literal("rules").executes(c -> inspect(c.getSource(), true)))
            .then(withReason("start", AdminAction.START))
            .then(withReason("abort", AdminAction.ABORT))
            .then(withReason("retry-world", AdminAction.RETRY))
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
                            IntegerArgumentType.getInteger(c, "page"))))))
            .then(Commands.literal("confirm").then(Commands.argument("token", StringArgumentType.word())
                .executes(c -> confirm(c.getSource(), StringArgumentType.getString(c, "token"))))));
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
        var session = session(source);
        if (session == null || session.snapshot().isEmpty()) return 0;
        var round = session.snapshot().orElseThrow();
        String failure = worldFailure.apply(source.getServer()).orElse("");
        if (action == AdminAction.RETRY && failure.isEmpty()) return failure(source, "当前没有世界准备失败。日常配置不能清除状态故障。");
        String tag = currentTag(round) + (action == AdminAction.RETRY ? ":" + digest(failure) : "");
        String impact = switch (action) {
            case START -> "跳过剩余开局倒计时；至少一名在线就绪者且世界／档案已核验；本轮成绩标待核验。";
            case ABORT -> "立即停止比赛计时、保存中止原因并全员回大厅；安全释放旧世界后创建下一轮，不入通关榜。";
            case RETRY -> "只重试原轮次、原种子的失败准备任务，保留半完成文件；不会恢复数据库／档案故障。";
            default -> throw new IllegalArgumentException("Unsupported current-round action");
        };
        return issue(source, action, round.roundId(), tag, reason, impact, () -> {
            var now = session.snapshot().orElseThrow();
            return CompletableFuture.completedFuture(currentTag(now)
                    + (action == AdminAction.RETRY ? ":" + digest(worldFailure.apply(source.getServer()).orElse("")) : ""));
        }, confirmed -> {
            String actor = actor(confirmed).identity();
            if (action == AdminAction.START) session.adminStart(actor, reason, r -> reply(confirmed, r));
            else if (action == AdminAction.ABORT) session.adminAbort(actor, reason, r -> reply(confirmed, r));
            else {
                var audit = new AdminAudit(UUID.randomUUID(), round.roundId(), actor, "RETRY_WORLD", reason,
                        failure, "retry-requested;round=" + round.roundId() + ";seed-preserved", System.currentTimeMillis());
                session.auditWorldRetry(audit, () -> retryWorld.accept(source.getServer(), failure), r -> reply(confirmed, r));
            }
        });
    }

    public int nextMode(CommandSourceStack source, String value, String reason) {
        var session = session(source);
        if (session == null || session.snapshot().isEmpty()) return 0;
        GameMode mode;
        if (value.equals("normal")) mode = GameMode.NORMAL;
        else if (value.equals("hardcore")) mode = GameMode.HARDCORE;
        else return failure(source, "模式只接受 normal 或 hardcore。");
        var round = session.snapshot().orElseThrow();
        var before = session.nextRules().orElseThrow();
        String tag = round.roundId() + ":" + before.fingerprint();
        return issue(source, AdminAction.SET_RULES, round.roundId(), tag, reason,
                "只改变下轮模式 " + before.mode() + " → " + mode + "；当前规则与故障保持原值。下轮指纹 " + before.withMode(mode).fingerprint(),
                () -> CompletableFuture.completedFuture(session.snapshot().orElseThrow().roundId() + ":"
                    + session.nextRules().orElseThrow().fingerprint()),
                confirmed -> session.updateNextMode(mode, actor(confirmed).identity(), reason, r -> reply(confirmed, r)));
    }

    private int revise(CommandSourceStack source, String input, long millis, String winnerInput, String reason, boolean verify) {
        var session = session(source);
        if (session == null) return 0;
        UUID winner;
        try { winner = winnerInput.equals("none") ? null : UUID.fromString(winnerInput); AdminText.reason(reason); }
        catch (IllegalArgumentException e) { return failure(source, "胜者必须为UUID或none；原因必须为安全的非空文本。"); }
        resolve(session, input).whenComplete((found, error) -> {
            if (error != null || found.isEmpty()) { failure(source, "目标历史轮次不存在或查询失败。"); return; }
            var before = found.orElseThrow();
            if (!verify && before.result().kind() != RoundResult.Kind.COMPLETED) {
                failure(source, "失败／中止记录保留原结果，不能修成通关成绩。"); return;
            }
            RoundResult result = verify ? before.result() : new RoundResult(before.result().kind(), winner, reason);
            long nanos = verify ? before.challengeNanos() : Math.multiplyExact(millis, 1_000_000);
            String tag = before.roundId() + ":edition=" + before.edition();
            issue(source, verify ? AdminAction.VERIFY_RECORD : AdminAction.CORRECT_RECORD, before.roundId(), tag, reason,
                    "书版 " + before.edition() + " → " + (before.edition() + 1) + "；原耗时 " + before.challengeMillis()
                    + " ms / 胜者 " + before.result().winner() + " → " + nanos / 1_000_000 + " ms / " + result.winner()
                    + (verify ? "；根据原始可信首龙证据核验正常榜资格。" : "；修正后标待核验并退出正常榜。")
                    + "旧终局证据和已发书册保持原快照。",
                    () -> session.history(before.roundId()).thenApply(row -> row.map(r -> r.roundId() + ":edition=" + r.edition()).orElse("missing")),
                    confirmed -> {
                        var operation = UUID.randomUUID();
                        var revision = new HistoryRevision(operation, before.roundId(), before.edition(), result, nanos,
                                !verify, verify ? HistoryRevision.Action.VERIFY : HistoryRevision.Action.CORRECT);
                        var audit = new AdminAudit(operation, before.roundId(), actor(confirmed).identity(),
                                verify ? "VERIFY_RECORD" : "CORRECT_RECORD", reason, historyValue(before),
                                "edition=" + (before.edition() + 1) + ",result=" + result + ",challengeNanos=" + nanos + ",uncertain=" + !verify,
                                System.currentTimeMillis());
                        session.reviseHistory(revision, audit, r -> reply(confirmed, r));
                    });
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

    private int issue(CommandSourceStack source, AdminAction action, UUID roundId, String tag, String reason, String impact,
            Supplier<CompletableFuture<String>> context, Consumer<CommandSourceStack> execute) {
        if (!permitted(source, action)) return failure(source, "当前权限不足，管理操作未受理。");
        try {
            var controller = controllers.computeIfAbsent(source.getServer(), ignored -> new Controller());
            controller.prune();
            var ticket = controller.confirmations.issue(new AdminRequest(actor(source), action, roundId, tag, reason, impact));
            controller.pending.put(ticket.id(), new Pending(ticket, context, execute));
            say(source, "待确认 " + action + " · 具体轮次 " + roundId + "\n影响：" + safe(impact) + "\n原因：" + safe(reason)
                    + "\n120秒内由同一操作者执行 /hardrush admin confirm " + ticket.id());
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) { return failure(source, error.getMessage()); }
    }

    private int confirm(CommandSourceStack source, String input) {
        UUID token;
        try { token = UUID.fromString(input); }
        catch (IllegalArgumentException e) { return failure(source, "确认票据必须为UUID。"); }
        var controller = controllers.get(source.getServer());
        if (controller == null) return failure(source, "确认票据不存在或已过期。");
        controller.prune();
        var pending = controller.pending.get(token);
        if (pending == null) return failure(source, "确认票据不存在、已消费或已过期。");
        var request = pending.ticket().request();
        if (!permitted(source, request.action())) return failure(source, "当前权限不足，不能确认此操作。");
        pending.context().get().whenComplete((tag, error) -> {
            if (error != null) { failure(source, "无法重新核对目标，请稍后重新申请。"); return; }
            try {
                if (!permitted(source, request.action())) throw new IllegalStateException("权限已改变。");
                controller.confirmations.consume(token, actor(source), request.roundId(), tag);
                controller.pending.remove(token);
                pending.execute().accept(source);
            } catch (IllegalArgumentException | IllegalStateException e) { failure(source, "确认未执行：" + e.getMessage()); }
        });
        return 0;
    }

    private static boolean permitted(CommandSourceStack source, AdminAction action) {
        return Commands.hasPermission(action == AdminAction.VERIFY_RECORD || action == AdminAction.CORRECT_RECORD
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
    private static String currentTag(RoundSnapshot r) {
        String players = r.participants().values().stream().sorted(java.util.Comparator.comparing(p -> p.id().toString()))
                .map(p -> p.id() + ":" + p.role() + ":" + p.online() + ":" + p.ready() + ":" + p.lifeSequence())
                .collect(java.util.stream.Collectors.joining(";"));
        return r.roundId() + ":" + r.phase() + ":" + r.rules().fingerprint() + ":" + digest(String.valueOf(r.result())) + ":" + digest(players);
    }
    private static String digest(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
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
    private record Pending(AdminTicket ticket, Supplier<CompletableFuture<String>> context, Consumer<CommandSourceStack> execute) {}
    private static final class Controller {
        final AdminConfirmations confirmations = new AdminConfirmations(MonotonicClock.system());
        final Map<UUID, Pending> pending = new HashMap<>();
        void prune() { long now = System.nanoTime(); pending.values().removeIf(p -> now - p.ticket().issuedNanos() >= 120_000_000_000L); }
    }
}
