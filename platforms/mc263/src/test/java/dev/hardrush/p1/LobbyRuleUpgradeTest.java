package dev.hardrush.p1;

import static org.junit.jupiter.api.Assertions.*;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.persistence.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Uses real SQLite and session lifecycle, not only the core restore predicate. */
final class LobbyRuleUpgradeTest {
    @TempDir Path directory;
    final LinkedBlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
    @Test void cleanLegacyCountdownCannotResumeOrJoinAndAuditedClosureAloneCreatesNewSemantics() throws Exception {
        Path db = directory.toRealPath().resolve("old-active.sqlite");
        var old = RulesConfig.defaults().withHuntRule(RulesConfig.HuntRule.LEGACY_VOTE);
        var id = UUID.randomUUID();
        var player = UUID.randomUUID();
        var participant = new Participant(player, "SavedReady", ParticipantRole.LOBBY, true, true, 0, 0, 0, false);
        var stored = new RoundSnapshot(id, 9, 1234, 0, old, RoundPhase.COUNTDOWN,
                PauseReason.NOT_RUNNING, 0, 12_345_000_000L, Map.of(player, participant), true, false, null);
        byte[] original = RoundSnapshotCodec.encode(stored);
        try (var store = new SqliteRoundStore(db)) {
            assertEquals(CommitResult.APPLIED, store.savePendingRules(old.encode().getBytes(StandardCharsets.UTF_8), UUID.randomUUID(), "fixture"));
            assertEquals(CommitResult.APPLIED, store.commit(new SnapshotWrite(id, -1, 0, original, UUID.randomUUID(), "fixture")));
        }
        try (var session = open(db)) {
            assertEquals(RoundPhase.RECOVERY_REQUIRED, session.snapshot().orElseThrow().phase());
            assertEquals(old.fingerprint(), session.snapshot().orElseThrow().rules().fingerprint());
            assertEquals(12_345_000_000L, session.snapshot().orElseThrow().remainingPhaseNanos());
            session.adaptersReady(id); session.onOnline(player, "SavedReady", true); session.tick();
            assertEquals(RoundPhase.RECOVERY_REQUIRED, session.snapshot().orElseThrow().phase());
            assertTrue(session.competitionLocked());
            var rejected = new AtomicReference<P1LobbySession.Reply>();
            session.requestReady(player, true, rejected::set); assertFalse(rejected.get().success());
            try (var store = new SqliteRoundStore(db)) { assertArrayEquals(original, store.latest().orElseThrow().payload()); }
            assertEquals(RulesConfig.HuntRule.PERSONAL_TARGET, session.nextRules().orElseThrow().huntRule());
            var closed = new AtomicReference<P1LobbySession.Reply>();
            session.closeRecovery("operator", "升级前确认结束旧投票轮", closed::set); pump(() -> closed.get()!=null);
            assertTrue(closed.get().success(), closed.get().message());
            assertEquals(RoundPhase.ROTATING, session.snapshot().orElseThrow().phase());
            try (var store = new SqliteRoundStore(db)) {
                assertEquals("CLOSE_RECOVERY", store.audits(id,10,0).getFirst().action());
                assertEquals(old.fingerprint(), store.roundHistory(id).orElseThrow().rules().fingerprint());
            }
        }
        try (var reopened = open(db)) {
            assertEquals(RoundPhase.ROTATING, reopened.snapshot().orElseThrow().phase());
            assertEquals(old.fingerprint(), reopened.snapshot().orElseThrow().rules().fingerprint());
            assertEquals(RulesConfig.HuntRule.PERSONAL_TARGET, reopened.nextRules().orElseThrow().huntRule());
        }
    }
    private P1LobbySession open(Path db) throws Exception {
        var session = new P1LobbySession(db, () -> 0, callbacks::add);
        pump(() -> session.state() == P1LobbySession.State.OPEN || session.state() == P1LobbySession.State.FAILED);
        assertEquals(P1LobbySession.State.OPEN, session.state(), session.problem()); return session;
    }
    private void pump(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (!done.getAsBoolean() && System.nanoTime()<deadline) { var task=callbacks.poll(20,TimeUnit.MILLISECONDS); if(task!=null) task.run(); }
        assertTrue(done.getAsBoolean(), "Session IO completed");
    }
}
