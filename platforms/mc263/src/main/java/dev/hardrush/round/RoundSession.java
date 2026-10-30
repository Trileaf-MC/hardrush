package dev.hardrush.round;

import dev.hardrush.HardRushProperties;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.RoundEngine;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.core.round.RoundSnapshotCodec;
import dev.hardrush.core.round.RoundTransition;
import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.core.round.MonsterSkill;
import dev.hardrush.core.round.MonsterDimension;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.DragonDeathArbiter;
import dev.hardrush.core.round.RoundDeath;
import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.combat.CombatEventCodec;
import dev.hardrush.persistence.CommitResult;
import dev.hardrush.persistence.SnapshotWrite;
import dev.hardrush.persistence.SqliteRoundStore;
import dev.hardrush.persistence.EvidenceWrite;
import dev.hardrush.storage.StorageFailure;
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
public final class RoundSession implements AutoCloseable {
    public enum State { OPENING, OPEN, FAILED, RECHECKING, CLOSED }
    public record Reply(boolean success, String message) {}

    private static final long CHECKPOINT_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final String ADAPTERS_PENDING =
            "世界准备与玩家档案适配器尚未接通，暂不能加入或就绪。";

    private final MonotonicClock clock;
    private final Path database;
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
    // A clean restart makes saved sessions offline without a player choosing to leave.
    // Hold only that countdown until a saved ready UUID passes the profile/online gate.
    private boolean recoveringCountdown;
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
    private final Map<UUID, Map<UUID, RoundDeath>> deathBatches = new LinkedHashMap<>();
    private record DeathIdentity(int life, int deployment) {}
    private final Map<UUID, DeathIdentity> capturedDeaths = new LinkedHashMap<>();
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
    private CompletableFuture<Loaded> pendingRecoveryWrite;
    private final boolean restoredFullCheckpoint;
    private String startupRecoveryMessage;
    private boolean fullCheckpointSaving;
    private long fullCheckpointStartedNanos;
    private boolean fullCheckpointCaptured;
    private boolean fullCheckpointResumePending;

    public RoundSession(Path database, MonotonicClock clock, Executor callbacks) {
        this(database, clock, callbacks, (message, error) -> {});
    }

    public RoundSession(Path database, MonotonicClock clock, Executor callbacks,
            BiConsumer<String, Throwable> diagnostics) {
        this(database, clock, callbacks, diagnostics, false, "");
    }

