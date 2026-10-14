package dev.hardrush.persistence;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.combat.CombatEventCodec;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerminalTransactionTest {
    @TempDir Path directory;
    private Path database;
    private final UUID round = UUID.randomUUID(), player = UUID.randomUUID(), dragon = UUID.randomUUID();

    @BeforeEach void databasePath() throws Exception { database = directory.toRealPath().resolve("rounds.sqlite"); }

    @Test void actualDeathAndStoppedSnapshotCommitTogetherThenReplayAcrossLaterCheckpointAndReopen() throws Exception {
        var death = death(true);
        var finish = finish(death, UUID.randomUUID());
        try (var store = new SqliteRoundStore(database)) {
            create(store);
            assertEquals(CommitResult.APPLIED, store.commitTerminal(finish, evidence(death), dragon));
            var terminal = store.terminal(round).orElseThrow();
            assertEquals(death.observedNanoTime(), terminal.capturedNanos());
            assertEquals(dragon, terminal.firstDragonId());
            assertEquals(death, CombatEventCodec.decode(terminal.evidence().payload()));
            assertEquals(player, RoundSnapshotCodec.decode(terminal.snapshot().payload()).result().winner());
            var settled = RoundSnapshotCodec.decode(finish.payload());
            var later = new RoundSnapshot(round, 1, 123, 2, settled.rules(), RoundPhase.CELEBRATING,
                    PauseReason.SETTLED, settled.challengeNanos(), 100, settled.participants(), false, false, settled.result());
            assertEquals(CommitResult.APPLIED, store.commit(new SnapshotWrite(round, 1, 2,
                    RoundSnapshotCodec.encode(later), UUID.randomUUID(), "TICK")));
            assertEquals(CommitResult.DUPLICATE, store.commitTerminal(finish, evidence(death), dragon));
            assertEquals(2, store.load(round).orElseThrow().version());
            assertEquals(1, store.terminal(round).orElseThrow().snapshot().newVersion());
        }
        try (var store = new SqliteRoundStore(database)) {
            assertEquals(CommitResult.DUPLICATE, store.commitTerminal(finish, evidence(death), dragon));
            assertEquals(2, store.load(round).orElseThrow().version());
            assertEquals(1, store.evidence(round, "COMBAT", 10).size());
        }
    }

    @Test void terminalInsertFailureRollsBackEvidenceSnapshotAndEventAndAllowsIdenticalRetry() throws Exception {
        var death = death(true); var finish = finish(death, UUID.randomUUID());
        try (var store = new SqliteRoundStore(database); var raw = raw(); var sql = raw.createStatement()) {
            create(store);
            sql.execute("CREATE TRIGGER fail_terminal BEFORE INSERT ON round_terminals BEGIN SELECT RAISE(ABORT, 'injected'); END");
            assertThrows(SQLException.class, () -> store.commitTerminal(finish, evidence(death), dragon));
            assertEquals(0, store.load(round).orElseThrow().version());
            assertTrue(store.terminal(round).isEmpty());
            assertTrue(store.evidence(round, "COMBAT", 10).isEmpty());
            sql.execute("DROP TRIGGER fail_terminal");
            assertEquals(CommitResult.APPLIED, store.commitTerminal(finish, evidence(death), dragon));
        }
    }

    @Test void competingTerminalCannotReplaceResultAndDoesNotLeaveLosingEvidence() throws Exception {
        var first = death(true); var second = death(false);
        try (var a = new SqliteRoundStore(database); var b = new SqliteRoundStore(database)) {
            create(a);
            assertEquals(CommitResult.APPLIED, a.commitTerminal(finish(first, UUID.randomUUID()), evidence(first), dragon));
            assertEquals(CommitResult.STALE, b.commitTerminal(finish(second, UUID.randomUUID()), evidence(second), dragon));
            assertEquals(first.eventId(), b.terminal(round).orElseThrow().evidence().eventId());
            assertEquals(1, b.evidence(round, "COMBAT", 10).size());
        }
    }

    @Test void unknownActualDeathHasDurableCompletionWithPendingWinner() throws Exception {
        var death = death(false);
        try (var store = new SqliteRoundStore(database)) {
            create(store); store.commitTerminal(finish(death, UUID.randomUUID()), evidence(death), dragon);
            var result = RoundSnapshotCodec.decode(store.load(round).orElseThrow().payload()).result();
            assertEquals(RoundResult.Kind.COMPLETED, result.kind()); assertNull(result.winner());
            assertTrue(result.reason().contains("PENDING_VERIFICATION"));
        }
    }

    @Test void previouslyRecordedIdenticalEvidenceIsReusableButCollisionRejectsWithoutPartialTerminal() throws Exception {
        var death = death(true);
        try (var store = new SqliteRoundStore(database)) {
            create(store);
            assertEquals(CommitResult.APPLIED, store.recordEvidence(evidence(death)));
            var collision = new CombatEvent(round, death.eventId(), death.batchId(), death.sequence(), dragon,
                    Optional.empty(), CombatEvent.Attribution.PENDING_VERIFICATION, death.rawDamageType(),
                    death.rawDirectEntityId(), death.rawCausingEntityId(), death.appliedDamage(), true, death.observedNanoTime());
            assertThrows(EventCollisionException.class, () -> store.commitTerminal(
                    finish(collision, UUID.randomUUID()), evidence(collision), dragon));
            assertTrue(store.terminal(round).isEmpty());
            assertEquals(0, store.load(round).orElseThrow().version());
            assertEquals(CommitResult.APPLIED, store.commitTerminal(finish(death, UUID.randomUUID()), evidence(death), dragon));
            assertEquals(1, store.evidence(round, "COMBAT", 10).size());
        }
    }

    @Test void terminalEventCollidesWithOrdinarySnapshotReplayAndEveryChangedTerminalField() throws Exception {
        var death = death(true); var finish = finish(death, UUID.randomUUID());
        try (var store = new SqliteRoundStore(database)) {
            create(store); store.commitTerminal(finish, evidence(death), dragon);
            assertThrows(EventCollisionException.class, () -> store.commit(finish));
            var changed = new CombatEvent(round, UUID.randomUUID(), death.batchId(), death.sequence(), dragon,
                    death.origin(), death.attribution(), death.rawDamageType(), death.rawDirectEntityId(),
                    death.rawCausingEntityId(), death.appliedDamage(), true, death.observedNanoTime());
            assertThrows(EventCollisionException.class, () -> store.commitTerminal(finish, evidence(changed), dragon));
            var differentPayload = new SnapshotWrite(round, 0, 1,
                    RoundSnapshotCodec.encode(settling(death, 300)), finish.eventId(), "FINISH");
            assertThrows(EventCollisionException.class, () -> store.commitTerminal(differentPayload, evidence(death), dragon));
        }
    }

    @Test void laterGenericCasCannotReplaceWinnerOrRestartChallengeTime() throws Exception {
        var death = death(true); var finish = finish(death, UUID.randomUUID());
        try (var store = new SqliteRoundStore(database)) {
            create(store); store.commitTerminal(finish, evidence(death), dragon);
            var original = RoundSnapshotCodec.decode(finish.payload());
            for (var result : List.of(original.result(), new RoundResult(RoundResult.Kind.COMPLETED, null, "replaced"))) {
                var changed = new RoundSnapshot(round, 1, 123, 2, original.rules(), RoundPhase.SETTLING,
                        PauseReason.SETTLED, result == original.result() ? 999 : original.challengeNanos(), 0,
                        original.participants(), false, false, result);
                assertThrows(IllegalArgumentException.class, () -> store.commit(new SnapshotWrite(round, 1, 2,
                        RoundSnapshotCodec.encode(changed), UUID.randomUUID(), "CHECKPOINT")));
            }
            assertEquals(1, store.load(round).orElseThrow().version());
        }
    }

    @Test void mismatchedWinnerAndWrongEvidenceTagsAreRejectedBeforeAnyWrite() throws Exception {
        var death = death(false);
        try (var store = new SqliteRoundStore(database)) {
            create(store);
            var correct = settling(death, 250);
            var wrong = new RoundSnapshot(round, 1, 123, 1, correct.rules(), correct.phase(), correct.pauseReason(),
                    correct.challengeNanos(), correct.remainingPhaseNanos(), correct.participants(), false, false,
                    new RoundResult(RoundResult.Kind.COMPLETED, player, "invented winner"));
            assertThrows(IllegalArgumentException.class, () -> store.commitTerminal(new SnapshotWrite(round, 0, 1,
                    RoundSnapshotCodec.encode(wrong), UUID.randomUUID(), "FINISH"), evidence(death), dragon));
            assertThrows(IllegalArgumentException.class, () -> store.commitTerminal(finish(death, UUID.randomUUID()), evidence(death), UUID.randomUUID()));
            assertEquals(0, store.load(round).orElseThrow().version()); assertTrue(store.terminal(round).isEmpty());
            assertTrue(store.evidence(round, "COMBAT", 10).isEmpty());
        }
    }

    @Test void nativeWriteLockFailureDoesNotExposeTerminalAndCanRetry() throws Exception {
        var death = death(true); var finish = finish(death, UUID.randomUUID());
        try (var store = new SqliteRoundStore(database, 30); var lock = raw(); var sql = lock.createStatement()) {
            create(store); sql.execute("BEGIN IMMEDIATE");
            var failure = assertThrows(SQLException.class, () -> store.commitTerminal(finish, evidence(death), dragon));
            assertTrue(failure.getMessage().contains("SQLITE_BUSY"));
            assertTrue(store.terminal(round).isEmpty()); assertTrue(store.evidence(round, "COMBAT", 10).isEmpty());
            sql.execute("ROLLBACK");
            assertEquals(CommitResult.APPLIED, store.commitTerminal(finish, evidence(death), dragon));
        }
    }

    @Test void terminalSnapshotOrCausalPayloadTamperingFailsReadsAndReplay() throws Exception {
        var death = death(true); var finish = finish(death, UUID.randomUUID());
        try (var store = new SqliteRoundStore(database); var raw = raw(); var sql = raw.createStatement()) {
            create(store); store.commitTerminal(finish, evidence(death), dragon);
            sql.execute("UPDATE round_terminals SET first_dragon_id='00000000-0000-0000-0000-000000000000'");
            assertThrows(SQLException.class, () -> store.terminal(round));
            assertThrows(SQLException.class, () -> store.commitTerminal(finish, evidence(death), dragon));
        }
    }

    @Test void schemaTwoMigratesWithoutChangingExistingSnapshotAndFailedMigrationPreservesVersion() throws Exception {
        try (var store = new SqliteRoundStore(database)) { create(store); }
        try (var raw = raw(); var sql = raw.createStatement()) {
            sql.execute("DROP TABLE round_terminals"); sql.execute("PRAGMA user_version=2");
        }
        try (var store = new SqliteRoundStore(database)) {
            assertEquals(0, store.load(round).orElseThrow().version()); assertTrue(store.terminal(round).isEmpty());
        }
        try (var raw = raw(); var sql = raw.createStatement()) {
            sql.execute("PRAGMA user_version=2");
        }
        assertThrows(SQLException.class, () -> new SqliteRoundStore(database));
        try (var raw = raw(); var sql = raw.createStatement(); var rows = sql.executeQuery("PRAGMA user_version")) {
            assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
        }
    }

    @Test void migratedLegacyResultCannotBeReplacedOrAssignedInventedCausalEvidence() throws Exception {
        var death = death(true); var finish = finish(death, UUID.randomUUID());
        try (var store = new SqliteRoundStore(database)) { create(store); store.commit(finish); }
        try (var raw = raw(); var sql = raw.createStatement()) {
            sql.execute("DROP TABLE round_terminals"); sql.execute("PRAGMA user_version=2");
        }
        try (var store = new SqliteRoundStore(database)) {
            assertTrue(store.terminal(round).isEmpty(), "Migration must not invent original combat evidence");
            var original = RoundSnapshotCodec.decode(store.load(round).orElseThrow().payload());
            var replaced = new RoundSnapshot(round, 1, 123, 2, original.rules(), RoundPhase.SETTLING, PauseReason.SETTLED,
                    original.challengeNanos(), 0, original.participants(), false, false,
                    new RoundResult(RoundResult.Kind.COMPLETED, null, "replaced"));
            assertThrows(IllegalArgumentException.class, () -> store.commit(new SnapshotWrite(round, 1, 2,
                    RoundSnapshotCodec.encode(replaced), UUID.randomUUID(), "CHECKPOINT")));
            var retried = new RoundSnapshot(round, 1, 123, 2, original.rules(), RoundPhase.SETTLING, PauseReason.SETTLED,
                    original.challengeNanos(), 0, original.participants(), false, false, original.result());
            assertEquals(CommitResult.STALE, store.commitTerminal(new SnapshotWrite(round, 1, 2,
                    RoundSnapshotCodec.encode(retried), UUID.randomUUID(), "FINISH"), evidence(death), dragon));
            assertTrue(store.evidence(round, "COMBAT", 10).isEmpty());
        }
    }

    private Connection raw() throws SQLException { return DriverManager.getConnection("jdbc:sqlite:" + database); }

    private void create(SqliteRoundStore store) throws SQLException {
        assertEquals(CommitResult.APPLIED, store.commit(new SnapshotWrite(round, -1, 0,
                RoundSnapshotCodec.encode(running()), UUID.randomUUID(), "ROUND_CREATED")));
    }

    private RoundSnapshot running() {
        return new RoundSnapshot(round, 1, 123, 0, RulesConfig.defaults().withMode(GameMode.NORMAL), RoundPhase.RUNNING,
                PauseReason.NONE, 100, 0, Map.of(player, new Participant(player, "Alice", ParticipantRole.HUMAN_RESPAWNING,
                true, false, 100, 0, 1, false)), false, false, null);
    }

    private CombatEvent death(boolean known) {
        Optional<CombatOrigin> origin = known ? Optional.of(new CombatOrigin(round, UUID.randomUUID(),
                new CombatActor(player, ParticipantRole.HUMAN_ACTIVE, 1, 0), AttackKind.ARROW,
                Optional.of(UUID.randomUUID()), List.of())) : Optional.empty();
        return new CombatEvent(round, UUID.randomUUID(), UUID.randomUUID(), 1, dragon, origin,
                CombatEvent.classify(round, origin), "minecraft:arrow", origin.flatMap(CombatOrigin::directEntityId),
                Optional.of(player), 1, true, -900);
    }

    private RoundSnapshot settling(CombatEvent death, long challenge) {
        var before = running();
        return new RoundSnapshot(round, 1, 123, 1, before.rules(), RoundPhase.SETTLING, PauseReason.SETTLED,
                challenge, before.rules().successCelebrationMillis() * 1_000_000, before.participants(), false, false,
                DragonDeathArbiter.adjudicate(before, death, dragon));
    }

    private SnapshotWrite finish(CombatEvent death, UUID eventId) {
        return new SnapshotWrite(round, 0, 1, RoundSnapshotCodec.encode(settling(death, 250)), eventId, "FINISH");
    }

    private EvidenceWrite evidence(CombatEvent death) { return new EvidenceWrite(round, death.eventId(), "COMBAT", CombatEventCodec.encode(death)); }
}
