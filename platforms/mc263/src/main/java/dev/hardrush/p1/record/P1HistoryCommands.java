package dev.hardrush.p1.record;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.hardrush.core.record.RoundHistory;
import dev.hardrush.core.record.RoundTab;
import dev.hardrush.core.book.SafeBookText;
import dev.hardrush.p1.P1LobbySession;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** Read-only, bounded commands. All displayed input is literal text, never a generated command. */
public final class P1HistoryCommands {
    private static final int PAGE_SIZE = 20;
    @FunctionalInterface public interface BookRequest { void receive(CommandSourceStack source, UUID roundId, int volume); }
    private P1HistoryCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> attach(LiteralArgumentBuilder<CommandSourceStack> root,
            Function<MinecraftServer, P1LobbySession> sessions, BookRequest books) {
        return root.then(Commands.literal("history")
                .executes(context -> recent(context.getSource(), sessions, 1))
                .then(Commands.literal("page").then(Commands.argument("page", IntegerArgumentType.integer(1, 10_000))
                        .executes(context -> recent(context.getSource(), sessions, IntegerArgumentType.getInteger(context, "page")))))
                .then(Commands.argument("round", StringArgumentType.word())
                        .executes(context -> detail(context.getSource(), sessions, StringArgumentType.getString(context, "round"), false))))
            .then(Commands.literal("leaderboard")
                .executes(context -> leaderboard(context.getSource(), sessions, null))
                .then(Commands.argument("fingerprint", StringArgumentType.word())
                        .executes(context -> leaderboard(context.getSource(), sessions, StringArgumentType.getString(context, "fingerprint")))))
            .then(Commands.literal("participants")
                .then(Commands.argument("round", StringArgumentType.word())
                        .executes(context -> participants(context.getSource(), sessions, StringArgumentType.getString(context, "round"), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 10_000))
                                .executes(context -> participants(context.getSource(), sessions,
                                        StringArgumentType.getString(context, "round"), IntegerArgumentType.getInteger(context, "page"))))))
            .then(Commands.literal("verify").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("round", StringArgumentType.word())
                        .executes(context -> detail(context.getSource(), sessions, StringArgumentType.getString(context, "round"), true))))
            .then(Commands.literal("book")
                .then(Commands.argument("round", StringArgumentType.word())
                        .executes(context -> book(context.getSource(), sessions, books, StringArgumentType.getString(context, "round"), 1))
                        .then(Commands.argument("volume", IntegerArgumentType.integer(1, 10_000))
                                .executes(context -> book(context.getSource(), sessions, books,
                                        StringArgumentType.getString(context, "round"), IntegerArgumentType.getInteger(context, "volume"))))));
    }

    private static P1LobbySession session(CommandSourceStack source, Function<MinecraftServer, P1LobbySession> sessions) {
        var session = sessions.apply(source.getServer());
        if (session == null) source.sendFailure(Component.literal("P1 尚未启用。"));
        return session;
    }
    private static int recent(CommandSourceStack source, Function<MinecraftServer, P1LobbySession> sessions, int page) {
        var session = session(source, sessions);
        if (session == null) return 0;
        session.history(PAGE_SIZE, (page - 1) * PAGE_SIZE).whenComplete((rows, error) -> {
            if (error != null) { failed(source); return; }
            say(source, "已结算历史 · 第 " + page + " 页（每页最多 " + PAGE_SIZE + " 轮）");
            if (rows.isEmpty()) say(source, "暂无记录。");
            for (var row : rows) say(source, line(row));
            say(source, "详情 /hardrush history <轮次序号或UUID>；后页 /hardrush history page " + (page + 1));
        });
        return 0;
    }
    private static CompletableFuture<Optional<RoundHistory>> resolve(P1LobbySession session, String input) {
        try {
            if (input.matches("[1-9][0-9]{0,18}")) return session.historyBySequence(Long.parseLong(input));
            return session.history(UUID.fromString(input));
        } catch (IllegalArgumentException error) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("轮次必须为正整数序号或UUID。"));
        }
    }
    private static int detail(CommandSourceStack source, Function<MinecraftServer, P1LobbySession> sessions, String round, boolean verify) {
        var session = session(source, sessions);
        if (session == null) return 0;
        resolve(session, round).whenComplete((found, error) -> {
            if (error != null) { failed(source); return; }
            if (found.isEmpty()) { missing(source); return; }
            var row = found.orElseThrow();
            say(source, line(row));
            say(source, "轮次 " + row.roundId() + "；种子 " + row.seed() + "；规则指纹 " + row.rulesFingerprint());
            say(source, "Minecraft " + row.rules().minecraftVersion() + "；难度 " + row.rules().difficulty()
                    + "；胜者UUID " + (row.result().winner() == null ? "待核验" : row.result().winner()));
            say(source, "结果原因 " + safe(row.result().reason()) + "；计时待核验 " + row.timingUncertain()
                    + "；首龙归因已核验 " + row.trustedWinner() + "；正常榜资格 " + row.leaderboardEligible());
            say(source, "进度集合指纹 " + (row.advancementSetFingerprints().isEmpty() ? "旧记录未采集"
                    : String.join(",", row.advancementSetFingerprints().stream().sorted().toList())));
            if (verify) say(source, "已核对历史快照与进度集合摘要；可信首龙归因 " + row.trustedWinner()
                    + "；本查询不修正成绩。旧记录可能缺少首龙证据。书版 " + row.edition());
        });
        return 0;
    }
    private static int leaderboard(CommandSourceStack source, Function<MinecraftServer, P1LobbySession> sessions, String input) {
        var session = session(source, sessions);
        if (session == null || session.snapshot().isEmpty()) return 0;
        String fingerprint = input == null ? session.snapshot().orElseThrow().rules().fingerprint() : input;
        if (!fingerprint.matches("[0-9a-f]{64}")) { source.sendFailure(Component.literal("请输入完整64位规则指纹。")); return 0; }
        session.leaderboard(fingerprint, 10).whenComplete((rows, error) -> {
            if (error != null) { failed(source); return; }
            say(source, "规则榜（最多10条）" + fingerprint);
            if (rows.isEmpty()) say(source, "暂无已核验通关成绩。");
            for (var row : rows) say(source, "第 " + row.rank() + " 名 · " + safe(row.winnerName()) + " · "
                    + row.round().challengeMillis() + " ms · 第 " + row.round().sequence() + " 轮");
        });
        return 0;
    }
    private static int participants(CommandSourceStack source, Function<MinecraftServer, P1LobbySession> sessions, String round, int page) {
        var session = session(source, sessions);
        if (session == null) return 0;
        resolve(session, round).thenCompose(found -> found.isEmpty()
                ? CompletableFuture.failedFuture(new IllegalArgumentException("历史轮次不存在"))
                : session.participants(found.orElseThrow().roundId(), PAGE_SIZE, (page - 1) * PAGE_SIZE))
                .whenComplete((rows, error) -> {
                    if (error != null) { failed(source); return; }
                    say(source, "参赛记录 · 第 " + page + " 页");
                    for (var row : rows) say(source, safe(row.playerName()) + "（" + row.playerId() + "）· 人类参与 "
                            + row.humanMillis() + " ms · 死亡 " + row.deathCount() + " / 复生 " + row.revivalCount()
                            + " · 进度 " + row.advancementCount() + " · 首次末地 "
                            + (row.firstEndNanos() == null ? "未进入" : row.firstEndNanos() / 1_000_000 + " ms")
                            + " · 最终 " + row.finalRole());
                    if (rows.isEmpty()) say(source, "暂无记录。");
                });
        return 0;
    }
    private static int book(CommandSourceStack source, Function<MinecraftServer, P1LobbySession> sessions,
            BookRequest books, String round, int volume) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        source.getPlayerOrException();
        var session = session(source, sessions);
        if (session == null) return 0;
        resolve(session, round).whenComplete((found, error) -> {
            if (error != null) { failed(source); return; }
            if (found.isEmpty()) { missing(source); return; }
            books.receive(source, found.orElseThrow().roundId(), volume);
        });
        return 0;
    }
    private static String line(RoundHistory row) {
        return "第 " + row.sequence() + " 轮 · " + row.result().kind() + " · " + RoundTab.duration(row.challengeMillis())
                + "（" + row.challengeMillis() + " ms）· " + (row.leaderboardEligible() ? "正常榜" : "保留／待核验");
    }
    private static String safe(String text) { return SafeBookText.literal(text, 1024); }
    private static void say(CommandSourceStack source, String text) { source.sendSuccess(() -> Component.literal(text), false); }
    private static void failed(CommandSourceStack source) { source.sendFailure(Component.literal("历史查询失败或繁忙，请检查轮次输入后稍后重试。")); }
    private static void missing(CommandSourceStack source) { source.sendFailure(Component.literal("没有该轮的已结算历史记录。")); }
}