    public RoundSession(Path database, MonotonicClock clock, Executor callbacks,
            BiConsumer<String, Throwable> diagnostics, boolean restoredFullCheckpoint, String startupRecoveryMessage) {
        Objects.requireNonNull(database);
        this.database = database.toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock);
        this.callbacks = Objects.requireNonNull(callbacks);
        this.diagnostics = Objects.requireNonNull(diagnostics);
        this.restoredFullCheckpoint = restoredFullCheckpoint;
        this.startupRecoveryMessage = Objects.requireNonNull(startupRecoveryMessage);
        this.io = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "hardrush-round-sqlite");
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
                ? RulesConfig.decode(new String(pending.get(), StandardCharsets.UTF_8)).forNewRound()
                : RulesConfig.defaults();
        if (pending.isEmpty()) {
            String initialMode = HardRushProperties.initialMode();
            if (!initialMode.equals("normal") && !initialMode.equals("hardcore")) {
                throw new IllegalArgumentException("首次开发模式只接受 normal 或 hardcore");
            }
            if (initialMode.equals("normal")) rules = rules.withMode(GameMode.NORMAL);
        }
        requireVersion(rules);
        if (pending.isEmpty() || !java.util.Arrays.equals(pending.get(), encodedRules(rules))) {
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
        engine = restoredFullCheckpoint ? RoundEngine.restoreVerifiedCheckpoint(stored, this::engineTime)
                : !startupRecoveryMessage.isEmpty() ? RoundEngine.restoreForRecovery(stored, this::engineTime) : loaded.fresh()
                ? RoundEngine.create(stored.roundId(), stored.sequence(), stored.seed(), stored.rules(), this::engineTime)
                : RoundEngine.restore(stored, this::engineTime);
        fullCheckpointResumePending = restoredFullCheckpoint;
        if (!startupRecoveryMessage.isEmpty()) problem = startupRecoveryMessage;
        nextRules = loaded.nextRules();
        if (RoundEngine.requiresLegacyClosure(stored)) problem = "旧投票规则活动轮需在旧构建完成，或经审计结束恢复轮；禁止自动恢复投票或切换个人追猎。";
        endEntrants = loaded.endEntrants();
        recoveringCountdown = !RoundEngine.requiresLegacyClosure(stored) && !loaded.fresh() && stored.cleanShutdown() && stored.phase() == RoundPhase.COUNTDOWN
                && stored.participants().values().stream().anyMatch(player -> player.ready()
                        && player.role() == dev.hardrush.core.round.ParticipantRole.LOBBY);
        restoredReady.clear();
        if (recoveringCountdown) stored.participants().values().stream()
                .filter(p -> p.ready() && p.role() == ParticipantRole.LOBBY).forEach(p -> restoredReady.add(p.id()));
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
    public boolean mutationPending() { return pendingWrite != null || pendingRulesWrite != null || pendingRotationWrite != null || pendingAdminWrite != null || pendingRecoveryWrite != null; }
    public Optional<RoundSnapshot> view() { return engine == null ? Optional.empty() : Optional.of(engine.view()); }
    public long endEntrants() { return endEntrants; }
    public void onRecordsChanged(Runnable listener) { recordsChanged = Objects.requireNonNull(listener); }

    /** Immediately closes new competitive actions, even while the terminal transaction is queued. */
    public boolean competitionLocked() {
        return fullCheckpointSaving || fullCheckpointResumePending || shutdownBoundary != null || adminBoundary != null || state != State.OPEN || terminalCandidate != null || engine == null
                || pendingTransition != null && pendingTransition.after().result() != null
                || engine.rescueExpiredAt(clock.nanoTime())
                || engine.committed().phase() != RoundPhase.RUNNING && engine.committed().phase() != RoundPhase.RESCUE;
    }

    public Optional<UUID> terminalBatchId() {
        return terminalCandidate == null ? Optional.empty() : Optional.of(terminalCandidate.batchId());
    }

    /** Called only after the platform verifies the native first dragon and its irreversible death. */
    public void captureDragonDeath(CombatEvent event, UUID dragonId) {
        Objects.requireNonNull(event); Objects.requireNonNull(dragonId);
        if (stopping || adminBoundary != null || shutdownBoundary != null || state != State.OPEN || !adaptersReady || terminalCandidate != null
                || !event.roundId().equals(engine.committed().roundId())
                || pendingTransition != null && pendingTransition.after().result() != null
                || engine.rescueExpiredAt(event.observedNanoTime())
                || engine.committed().result() != null || engine.committed().phase() != RoundPhase.RUNNING
                    && engine.committed().phase() != RoundPhase.RESCUE) return;
        // Validate before locking; source identity is frozen at attack creation, not checked against today's role.
        DragonDeathArbiter.adjudicate(engine.committed(), event, dragonId);
        terminalCandidate = event;
        firstDragonId = dragonId;
    }

    /** Native AFTER_DEATH runs before hurtServer returns its immutable lethal evidence. */
    public void captureDeath(UUID roundId, UUID playerId, int lifeSequence, int deploymentSequence, UUID batchId) {
        if (engine == null || stopping || state != State.OPEN || !roundId.equals(engine.committed().roundId())
                || engine.committed().result() != null || terminalQueued) return;
        var participant = engine.committed().participants().get(playerId);
        var identity = new DeathIdentity(lifeSequence, deploymentSequence);
        if (participant == null || participant.lifeSequence() != lifeSequence
                || participant.monsterDeploymentSequence() != deploymentSequence
                || participant.role() != ParticipantRole.HUMAN_ACTIVE && participant.role() != ParticipantRole.MONSTER_DEPLOYED
                || identity.equals(capturedDeaths.get(playerId))) return;
        capturedDeaths.put(playerId, identity);
        UUID batch = batchId == null ? UUID.randomUUID() : batchId;
        deathBatches.computeIfAbsent(batch, ignored -> new LinkedHashMap<>()).putIfAbsent(playerId,
                new RoundDeath(playerId, lifeSequence, deploymentSequence, Optional.empty()));
        if (batchId == null) finishDamageBatch(roundId, batch);
    }

    public void captureLethalEvidence(CombatEvent event) {
        if (!event.lethal()) return;
        var deaths = deathBatches.get(event.batchId());
        var death = deaths == null ? null : deaths.get(event.victimId());
        if (death != null) deaths.put(event.victimId(), new RoundDeath(death.playerId(), death.lifeSequence(),
                death.deploymentSequence(), Optional.of(event)));
    }

    /** All victims, rewards and self-death commit once, before rescue or completion is published. */
    public void finishDamageBatch(UUID roundId, UUID batchId) {
        if (engine == null || !engine.committed().roundId().equals(roundId)) return;
        boolean completes = terminalCandidate != null && !terminalQueued
                && terminalCandidate.roundId().equals(roundId) && terminalCandidate.batchId().equals(batchId);
        var deaths = deathBatches.remove(batchId);
        if (!completes && (deaths == null || deaths.isEmpty())) return;
        if (completes) terminalQueued = true;
        var terminal = completes ? terminalCandidate : null;
        var dragon = firstDragonId;
        var victims = deaths == null ? List.<RoundDeath>of() : List.copyOf(deaths.values());
        long time = completes ? terminal.observedNanoTime() : clock.nanoTime();
        enqueueAt(time, e -> e.proposeDeathBatch(UUID.randomUUID(), roundId, victims,
                terminal == null ? null : DragonDeathArbiter.adjudicate(e.committed(), terminal, dragon)));
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
        deathBatches.clear();
        capturedDeaths.clear();
        adminBoundary = null;
        requestedRespawns.clear();
        endEntrants = 0;
        adaptersReady = false;
        unsupportedPhase = false;
        recoveringCountdown = false;
        startupRecoveryMessage = "";
        fullCheckpointResumePending = false;
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
        if (phase == RoundPhase.RECOVERY_REQUIRED) return;
        adaptersReady = true;
        unsupportedPhase = false;
        if (phase == RoundPhase.PREPARING) submit(engine.proposeWorldReady(UUID.randomUUID()));
        else if (engine.committed().cleanShutdown() || fullCheckpointResumePending) {
            submit(engine.proposeCheckpoint(UUID.randomUUID(), false), reply -> {
                if (reply.success()) fullCheckpointResumePending = false;
            }, "完整保存点续局已持久化。");
        }
    }

    /** Called on the server thread only; no transition or callback can be in flight. */
    public boolean canSaveFullCheckpoint() {
        return state == State.OPEN && !stopping && adaptersReady && !unsupportedPhase && !competitionLocked()
                && !mutationPending() && events.isEmpty() && evidencePending == 0 && progressPending == 0 && queriesPending == 0
                && deathBatches.isEmpty() && engine.committed().phase() == RoundPhase.RUNNING;
    }

    public boolean beginFullCheckpoint() {
        if (!canSaveFullCheckpoint()) return false;
        fullCheckpointStartedNanos = clock.nanoTime();
        fullCheckpointCaptured = false;
        fullCheckpointSaving = true;
        return true;
    }

    public RoundSnapshot exportFullCheckpoint(Path target) throws Exception {
        if (!fullCheckpointSaving) throw new IllegalStateException("Full checkpoint barrier is not held");
        var transition = engine.proposeCheckpoint(UUID.randomUUID(), false);
        try {
            var result = supplyIo(() -> commitTransition(transition)).get(15, TimeUnit.SECONDS);
            complete(transition, result, null);
            if (state != State.OPEN) throw new IllegalStateException(problem);
            fullCheckpointCaptured = true;
        } catch (Exception error) {
            if (engine.pending()) { engine.reject(transition, "完整保存点状态提交失败"); fail("完整保存点状态提交失败", error); }
            throw error;
        }
        supplyIo(() -> { store.exportCheckpoint(target); return null; }).get(15, TimeUnit.SECONDS);
        return engine.committed();
    }

    public void endFullCheckpoint() {
        fullCheckpointSaving = false;
        if (state == State.OPEN && engine != null && !engine.pending()) {
            if (fullCheckpointCaptured) engine.resumeAfterCheckpoint();
            else engine.excludeFailedCheckpointPause(clock.nanoTime() - fullCheckpointStartedNanos);
        }
        lastCheckpointNanos = clock.nanoTime();
    }

    public String startupRecoveryMessage() {
        if (!startupRecoveryMessage.isEmpty()) return startupRecoveryMessage;
        return engine != null && engine.committed().phase() == RoundPhase.RECOVERY_REQUIRED
                ? "服务器异常退出，当前没有可用于自动续局的完整保存点；已保留对局和档案，请等待管理员处理。" : "";
    }

    public void adapterFailure(String reason, Throwable error) { fail(reason, error); }

    public void recordEvidence(EvidenceWrite evidence) {
        // A committed result closes the round's evidence record; celebration fights are never stored.
        if (stopping || state != State.OPEN || !engine.committed().roundId().equals(evidence.roundId())
                || engine.committed().result() != null) return;
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
                    reply.accept(new Reply(false, "管理目标已改变，请重新查看后执行命令。")); return;
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
        requestAdminTransition(actor, reason, false, () -> engine.proposeAdmissionAdminStart(UUID.randomUUID()), reply,
                "管理员开赛已持久化；本轮计时标记待核验，不自动进入正常榜。");
    }

    public void adminAbort(String actor, String reason, Consumer<Reply> reply) {
        requestAdminTransition(actor, reason, true, () -> engine.proposeAbort(UUID.randomUUID(), reason), reply,
                "本轮已中止并保存原因与审计；立即回大厅，安全释放旧世界后轮换。");
    }

    /** Trusted server API shared by commands and future automatic rules; actor identifies its source. */
    public void requestParticipationExit(UUID roundId, UUID playerId, ParticipantRole expectedRole,
            int expectedLife, int expectedDeployment, ParticipantRole destination,
            String actor, String reason, Consumer<Reply> reply) {
        requestAdminTransition(actor, reason, false,
                () -> engine.proposeParticipationExit(UUID.randomUUID(), roundId, playerId, expectedRole,
                        expectedLife, expectedDeployment, destination), reply,
                destination == ParticipantRole.DISQUALIFIED ? "本轮参赛资格已取消；下轮恢复资格。"
                        : "已退出本轮；本轮不能重新参赛，下轮恢复资格。",
                destination == ParticipantRole.DISQUALIFIED ? "DISQUALIFY" : "WITHDRAW",
                r -> adminValue(r) + ",player=" + playerId + ",identity=" + r.participants().get(playerId));
    }

    /** Seal input during the durable exit, before the old competition profile is suspended. */
    public boolean participationExitReserved(UUID playerId) {
        if (pendingTransition == null) return false;
        var after = pendingTransition.after().participants().get(playerId);
        var before = pendingTransition.before().participants().get(playerId);
        return before != null && after != null && before.role() != after.role() && after.role().isExcluded();
    }

    public void adminGrantRevival(UUID roundId, UUID playerId, int lifeSequence, int expectedCharges,
            int count, String actor, String reason, Consumer<Reply> reply) {
        requestAdminTransition(actor, reason, false,
                () -> engine.proposeAdminGrantRevival(UUID.randomUUID(), roundId, playerId, lifeSequence, expectedCharges, count),
                reply, "已发放 " + count + " 次复生，剩余 " + ((long) expectedCharges + count)
                        + " 次；本轮成绩标记待核验。", "GRANT_REVIVAL",
                r -> adminValue(r) + ",player=" + playerId + ",revivalCharges=" + r.participants().get(playerId).revivalCharges());
    }

    /** This only restores database availability. Native effects and competition remain quarantined. */
    public void recheckStorage(String actor, String reason, Consumer<Reply> reply) {
        if (state != State.FAILED || stopping || shutdownBoundary != null || engine == null) {
            reply.accept(new Reply(false, "仅支持已有轮次的 FAILED 数据库重新核验；启动失败需保留资料并重启处理。")); return;
        }
        if (mutationPending() || evidencePending != 0 || progressPending != 0 || queriesPending != 0) {
            reply.accept(new Reply(false, "已受理的 IO 或回调尚未排空，请稍后重新申请。")); return;
        }
        try { validateAdminMetadata(actor, reason); }
        catch (IllegalArgumentException error) { reply.accept(new Reply(false, error.getMessage())); return; }
        var previous = engine.committed();
        String previousProblem = problem;
        state = State.RECHECKING;
        var write = supplyIo(() -> {
            // Never turn a missing/replaced database into a new empty bootstrap database.
            if (!java.nio.file.Files.isRegularFile(database, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new java.io.IOException("原数据库缺失或不是普通文件，拒绝重新建库");
            }
            if (store != null) { store.close(); store = null; }
            store = new SqliteRoundStore(database);
            store.checkIntegrity();
            var data = store.latest().orElseThrow(() -> new IllegalStateException("原数据库没有轮次快照"));
            var durable = RoundSnapshotCodec.decode(data.payload());
            if (!data.roundId().equals(durable.roundId()) || data.version() != durable.version()
                    || !durable.roundId().equals(previous.roundId()) || durable.version() < previous.version()
                    || durable.sequence() != previous.sequence() || durable.seed() != previous.seed()
                    || !durable.rules().equals(previous.rules())) {
                throw new IllegalStateException("数据库最新轮次与故障目标不符，拒绝替换或回退原轮次");
            }
            requireVersion(durable.rules());
            store.roundHistory(durable.roundId()); // Verify the immutable terminal and any audited projections.
            var pending = store.loadPendingRules().orElseThrow(() -> new IllegalStateException("下轮规则缺失"));
            var rules = RulesConfig.decode(new String(pending, StandardCharsets.UTF_8));
            requireVersion(rules);
            var recovered = RoundEngine.restoreForRecovery(durable, () -> 0);
            var transition = recovered.proposeCheckpoint(UUID.randomUUID(), false);
            var after = transition.after();
            var audit = new AdminAudit(transition.eventId(), after.roundId(), actor, "RECHECK_STORAGE", reason,
                    adminValue(durable) + ";failure=" + dev.hardrush.core.book.SafeBookText.literal(previousProblem, 4096),
                    adminValue(after), System.currentTimeMillis());
            requireCommitted(store.commitAdmin(new SnapshotWrite(after.roundId(), durable.version(), after.version(),
                    RoundSnapshotCodec.encode(after), transition.eventId(), "RECOVERY_RECHECK"), audit));
            return new Loaded(after, rules, false, store.endEntrantCount(after.roundId()));
        });
        pendingRecoveryWrite = write;
        write.whenComplete((loaded, error) -> callbacks.execute(() -> {
            if (stopping || pendingRecoveryWrite != write) return;
            pendingRecoveryWrite = null;
            if (error != null) {
                fail("存储重新核验失败，继续隔离", error); reply.accept(new Reply(false, problem)); return;
            }
            installRecovery(loaded);
            reply.accept(new Reply(true, "数据库已重新核验并持久化审计；仍为 RECOVERY_REQUIRED，全员留厅。世界／档案故障仍需单独修复。"));
        }));
    }

    private void installRecovery(Loaded loaded) {
        engine = RoundEngine.restoreForRecovery(loaded.snapshot(), this::engineTime);
        nextRules = loaded.nextRules();
        endEntrants = loaded.endEntrants();
        events.clear(); requestedRespawns.clear(); deathBatches.clear(); capturedDeaths.clear();
        terminalCandidate = null; firstDragonId = null; terminalQueued = false;
        evidenceFailure = null; failureBoundary = null; adminBoundary = null;
        recoveringCountdown = false; adaptersReady = false; unsupportedPhase = true;
        lastCheckpointNanos = clock.nanoTime();
        problem = "";
        state = State.OPEN;
    }

    public boolean canRetryRecovery() {
        return !stopping && shutdownBoundary == null && engine != null
                && (state == State.FAILED || state == State.OPEN && engine.committed().phase() == RoundPhase.RECOVERY_REQUIRED)
                && !mutationPending() && evidencePending == 0 && progressPending == 0 && queriesPending == 0;
    }

    /** Keep this session object and its adapter bindings; only the worker's database handle is replaced. */
    public void suspendForOnlineRecovery() throws Exception {
        if (!canRetryRecovery()) throw new IllegalStateException("当前不是可重试的故障轮，或已受理的写入尚未排空");
        state = State.RECHECKING;
        supplyIo(() -> { if (store != null) { store.close(); store = null; } return null; }).get(15, TimeUnit.SECONDS);
    }

    public void reloadOnlineCheckpoint(RoundSnapshot expected, String actor, long savedAt) throws Exception {
        if (state != State.RECHECKING) throw new IllegalStateException("在线恢复未持有数据库屏障");
        Loaded loaded = supplyIo(() -> open(database)).get(15, TimeUnit.SECONDS);
        if (!loaded.snapshot().equals(expected)) throw new IllegalStateException("恢复后的数据库与已确认保存点不一致");
        installRecovery(loaded);
        engine = RoundEngine.restoreVerifiedCheckpoint(loaded.snapshot(), this::engineTime);
        online.clear(); restoredReady.clear();
        fullCheckpointResumePending = true;
        startupRecoveryMessage = "管理员已恢复到保存点 " + java.time.Instant.ofEpochMilli(savedAt) + "；成绩待核验";
        var transition = engine.proposeCheckpoint(UUID.randomUUID(), false);
        var audit = new AdminAudit(transition.eventId(), expected.roundId(), actor, "RECOVER_CHECKPOINT",
                "管理员确认在线续局", "故障轮；恢复到=" + java.time.Instant.ofEpochMilli(savedAt),
                adminValue(transition.after()), System.currentTimeMillis());
        var result = supplyIo(() -> store.commitAdmin(new SnapshotWrite(expected.roundId(), expected.version(),
                transition.after().version(), RoundSnapshotCodec.encode(transition.after()), transition.eventId(), "RECOVER_CHECKPOINT"), audit))
                .get(15, TimeUnit.SECONDS);
        requireCommitted(result);
        if (!engine.accept(transition)) throw new IllegalStateException("在线续局提交失效");
    }

    /** Used after a failed attempt so another point can be offered without unlocking gameplay. */
    public void onlineRecoveryFailed(Throwable error) {
        fullCheckpointResumePending = false;
        fail("无法自动恢复，继续保留故障隔离", error);
    }

    public void closeRecovery(String actor, String reason, Consumer<Reply> reply) {
        if (state != State.OPEN || stopping || shutdownBoundary != null || engine == null
                || engine.committed().phase() != RoundPhase.RECOVERY_REQUIRED) {
            reply.accept(new Reply(false, "仅允许结束当前 RECOVERY_REQUIRED 轮次；FAILED 需先重新核验存储。")); return;
        }
        if (mutationPending() || evidencePending != 0 || progressPending != 0 || queriesPending != 0) {
            reply.accept(new Reply(false, "已受理的 IO 尚未排空，请稍后重试。")); return;
        }
        try {
            validateAdminMetadata(actor, reason);
            var transition = engine.proposeCloseRecovery(UUID.randomUUID(), reason);
            var audit = new AdminAudit(transition.eventId(), transition.after().roundId(), actor, "CLOSE_RECOVERY", reason,
                    adminValue(transition.before()), adminValue(transition.after()), System.currentTimeMillis());
            submit(transition, audit, result -> {
                if (result.success()) { unsupportedPhase = false; recordsChanged.run(); }
                reply.accept(result);
            }, "恢复轮次结束决策与审计已持久化；原终局／资格记录保留，待档案、录像与世界安全释放后才创建下轮。");
        } catch (IllegalStateException | IllegalArgumentException error) { reply.accept(new Reply(false, error.getMessage())); }
    }

    private static void validateAdminMetadata(String actor, String reason) {
        dev.hardrush.core.admin.AdminText.text(actor, 256);
        dev.hardrush.core.admin.AdminText.reason(reason);
        if (actor.length() > 256) throw new IllegalArgumentException("操作者标识超过审计字段长度。");
    }

    private void requestAdminTransition(String actor, String reason, boolean abort,
            java.util.function.Supplier<RoundTransition> proposal, Consumer<Reply> reply, String success) {
        requestAdminTransition(actor, reason, abort, proposal, reply, success, abort ? "ABORT" : "START",
                RoundSession::adminValue);
    }

    private void requestAdminTransition(String actor, String reason, boolean abort,
            java.util.function.Supplier<RoundTransition> proposal, Consumer<Reply> reply, String success,
            String action, java.util.function.Function<RoundSnapshot, String> values) {
        if (fullCheckpointSaving || state != State.OPEN || stopping || shutdownBoundary != null || terminalCandidate != null) {
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
                    action, reason, values.apply(transition.before()), values.apply(transition.after()),
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
        if (fullCheckpointSaving && durable) return CompletableFuture.failedFuture(new IllegalStateException("正在保存完整对局"));
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
        if (state == State.OPENING) return "比赛模式正在打开轮次数据库，请稍后查询。";
        if (state == State.RECHECKING) return "正在重新核验原数据库并写入恢复审计；比赛继续隔离。";
        if (state == State.FAILED) return "比赛模式已停止状态变更：" + problem
                + (engine == null ? "" : "轮次 " + engine.committed().roundId() + "，版本 " + engine.committed().version()
                    + "。存储修复后，权限等级 3 的管理员可申请 /hardrush admin recheck-storage <原因>；核验后仍留厅隔离。");
        if (state == State.CLOSED) return "比赛模式已停止。";
        var round = engine.committed();
        return "轮次 " + round.sequence() + "（" + round.roundId() + "），阶段 " + round.phase()
                + "，本轮模式 " + modeName(round.rules().mode()) + "，下轮模式 " + modeName(nextRules.mode())
                + "，已持久化版本 " + round.version() + "，规则 " + round.rules().fingerprint()
                + "。" + (mutationPending() ? "正在提交状态。" : "")
                + (startupRecoveryMessage().isEmpty() ? "" : startupRecoveryMessage() + "。")
                + (round.phase() == RoundPhase.RECOVERY_REQUIRED
                    ? "恢复隔离中；全员留在大厅。权限等级 4 的管理员可用 /hardrush admin recovery 检查并确认在线续局，或用 /hardrush admin close-recovery 放弃故障轮并开始新轮。" : "")
                + (recoveringCountdown ? "正常重启倒计时已暂挂，等待原已就绪玩家完成档案核验。" : "")
                + (adaptersReady ? "有效挑战 " + engine.view().challengeMillis() + " ms，在线活人 "
                    + engine.view().onlineHumans() + "，暂停原因 " + engine.view().pauseReason() + "。"
                    : unsupportedPhase ? "已保留原阶段；正在核对世界与档案。" : ADAPTERS_PENDING)
                + (round.result() == null ? "" : "结算 " + round.result().kind() + "，胜者 "
                    + (round.result().winner() == null ? "待核验" : round.participants().get(round.result().winner()).name()
                        + "（" + round.result().winner() + "）")
                    + "，阶段剩余 " + engine.view().remainingPhaseMillis() + " ms。");
    }

    private java.util.function.Predicate<UUID> assemblyGuard;
    private java.util.Set<UUID> stopAssemblyEligible;
    private final java.util.Set<UUID> restoredReady = new java.util.HashSet<>();
    public void assemblyGuard(java.util.function.Predicate<UUID> guard) { assemblyGuard = Objects.requireNonNull(guard); }
    /** Per-player reservation begins only after the final native position proof has been accepted. */
    public boolean admissionReserved(UUID id) {
        if (pendingTransition == null) return false;
        var after = pendingTransition.after().participants().get(id);
        var before = pendingTransition.before().participants().get(id);
        return after != null && after.role() == ParticipantRole.HUMAN_ACTIVE && (before == null || before.role() == ParticipantRole.LOBBY);
    }
    public boolean recoveringCountdown() { return recoveringCountdown; }
    public boolean originalReady(UUID roundId, UUID id) {
        var round = snapshot().orElse(null);
        return restoredReady.contains(id) && round != null && round.phase() == RoundPhase.COUNTDOWN && round.roundId().equals(roundId)
                && round.participants().containsKey(id) && round.participants().get(id).ready()
                && round.participants().get(id).role() == ParticipantRole.LOBBY;
    }
    /** Guard and round tag are checked at acceptance; late completion is reconciled before any start. */
    public void requestAssembly(UUID roundId, UUID playerId, String name, boolean ready, Consumer<Reply> reply) {
        if (ready && assemblyGuard != null && !assemblyGuard.test(playerId)) {
            reply.accept(new Reply(false, "请在档案核验完成后主动站入集结区。")); return;
        }
        requestAction(() -> engine.proposeAssembly(UUID.randomUUID(), roundId, playerId, name, ready), result ->
                reply.accept(result.success() && ready && assemblyGuard != null && !assemblyGuard.test(playerId)
                        ? new Reply(false, "登记期间已离开集结区，本次准备已失效。") : result),
                ready ? "已准备，离开集结区可取消。" : "已取消准备。");
    }
    /** Only the native adapter can prove a saved UUID has verified and safely regained its circle position. */
    public void restoreReady(UUID roundId, UUID playerId, String name, boolean safe, Consumer<Reply> reply) {
        if (!originalReady(roundId, playerId) || state != State.OPEN || stopping || !adaptersReady
                || shutdownBoundary != null || mutationPending() || !events.isEmpty()) {
            reply.accept(new Reply(false, "上次集结仍在恢复。")); return;
        }
        if (safe && assemblyGuard != null && !assemblyGuard.test(playerId)) {
            reply.accept(new Reply(false, "恢复站位尚未核验。")); return;
        }
        online.put(playerId, name);
        var transition = engine.proposeRestoredReady(UUID.randomUUID(), roundId, playerId, name, safe);
        submit(transition, result -> {
            if (result.success() && engine.committed().roundId().equals(roundId)) {
                restoredReady.remove(playerId);
                if (safe) recoveringCountdown = false;
            }
            reply.accept(result);
        }, safe ? "恢复上次准备，离区取消。" : "原集结落点不安全，已取消准备并放到圈外。");
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
        requestAction(() -> engine.proposeReady(UUID.randomUUID(), playerId, ready), reply,
                ready ? "已就绪；首位就绪玩家触发本轮开局倒计时。" : "已取消就绪。");
    }

    public void requestRevival(UUID roundId, UUID playerId, int lifeSequence, int deploymentSequence,
            Consumer<Reply> reply) {
        requestAction(() -> engine.proposeAdmissionRevival(UUID.randomUUID(), roundId, playerId,
                lifeSequence, deploymentSequence), reply, "复生资格已消费，正在空装返回本轮出生点。");
    }

    public void requestRevivalTotemDrop(UUID roundId, UUID playerId, int lifeSequence, int deploymentSequence,
            int count, Consumer<Reply> reply) {
        requestAction(() -> engine.proposeRevivalTotemDrop(UUID.randomUUID(), roundId, playerId,
                lifeSequence, deploymentSequence, count), reply, "复生图腾已丢出。");
    }

    public void requestRevivalTotemPickup(UUID roundId, UUID playerId, int lifeSequence, int deploymentSequence,
            int count, Consumer<Reply> reply) {
        requestAction(() -> engine.proposeRevivalTotemPickup(UUID.randomUUID(), roundId, playerId,
                lifeSequence, deploymentSequence, count), reply, "复生图腾已拾取。");
    }

    public void requestMonsterSelection(UUID roundId, UUID playerId, int lifeSequence, int deploymentSequence,
            MonsterRole role, MonsterDimension dimension, Consumer<Reply> reply) {
        requestAction(() -> engine.proposeMonsterSelection(UUID.randomUUID(), roundId, playerId,
                lifeSequence, deploymentSequence, role, dimension), reply, "怪物角色和出场维度已保存。");
    }

    /** Trusted durability primitive retained for historical/internal fixtures; no player route calls it. */
    public void requestMonsterDeploy(UUID roundId, UUID playerId, int lifeSequence, int deploymentSequence,
            Consumer<Reply> reply) {
        requestAction(() -> engine.proposeMonsterDeploy(UUID.randomUUID(), roundId, playerId,
                lifeSequence, deploymentSequence), reply, "怪物出场身份已保存。");
    }

    public void requestMonsterRole(UUID round, UUID player, int life, int sortie, MonsterRole role, Consumer<Reply> reply) {
        requestAction(() -> engine.proposeMonsterRole(UUID.randomUUID(), round, player, life, sortie, role),
                reply, "怪物角色已保存；维度由追猎目标决定。");
    }

    public void requestPersonalHunt(dev.hardrush.core.hunt.PersonalHuntRequest request, int sortie, Consumer<Reply> reply) {
        requestAction(() -> engine.proposePersonalHunt(UUID.randomUUID(), request, sortie), reply, "个人追猎出场身份已保存。");
    }

    /** Reserves cooldown in SQLite before the success callback can create a native skill effect. */
    public void requestMonsterSkill(UUID roundId, UUID playerId, int lifeSequence, int deploymentSequence,
            MonsterSkill skill, long nowEpochMillis, Consumer<Reply> reply) {
        if (competitionLocked()) { reply.accept(new Reply(false, "本轮已冻结，不能使用怪物技能。")); return; }
        requestAction(() -> engine.proposeMonsterSkill(UUID.randomUUID(), roundId, playerId,
                lifeSequence, deploymentSequence, skill, nowEpochMillis), reply, "技能冷却已保存。");
    }

    /** Internal compensation for a reserved native effect that failed to begin. */
    public void requestMonsterSkillCancelled(UUID roundId, UUID playerId, int lifeSequence, int deploymentSequence,
            MonsterSkill skill, long consumedReadyAtMillis, long previousReadyAtMillis, Consumer<Reply> reply) {
        if (competitionLocked()) { reply.accept(new Reply(false, "本轮已冻结，不能退回技能冷却。")); return; }
        requestAction(() -> engine.proposeMonsterSkillCancelled(UUID.randomUUID(), roundId, playerId,
                lifeSequence, deploymentSequence, skill, consumedReadyAtMillis, previousReadyAtMillis),
                reply, "技能未生效，已退回本次冷却。");
    }

    public void requestMonsterReturn(UUID roundId, UUID playerId, int lifeSequence, int deploymentSequence,
            Consumer<Reply> reply) {
        requestAction(() -> engine.proposeMonsterReturn(UUID.randomUUID(), roundId, playerId,
                lifeSequence, deploymentSequence), reply, "已返回怪物大厅；再次出场需等待 30 秒。");
    }

    /** Internal platform compensation; no player command may invoke this cooldown refund. */
    public void requestMonsterDeployCancelled(UUID roundId, UUID playerId, int lifeSequence, int deploymentSequence,
            Consumer<Reply> reply) {
        requestAction(() -> engine.proposeMonsterDeployCancelled(UUID.randomUUID(), roundId, playerId,
                lifeSequence, deploymentSequence), reply, "出场安全重验未通过，已回到大厅且保留出场机会。");
    }

    public void monsterDeath(UUID roundId, UUID playerId, int lifeSequence, int deploymentSequence) {
        if (engine == null || stopping || state != State.OPEN || competitionLocked()
                || !engine.committed().roundId().equals(roundId)) return;
        enqueue(e -> {
            var current = e.committed();
            var player = current.participants().get(playerId);
            // Queued duplicate deaths or callbacks from an earlier outing cannot reset a new outing.
            if (!current.roundId().equals(roundId) || current.phase() != RoundPhase.RUNNING
                    || player == null || player.role() != ParticipantRole.MONSTER_DEPLOYED || !player.online()
                    || player.lifeSequence() != lifeSequence || player.monsterDeploymentSequence() != deploymentSequence) {
                return e.proposeCheckpoint(UUID.randomUUID(), false);
            }
            return e.proposeMonsterDeath(UUID.randomUUID(), roundId, playerId, lifeSequence, deploymentSequence);
        });
    }

    private void requestAction(java.util.function.Supplier<RoundTransition> action, Consumer<Reply> reply, String success) {
        if (fullCheckpointSaving) { reply.accept(new Reply(false, "正在保存完整对局，请稍候。")); return; }
        if (terminalCandidate != null || shutdownBoundary != null) { reply.accept(new Reply(false, "本轮已冻结，不能开始新的竞技操作。")); return; }
        if (state != State.OPEN || stopping || !adaptersReady) { reply.accept(blockedParticipation()); return; }
        if (recoveringCountdown) {
            reply.accept(new Reply(false, "正常重启倒计时仍在等待原已就绪玩家完成档案核验，请稍后加入或就绪。")); return;
        }
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
        if (fullCheckpointSaving) return;
        if (shutdownBoundary != null || adminBoundary != null) return;
        if (terminalCandidate != null && !terminalQueued) return;
        boolean awaitingProof = restoredReady.contains(playerId);
        if (awaitingProof && !connected) {
            restoredReady.remove(playerId);
            UUID recoveredRound = engine.committed().roundId();
            enqueue(e -> {
                var current = e.committed(); var saved = current.participants().get(playerId);
                return current.roundId().equals(recoveredRound) && current.phase() == RoundPhase.COUNTDOWN && saved != null && saved.ready()
                        ? e.proposeRestoredReady(UUID.randomUUID(), recoveredRound, playerId, name, false)
                        : e.proposeCheckpoint(UUID.randomUUID(), false);
            });
            return;
        }
        if (recoveringCountdown || awaitingProof) return;
        if (adaptersReady && engine != null && (engine.committed().participants().containsKey(playerId)
                || pendingTransition != null && pendingTransition.after().participants().containsKey(playerId))) {
            enqueue(e -> e.proposeOnline(UUID.randomUUID(), playerId, name, connected));
        }
    }

    public void normalDeath(UUID playerId, int lifeSequence) {
        normalDeath(playerId, lifeSequence, null);
    }

    public void normalDeath(UUID playerId, int lifeSequence, UUID batchId) {
        humanDeath(playerId, lifeSequence, batchId);
    }

    public void humanDeath(UUID playerId, int lifeSequence, UUID batchId) {
        if (engine == null || engine.committed().phase() != RoundPhase.RUNNING) return;
        Function<RoundEngine, RoundTransition> proposal = e -> e.committed().rules().mode() == GameMode.NORMAL
                ? e.proposeNormalDeath(UUID.randomUUID(), playerId, lifeSequence)
                : e.proposeHardcoreDeath(UUID.randomUUID(), playerId, lifeSequence);
        if (terminalCandidate != null) {
            if (terminalQueued || !terminalCandidate.batchId().equals(batchId)) return;
            enqueueAt(terminalCandidate.observedNanoTime(), proposal);
        } else enqueue(proposal);
    }

    public boolean leaveProtection(UUID roundId, UUID playerId, int lifeSequence) {
        if (engine == null || stopping || state != State.OPEN || competitionLocked()
                || !engine.committed().roundId().equals(roundId)) return false;
        enqueue(e -> {
            var current = e.committed();
            var player = current.participants().get(playerId);
            // Native movement can race an earlier accepted death or round close while SQLite is pending.
            // A stale movement callback cannot consume the replacement life's exit or fail the round.
            if (!current.roundId().equals(roundId) || current.phase() != RoundPhase.RUNNING
                    || player == null || player.role() != dev.hardrush.core.round.ParticipantRole.HUMAN_ACTIVE
                    || player.lifeSequence() != lifeSequence || player.hasLeftProtection()) {
                return e.proposeCheckpoint(UUID.randomUUID(), false);
            }
            return e.proposeLeaveProtection(UUID.randomUUID(), playerId, lifeSequence);
        });
        return true;
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
        if (fullCheckpointSaving) { reply.accept(new Reply(false, "正在保存完整对局，请稍候。")); return; }
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

    public void celebrationComplete(UUID roundId) {
        if (stopping || state != State.OPEN || !adaptersReady || mutationPending() || !events.isEmpty()
                || engine == null || !engine.committed().roundId().equals(roundId)) return;
        var round = engine.committed();
        if ((round.phase() == RoundPhase.SETTLING || round.phase() == RoundPhase.CELEBRATING)
                && round.result() != null && round.result().kind() == dev.hardrush.core.round.RoundResult.Kind.COMPLETED) {
            submit(engine.proposeCelebrationComplete(UUID.randomUUID()));
        }
    }

    public void tick() {
        if (shutdownBoundary != null || terminalCandidate != null && !terminalQueued) return;
        if (stopping || state != State.OPEN || unsupportedPhase || mutationPending()) return;
        if (!events.isEmpty()) { drainEvent(); return; }
        if (recoveringCountdown) {
            long now = clock.nanoTime();
            if (now - lastCheckpointNanos >= CHECKPOINT_NANOS) {
                lastCheckpointNanos = now;
                submit(engine.proposeCheckpoint(UUID.randomUUID(), false));
            }
            return;
        }
        if (assemblyGuard != null && engine.committed().phase() == RoundPhase.COUNTDOWN) {
            for (var player : engine.committed().participants().values()) {
                if (player.ready() && player.online() && !assemblyGuard.test(player.id())) {
                    submit(engine.proposeAssembly(UUID.randomUUID(), engine.committed().roundId(), player.id(), player.name(), false)); return;
                }
            }
        }
        var round = engine.committed();
        for (var participant : round.participants().values()) {
            var name = online.get(participant.id());
            boolean connected = name != null;
            // A newly admitted life deliberately remains offline until the profile adapter unlocks it.
            if (participant.role() == dev.hardrush.core.round.ParticipantRole.HUMAN_ACTIVE && !participant.online() && connected) continue;
            if (restoredReady.contains(participant.id()) && !participant.online() && connected) continue;
            if (participant.online() != connected || connected && !participant.name().equals(name)) {
                submit(engine.proposeOnline(UUID.randomUUID(), participant.id(),
                        connected ? name : participant.name(), connected));
                return;
            }
        }
        long now = clock.nanoTime();
        if (now - lastCheckpointNanos >= CHECKPOINT_NANOS) {
            lastCheckpointNanos = now;
            if (adaptersReady) {
                var eligible = assemblyGuard == null ? null : engine.committed().participants().keySet().stream()
                        .filter(assemblyGuard).collect(java.util.stream.Collectors.toSet());
                submit(eligible == null ? engine.proposeAdmissionTick(UUID.randomUUID()) : engine.proposeAdmissionTick(UUID.randomUUID(), eligible));
            }
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
        if (transition.before().result() == null && transition.after().result() != null && terminalCandidate != null) {
            return store.commitTerminal(write, new EvidenceWrite(terminalCandidate.roundId(), terminalCandidate.eventId(),
                    "COMBAT", CombatEventCodec.encode(terminalCandidate)), firstDragonId);
        }
        return store.commit(write);
    }

    private void complete(RoundTransition transition, CommitResult result, Throwable error) {
        if (error == null && (result == CommitResult.APPLIED || result == CommitResult.DUPLICATE)) {
            if (!engine.accept(transition)) fail("轮次提交回调标签已过期", new IllegalStateException("旧轮次回调被拒绝"));
            else if (!stopping && state != State.FAILED) {
                if (transition.after().phase() != RoundPhase.COUNTDOWN) { recoveringCountdown = false; restoredReady.clear(); }
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
            if (pendingRecoveryWrite != null) {
                installRecovery(pendingRecoveryWrite.get());
                pendingRecoveryWrite = null;
            }
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
            if (state == State.OPEN && !recoveringCountdown && assemblyGuard != null && engine.committed().phase() == RoundPhase.COUNTDOWN) {
                for (var player : java.util.List.copyOf(engine.committed().participants().values())) {
                    if (engine.committed().phase() != RoundPhase.COUNTDOWN) break;
                    if (player.ready() && player.online() && !(stopAssemblyEligible == null ? assemblyGuard.test(player.id()) : stopAssemblyEligible.contains(player.id()))) {
                        eventTime = shutdownTime;
                        var cancelled = engine.proposeAssembly(UUID.randomUUID(), engine.committed().roundId(), player.id(), player.name(), false);
                        eventTime = Long.MIN_VALUE;
                        var result = supplyIo(() -> commitTransition(cancelled)).get(); requireCommitted(result); complete(cancelled, result, null);
                    }
                }
            }
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
        if (failure != null) throw new IllegalStateException("比赛正常停服保存未完成，请保留数据库并检查日志", failure);
    }

    /** Capture the stop time before the platform drains profile, recording and world IO. */
    public void prepareStop() {
        if (shutdownBoundary == null) {
            shutdownBoundary = clock.nanoTime();
            if (assemblyGuard != null) stopAssemblyEligible = online.keySet().stream().filter(assemblyGuard).collect(java.util.stream.Collectors.toSet());
        }
        if (terminalCandidate != null && !terminalQueued) finishDamageBatch(terminalCandidate.roundId(), terminalCandidate.batchId());
    }

    private void fail(String prefix, Throwable error) {
        if (failureBoundary == null) failureBoundary = clock.nanoTime();
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        problem = prefix + "：" + (cause == null ? "未知错误" : cause.getMessage()) + "。" + StorageFailure.advice(error);
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
