package dev.hardrush.storage;

import dev.hardrush.core.api.OperationTag;
import dev.hardrush.core.api.ReplayPort;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.round.RoundSession;
import dev.hardrush.round.MainThreadExecutor;
import dev.hardrush.round.RoundRelease;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.world.RoundWorldBundle;
import dev.hardrush.world.RoundWorldPaths;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Owner-bound chat confirmation followed by an online, evacuated-world/database replacement barrier. */
public final class RecoveryRuntime implements AutoCloseable {
    private static final Map<MinecraftServer, RecoveryRuntime> SERVICES = new IdentityHashMap<>();
    private enum Stage { IDLE, SEARCH, OFFER, RELEASE, APPLY, VERIFY }
    private final MinecraftServer server;
    private final RoundSession session;
    private final PlayerProfiles profiles;
    private final Supplier<RoundWorldBundle> worlds;
    private final Consumer<RoundWorldBundle> replaceWorlds;
    private final Supplier<ReplayPort> recorder;
    private final Runnable resetCaches;
    private final Path root;
    private final FullCheckpoint checkpoints;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "hardrush-recovery"); thread.setDaemon(true); return thread;
    });
    private final Set<UUID> rejected = new HashSet<>();
    private Stage stage = Stage.IDLE;
    private CommandSourceStack requester;
    private RoundSnapshot original;
    private RoundSnapshot offeredAgainst;
    private FullCheckpoint.Candidate candidate;
    private UUID ticket;
    private long expires;
    private long verifyStarted;
    private CompletableFuture<FullCheckpoint.Search> search;
    private CompletableFuture<Void> work;
    private boolean closed;

    public RecoveryRuntime(MinecraftServer server, RoundSession session, PlayerProfiles profiles,
            Supplier<RoundWorldBundle> worlds, Consumer<RoundWorldBundle> replaceWorlds,
            Supplier<ReplayPort> recorder, Runnable resetCaches) {
        this.server = server; this.session = session; this.profiles = profiles; this.worlds = worlds;
        this.replaceWorlds = replaceWorlds; this.recorder = recorder; this.resetCaches = resetCaches;
        root = server.getWorldPath(LevelResource.ROOT); checkpoints = new FullCheckpoint(root);
        SERVICES.put(server, this);
    }

    public static int request(CommandSourceStack source) {
        var service = SERVICES.get(source.getServer());
        if (service == null) return failure(source, "恢复服务尚未启用。");
        return service.find(source, true);
    }

    public static int decide(CommandSourceStack source, String answer, String token) {
        var service = SERVICES.get(source.getServer());
        if (service == null) return failure(source, "恢复服务尚未启用。");
        return service.confirm(source, answer, token);
    }

    public static int closeRecovery(CommandSourceStack source, RoundSession target, String reason) {
        var service = SERVICES.get(source.getServer());
        if (service == null || service.session != target) {
            if (!Commands.hasPermission(Commands.LEVEL_OWNERS).test(source)) return failure(source, "结束故障轮需要管理员权限等级 4。");
            target.closeRecovery(owner(source), reason, result -> {
                if (result.success()) say(source, result.message()); else failure(source, result.message());
            });
            return 1;
        }
        if (!authorized(source)) return failure(source, "结束故障轮需要管理员权限等级 4。");
        if (busy(source.getServer()) || service.stage == Stage.SEARCH) return failure(source, "请等待正在进行的恢复操作完成。");
        service.ticket = null; service.stage = Stage.IDLE;
        Consumer<RoundSession.Reply> reply = result -> {
            if (result.success()) say(source, result.message()); else failure(source, result.message());
        };
        Runnable close = () -> {
            if (!authorized(source)) { failure(source, "管理权限已变化，请重新执行命令。"); return; }
            try { service.profiles.resumeAfterRecoveryFailure(service.session.snapshot().orElseThrow()); }
            catch (Exception error) { failure(source, "故障档案尚不能安全释放：" + error.getMessage()); return; }
            service.session.closeRecovery(owner(source), reason, result -> {
                if (result.success()) {
                    try { service.checkpoints.abandonRecoveryTransaction(); }
                    catch (Exception error) { service.session.onlineRecoveryFailed(error); failure(source, "中止决策已保存，但恢复事务标记未安全封存：" + error.getMessage()); return; }
                }
                reply.accept(result);
            });
        };
        if (service.session.state() == RoundSession.State.FAILED) {
            service.session.recheckStorage(owner(source), reason, result -> { if (result.success()) close.run(); else reply.accept(result); });
        } else close.run();
        return 1;
    }

    public static boolean tick(MinecraftServer server) {
        var service = SERVICES.get(server);
        return service != null && service.tick();
    }

    public static boolean busy(MinecraftServer server) {
        var service = SERVICES.get(server);
        return service != null && (service.stage == Stage.RELEASE || service.stage == Stage.APPLY || service.stage == Stage.VERIFY);
    }

    public static void stop(MinecraftServer server) throws Exception {
        var service = SERVICES.remove(server);
        if (service != null) service.close();
    }

    private int find(CommandSourceStack source, boolean fresh) {
        if (!authorized(source)) return failure(source, "在线恢复需要管理员权限等级 4。");
        if (closed || stage == Stage.SEARCH || busy(server)) return failure(source, "恢复检查或恢复操作正在进行，请稍候。");
        if (!session.canRetryRecovery()) return failure(source, "仅可在故障隔离状态重试；请等待已受理的写入排空。");
        requester = source;
        if (fresh) { original = session.snapshot().orElseThrow(); rejected.clear(); }
        offeredAgainst = session.snapshot().orElseThrow();
        ticket = null; candidate = null; stage = Stage.SEARCH;
        say(source, "正在从新到旧检查本轮保存点；找到可用点后将请求确认。");
        var omitted = Set.copyOf(rejected);
        var current = original;
        search = CompletableFuture.supplyAsync(() -> {
            try {
                var found = checkpoints.search(current, omitted);
                var usable = new ArrayList<FullCheckpoint.Candidate>();
                var failures = new ArrayList<>(found.failures());
                for (var point : found.candidates()) {
                    try {
                        Path data = root.resolve("hardrush/full-checkpoints/" + point.id() + "/data");
                        RoundWorldBundle.verifyCheckpoint(data, point.round());
                        PlayerProfiles.verifyCheckpointProfiles(data, point.round());
                        usable.add(point);
                    } catch (Exception error) { failures.add(point.id() + ": " + error.getMessage()); }
                }
                return new FullCheckpoint.Search(usable, failures);
            } catch (Exception error) { throw new CompletionException(error); }
        }, io);
        return 1;
    }

    private int confirm(CommandSourceStack source, String answer, String token) {
        if (!authorized(source)) return failure(source, "在线恢复需要管理员权限等级 4。");
        if (stage != Stage.OFFER || ticket == null || !ticket.toString().equals(token)
                || !owner(source).equals(owner(requester)) || !currentRequester()
                || System.nanoTime() - expires >= 0 || !unchanged()) {
            return failure(source, "恢复确认已失效或不属于你，请重新执行 /hardrush admin recovery。");
        }
        if (!answer.equals("yes") && !answer.equals("no")) return failure(source, "请选择 yes 或 no。");
        ticket = null;
        if (answer.equals("no")) { stage = Stage.IDLE; say(source, "已取消恢复，继续保留故障轮。"); return 1; }
        stage = Stage.RELEASE;
        say(source, "已确认，正在隔离玩家并安全释放本轮世界；恢复期间请等待。");
        try {
            profiles.pauseForOnlineRecovery();
            session.suspendForOnlineRecovery();
            var bundle = worlds.get();
            var tag = new OperationTag(original.roundId(), UUID.randomUUID(), offeredAgainst.version());
            work = RoundRelease.release(Optional.ofNullable(recorder.get()), tag, MainThreadExecutor.forServer(server),
                    () -> !closed && stage == Stage.RELEASE && worlds.get() == bundle,
                    () -> {
                        if (bundle != null) return bundle.saveAndClose();
                        requireUnloaded();
                        return CompletableFuture.completedFuture(null);
                    }).toCompletableFuture();
        } catch (Exception error) { failed(error, false); }
        return 1;
    }

    private boolean tick() {
        if (closed) return false;
        try {
            if (stage == Stage.SEARCH && search.isDone()) {
                var found = search.join();
                if (!unchanged() || !currentRequester()) { stage = Stage.IDLE; return false; }
                if (found.candidates().isEmpty()) {
                    stage = Stage.IDLE;
                    failure(requester, "无法自动恢复：没有通过核验的本轮保存点。" + (found.failures().isEmpty() ? "" : " 原因：" + found.failures().getFirst())
                            + "。可修复存储后重试，或执行 /hardrush admin close-recovery 放弃故障轮并开始新轮。");
                } else {
                    candidate = found.candidates().getFirst();
                    ticket = UUID.randomUUID(); expires = System.nanoTime() + TimeUnit.MINUTES.toNanos(2); stage = Stage.OFFER;
                    long seconds = Math.max(0, (System.currentTimeMillis() - candidate.savedAt()) / 1000);
                    String prefix = "/hardrush admin recovery ";
                    var message = Component.literal("可恢复到 " + java.time.Instant.ofEpochMilli(candidate.savedAt())
                            + "（约 " + duration(seconds) + "前，第 " + original.sequence() + " 轮）。这之后的进度会回退，成绩待核验。是否恢复？ ")
                            .append(button("[yes]", prefix + "yes " + ticket, ChatFormatting.GREEN))
                            .append(Component.literal("  "))
                            .append(button("[no]", prefix + "no " + ticket, ChatFormatting.RED))
                            .append(Component.literal("（2分钟内有效）"));
                    requester.sendSuccess(() -> message, false);
                    if (requester.getEntity() == null) say(requester, "控制台确认：" + prefix + "yes " + ticket + "；取消：" + prefix + "no " + ticket);
                }
            } else if (stage == Stage.RELEASE) {
                var bundle = worlds.get();
                if (bundle != null) bundle.tick();
                if (work.isDone()) {
                    work.join(); requireUnloaded(); replaceWorlds.accept(null);
                    stage = Stage.APPLY;
                    var point = candidate;
                    work = CompletableFuture.runAsync(() -> {
                        try { checkpoints.restoreCandidate(point, original); }
                        catch (Exception error) { throw new CompletionException(error); }
                    }, io);
                }
            } else if (stage == Stage.APPLY && work.isDone()) {
                work.join();
                session.reloadOnlineCheckpoint(candidate.round(), owner(requester), candidate.savedAt());
                profiles.reloadOnlineCheckpoint(candidate.round()); resetCaches.run();
                replaceWorlds.accept(RoundWorldBundle.open(server, session.snapshot().orElseThrow()));
                stage = Stage.VERIFY; verifyStarted = System.nanoTime();
            } else if (stage == Stage.VERIFY) {
                var bundle = worlds.get(); bundle.tick();
                if (bundle.failure().isPresent()) throw new IllegalStateException(bundle.failure().orElseThrow());
                var round = session.snapshot().orElseThrow();
                profiles.tick(round);
                for (var player : server.getPlayerList().getPlayers()) {
                    if (profiles.failure(player.getUUID()).isPresent()) throw new IllegalStateException(profiles.failure(player.getUUID()).orElseThrow());
                }
                if (System.nanoTime() - verifyStarted > TimeUnit.MINUTES.toNanos(3)) throw new IllegalStateException("保存点世界／玩家档案载入超时");
                if (bundle.ready() && profiles.readyForFullCheckpoint(round)) {
                    session.adaptersReady(round.roundId());
                    if (session.competitionLocked()) return true;
                    stage = Stage.IDLE;
                    server.getPlayerList().broadcastSystemMessage(Component.literal("管理员已在线恢复本轮到 "
                            + java.time.Instant.ofEpochMilli(candidate.savedAt()) + "；玩家将自动返回对局，成绩待核验。"), false);
                    say(requester, "续局恢复成功，服务器已返回正常状态。");
                }
            }
        } catch (Exception error) {
            if (stage == Stage.SEARCH) {
                stage = Stage.IDLE;
                while (error.getCause() instanceof Exception cause) error = cause;
                failure(requester, "无法自动恢复：保存点检查失败：" + error.getMessage());
            } else failed(error, stage == Stage.APPLY || stage == Stage.VERIFY);
        }
        return busy(server);
    }

    private void failed(Throwable error, boolean tryOlder) {
        while (error.getCause() != null) error = error.getCause();
        session.onlineRecoveryFailed(error);
        stage = Stage.IDLE; ticket = null;
        failure(requester, "本次无法自动恢复：" + error.getMessage());
        if (tryOlder) {
            rejected.add(candidate.id());
            say(requester, "正在自动尝试其他较早保存点；如果可用，会再次展示回退时间供确认。");
            find(requester, false);
        }
    }

    private void requireUnloaded() {
        for (var level : server.getAllLevels()) for (String name : List.of("overworld", "nether", "end")) {
            if (level.dimension().identifier().toString().equals(RoundWorldPaths.identifier(original.roundId(), name))) {
                throw new IllegalStateException("故障轮原生世界尚未安全卸载，拒绝覆盖文件");
            }
        }
    }

    private boolean unchanged() { return session.canRetryRecovery() && session.snapshot().filter(offeredAgainst::equals).isPresent(); }
    private boolean currentRequester() {
        return authorized(requester) && (requester.getEntity() == null
                || server.getPlayerList().getPlayer(requester.getEntity().getUUID()) == requester.getEntity());
    }
    private static boolean authorized(CommandSourceStack source) {
        var current = source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player ? player.createCommandSourceStack() : source;
        return Commands.hasPermission(Commands.LEVEL_OWNERS).test(current);
    }
    private static String owner(CommandSourceStack source) { return source.getEntity() == null ? "console:" + source.getTextName() : "entity:" + source.getEntity().getUUID(); }
    private static Component button(String label, String command, ChatFormatting color) {
        return Component.literal(label).withStyle(s -> s.withColor(color).withBold(true).withClickEvent(new ClickEvent.RunCommand(command)));
    }
    private static String duration(long seconds) { return seconds < 60 ? seconds + " 秒" : seconds < 3600 ? seconds / 60 + " 分 " + seconds % 60 + " 秒" : seconds / 3600 + " 小时 " + seconds / 60 % 60 + " 分"; }
    private static void say(CommandSourceStack source, String message) { source.sendSuccess(() -> Component.literal(message), false); }
    private static int failure(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(dev.hardrush.core.book.SafeBookText.literal(message, 2048))); return 0;
    }

    @Override public void close() throws Exception {
        closed = true; ticket = null;
        io.shutdown();
        if (!io.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("在线恢复文件操作尚未排空，禁止保存旧内存状态");
        if (stage == Stage.APPLY && work.isDone()) {
            work.join();
            session.reloadOnlineCheckpoint(candidate.round(), owner(requester), candidate.savedAt());
            profiles.reloadOnlineCheckpoint(candidate.round());
        }
    }
}
