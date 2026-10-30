package dev.hardrush.round;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.record.ProgressWrite;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.core.round.RoundSnapshotCodec;
import dev.hardrush.persistence.CommitResult;
import dev.hardrush.persistence.SqliteRoundStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Standalone child process; assertions are explicit and never depend on JVM -ea or JUnit. */
public final class RestartProcessFixture {
    private static final UUID PLAYER = UUID.fromString("ae54da5a-1c3c-4d34-8f93-e5b7992a2b76");
    private static final String OLD_NAME = "BeforeRestart";
    private static final String NEW_NAME = "CurrentRound";
    private final AtomicLong clock;
    private final LinkedBlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();

    private RestartProcessFixture(long origin) { clock = new AtomicLong(origin); }

    public static void main(String[] args) throws Exception {
        check(args.length == 3, "Expected operation, phase and database arguments");
        var phase = RoundPhase.valueOf(args[1]);
        var database = Path.of(args[2]);
        var fixture = new RestartProcessFixture(args[0].equals("write") ? nanos(10) : TimeUnit.DAYS.toNanos(40));
        switch (args[0]) {
            case "write" -> fixture.write(database, phase);
            case "recover" -> fixture.recover(database, phase);
            default -> throw new AssertionError("Unknown fixture operation " + args[0]);
        }
        System.out.println("PASS " + args[0] + " " + phase + " pid=" + ProcessHandle.current().pid());
    }

