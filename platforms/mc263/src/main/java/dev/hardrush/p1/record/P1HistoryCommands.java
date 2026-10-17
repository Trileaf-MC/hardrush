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
        new P1HistoryQueries(session).recent(page).whenComplete((rows, error) -> {
            if (error != null) { failed(source); return; }
            say(source, "已结算历史 · 第 " + page + " 页（每页最多 " + PAGE_SIZE + " 轮）");
            if (rows.isEmpty()) say(source, "暂无记录。");
            for (var row : rows.stream().limit(PAGE_SIZE).toList()) say(source, P1HistoryQueries.line(row));
            say(source, "也可在书屋选择轮次；命令后页 /hardrush history page " + (page + 1));
        });
        return 0;
    }
    private static CompletableFuture<Optional<RoundHistory>> resolve(P1LobbySession session, String input) {
        return new P1HistoryQueries(session).resolve(input);
    }

    private static int detail(CommandSourceStack source, Function<MinecraftServer, P1LobbySession> sessions, String round, boolean verify) {
        var session = session(source, sessions);
        if (session == null) return 0;
        resolve(session, round).whenComplete((found, error) -> {
            if (error != null) { failed(source); return; }
            if (found.isEmpty()) { missing(source); return; }
            var row = found.orElseThrow();
            P1HistoryQueries.details(row).forEach(text -> say(source, text));
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
        new P1HistoryQueries(session).leaders(fingerprint).whenComplete((rows, error) -> {
            if (error != null) { failed(source); return; }
            say(source, "规则榜（最多10条）" + fingerprint);
            if (rows.isEmpty()) say(source, "暂无已核验通关成绩。");
            for (var row : rows) say(source, P1HistoryQueries.leader(row));
        });
        return 0;
    }
    private static int participants(CommandSourceStack source, Function<MinecraftServer, P1LobbySession> sessions, String round, int page) {
        var session = session(source, sessions);
        if (session == null) return 0;
        resolve(session, round).thenCompose(found -> found.isEmpty()
                ? CompletableFuture.failedFuture(new IllegalArgumentException("历史轮次不存在"))
                : new P1HistoryQueries(session).participants(found.orElseThrow().roundId(), page))
                .whenComplete((rows, error) -> {
                    if (error != null) { failed(source); return; }
                    say(source, "参赛记录 · 第 " + page + " 页");
                    for (var row : rows.stream().limit(PAGE_SIZE).toList()) say(source, P1HistoryQueries.participant(row));
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
    private static String safe(String text) { return SafeBookText.literal(text, 1024); }
    private static void say(CommandSourceStack source, String text) { source.sendSuccess(() -> Component.literal(text), false); }
    private static void failed(CommandSourceStack source) { source.sendFailure(Component.literal("历史查询失败或繁忙，请检查轮次输入后稍后重试。")); }
    private static void missing(CommandSourceStack source) { source.sendFailure(Component.literal("没有该轮的已结算历史记录。")); }
}
