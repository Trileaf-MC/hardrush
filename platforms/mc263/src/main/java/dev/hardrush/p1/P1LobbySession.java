package dev.hardrush.p1;

import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.RoundEngine;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.core.round.RoundSnapshotCodec;
import dev.hardrush.core.round.RoundTransition;
import dev.hardrush.persistence.CommitResult;
import dev.hardrush.persistence.SnapshotWrite;
import dev.hardrush.persistence.SqliteRoundStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Main-thread session plus one SQLite worker. This foundation deliberately does
 * not open competition worlds or unlock player participation.
 */
public final class P1LobbySession implements AutoCloseable {
    public enum State { OPENING, OPEN, FAILED, CLOSED }
    public record Reply(boolean success, String message) {}

    private static final long CHECKPOINT_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final String ADAPTERS_PENDING =
            "世界准备与玩家档案适配器尚未接通，暂不能加入或就绪。";

    private final MonotonicClock clock;
    private final Executor callbacks;
    private final BiConsumer<String, Throwable> diagnostics;
    private final ExecutorService io;
    private final CompletableFuture<Loaded> opening;
    private final Map<UUID, String> online = new LinkedHashMap<>();
    // Assigned and used only by the single IO worker.
    private SqliteRoundStore store;
    private State state = State.OPENING;
    private RoundEngine engine;
    private RulesConfig nextRules;
    private String problem = "";
    private boolean stopping;
    private boolean unsupportedPhase;
    private long lastCheckpointNanos;
    private RoundTransition pendingTransition;
    private CompletableFuture<CommitResult> pendingWrite;
    private CompletableFuture<CommitResult> pendingRulesWrite;

    public P1LobbySession(Path database, MonotonicClock clock, Executor callbacks) {
        this(database, clock, callbacks, (message, error) -> {});
    }

