package dev.hardrush.p1;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.combat.CombatEventCodec;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.persistence.CommitResult;
import dev.hardrush.persistence.SnapshotWrite;
import dev.hardrush.persistence.SqliteRoundStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Test-only callback withholding creates the actual commit-to-main-thread crash window. */
public final class P3CrashProcessFixture {
    private static final UUID ROUND = UUID.fromString("7f6d8556-f1f4-4a31-9d10-2df267312001");
    private static final UUID HUNTER = UUID.fromString("7f6d8556-f1f4-4a31-9d10-2df267312002");
    private static final UUID VICTIM = UUID.fromString("7f6d8556-f1f4-4a31-9d10-2df267312003");
    private static final UUID WINNER = UUID.fromString("7f6d8556-f1f4-4a31-9d10-2df267312004");
    private static final UUID BATCH = UUID.fromString("7f6d8556-f1f4-4a31-9d10-2df267312005");
    private static final UUID DRAGON = UUID.fromString("7f6d8556-f1f4-4a31-9d10-2df267312006");
    private final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(10));
    private final LinkedBlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();

    public static void main(String[] args) throws Exception {
        check(args.length == 3, "Expected operation, boundary and database");
        var fixture = new P3CrashProcessFixture();
        Path database = Path.of(args[2]);
        if (args[0].equals("write")) fixture.write(database, args[1]);
        else if (args[0].equals("recover")) {
            fixture.recover(database, args[1]);
            System.out.println("PASS recover " + args[1] + " pid=" + ProcessHandle.current().pid());
        } else throw new AssertionError("Unknown operation");
    }

    private void write(Path database, String boundary) throws Exception {
        seed(database);
        // Deliberately not AutoCloseable: only the parent may end this process, with destroyForcibly().
        var session = open(database);
        session.adaptersReady(ROUND);
        idle(session);
        session.onOnline(HUNTER, "Hunter", true);
        idle(session);
        session.onOnline(WINNER, "Winner", true);
        idle(session);
        var deployed = new AtomicReference<P1LobbySession.Reply>();
        session.requestMonsterDeploy(ROUND, HUNTER, 1, 0, deployed::set);
        pump(() -> deployed.get() != null);
        check(deployed.get().success(), deployed.get().message());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3));

        if (!boundary.equals("AWARD")) {
            award(session);
            idle(session);
            check(snapshot(session).participants().get(HUNTER).revivalEntitled(), "Award committed before next boundary");
        }
        var before = snapshot(session);
        Files.write(companion(database, "memory"), RoundSnapshotCodec.encode(before));
        var reply = new AtomicReference<P1LobbySession.Reply>();
        switch (boundary) {
            case "AWARD" -> award(session);
            case "CONSUME" -> session.requestRevival(ROUND, HUNTER, 1, 1, reply::set);
            case "TERMINAL" -> finish(session);
            default -> throw new AssertionError("Unknown boundary " + boundary);
        }
        check(session.mutationPending(), "Boundary has a pending main-thread commit callback");
        // Read on a separate SQLite connection without executing any queued Session callback.
        RoundSnapshot committed = awaitDurable(database, before.version() + 1);
        check(snapshot(session).equals(before), "Engine must still hold its precommit identity");
        check(!callbacks.isEmpty() || awaitCallback(), "Durable commit must have queued its unapplied callback");
        check(reply.get() == null, "Native-effect success callback must not run before forced death");
        check(!committed.cleanShutdown(), "Writer is dirty at the forced boundary");
        verifyBoundary(committed, boundary);
        Files.write(companion(database, "durable"), RoundSnapshotCodec.encode(committed));
        try (var store = new SqliteRoundStore(database)) { verifyRecords(store, committed, boundary); }
        Path marker = companion(database, "ready");
        Path pendingMarker = companion(database, "ready.pending");
        Files.writeString(pendingMarker, boundary + ":" + ProcessHandle.current().pid());
        Files.move(pendingMarker, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        System.out.println("READY " + boundary + " durableVersion=" + committed.version() + ", callback=withheld");
        System.out.flush();
        new CountDownLatch(1).await();
        throw new AssertionError("Writer must be forcibly terminated by its parent");
    }

    private void recover(Path database, String boundary) throws Exception {
        byte[] durableBytes = Files.readAllBytes(companion(database, "durable"));
        var durable = RoundSnapshotCodec.decode(durableBytes);
        try (var store = new SqliteRoundStore(database)) {
            check(Arrays.equals(durableBytes, store.latest().orElseThrow().payload()), "Killed writer's WAL commit survives exactly");
            verifyIntegrity(database);
            verifyRecords(store, durable, boundary);
        }
        // A second open/close in this fresh process must not turn quarantine into a clean competitive resume.
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var session = open(database)) {
                var restored = snapshot(session);
                equal(RoundPhase.RECOVERY_REQUIRED, restored.phase(), "Dirty state requires explicit recovery");
                equal(durable.version(), restored.version(), "Quarantine does not invent a new durable event");
                equal(durable.roundId(), restored.roundId(), "Round UUID retained");
                equal(durable.sequence(), restored.sequence(), "Round sequence retained");
                equal(durable.seed(), restored.seed(), "World seed retained");
                equal(durable.rules(), restored.rules(), "Rules retained");
                equal(durable.result(), restored.result(), "Committed terminal retained");
                check(restored.timingUncertain() && !restored.cleanShutdown(), "Uncertain runtime remains visibly quarantined");
                durable.participants().forEach((id, player) -> equal(player.withSession(player.name(), false),
                        restored.participants().get(id), "Every durable identity retained and offline"));
                check(session.competitionLocked(), "Recovery cannot admit native gameplay");
                session.adaptersReady(ROUND);
                check(!session.mutationPending(), "Adapter-ready cannot release recovery quarantine");
                var joined = new AtomicReference<P1LobbySession.Reply>();
                session.requestJoin(UUID.randomUUID(), "UnexpectedJoin", joined::set);
                check(joined.get() != null && !joined.get().success(), "New participation is rejected");
                var revived = new AtomicReference<P1LobbySession.Reply>();
                session.requestRevival(ROUND, HUNTER, 1, 1, revived::set);
                check(revived.get() != null && !revived.get().success(), "Old revival callback cannot consume again");
                session.onOnline(HUNTER, "Hunter", true);
                session.captureDeath(ROUND, VICTIM, 1, 0, BATCH);
                session.captureLethalEvidence(lethal());
                session.finishDamageBatch(ROUND, BATCH);
                finish(session); // A repeated dragon signal must not publish another terminal.
                session.rotationReady(ROUND);
                clock.addAndGet(TimeUnit.DAYS.toNanos(100));
                session.tick();
                equal(restored, snapshot(session), "Repeated native events, long downtime and rotation callbacks cannot advance recovery");
                equal(restored.challengeNanos(), session.view().orElseThrow().challengeNanos(), "Challenge time is frozen");
                equal(restored.remainingPhaseNanos(), session.view().orElseThrow().remainingPhaseNanos(), "Phase remainder is frozen");
                check(!session.mutationPending(), "Quarantine did not schedule writes or duplicate rewards");
            }
            try (var store = new SqliteRoundStore(database)) {
                check(Arrays.equals(durableBytes, store.latest().orElseThrow().payload()), "Quarantine close preserves original crash evidence");
                verifyRecords(store, durable, boundary);
            }
        }
    }

    private static void seed(Path database) throws Exception {
        var hunter = new Participant(HUNTER, "Hunter", ParticipantRole.MONSTER_LOBBY, false, false,
                100, 0, 1, false, true, MonsterRole.CREEPER, MonsterDimension.OVERWORLD, 0, 0);
        var victim = new Participant(VICTIM, "Victim", ParticipantRole.HUMAN_ACTIVE, false, false, 100, 0, 1, false);
        var winner = new Participant(WINNER, "Winner", ParticipantRole.HUMAN_ACTIVE, false, false, 100, 0, 1, false);
        var initial = new RoundSnapshot(ROUND, 3, 12345, 0, RulesConfig.defaults(), RoundPhase.RUNNING,
                PauseReason.SERVER_STOPPED, 100, 0, Map.of(HUNTER, hunter, VICTIM, victim, WINNER, winner), true, false, null);
        try (var store = new SqliteRoundStore(database)) {
            equal(CommitResult.APPLIED, store.commit(new SnapshotWrite(ROUND, -1, 0,
                    RoundSnapshotCodec.encode(initial), UUID.randomUUID(), "P3_TEST_SEED")), "Initial fixture stored");
        }
    }

    private void award(P1LobbySession session) {
        session.captureDeath(ROUND, VICTIM, 1, 0, BATCH);
        session.captureDeath(ROUND, HUNTER, 1, 1, BATCH);
        session.captureLethalEvidence(lethal());
        session.finishDamageBatch(ROUND, BATCH);
    }

    private CombatEvent lethal() {
        var origin = new CombatOrigin(ROUND, UUID.randomUUID(),
                new CombatActor(HUNTER, ParticipantRole.MONSTER_DEPLOYED, 1, 1), AttackKind.CREEPER, Optional.empty(), List.of());
        return new CombatEvent(ROUND, UUID.randomUUID(), BATCH, 1, VICTIM, Optional.of(origin), CombatEvent.Attribution.MONSTER,
                "minecraft:explosion", Optional.empty(), Optional.empty(), 20, true, clock.get());
    }

    private void finish(P1LobbySession session) {
        var origin = new CombatOrigin(ROUND, UUID.randomUUID(),
                new CombatActor(WINNER, ParticipantRole.HUMAN_ACTIVE, 1, 0), AttackKind.MELEE, Optional.of(WINNER), List.of());
        var death = new CombatEvent(ROUND, UUID.randomUUID(), UUID.randomUUID(), 2, DRAGON, Optional.of(origin),
                CombatEvent.Attribution.HUMAN, "minecraft:player_attack", Optional.of(WINNER), Optional.of(WINNER), 1, true, clock.get());
        session.captureDragonDeath(death, DRAGON);
        session.finishDamageBatch(ROUND, death.batchId());
    }

    private static void verifyBoundary(RoundSnapshot value, String boundary) {
        var hunter = value.participants().get(HUNTER);
        equal(ParticipantRole.MONSTER_LOBBY, value.participants().get(VICTIM).role(), "Victim death is atomic with award");
        equal(boundary.equals("CONSUME") ? 2 : 1, hunter.lifeSequence(), "Only consumed revival increments life");
        equal(boundary.equals("AWARD"), hunter.revivalEntitled(), "Exactly one nonstacking entitlement or durable consumption");
        equal(boundary.equals("CONSUME") ? ParticipantRole.HUMAN_ACTIVE : ParticipantRole.MONSTER_LOBBY,
                hunter.role(), "Committed identity matches boundary");
        equal(boundary.equals("TERMINAL") ? RoundPhase.SETTLING : RoundPhase.RUNNING, value.phase(), "Committed phase matches boundary");
        if (boundary.equals("TERMINAL")) {
            equal(RoundResult.Kind.COMPLETED, value.result().kind(), "Terminal result committed");
            equal(WINNER, value.result().winner(), "Trusted first-dragon winner committed");
        } else equal(null, value.result(), "Active identity boundary cannot create history");
    }

    private static void verifyRecords(SqliteRoundStore store, RoundSnapshot durable, String boundary) throws Exception {
        verifyBoundary(durable, boundary);
        equal(1, store.participantRecord(ROUND, VICTIM).orElseThrow().deathCount(), "Victim death indexed once");
        equal(boundary.equals("CONSUME") ? 1 : 0, store.participantRecord(ROUND, HUNTER).orElseThrow().revivalCount(),
                "Revival indexed exactly once only after consumption");
        equal(3L, store.participantCount(ROUND), "Exactly the fixture's real participants");
        if (boundary.equals("TERMINAL")) {
            var terminal = store.terminal(ROUND).orElseThrow();
            check(Arrays.equals(RoundSnapshotCodec.encode(durable), terminal.snapshot().payload()), "Original terminal snapshot preserved byte-for-byte");
            equal(DRAGON, terminal.firstDragonId(), "Original dragon identity preserved");
            equal(WINNER, CombatEventCodec.decode(terminal.evidence().payload()).origin().orElseThrow().actor().playerId(),
                    "Original causal winner preserved");
            equal(1, store.evidence(ROUND, "COMBAT", 10).size(), "One original causal terminal evidence");
            var history = store.roundHistory(ROUND).orElseThrow();
            equal(durable.result(), history.result(), "History matches committed original terminal");
            equal(durable.challengeNanos(), history.challengeNanos(), "Final duration stays frozen");
            check(history.trustedWinner() && history.leaderboardEligible(), "Already committed certain terminal keeps its proven historical ranking");
            equal(1, store.history(10, 0).size(), "Exactly one history row");
            equal(1, store.leaderboard(durable.rules().fingerprint(), 10).size(), "Exactly one trusted leaderboard row");
        } else {
            check(store.terminal(ROUND).isEmpty() && store.history(10, 0).isEmpty(), "No invented terminal or history");
            check(store.leaderboard(durable.rules().fingerprint(), 10).isEmpty(), "Active uncertain state cannot rank");
        }
    }

    private RoundSnapshot awaitDurable(Path database, long version) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            try (var store = new SqliteRoundStore(database)) {
                var value = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
                if (value.version() == version) return value;
                check(value.version() < version, "Writer unexpectedly committed another version");
            }
            Thread.sleep(10);
        }
        throw new AssertionError("SQLite commit did not reach the withheld callback boundary");
    }

    private boolean awaitCallback() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (callbacks.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        return !callbacks.isEmpty();
    }

    private static void verifyIntegrity(Path database) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA quick_check")) {
            check(rows.next() && rows.getString(1).equals("ok") && !rows.next(), "SQLite quick_check after forced death");
        }
    }

    private P1LobbySession open(Path database) throws Exception {
        var session = new P1LobbySession(database, clock::get, callbacks::add);
        pump(() -> session.state() != P1LobbySession.State.OPENING);
        equal(P1LobbySession.State.OPEN, session.state(), session.problem());
        idle(session);
        return session;
    }

    private void idle(P1LobbySession session) throws Exception { pump(() -> !session.mutationPending()); }
    private void pump(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            var callback = callbacks.poll(20, TimeUnit.MILLISECONDS);
            if (callback != null) callback.run();
        }
        check(done.getAsBoolean(), "SQLite callback timeout");
    }

    static Path companion(Path database, String suffix) { return database.resolveSibling(database.getFileName() + "." + suffix); }
    private static RoundSnapshot snapshot(P1LobbySession session) { return session.snapshot().orElseThrow(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
    }
}
