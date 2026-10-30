package dev.hardrush.persistence;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.combat.*;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Shared real-rule/SQLite setup; no production crash hooks or Minecraft runtime are involved. */
final class StorageFaultFixture {
    enum Scenario { GRANT, CONSUME, TERMINAL }
    static final UUID ROUND = id("round"), ALICE = id("alice"), BOB = id("bob"), DRAGON = id("dragon");
    static final UUID PROFILE_OPERATION = id("profile-operation");

    record Request(SnapshotWrite snapshot, EvidenceWrite evidence) {
        CommitResult commit(SqliteRoundStore store) throws Exception {
            return evidence == null ? store.commit(snapshot) : store.commitTerminal(snapshot, evidence, DRAGON);
        }
    }

    static void prepare(Path database, Scenario scenario) throws Exception {
        var clock = new Clock();
        var engine = RoundEngine.create(ROUND, 1, 123, RulesConfig.defaults().withMode(GameMode.HARDCORE), clock);
        try (var store = new SqliteRoundStore(database)) {
            require(store.commit(new SnapshotWrite(ROUND, -1, 0, RoundSnapshotCodec.encode(engine.committed()),
                    id("created"), "ROUND_CREATED")) == CommitResult.APPLIED, "initial snapshot");
            accept(store, engine, engine.proposeWorldReady(UUID.randomUUID()));
            accept(store, engine, engine.proposeJoin(UUID.randomUUID(), ALICE, "Alice"));
            accept(store, engine, engine.proposeJoin(UUID.randomUUID(), BOB, "Bob"));
            accept(store, engine, engine.proposeReady(UUID.randomUUID(), ALICE, true));
            accept(store, engine, engine.proposeReady(UUID.randomUUID(), BOB, true));
            clock.millis(30_000);
            accept(store, engine, engine.proposeTick(UUID.randomUUID()));

            RoundTransition target;
            CombatEvent evidence = null;
            if (scenario == Scenario.TERMINAL) {
                var origin = new CombatOrigin(ROUND, id("dragon-origin"),
                        new CombatActor(ALICE, ParticipantRole.HUMAN_ACTIVE, 1, 0), AttackKind.ARROW,
                        Optional.of(id("arrow")), List.of());
                evidence = new CombatEvent(ROUND, id("dragon-event"), id("dragon-batch"), 1, DRAGON,
                        Optional.of(origin), CombatEvent.Attribution.HUMAN, "minecraft:arrow",
                        origin.directEntityId(), Optional.of(ALICE), 1, true, clock.nanoTime());
                target = engine.proposeFinish(event(scenario), DragonDeathArbiter.adjudicate(engine.committed(), evidence, DRAGON));
            } else {
                accept(store, engine, engine.proposeDeathBatch(UUID.randomUUID(), ROUND,
                        List.of(death(engine.committed().participants().get(BOB), Optional.empty())), null));
                clock.millis(30_000);
                accept(store, engine, engine.proposeTick(UUID.randomUUID()));
                var bob = engine.committed().participants().get(BOB);
                accept(store, engine, engine.proposeMonsterDeploy(UUID.randomUUID(), ROUND, BOB,
                        bob.lifeSequence(), bob.monsterDeploymentSequence()));
                bob = engine.committed().participants().get(BOB);
                var origin = new CombatOrigin(ROUND, id("monster-origin"),
                        new CombatActor(BOB, bob.role(), bob.lifeSequence(), bob.monsterDeploymentSequence()),
                        AttackKind.MELEE, Optional.of(BOB), List.of());
                var lethal = new CombatEvent(ROUND, id("human-death"), id("monster-batch"), 1, ALICE,
                        Optional.of(origin), CombatEvent.Attribution.MONSTER, "minecraft:mob_attack",
                        Optional.of(BOB), Optional.of(BOB), 20, true, clock.nanoTime());
                target = engine.proposeDeathBatch(event(Scenario.GRANT), ROUND,
                        List.of(death(engine.committed().participants().get(ALICE), Optional.of(lethal))), null);
                if (scenario == Scenario.CONSUME) {
                    accept(store, engine, target);
                    // Bob's kill already returned him to the camp; he revives from there.
                    bob = engine.committed().participants().get(BOB);
                    target = engine.proposeAdmissionRevival(event(scenario), ROUND, BOB,
                            bob.lifeSequence(), bob.monsterDeploymentSequence());
                }
            }
            Files.write(sidecar(database, "before"), RoundSnapshotCodec.encode(target.before()));
            Files.write(sidecar(database, "after"), RoundSnapshotCodec.encode(target.after()));
            if (evidence != null) Files.write(sidecar(database, "evidence"), CombatEventCodec.encode(evidence));
            // A test-only payload forces dirty page spill before abrupt termination/full-disk failure.
            try (var sql = connection(store).createStatement()) { sql.execute("CREATE TABLE fault_payload(payload BLOB)"); }
        }
    }