    private void write(Path database, RoundPhase phase) throws Exception {
        var defaults = RulesConfig.defaults().withMode(GameMode.NORMAL);
        var rules = new RulesConfig(defaults.schemaVersion(), defaults.mode(), defaults.minecraftVersion(),
                defaults.difficulty(), defaults.generationRule(), defaults.combatValues(), defaults.protectionRadius(),
                2_000, 4_000, 1_000);
        try (var store = new SqliteRoundStore(database)) {
            equal(CommitResult.APPLIED, store.savePendingRules(rules.encode().getBytes(StandardCharsets.UTF_8),
                    UUID.randomUUID(), "restart-fixture"), "Bootstrap rules committed");
        }
        var session = open(database);
        try {
            // Preserve an actual completed prior round, its causal evidence, statistics and delivered book.
            readyAdapters(session);
            enterRunning(session, OLD_NAME);
            clock.addAndGet(nanos(1));
            progress(session, OLD_NAME, Set.of("minecraft:story/mine_stone"), "a".repeat(64));
            finish(session);
            var previous = session.snapshot().orElseThrow();
            var book = await(session.ensureBook(PLAYER, previous.roundId(), 1, 1));
            check(!book.delivered(), "Initial prior-round book is pending");
            check(await(session.markBookDelivered(PLAYER, previous.roundId(), 1, 1)), "Prior-round book delivered");
            clock.addAndGet(nanos(4));
            tick(session);
            equal(RoundPhase.ROTATING, snapshot(session).phase(), "Prior round reaches rotation");
            previous = snapshot(session);
            Files.write(companion(database, "previous"), RoundSnapshotCodec.encode(previous));
            session.rotationReady(previous.roundId()); // Verified world-release seam; no physical-world claim.
            idle(session);
            equal(2L, snapshot(session).sequence(), "Second round created once");
            check(snapshot(session).participants().isEmpty(), "Old roster absent in second round");
            equal(0L, session.endEntrants(), "Old end count absent in second round");

            if (phase != RoundPhase.PREPARING) {
                readyAdapters(session);
                session.onOnline(PLAYER, NEW_NAME, true);
                session.requestJoin(PLAYER, NEW_NAME, reply -> check(reply.success(), reply.message()));
                idle(session);
            }
            if (phase == RoundPhase.COUNTDOWN || phase == RoundPhase.RUNNING || phase == RoundPhase.SETTLING
                    || phase == RoundPhase.CELEBRATING || phase == RoundPhase.ROTATING) {
                session.requestReady(PLAYER, true, reply -> check(reply.success(), reply.message()));
                idle(session);
            }
            if (phase == RoundPhase.RUNNING || phase == RoundPhase.SETTLING
                    || phase == RoundPhase.CELEBRATING || phase == RoundPhase.ROTATING) {
                clock.addAndGet(nanos(2));
                tick(session);
                unlock(session, NEW_NAME);
                clock.addAndGet(nanos(2));
                session.normalDeath(PLAYER, 1);
                idle(session);
                clock.addAndGet(nanos(4)); // Respawning time must not accrue challenge or human time.
                session.normalRespawn(PLAYER, 1);
                idle(session);
                unlock(session, NEW_NAME);
                clock.addAndGet(nanos(1));
                progress(session, NEW_NAME, Set.of("minecraft:story/mine_stone", "minecraft:story/upgrade_tools"),
                        "b".repeat(64));
                equal(2, snapshot(session).participants().get(PLAYER).lifeSequence(), "Current identity has second life");
                equal(nanos(3), session.view().orElseThrow().challengeNanos(), "Respawn delay excluded");
            }
            if (phase == RoundPhase.SETTLING || phase == RoundPhase.CELEBRATING || phase == RoundPhase.ROTATING) {
                finish(session);
                if (phase != RoundPhase.SETTLING) {
                    tick(session);
                    equal(RoundPhase.CELEBRATING, snapshot(session).phase(), "Current terminal advances to celebration");
                    if (phase == RoundPhase.ROTATING) {
                        clock.addAndGet(nanos(4));
                        tick(session);
                    }
                }
            }
            equal(phase, snapshot(session).phase(), "Requested phase reached through Session transitions");
            session.updateNextMode(GameMode.HARDCORE, "restart-fixture", reply -> check(reply.success(), reply.message()));
            idle(session);
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));
            var boundary = session.view().orElseThrow();
            session.prepareStop();
            clock.addAndGet(nanos(100)); // A slow platform stop drain must not move the captured boundary.
            session.close();
            equal(RoundSession.State.CLOSED, session.state(), "Writer session closed normally");
            try (var store = new SqliteRoundStore(database)) {
                var stopped = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
                equal(phase, stopped.phase(), "Normal stop retains phase");
                equal(boundary.challengeNanos(), stopped.challengeNanos(), "Stop challenge excludes platform drain");
                equal(boundary.remainingPhaseNanos(), stopped.remainingPhaseNanos(), "Stop remainder excludes platform drain");
                equal(boundary.participants(), stopped.participants(), "Stop preserves complete participant identity and accrual");
                check(stopped.cleanShutdown() && !stopped.timingUncertain(), "Durable clean stop is certain");
                Files.write(companion(database, "stopped"), RoundSnapshotCodec.encode(stopped));
                verifyHistory(store, RoundSnapshotCodec.decode(Files.readAllBytes(companion(database, "previous"))), stopped);
            }
        } finally { session.close(); }
    }

    private void recover(Path database, RoundPhase phase) throws Exception {
        var stopped = RoundSnapshotCodec.decode(Files.readAllBytes(companion(database, "stopped")));
        var previous = RoundSnapshotCodec.decode(Files.readAllBytes(companion(database, "previous")));
        try (var store = new SqliteRoundStore(database)) {
            equal(stopped, RoundSnapshotCodec.decode(store.latest().orElseThrow().payload()), "Separate process sees exact stopped snapshot");
            verifyHistory(store, previous, stopped);
        }
        var session = open(database);
        try {
            var restored = snapshot(session);
            // A successful result is never resumed as a celebration; the restart rotates directly.
            boolean rotates = phase == RoundPhase.SETTLING || phase == RoundPhase.CELEBRATING;
            long remaining = rotates ? 0 : stopped.remainingPhaseNanos();
            equal(rotates ? RoundPhase.ROTATING : phase, restored.phase(), "Clean recovery retains phase");
            equal(stopped.roundId(), restored.roundId(), "Clean recovery retains UUID");
            equal(stopped.sequence(), restored.sequence(), "Clean recovery retains sequence");
            equal(stopped.seed(), restored.seed(), "Clean recovery retains seed");
            equal(stopped.rules(), restored.rules(), "Clean recovery retains active rule snapshot");
            equal(GameMode.HARDCORE, session.nextRules().orElseThrow().mode(), "Next mode remains separate from active normal rules");
            equal(stopped.challengeNanos(), restored.challengeNanos(), "No prior-process clock origin or downtime in challenge");
            equal(remaining, restored.remainingPhaseNanos(), "No downtime in phase remainder");
            equal(stopped.result(), restored.result(), "Terminal result survives exactly");
            check(!restored.timingUncertain(), "Normal recovery remains certain");
            equal(stopped.participants().keySet(), restored.participants().keySet(), "Recovered roster is same round only");
            stopped.participants().forEach((id, player) -> equal(player.withSession(player.name(), false),
                    restored.participants().get(id), "Restored identity is intact and offline"));
            clock.addAndGet(TimeUnit.DAYS.toNanos(1));
            tick(session);
            equal(stopped.challengeNanos(), session.view().orElseThrow().challengeNanos(), "Unverified adapters cannot count time");
            equal(remaining, session.view().orElseThrow().remainingPhaseNanos(), "Unverified adapters hold remainder");
            if (phase != RoundPhase.PREPARING) equal(restored, snapshot(session), "Unverified later phase does not mutate");
            readyAdapters(session);
            check(!snapshot(session).cleanShutdown(), "Verified resume durably marks operation dirty");
            equal(stopped.challengeNanos(), snapshot(session).challengeNanos(), "Adapter verification adds no elapsed race time");
            equal(remaining, snapshot(session).remainingPhaseNanos(), "Adapter verification adds no display time");

            switch (phase) {
                case PREPARING -> equal(RoundPhase.WAITING, snapshot(session).phase(), "Recovered worlds become waiting only after verification");
                case WAITING -> {
                    unlock(session, NEW_NAME);
                    session.requestReady(PLAYER, true, reply -> check(reply.success(), reply.message()));
                    idle(session);
                    equal(RoundPhase.COUNTDOWN, snapshot(session).phase(), "Recovered waiting remains playable");
                    equal(nanos(2), snapshot(session).remainingPhaseNanos(), "A new ready action receives full configured countdown");
                }
                case COUNTDOWN -> {
                    check(snapshot(session).participants().get(PLAYER).ready(), "Saved ready flag survives normal stop");
                    clock.addAndGet(nanos(5));
                    tick(session);
                    equal(RoundPhase.COUNTDOWN, snapshot(session).phase(), "Verified worlds alone cannot cancel the restored countdown");
                    equal(stopped.remainingPhaseNanos(), snapshot(session).remainingPhaseNanos(), "Waiting for saved ready profile preserves remainder");
                    unlock(session, NEW_NAME);
                    equal(stopped.remainingPhaseNanos(), snapshot(session).remainingPhaseNanos(), "Reconnection preserves countdown remainder");
                    clock.addAndGet(stopped.remainingPhaseNanos());
                    tick(session);
                    equal(RoundPhase.RUNNING, snapshot(session).phase(), "Recovered countdown starts at exact saved remainder");
                    equal(0L, snapshot(session).challengeNanos(), "Countdown creates no challenge time");
                    equal(ParticipantRole.HUMAN_ACTIVE, snapshot(session).participants().get(PLAYER).role(), "Countdown admits human life");
                    check(!snapshot(session).participants().get(PLAYER).online(), "Profile unlock still gates resumed human timing");
                }
                case RUNNING -> {
                    clock.addAndGet(TimeUnit.DAYS.toNanos(2));
                    tick(session);
                    equal(stopped.challengeNanos(), snapshot(session).challengeNanos(), "Restored offline human cannot run timer");
                    unlock(session, NEW_NAME);
                    clock.addAndGet(nanos(1));
                    tick(session);
                    equal(stopped.challengeNanos() + nanos(1), snapshot(session).challengeNanos(), "Race resumes only after verified reconnect");
                    var player = snapshot(session).participants().get(PLAYER);
                    equal(stopped.participants().get(PLAYER).humanNanos() + nanos(1), player.humanNanos(), "Human accrual resumes from saved duration");
                    equal(2, player.lifeSequence(), "Reconnect cannot replace saved second life");
                }
                case SETTLING, CELEBRATING, ROTATING -> {
                    equal(stopped.challengeNanos(), snapshot(session).challengeNanos(), "Rotation cannot alter result time");
                    rotateRecovered(session, stopped);
                }
                default -> throw new AssertionError("Unsupported matrix phase " + phase);
            }
            session.prepareStop();
            clock.addAndGet(nanos(100));
            session.close();
        } finally { session.close(); }
        try (var store = new SqliteRoundStore(database)) {
            var saved = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
            check(saved.cleanShutdown() && !saved.timingUncertain(), "Recovered session also stops normally");
            verifyHistory(store, previous, stopped, false);
            if (phase == RoundPhase.SETTLING || phase == RoundPhase.CELEBRATING || phase == RoundPhase.ROTATING) {
                equal(3L, saved.sequence(), "Recovered terminal creates exactly one next round");
                equal(GameMode.HARDCORE, saved.rules().mode(), "New round takes pending rules");
                check(saved.participants().isEmpty() && saved.result() == null, "Next round has no old identity or result");
                equal(0L, store.endEntrantCount(saved.roundId()), "New round has no inherited statistics");
            } else {
                equal(stopped.roundId(), saved.roundId(), "Nonterminal resume cannot create another round");
                if (phase == RoundPhase.COUNTDOWN) {
                    var admitted = store.participantRecords(saved.roundId(), 20, 0).getFirst();
                    equal(NEW_NAME, admitted.playerName(), "Resumed countdown admits current name only");
                    equal(0, admitted.deathCount(), "New admission cannot inherit prior death");
                    equal(0, admitted.advancementCount(), "New admission cannot inherit prior progress");
                    equal(null, admitted.firstEndNanos(), "New admission cannot inherit prior end entry");
                }
            }
        }
    }

    private void rotateRecovered(RoundSession session, RoundSnapshot stopped) throws Exception {
        session.rotationReady(stopped.roundId());
        idle(session);
        var next = snapshot(session);
        equal(3L, next.sequence(), "New JVM rotates exactly once");
        check(!next.roundId().equals(stopped.roundId()), "Rotation uses new UUID");
        equal(RoundPhase.PREPARING, next.phase(), "Rotation prepares next round");
        equal(0L, next.challengeNanos(), "New round challenge resets");
        check(next.participants().isEmpty() && next.result() == null, "Identity and result do not cross rotation");
        equal(0L, session.endEntrants(), "End count does not cross rotation");
        session.rotationReady(stopped.roundId());
        equal(next, snapshot(session), "Late old-round release callback cannot rotate twice");
    }

    private static void verifyHistory(SqliteRoundStore store, RoundSnapshot previous, RoundSnapshot current) throws Exception {
        verifyHistory(store, previous, current, true);
    }

    private static void verifyHistory(SqliteRoundStore store, RoundSnapshot previous, RoundSnapshot current,
            boolean exactCurrentParticipation) throws Exception {
        var old = store.roundHistory(previous.roundId()).orElseThrow();
        equal(previous.sequence(), old.sequence(), "Prior history sequence intact");
        equal(previous.seed(), old.seed(), "Prior history seed intact");
        equal(previous.result(), old.result(), "Prior history result intact");
        equal(nanos(1), old.challengeNanos(), "Prior history duration intact");
        check(old.leaderboardEligible(), "Prior normal result remains eligible");
        var roster = store.participantRecords(previous.roundId(), 20, 0);
        equal(1, roster.size(), "Prior history roster intact");
        equal(OLD_NAME, roster.getFirst().playerName(), "Same UUID current name cannot rewrite previous identity");
        equal(1, roster.getFirst().advancementCount(), "Prior progress stays isolated");
        equal(0, roster.getFirst().deathCount(), "Current-round death cannot rewrite prior record");
        equal(nanos(1), roster.getFirst().firstEndNanos(), "Prior first-end time stays isolated");
        equal(Set.of("a".repeat(64)), roster.getFirst().advancementSetFingerprints(), "Prior advancement set stays isolated");
        equal(1L, store.endEntrantCount(previous.roundId()), "Prior end count intact");
        check(store.ensureAutomaticBookClaim(PLAYER, previous.roundId(), 1, 1).delivered(), "Prior delivered book is durable");
        equal(1, store.evidence(previous.roundId(), "COMBAT", 10).size(), "Prior first-dragon evidence remains unique");
        var currentRoster = store.participantRecords(current.roundId(), 20, 0);
        boolean played = current.phase() == RoundPhase.RUNNING || current.result() != null;
        if (exactCurrentParticipation) equal(played ? 1 : 0, currentRoster.size(), "Current roster contains only actual current participation");
        if (played && exactCurrentParticipation) {
            equal(NEW_NAME, currentRoster.getFirst().playerName(), "Current record uses current identity");
            equal(2, currentRoster.getFirst().advancementCount(), "Current progress stays separate");
            equal(1, currentRoster.getFirst().deathCount(), "Current death stays separate");
            equal(nanos(3), currentRoster.getFirst().firstEndNanos(), "Current first-end time stays separate");
            equal(Set.of("b".repeat(64)), currentRoster.getFirst().advancementSetFingerprints(), "Current advancement set stays separate");
        }
        equal(played ? 1L : 0L, store.endEntrantCount(current.roundId()), "Current end count cannot inherit old round");
        equal(current.result() == null ? 1 : 2, store.history(20, 0).size(), "History has exactly the real terminal rounds");
        if (current.result() != null) {
            var history = store.roundHistory(current.roundId()).orElseThrow();
            equal(current.result(), history.result(), "Current terminal result cannot be overwritten on reopen");
            equal(current.challengeNanos(), history.challengeNanos(), "Current terminal duration frozen");
            check(history.leaderboardEligible(), "Clean current terminal remains eligible");
            equal(1, store.evidence(current.roundId(), "COMBAT", 10).size(), "Current terminal evidence is unique");
        } else check(store.roundHistory(current.roundId()).isEmpty(), "Active round cannot appear in historical results");
    }

    private RoundSession open(Path database) throws Exception {
        var session = new RoundSession(database, clock::get, callbacks::add);
        pump(() -> session.state() != RoundSession.State.OPENING);
        equal(RoundSession.State.OPEN, session.state(), session.problem());
        idle(session);
        return session;
    }

    private void readyAdapters(RoundSession session) throws Exception {
        session.adaptersReady(snapshot(session).roundId());
        idle(session);
    }

    private void enterRunning(RoundSession session, String name) throws Exception {
        session.onOnline(PLAYER, name, true);
        if (session.recoveringCountdown()) session.restoreReady(snapshot(session).roundId(), PLAYER, name, true, reply -> check(reply.success(), reply.message()));
        session.requestJoin(PLAYER, name, reply -> check(reply.success(), reply.message()));
        idle(session);
        session.requestReady(PLAYER, true, reply -> check(reply.success(), reply.message()));
        idle(session);
        clock.addAndGet(nanos(2));
        tick(session);
        equal(RoundPhase.RUNNING, snapshot(session).phase(), "Round enters running through countdown");
        unlock(session, name);
    }

    private void unlock(RoundSession session, String name) throws Exception {
        session.onOnline(PLAYER, name, true);
        if (session.recoveringCountdown()) session.restoreReady(snapshot(session).roundId(), PLAYER, name, true, reply -> check(reply.success(), reply.message()));
        idle(session);
        check(snapshot(session).participants().get(PLAYER).online(), "Profile adapter confirms online session");
    }

    private void progress(RoundSession session, String name, Set<String> advancements, String fingerprint) throws Exception {
        session.recordProgress(new ProgressWrite(snapshot(session).roundId(), UUID.randomUUID(), PLAYER, name,
                advancements, fingerprint, true, session.view().orElseThrow().challengeNanos()));
        // A query is queued after the progress IO and its callback, giving an explicit worker barrier.
        equal(1L, await(session.participantCount(snapshot(session).roundId())), "Accepted progress is durable before stop");
    }

    private void finish(RoundSession session) throws Exception {
        UUID round = snapshot(session).roundId(), dragon = UUID.randomUUID();
        int life = snapshot(session).participants().get(PLAYER).lifeSequence();
        var origin = new CombatOrigin(round, UUID.randomUUID(), new CombatActor(PLAYER, ParticipantRole.HUMAN_ACTIVE,
                life, 0), AttackKind.MELEE, Optional.of(PLAYER), List.of());
        var lethal = new CombatEvent(round, UUID.randomUUID(), UUID.randomUUID(), 1, dragon, Optional.of(origin),
                CombatEvent.Attribution.HUMAN, "minecraft:player_attack", Optional.of(PLAYER), Optional.of(PLAYER),
                1, true, clock.get());
        session.captureDragonDeath(lethal, dragon);
        session.finishDamageBatch(round, lethal.batchId());
        idle(session);
        check(snapshot(session).result() != null, "Actual terminal transaction produced result");
    }

    private void tick(RoundSession session) throws Exception { session.tick(); idle(session); }
    private void idle(RoundSession session) throws Exception { pump(() -> !session.mutationPending()); }
    private <T> T await(CompletableFuture<T> future) throws Exception { pump(future::isDone); return future.join(); }

    private void pump(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + nanos(8);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            var task = callbacks.poll(20, TimeUnit.MILLISECONDS);
            if (task != null) task.run();
        }
        check(done.getAsBoolean(), "Asynchronous SQLite callback exceeded 8 seconds");
    }

    private static RoundSnapshot snapshot(RoundSession session) { return session.snapshot().orElseThrow(); }
    private static Path companion(Path database, String suffix) { return database.resolveSibling(database.getFileName() + "." + suffix); }
    private static long nanos(long seconds) { return TimeUnit.SECONDS.toNanos(seconds); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
    }
}
