package dev.hardrush.p1;

import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.RoundEngine;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.core.round.RoundSnapshotCodec;
import dev.hardrush.core.round.RoundTransition;
import dev.hardrush.core.round.DragonDeathArbiter;
import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.combat.CombatEventCodec;
import dev.hardrush.persistence.CommitResult;
import dev.hardrush.persistence.SnapshotWrite;
import dev.hardrush.persistence.SqliteRoundStore;
import dev.hardrush.persistence.EvidenceWrite;
import dev.hardrush.core.record.ProgressWrite;
import dev.hardrush.core.record.RoundHistory;
import dev.hardrush.core.record.ParticipantRecord;
import dev.hardrush.core.record.LeaderboardEntry;
import dev.hardrush.core.record.BookClaim;
import dev.hardrush.core.record.AdminAudit;
import dev.hardrush.core.record.HistoryRevision;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
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
import java.util.function.Function;

/**
 * Main-thread arbiter plus one SQLite worker. Platform gates must verify worlds
 * and player profiles before participation is enabled.
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
    private final Map<UUID, Integer> requestedRespawns = new LinkedHashMap<>();
    // Assigned and used only by the single IO worker.
    private SqliteRoundStore store;
    private State state = State.OPENING;
    private RoundEngine engine;
    private RulesConfig nextRules;
    private String problem = "";
    private boolean stopping;
    private boolean unsupportedPhase;
    private boolean adaptersReady;
    private long eventTime = Long.MIN_VALUE;
    private final ArrayDeque<QueuedEvent> events = new ArrayDeque<>();
    private BiConsumer<RoundSnapshot, RoundSnapshot> committedListener = (before, after) -> {};
    private long lastCheckpointNanos;
    private RoundTransition pendingTransition;
    private CompletableFuture<CommitResult> pendingWrite;
    private CompletableFuture<CommitResult> pendingRulesWrite;
    private int evidencePending;
    private volatile Throwable evidenceFailure;
    private CombatEvent terminalCandidate;
    private UUID firstDragonId;
    private boolean terminalQueued;
    private CompletableFuture<CommitResult> pendingRotationWrite;
    private RoundSnapshot pendingNextRound;
    private Long shutdownBoundary;
    private int progressPending;
    private int queriesPending;
    private long endEntrants;
    private Runnable recordsChanged = () -> {};
    private CompletableFuture<CommitResult> pendingAdminWrite;
    private Long adminBoundary;
    private Long failureBoundary;

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
        if (pending.isEmpty()) {
            String initialMode = System.getProperty("hardrush.p1.initialMode", "hardcore");
            if (!initialMode.equals("normal") && !initialMode.equals("hardcore")) {
                throw new IllegalArgumentException("首次开发模式只接受 normal 或 hardcore");
            }
            if (initialMode.equals("normal")) rules = rules.withMode(GameMode.NORMAL);
        }
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
        return new Loaded(snapshot, rules, latest.isEmpty(), store.endEntrantCount(snapshot.roundId()));
    }

    private void install(Loaded loaded) {
        var stored = loaded.snapshot();
        engine = loaded.fresh()
                ? RoundEngine.create(stored.roundId(), stored.sequence(), stored.seed(), stored.rules(), this::engineTime)
                : RoundEngine.restore(stored, this::engineTime);
        nextRules = loaded.nextRules();
        endEntrants = loaded.endEntrants();
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
    public boolean mutationPending() { return pendingWrite != null || pendingRulesWrite != null || pendingRotationWrite != null || pendingAdminWrite != null; }
    public Optional<RoundSnapshot> view() { return engine == null ? Optional.empty() : Optional.of(engine.view()); }
    public long endEntrants() { return endEntrants; }
    public void onRecordsChanged(Runnable listener) { recordsChanged = Objects.requireNonNull(listener); }

    /** Immediately closes new competitive actions, even while the terminal transaction is queued. */
    public boolean competitionLocked() {
        return shutdownBoundary != null || adminBoundary != null || state != State.OPEN || terminalCandidate != null || engine == null
                || engine.committed().phase() != RoundPhase.RUNNING;
    }

    public Optional<UUID> terminalBatchId() {
        return terminalCandidate == null ? Optional.empty() : Optional.of(terminalCandidate.batchId());
    }

    /** Called only after the platform verifies the native first dragon and its irreversible death. */
    public void captureDragonDeath(CombatEvent event, UUID dragonId) {
        Objects.requireNonNull(event); Objects.requireNonNull(dragonId);
        if (stopping || adminBoundary != null || shutdownBoundary != null || state != State.OPEN || !adaptersReady || terminalCandidate != null
                || !event.roundId().equals(engine.committed().roundId())
                || engine.committed().result() != null || engine.committed().phase() != RoundPhase.RUNNING) return;
        // Validate before locking; source identity is frozen at attack creation, not checked against today's role.
        DragonDeathArbiter.adjudicate(engine.committed(), event, dragonId);
        terminalCandidate = event;
        firstDragonId = dragonId;
    }

    /** Complete the native batch first, so simultaneous human deaths precede its single terminal transaction. */
    public void finishDamageBatch(UUID roundId, UUID batchId) {
        if (terminalCandidate == null || terminalQueued || !terminalCandidate.roundId().equals(roundId)
                || !terminalCandidate.batchId().equals(batchId)) return;
        terminalQueued = true;
        var death = terminalCandidate;
        var dragon = firstDragonId;
        enqueueAt(death.observedNanoTime(), e -> e.proposeFinish(UUID.randomUUID(),
                DragonDeathArbiter.adjudicate(e.committed(), death, dragon)));
    }

    /** A verified adapter calls this only after players, recordings and all world handles have been released. */
    public void rotationReady(UUID oldRoundId) {
        if (stopping || state != State.OPEN || mutationPending() || !events.isEmpty()
                || !engine.committed().roundId().equals(oldRoundId)
                || engine.committed().phase() != RoundPhase.ROTATING) return;
        var next = RoundEngine.create(UUID.randomUUID(), Math.addExact(engine.committed().sequence(), 1),
                ThreadLocalRandom.current().nextLong(), nextRules, this::engineTime).committed();
        pendingNextRound = next;
        adaptersReady = false;
        unsupportedPhase = true;
        var write = supplyIo(() -> store.commit(new SnapshotWrite(next.roundId(), -1, 0,
                RoundSnapshotCodec.encode(next), UUID.randomUUID(), "ROUND_PREPARING")));
        pendingRotationWrite = write;
        write.whenComplete((result, error) -> callbacks.execute(() -> {
            if (stopping || pendingRotationWrite != write || pendingNextRound != next) return;
            pendingRotationWrite = null;
            pendingNextRound = null;
            if (error != null || result == CommitResult.STALE) {
                fail("下轮创建提交失败", error != null ? error : new IllegalStateException("轮次版本已过期"));
                return;
            }
            installNextRound(next);
        }));
    }

    private void installNextRound(RoundSnapshot next) {
        var before = engine.committed();
        engine = RoundEngine.create(next.roundId(), next.sequence(), next.seed(), next.rules(), this::engineTime);
        terminalCandidate = null;
        firstDragonId = null;
        terminalQueued = false;
        adminBoundary = null;
        requestedRespawns.clear();
        endEntrants = 0;
        adaptersReady = false;
        unsupportedPhase = false;
        lastCheckpointNanos = clock.nanoTime();
        if (!stopping) try { committedListener.accept(before, next); }
        catch (RuntimeException failure) { fail("新轮已持久化但适配器应用失败", failure); }
    }

    public void onCommitted(BiConsumer<RoundSnapshot, RoundSnapshot> listener) {
        committedListener = Objects.requireNonNull(listener);
    }

    /** Only called by verified world/profile adapters, never an operator command. */
    public void adaptersReady(UUID roundId) {
        if (state != State.OPEN || mutationPending() || engine == null
                || !engine.committed().roundId().equals(roundId)) return;
        var phase = engine.committed().phase();
        if (phase == RoundPhase.RECOVERY_REQUIRED || phase == RoundPhase.RESCUE) return;
        adaptersReady = true;
        unsupportedPhase = false;
        if (phase == RoundPhase.PREPARING) submit(engine.proposeWorldReady(UUID.randomUUID()));
        else if (engine.committed().cleanShutdown()) submit(engine.proposeCheckpoint(UUID.randomUUID(), false));
    }

    public void adapterFailure(String reason, Throwable error) { fail(reason, error); }

    public void recordEvidence(EvidenceWrite evidence) {
        if (stopping || state != State.OPEN || !engine.committed().roundId().equals(evidence.roundId())) return;
        if (evidencePending >= 256) { fail("战斗证据队列超出预算", new IllegalStateException("暂停比赛，保留证据")); return; }
        evidencePending++;
        supplyIo(() -> store.recordEvidence(evidence)).whenComplete((result, error) -> {
            if (error != null) evidenceFailure = error;
            callbacks.execute(() -> {
                if (stopping) return;
                evidencePending--;
                if (error != null) fail("战斗证据保存失败", error);
            });
        });
    }

    /** The native collector calls this at observation time, before any later FINISH can enter the IO queue. */
    public void recordProgress(ProgressWrite progress) {
        if (competitionLocked() || stopping || !adaptersReady || !engine.committed().roundId().equals(progress.roundId())) return;
        var participant = engine.committed().participants().get(progress.playerId());
        if (participant == null || !participant.online() || !participant.role().isActiveHuman()) return;
        if (progressPending >= 256) { fail("统计记录队列超出预算", new IllegalStateException("暂停比赛并保留档案")); return; }
        progressPending++;
        supplyIo(() -> {
            requireCommitted(store.recordProgress(progress));
            return store.endEntrantCount(progress.roundId());
        }).whenComplete((count, error) -> {
            if (error != null) evidenceFailure = error;
            callbacks.execute(() -> {
                progressPending--;
                if (stopping) return;
                if (error != null) fail("参赛统计保存失败", error);
                else if (engine.committed().roundId().equals(progress.roundId())) {
                    boolean changed = endEntrants != count;
                    endEntrants = count;
                    if (changed) recordsChanged.run();
                }
            });
        });
    }

    public CompletableFuture<List<RoundHistory>> history(int limit, int offset) {
        return query(() -> store.history(limit, offset));
    }
    public CompletableFuture<Optional<RoundHistory>> history(UUID roundId) {
        return query(() -> store.roundHistory(roundId));
    }
    public CompletableFuture<Optional<RoundHistory>> historyBySequence(long sequence) {
        return query(() -> store.historyBySequence(sequence));
    }
    public CompletableFuture<Optional<RoundHistory>> latestHistoryBefore(long sequence) {
        return query(() -> store.latestHistoryBefore(sequence));
    }
    public CompletableFuture<List<LeaderboardEntry>> leaderboard(String fingerprint, int limit) {
        return query(() -> store.leaderboard(fingerprint, limit));
    }
    public CompletableFuture<List<ParticipantRecord>> participants(UUID roundId, int limit, int offset) {
        return query(() -> store.participantRecords(roundId, limit, offset));
    }
    public CompletableFuture<Optional<ParticipantRecord>> participant(UUID roundId, UUID playerId) {
        return query(() -> store.participantRecord(roundId, playerId));
    }
    public CompletableFuture<Long> participantCount(UUID roundId) { return query(() -> store.participantCount(roundId)); }
    public CompletableFuture<List<AdminAudit>> audits(UUID roundId, int limit, int offset) {
        return query(() -> store.audits(roundId, limit, offset));
    }
    public void reviseHistory(HistoryRevision revision, AdminAudit audit, Consumer<Reply> reply) {
        adminIo(() -> store.reviseHistory(revision, audit), reply,
                "成绩修订与审计已持久化；旧书保留快照，可重新领取最新版。", () -> recordsChanged.run());
    }

    /** Persist a retry intent before touching external world tasks; never clears a failed session. */
    public void auditWorldRetry(AdminAudit audit, Runnable retry, Consumer<Reply> reply) {
        if (engine == null || !engine.committed().roundId().equals(audit.roundId())
                || engine.committed().phase() != RoundPhase.PREPARING) {
            reply.accept(new Reply(false, "仅允许重试当前 PREPARING 轮次的世界准备。")); return;
        }
        adminIo(() -> store.recordAdminAudit(audit), reply,
                "世界重试请求已审计；继续按原轮次、原种子准备，完成后才允许加入。", retry);
    }

    private void adminIo(IoAction<CommitResult> action, Consumer<Reply> reply, String success, Runnable applied) {
        if (state != State.OPEN || stopping || shutdownBoundary != null) { reply.accept(new Reply(false, status())); return; }
        if (mutationPending() || !events.isEmpty()) { reply.accept(new Reply(false, "上一项状态仍在提交，请稍后重试。")); return; }
        var write = supplyIo(action);
        pendingAdminWrite = write;
        write.whenComplete((result, error) -> {
            boolean invalidRequest = rootCause(error) instanceof IllegalArgumentException;
            if (error != null && !invalidRequest) evidenceFailure = error;
            callbacks.execute(() -> {
                if (stopping || pendingAdminWrite != write) return;
                pendingAdminWrite = null;
                if (state != State.OPEN) { reply.accept(new Reply(false, problem)); return; }
                if (error != null) {
                    if (invalidRequest) {
                        reply.accept(new Reply(false, "管理请求不符合原始证据或修订约束：" + rootCause(error).getMessage()));
                        drainEvent(); return;
                    }
                    fail("管理写入失败", error); reply.accept(new Reply(false, problem)); return;
                }
                if (result == CommitResult.STALE) {
                    reply.accept(new Reply(false, "管理目标已改变，请重新查看并申请确认。")); return;
                }
                try { applied.run(); reply.accept(new Reply(true, success)); }
                catch (RuntimeException failed) {
                    // The durable intent remains available even if the external adapter refuses the retry.
                    reply.accept(new Reply(false, "请求已审计，但适配器未执行：" + failed.getMessage()));
                    diagnostics.accept("管理适配器请求失败", failed);
                }
                drainEvent();
            });
        });
    }

    public void adminStart(String actor, String reason, Consumer<Reply> reply) {
        if (engine == null || engine.committed().rules().mode() != GameMode.NORMAL) {
            reply.accept(new Reply(false, "P1 仅允许普通模式开赛，极限闭环待 P2。")); return;
        }
        requestAdminTransition(actor, reason, false, () -> engine.proposeAdmissionAdminStart(UUID.randomUUID()), reply,
                "管理员开赛已持久化；本轮计时标记待核验，不自动进入正常榜。");
    }

    public void adminAbort(String actor, String reason, Consumer<Reply> reply) {
        requestAdminTransition(actor, reason, true, () -> engine.proposeAbort(UUID.randomUUID(), reason), reply,
                "本轮已中止并保存原因与审计；立即回大厅，安全释放旧世界后轮换。");
    }

    private void requestAdminTransition(String actor, String reason, boolean abort,
            java.util.function.Supplier<RoundTransition> proposal, Consumer<Reply> reply, String success) {
        if (state != State.OPEN || stopping || shutdownBoundary != null || terminalCandidate != null) {
            reply.accept(new Reply(false, "轮次已冻结或状态不可写，不能执行管理转换。")); return;
        }
        if (!adaptersReady) { reply.accept(blockedParticipation()); return; }
        if (abort && engine.committed().phase() == RoundPhase.PREPARING) {
            reply.accept(new Reply(false, "世界准备阶段保留原轮重试；本批只中止已就绪的轮次。")); return;
        }
        if (mutationPending() || !events.isEmpty()) { reply.accept(new Reply(false, "上一项状态仍在提交，请稍后重试。")); return; }
        long accepted = clock.nanoTime();
        eventTime = accepted;
        try {
            dev.hardrush.core.admin.AdminText.text(actor, 256);
            dev.hardrush.core.admin.AdminText.reason(reason);
            if (actor.length() > 256) throw new IllegalArgumentException("操作者标识超过审计字段长度。");
            var transition = proposal.get();
            var audit = new AdminAudit(transition.eventId(), transition.after().roundId(), actor,
                    abort ? "ABORT" : "START", reason, adminValue(transition.before()), adminValue(transition.after()),
                    System.currentTimeMillis());
            if (abort) adminBoundary = accepted;
            submit(transition, audit, reply, success);
        } catch (IllegalStateException | IllegalArgumentException error) { reply.accept(new Reply(false, error.getMessage())); }
        finally { eventTime = Long.MIN_VALUE; }
    }

    private static String adminValue(RoundSnapshot round) {
        return "round=" + round.roundId() + ",version=" + round.version() + ",phase=" + round.phase()
                + ",challengeNanos=" + round.challengeNanos() + ",uncertain=" + round.timingUncertain()
                + ",result=" + round.result() + ",rules=" + round.rules().fingerprint();
    }
    public CompletableFuture<BookClaim> ensureBook(UUID player, UUID roundId, int edition, int volume) {
        return durableBook(() -> store.ensureAutomaticBookClaim(player, roundId, edition, volume));
    }
    public CompletableFuture<Boolean> markBookDelivered(UUID player, UUID roundId, int edition, int volume) {
        return durableBook(() -> store.markBookDelivered(player, roundId, edition, volume));
    }

    private <T> CompletableFuture<T> durableBook(IoAction<T> action) {
        return query(action, true);
    }

    /** Bounded async reads; public futures are completed on the server callback executor. */
    private <T> CompletableFuture<T> query(IoAction<T> action) {
        return query(action, false);
    }
    private <T> CompletableFuture<T> query(IoAction<T> action, boolean durable) {
        if (stopping || state != State.OPEN) return CompletableFuture.failedFuture(new IllegalStateException(status()));
        if (queriesPending >= 32) return CompletableFuture.failedFuture(new IllegalStateException("历史查询繁忙，请稍后重试。"));
        queriesPending++;
        var delivered = new CompletableFuture<T>();
        supplyIo(action).whenComplete((value, error) -> {
            // The shutdown barrier sees failed durable IO without requiring a main-thread callback.
            if (durable && error != null) evidenceFailure = error;
            callbacks.execute(() -> {
            queriesPending--;
            if (stopping) delivered.completeExceptionally(new IllegalStateException("服务器正在停止。"));
            else if (error != null) {
                if (durable) fail("历史书待领状态保存失败", error);
                delivered.completeExceptionally(error);
            }
            else delivered.complete(value);
            });
        });
        return delivered;
    }

    public String status() {
        if (state == State.OPENING) return "P1 基础正在打开轮次数据库，请稍后查询。";
        if (state == State.FAILED) return "P1 基础已停止状态变更：" + problem;
        if (state == State.CLOSED) return "P1 基础已停止。";
        var round = engine.committed();
        return "P1 轮次 " + round.sequence() + "（" + round.roundId() + "），阶段 " + round.phase()
                + "，本轮模式 " + modeName(round.rules().mode()) + "，下轮模式 " + modeName(nextRules.mode())
                + "，已持久化版本 " + round.version() + "，规则 " + round.rules().fingerprint()
                + "。" + (mutationPending() ? "正在提交状态。" : "")
                + (adaptersReady ? "有效挑战 " + engine.view().challengeMillis() + " ms，在线活人 "
                    + engine.view().onlineHumans() + "，暂停原因 " + engine.view().pauseReason() + "。"
                    : unsupportedPhase ? "已保留原阶段；正在核对世界与档案。" : ADAPTERS_PENDING)
                + (round.result() == null ? "" : "结算 " + round.result().kind() + "，胜者 "
                    + (round.result().winner() == null ? "待核验" : round.participants().get(round.result().winner()).name()
                        + "（" + round.result().winner() + "）")
                    + "，阶段剩余 " + engine.view().remainingPhaseMillis() + " ms。");
    }

    public Reply requestJoin(UUID playerId, String name) {
        Objects.requireNonNull(playerId); Objects.requireNonNull(name);
        return blockedParticipation();
    }

    public Reply requestReady(UUID playerId, boolean ready) {
        Objects.requireNonNull(playerId);
        return blockedParticipation();
    }

    public void requestJoin(UUID playerId, String name, Consumer<Reply> reply) {
        requestAction(() -> engine.proposeAdmissionJoin(UUID.randomUUID(), playerId, name), reply, "已加入本轮。");
    }

    public void requestReady(UUID playerId, boolean ready, Consumer<Reply> reply) {
        if (ready && engine != null && engine.committed().rules().mode() == GameMode.HARDCORE) {
            reply.accept(new Reply(false, "极限模式死亡／怪物／复生闭环将在 P2 接通，本批禁止极限开赛。"));
            return;
        }
        requestAction(() -> engine.proposeReady(UUID.randomUUID(), playerId, ready), reply,
                ready ? "已就绪；首位就绪玩家触发本轮开局倒计时。" : "已取消就绪。");
    }

    private void requestAction(java.util.function.Supplier<RoundTransition> action, Consumer<Reply> reply, String success) {
        if (terminalCandidate != null || shutdownBoundary != null) { reply.accept(new Reply(false, "本轮已冻结，不能开始新的竞技操作。")); return; }
        if (state != State.OPEN || stopping || !adaptersReady) { reply.accept(blockedParticipation()); return; }
        if (mutationPending() || !events.isEmpty()) { reply.accept(new Reply(false, "上一项状态仍在提交，请稍后重试。")); return; }
        try { submit(action.get(), reply, success); }
        catch (IllegalStateException | IllegalArgumentException error) { reply.accept(new Reply(false, error.getMessage())); }
    }

    private Reply blockedParticipation() {
        return new Reply(false, state == State.OPEN ? ADAPTERS_PENDING : status());
    }

    /** Connection state is reconciled after the current durable transition. */
    public void onOnline(UUID playerId, String name, boolean connected) {
        if (stopping) return;
        if (connected) online.put(playerId, name);
        else online.remove(playerId);
        if (shutdownBoundary != null || adminBoundary != null) return;
        if (terminalCandidate != null && !terminalQueued) return;
        if (adaptersReady && engine != null && (engine.committed().participants().containsKey(playerId)
                || pendingTransition != null && pendingTransition.after().participants().containsKey(playerId))) {
            enqueue(e -> e.proposeOnline(UUID.randomUUID(), playerId, name, connected));
        }
    }

    public void normalDeath(UUID playerId, int lifeSequence) {
        normalDeath(playerId, lifeSequence, null);
    }

    public void normalDeath(UUID playerId, int lifeSequence, UUID batchId) {
        if (engine == null || engine.committed().phase() != RoundPhase.RUNNING) return;
        if (terminalCandidate != null) {
            if (terminalQueued || !terminalCandidate.batchId().equals(batchId)) return;
            enqueueAt(terminalCandidate.observedNanoTime(), e -> e.proposeNormalDeath(UUID.randomUUID(), playerId, lifeSequence));
        } else enqueue(e -> e.proposeNormalDeath(UUID.randomUUID(), playerId, lifeSequence));
    }

    public void normalRespawn(UUID playerId, int lifeSequence) {
        if (stopping || state != State.OPEN || !adaptersReady || competitionLocked()
                || Objects.equals(requestedRespawns.get(playerId), lifeSequence)) return;
        requestedRespawns.put(playerId, lifeSequence);
        enqueue(e -> e.proposeAdmissionRespawn(UUID.randomUUID(), playerId, lifeSequence));
    }

    private long engineTime() {
        if (eventTime != Long.MIN_VALUE) return eventTime;
        if (adminBoundary != null) return adminBoundary;
        if (failureBoundary != null) return failureBoundary;
        if (shutdownBoundary != null) return shutdownBoundary;
        return clock.nanoTime();
    }

    private void enqueue(Function<RoundEngine, RoundTransition> action) {
        enqueueAt(clock.nanoTime(), action);
    }

    private void enqueueAt(long time, Function<RoundEngine, RoundTransition> action) {
        if (stopping || state != State.OPEN || !adaptersReady) return;
        if (adminBoundary != null || shutdownBoundary != null && time > shutdownBoundary) return;
        if (events.size() >= 256) { fail("关键事件队列超出预算", new IllegalStateException("保留存档并停止比赛")); return; }
        events.add(new QueuedEvent(time, action));
        drainEvent();
    }

    private void drainEvent() {
        if (mutationPending() || events.isEmpty() || state != State.OPEN) return;
        var event = events.remove();
        eventTime = event.time();
        try { submit(event.action().apply(engine)); }
        catch (RuntimeException error) { fail("关键事件应用失败", error); }
        finally { eventTime = Long.MIN_VALUE; }
    }

    /** Never reports the next mode as changed before SQLite accepts it. */
    public void updateNextMode(GameMode mode, String actor, Consumer<Reply> reply) {
        updateNextMode(mode, actor, "管理命令设置下一轮模式", reply);
    }

    public void updateNextMode(GameMode mode, String actor, String reason, Consumer<Reply> reply) {
        Objects.requireNonNull(mode); Objects.requireNonNull(actor); Objects.requireNonNull(reply);
        if (state != State.OPEN || stopping) { reply.accept(new Reply(false, status())); return; }
        if (mutationPending()) { reply.accept(new Reply(false, "上一项状态仍在提交，请稍后重试。")); return; }
        var proposed = nextRules.withMode(mode);
        var eventId = UUID.randomUUID();
        var audit = new AdminAudit(eventId, engine.committed().roundId(), actor, "NEXT_RULES", reason,
                nextRules.encode(), proposed.encode(), System.currentTimeMillis());
        var write = supplyIo(() -> store.savePendingRulesAdmin(encodedRules(proposed), eventId, audit));
        pendingRulesWrite = write;
        write.whenComplete((result, error) -> callbacks.execute(() -> {
            if (stopping || pendingRulesWrite != write) return;
            pendingRulesWrite = null;
            if (state == State.FAILED) { reply.accept(new Reply(false, problem)); return; }
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
        if (shutdownBoundary != null || terminalCandidate != null && !terminalQueued) return;
        if (stopping || state != State.OPEN || unsupportedPhase || mutationPending()) return;
        if (!events.isEmpty()) { drainEvent(); return; }
        var round = engine.committed();
        for (var participant : round.participants().values()) {
            var name = online.get(participant.id());
            boolean connected = name != null;
            // A newly admitted life deliberately remains offline until the profile adapter unlocks it.
            if (participant.role() == dev.hardrush.core.round.ParticipantRole.HUMAN_ACTIVE && !participant.online() && connected) continue;
            if (participant.online() != connected || connected && !participant.name().equals(name)) {
                submit(engine.proposeOnline(UUID.randomUUID(), participant.id(),
                        connected ? name : participant.name(), connected));
                return;
            }
        }
        long now = clock.nanoTime();
        if (now - lastCheckpointNanos >= CHECKPOINT_NANOS) {
            lastCheckpointNanos = now;
            if (adaptersReady) submit(engine.proposeAdmissionTick(UUID.randomUUID()));
            else submit(engine.proposeCheckpoint(UUID.randomUUID(), false));
        }
    }

    private void submit(RoundTransition transition) {
        submit(transition, result -> {}, "");
    }

    private void submit(RoundTransition transition, Consumer<Reply> reply, String success) {
        submit(transition, null, reply, success);
    }

    private void submit(RoundTransition transition, AdminAudit audit, Consumer<Reply> reply, String success) {
        pendingTransition = transition;
        var after = transition.after();
        var write = supplyIo(() -> audit == null ? commitTransition(transition) : store.commitAdmin(new SnapshotWrite(
                after.roundId(), transition.before().version(), after.version(), RoundSnapshotCodec.encode(after),
                transition.eventId(), transition.eventType()), audit));
        pendingWrite = write;
        write.whenComplete((result, error) -> callbacks.execute(() -> {
            if (stopping || pendingWrite != write || pendingTransition != transition) return;
            pendingWrite = null;
            pendingTransition = null;
            complete(transition, result, error);
            if (state == State.OPENING) state = State.OPEN;
            reply.accept(new Reply(state == State.OPEN, state == State.OPEN ? success : problem));
            drainEvent();
        }));
    }

    private CommitResult commitTransition(RoundTransition transition) throws Exception {
        var after = transition.after();
        var write = new SnapshotWrite(after.roundId(), transition.before().version(), after.version(),
                RoundSnapshotCodec.encode(after), transition.eventId(), transition.eventType());
        if (transition.eventType().equals("FINISH") && terminalCandidate != null) {
            return store.commitTerminal(write, new EvidenceWrite(terminalCandidate.roundId(), terminalCandidate.eventId(),
                    "COMBAT", CombatEventCodec.encode(terminalCandidate)), firstDragonId);
        }
        return store.commit(write);
    }

    private void complete(RoundTransition transition, CommitResult result, Throwable error) {
        if (error == null && (result == CommitResult.APPLIED || result == CommitResult.DUPLICATE)) {
            if (!engine.accept(transition)) fail("轮次提交回调标签已过期", new IllegalStateException("旧轮次回调被拒绝"));
            else if (!stopping && state != State.FAILED) {
                try { committedListener.accept(transition.before(), transition.after()); }
                catch (RuntimeException failure) { fail("已持久化状态的玩家应用失败", failure); }
            }
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
        long shutdownTime = shutdownBoundary == null ? clock.nanoTime() : shutdownBoundary;
        if (terminalCandidate != null && !terminalQueued) finishDamageBatch(terminalCandidate.roundId(), terminalCandidate.batchId());
        stopping = true;
        Throwable failure = null;
        try {
            if (engine == null && state != State.FAILED) {
                eventTime = shutdownTime;
                install(opening.get());
                eventTime = Long.MIN_VALUE;
            }
            if (pendingWrite != null) {
                var transition = pendingTransition;
                try { complete(transition, pendingWrite.get(), null); }
                catch (Exception error) { complete(transition, null, error); throw error; }
                if (engine.committed().phase() == RoundPhase.RECOVERY_REQUIRED) throw new IllegalStateException(problem);
                pendingWrite = null;
                pendingTransition = null;
            }
            if (pendingRulesWrite != null) {
                requireCommitted(pendingRulesWrite.get());
                pendingRulesWrite = null;
            }
            if (pendingAdminWrite != null) {
                try { pendingAdminWrite.get(); }
                catch (java.util.concurrent.ExecutionException failedRequest) {
                    if (!(rootCause(failedRequest) instanceof IllegalArgumentException)) throw failedRequest;
                }
                pendingAdminWrite = null;
            }
            if (pendingRotationWrite != null) {
                requireCommitted(pendingRotationWrite.get());
                eventTime = shutdownTime;
                installNextRound(pendingNextRound);
                eventTime = Long.MIN_VALUE;
                pendingRotationWrite = null;
                pendingNextRound = null;
            }
            while (!events.isEmpty() && engine.committed().phase() != RoundPhase.RECOVERY_REQUIRED) {
                var event = events.remove();
                eventTime = event.time();
                var transition = event.action().apply(engine);
                eventTime = Long.MIN_VALUE;
                var result = supplyIo(() -> commitTransition(transition)).get();
                complete(transition, result, null);
                requireCommitted(result);
            }
            // Every accepted evidence write precedes this barrier on the single IO worker.
            // A failed evidence write must never be followed by a clean-stop marker.
            supplyIo(() -> null).get();
            if (evidenceFailure != null) throw new IllegalStateException("战斗证据保存未完成", evidenceFailure);
            if (state == State.OPEN && !unsupportedPhase) {
                eventTime = shutdownTime;
                var transition = engine.proposeCheckpoint(UUID.randomUUID(), true);
                eventTime = Long.MIN_VALUE;
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
            eventTime = Long.MIN_VALUE;
            try {
                supplyIo(() -> { if (store != null) store.close(); return null; }).get();
                if (evidenceFailure != null) throw new IllegalStateException("战斗证据保存未完成", evidenceFailure);
            }
            catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            io.shutdown();
            state = State.CLOSED;
        }
        if (failure != null) throw new IllegalStateException("P1 正常停服保存未完成，请保留数据库并检查日志", failure);
    }

    /** Capture the stop time before the platform drains profile, recording and world IO. */
    public void prepareStop() {
        if (shutdownBoundary == null) shutdownBoundary = clock.nanoTime();
        if (terminalCandidate != null && !terminalQueued) finishDamageBatch(terminalCandidate.roundId(), terminalCandidate.batchId());
    }

    private void fail(String prefix, Throwable error) {
        if (failureBoundary == null) failureBoundary = clock.nanoTime();
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

    private static Throwable rootCause(Throwable error) {
        while ((error instanceof CompletionException || error instanceof java.util.concurrent.ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error;
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
    private record Loaded(RoundSnapshot snapshot, RulesConfig nextRules, boolean fresh, long endEntrants) {}
    private record QueuedEvent(long time, Function<RoundEngine, RoundTransition> action) {}
    @FunctionalInterface private interface IoAction<T> { T run() throws Exception; }
}