    static Request request(Path database, Scenario scenario) throws Exception {
        var after = RoundSnapshotCodec.decode(Files.readAllBytes(sidecar(database, "after")));
        var operations = scenario == Scenario.CONSUME
                ? List.of(new PreparedOperation(PROFILE_OPERATION, "HUMAN_PROFILE", after.version(),
                        (BOB + ":life:2").getBytes(StandardCharsets.UTF_8))) : List.<PreparedOperation>of();
        var write = new SnapshotWrite(ROUND, after.version() - 1, after.version(), RoundSnapshotCodec.encode(after),
                event(scenario), switch (scenario) { case GRANT -> "DEATH_BATCH"; case CONSUME -> "REVIVAL"; case TERMINAL -> "FINISH"; }, operations);
        EvidenceWrite evidence = null;
        if (scenario == Scenario.TERMINAL) {
            byte[] payload = Files.readAllBytes(sidecar(database, "evidence"));
            evidence = new EvidenceWrite(ROUND, CombatEventCodec.decode(payload).eventId(), "COMBAT", payload);
        }
        return new Request(write, evidence);
    }

    static void verify(SqliteRoundStore store, Path database, Scenario scenario, boolean committed) throws Exception {
        var expected = RoundSnapshotCodec.decode(Files.readAllBytes(sidecar(database, committed ? "after" : "before")));
        var actual = store.load(ROUND).orElseThrow();
        require(java.util.Arrays.equals(RoundSnapshotCodec.encode(expected), actual.payload()), "snapshot must match one complete boundary");
        require(store.latest().orElseThrow().version() == expected.version(), "latest version");
        var restored = RoundEngine.restore(RoundSnapshotCodec.decode(actual.payload()), () -> 9_000_000_000_000L).committed();
        require(restored.phase() == RoundPhase.RECOVERY_REQUIRED && restored.timingUncertain(), "dirty recovery must fail closed");
        require(restored.participants().values().stream().noneMatch(Participant::online), "dirty recovery must leave every player offline");
        for (var participant : expected.participants().values()) {
            var record = store.participantRecord(ROUND, participant.id()).orElseThrow();
            require(record.finalRole() == participant.role(), "participant role projection");
            require(record.revivalCount() == (scenario == Scenario.CONSUME && committed && participant.id().equals(BOB) ? 1 : 0), "revival counted once");
            int deaths = scenario == Scenario.TERMINAL ? 0
                    : (participant.id().equals(BOB) || scenario == Scenario.CONSUME || committed ? 1 : 0);
            require(record.deathCount() == deaths, "death counted once");
        }
        var bob = expected.participants().get(BOB);
        if (scenario == Scenario.GRANT) require(bob.revivalEntitled() == committed, "grant entitlement boundary");
        if (scenario == Scenario.CONSUME) {
            require(bob.revivalEntitled() != committed && bob.lifeSequence() == (committed ? 2 : 1), "consumed entitlement/life boundary");
            require(store.pendingOperations(ROUND).size() == (committed ? 1 : 0), "profile intent atomic with consumption");
            if (committed) {
                var operation = store.operation(ROUND, PROFILE_OPERATION).orElseThrow();
                require(operation.stage() == OperationStage.PREPARED && operation.expectedVersion() == actual.version(), "profile intent remains recoverable");
            }
        }
        boolean terminal = scenario == Scenario.TERMINAL && committed;
        require(store.terminal(ROUND).isPresent() == terminal, "unique terminal boundary");
        require(store.evidence(ROUND, "COMBAT", 10).size() == (terminal ? 1 : 0), "combat evidence atomicity");
        require(store.history(10, 0).size() == (terminal ? 1 : 0), "history atomicity");
        if (terminal) require(store.roundHistory(ROUND).orElseThrow().result().winner().equals(ALICE), "original winner");
        try (var sql = connection(store).createStatement()) {
            try (var row = sql.executeQuery("SELECT COUNT(*) FROM store_events WHERE event_id='" + event(scenario) + "'")) {
                require(row.next() && row.getInt(1) == (committed ? 1 : 0), "owning event atomicity");
            }
            try (var row = sql.executeQuery("PRAGMA integrity_check")) { require(row.next() && "ok".equals(row.getString(1)), "SQLite integrity"); }
            try (var row = sql.executeQuery("PRAGMA foreign_key_check")) { require(!row.next(), "SQLite references"); }
        }
    }

    static Connection connection(SqliteRoundStore store) throws Exception {
        var field = SqliteRoundStore.class.getDeclaredField("connection");
        field.setAccessible(true);
        return (Connection) field.get(store);
    }

    static String lateTrigger(Scenario scenario) {
        return scenario == Scenario.TERMINAL ? "AFTER INSERT ON history_rounds"
                : "AFTER UPDATE ON participant_records WHEN NEW.player_id='" + BOB + "'";
    }

    static Path sidecar(Path database, String suffix) { return database.resolveSibling(database.getFileName() + "." + suffix); }
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static UUID id(String value) { return UUID.nameUUIDFromBytes(("fault-" + value).getBytes(StandardCharsets.UTF_8)); }
    private static UUID event(Scenario scenario) { return id("event-" + scenario); }
    private static RoundDeath death(Participant participant, Optional<CombatEvent> cause) {
        return new RoundDeath(participant.id(), participant.lifeSequence(), participant.monsterDeploymentSequence(), cause);
    }
    private static void accept(SqliteRoundStore store, RoundEngine engine, RoundTransition transition) throws Exception {
        require(store.commit(new SnapshotWrite(ROUND, transition.before().version(), transition.after().version(),
                RoundSnapshotCodec.encode(transition.after()), transition.eventId(), transition.eventType())) == CommitResult.APPLIED, "fixture commit");
        require(engine.accept(transition), "fixture accept");
    }
    private static final class Clock implements MonotonicClock {
        long nanos;
        @Override public long nanoTime() { return nanos; }
        void millis(long value) { nanos += value * 1_000_000; }
    }
}