    public P1LobbySession(Path database, MonotonicClock clock, Executor callbacks,
            BiConsumer<String, Throwable> diagnostics) {
        Objects.requireNonNull(database);
        this.clock = Objects.requireNonNull(clock);
        this.callbacks = Objects.requireNonNull(callbacks);
        this.diagnostics = Objects.requireNonNull(diagnostics);
        this.io = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "hardrush-p1-sqlite");
            thread.setDaemon(true);
            return thread;
        });
        this.opening = supplyIo(() -> open(database));
        opening.whenComplete((loaded, error) -> callbacks.execute(() -> {
            if (stopping || state != State.OPENING) return;
            if (error != null) fail("轮次数据库打开失败", error);
            else install(loaded);
        }));
    }

    private Loaded open(Path database) throws Exception {
        store = new SqliteRoundStore(database);
        var pending = store.loadPendingRules();
        RulesConfig rules = pending.isPresent()
                ? RulesConfig.decode(new String(pending.get(), StandardCharsets.UTF_8))
                : RulesConfig.defaults();
        requireVersion(rules);
        if (pending.isEmpty()) {
            requireCommitted(store.savePendingRules(encodedRules(rules), UUID.randomUUID(), "server-bootstrap"));
        }
        var latest = store.latest();
        RoundSnapshot snapshot;
        if (latest.isPresent()) {
            snapshot = RoundSnapshotCodec.decode(latest.get().payload());
            if (!snapshot.roundId().equals(latest.get().roundId()) || snapshot.version() != latest.get().version()) {
                throw new IllegalStateException("轮次快照与数据库标签不一致");
            }
            requireVersion(snapshot.rules());
        } else {
            snapshot = RoundEngine.create(UUID.randomUUID(), 1, ThreadLocalRandom.current().nextLong(),
                    rules, () -> 0).committed();
            requireCommitted(store.commit(new SnapshotWrite(snapshot.roundId(), -1, 0,
                    RoundSnapshotCodec.encode(snapshot), UUID.randomUUID(), "ROUND_PREPARING")));
        }
        return new Loaded(snapshot, rules, latest.isEmpty());
    }

    private void install(Loaded loaded) {
        var stored = loaded.snapshot();
        engine = loaded.fresh()
                ? RoundEngine.create(stored.roundId(), stored.sequence(), stored.seed(), stored.rules(), clock)
                : RoundEngine.restore(stored, clock);
        nextRules = loaded.nextRules();
        // An externally restored later phase needs the future world/profile
        // adapters. Keep its durable phase visible without progressing it.
        unsupportedPhase = engine.committed().phase() != RoundPhase.PREPARING;
        lastCheckpointNanos = clock.nanoTime();
        if (!loaded.fresh() && !unsupportedPhase && !stopping) {
            // A reopened clean checkpoint must become dirty before commands
            // open; a subsequent crash must never look like a clean stop.
            submit(engine.proposeCheckpoint(UUID.randomUUID(), false));
        } else {
            state = State.OPEN;
        }
    }

    public State state() { return state; }
    public String problem() { return problem; }
    public Optional<RoundSnapshot> snapshot() {
        return engine == null ? Optional.empty() : Optional.of(engine.committed());
    }
    public Optional<RulesConfig> nextRules() { return Optional.ofNullable(nextRules); }
    public boolean mutationPending() { return pendingWrite != null || pendingRulesWrite != null; }

    public String status() {
        if (state == State.OPENING) return "P1 基础正在打开轮次数据库，请稍后查询。";
        if (state == State.FAILED) return "P1 基础已停止状态变更：" + problem;
        if (state == State.CLOSED) return "P1 基础已停止。";
        var round = engine.committed();
        return "P1 轮次 " + round.sequence() + "（" + round.roundId() + "），阶段 " + round.phase()
                + "，本轮模式 " + modeName(round.rules().mode()) + "，下轮模式 " + modeName(nextRules.mode())
                + "，已持久化版本 " + round.version() + "，规则 " + round.rules().fingerprint()
                + "。" + (mutationPending() ? "正在提交状态。" : "")
                + (unsupportedPhase ? "已保留原阶段；恢复游玩需要世界与档案适配器。" : ADAPTERS_PENDING);
    }

    public Reply requestJoin(UUID playerId, String name) {
        Objects.requireNonNull(playerId); Objects.requireNonNull(name);
        return blockedParticipation();
    }

    public Reply requestReady(UUID playerId, boolean ready) {
        Objects.requireNonNull(playerId);
        return blockedParticipation();
    }

    private Reply blockedParticipation() {
        return new Reply(false, state == State.OPEN ? ADAPTERS_PENDING : status());
    }

    /** Connection state is reconciled after the current durable transition. */
    public void onOnline(UUID playerId, String name, boolean connected) {
        if (stopping) return;
        if (connected) online.put(playerId, name);
        else online.remove(playerId);
    }

    /** Never reports the next mode as changed before SQLite accepts it. */
    public void updateNextMode(GameMode mode, String actor, Consumer<Reply> reply) {
        Objects.requireNonNull(mode); Objects.requireNonNull(actor); Objects.requireNonNull(reply);
        if (state != State.OPEN || stopping) { reply.accept(new Reply(false, status())); return; }
        if (mutationPending()) { reply.accept(new Reply(false, "上一项状态仍在提交，请稍后重试。")); return; }
        var proposed = nextRules.withMode(mode);
        var eventId = UUID.randomUUID();
        var write = supplyIo(() -> store.savePendingRules(encodedRules(proposed), eventId, actor));
        pendingRulesWrite = write;
        write.whenComplete((result, error) -> callbacks.execute(() -> {
            if (stopping || pendingRulesWrite != write) return;
            pendingRulesWrite = null;
            if (error != null || result == CommitResult.STALE) {
                fail("下轮规则提交失败", error != null ? error : new IllegalStateException("状态版本已过期"));
                reply.accept(new Reply(false, "下轮模式未应用：" + problem));
                return;
            }
            nextRules = proposed;
            reply.accept(new Reply(true, "下轮模式已持久化为" + modeName(mode)
                    + "；本轮规则快照保持原值。规则指纹：" + proposed.fingerprint()));
        }));
    }

    public void tick() {
        if (stopping || state != State.OPEN || unsupportedPhase || mutationPending()) return;
        var round = engine.committed();
        for (var participant : round.participants().values()) {
            var name = online.get(participant.id());
            boolean connected = name != null;
            if (participant.online() != connected || connected && !participant.name().equals(name)) {
                submit(engine.proposeOnline(UUID.randomUUID(), participant.id(),
                        connected ? name : participant.name(), connected));
                return;
            }
        }
        long now = clock.nanoTime();
        if (now - lastCheckpointNanos >= CHECKPOINT_NANOS) {
            lastCheckpointNanos = now;
            submit(engine.proposeCheckpoint(UUID.randomUUID(), false));
        }
    }

    private void submit(RoundTransition transition) {
        pendingTransition = transition;
        var after = transition.after();
        var write = supplyIo(() -> store.commit(new SnapshotWrite(after.roundId(), transition.before().version(),
                after.version(), RoundSnapshotCodec.encode(after), transition.eventId(), transition.eventType())));
        pendingWrite = write;
        write.whenComplete((result, error) -> callbacks.execute(() -> {
            if (stopping || pendingWrite != write || pendingTransition != transition) return;
            pendingWrite = null;
            pendingTransition = null;
            complete(transition, result, error);
            if (state == State.OPENING) state = State.OPEN;
        }));
    }

    private void complete(RoundTransition transition, CommitResult result, Throwable error) {
        if (error == null && (result == CommitResult.APPLIED || result == CommitResult.DUPLICATE)) {
            if (!engine.accept(transition)) fail("轮次提交回调标签已过期", new IllegalStateException("旧轮次回调被拒绝"));
        }
        else {
            engine.reject(transition, "持久化失败");
            fail("轮次提交失败", error != null ? error : new IllegalStateException("状态版本已过期"));
        }
    }

    /**
     * Normal shutdown is the sole lifecycle barrier: drain accepted writes,
     * save the final checkpoint, then close the worker's database connection.
     * No worker callback needs the blocked server thread to complete this drain.
     */
    @Override
    public void close() throws Exception {
        if (stopping) return;
        stopping = true;
        Throwable failure = null;
        try {
            if (engine == null && state != State.FAILED) install(opening.get());
            if (pendingWrite != null) {
                var transition = pendingTransition;
                try { complete(transition, pendingWrite.get(), null); }
                catch (Exception error) { complete(transition, null, error); throw error; }
                if (state == State.FAILED) throw new IllegalStateException(problem);
                pendingWrite = null;
                pendingTransition = null;
            }
            if (pendingRulesWrite != null) {
                requireCommitted(pendingRulesWrite.get());
                pendingRulesWrite = null;
            }
            if (state == State.OPEN && !unsupportedPhase) {
                var transition = engine.proposeCheckpoint(UUID.randomUUID(), true);
                var after = transition.after();
                var result = supplyIo(() -> store.commit(new SnapshotWrite(after.roundId(),
                        transition.before().version(), after.version(), RoundSnapshotCodec.encode(after),
                        transition.eventId(), transition.eventType()))).get();
                requireCommitted(result);
                if (!engine.accept(transition)) throw new IllegalStateException("停服保存回调标签已过期");
            }
        } catch (Throwable error) {
            failure = error;
        } finally {
            try { supplyIo(() -> { if (store != null) store.close(); return null; }).get(); }
            catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            io.shutdown();
            state = State.CLOSED;
        }
        if (failure != null) throw new IllegalStateException("P1 正常停服保存未完成，请保留数据库并检查日志", failure);
    }

    private void fail(String prefix, Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        problem = prefix + "：" + (cause == null ? "未知错误" : cause.getMessage());
        state = State.FAILED;
        diagnostics.accept(problem, cause);
    }

    private <T> CompletableFuture<T> supplyIo(IoAction<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            try { return action.run(); }
            catch (Exception error) { throw new CompletionException(error); }
        }, io);
    }

    private static byte[] encodedRules(RulesConfig rules) { return rules.encode().getBytes(StandardCharsets.UTF_8); }
    private static void requireCommitted(CommitResult result) {
        if (result != CommitResult.APPLIED && result != CommitResult.DUPLICATE) {
            throw new IllegalStateException("数据库状态版本已过期");
        }
    }
    private static void requireVersion(RulesConfig rules) {
        if (!"26.3".equals(rules.minecraftVersion())) throw new IllegalArgumentException("此适配器仅支持 Minecraft 26.3");
    }
    private static String modeName(GameMode mode) { return mode == GameMode.HARDCORE ? "极限" : "普通"; }
    private record Loaded(RoundSnapshot snapshot, RulesConfig nextRules, boolean fresh) {}
    @FunctionalInterface private interface IoAction<T> { T run() throws Exception; }
}
