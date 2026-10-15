package dev.hardrush.p1;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.persistence.SnapshotWrite;
import dev.hardrush.persistence.SqliteRoundStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Main-thread callbacks and real SQLite persistence without any real Minecraft client. */
class P2MonsterSessionTest {
    @TempDir Path temporary;
    private final UUID round = UUID.randomUUID(), player = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong();
    private final LinkedBlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();

    @Test void choiceAdmissionReturnAndDuplicateDeathsAreDurableAndOutingScoped() throws Exception {
        Path path = seed();
        try (var session = open(path)) {
            var answer = new AtomicReference<P1LobbySession.Reply>();
            session.requestMonsterSelection(round, player, 3, 4, MonsterRole.CREEPER, MonsterDimension.NETHER, answer::set);
            assertNull(answer.get());
            assertEquals(MonsterRole.ZOMBIE, session.snapshot().orElseThrow().participants().get(player).monsterRole());
            pump(() -> answer.get() != null);
            assertTrue(answer.get().success(), answer.get().message());
            request(reply -> session.requestMonsterDeploy(round, player, 3, 4, reply));
            assertEquals(5, session.snapshot().orElseThrow().participants().get(player).monsterDeploymentSequence());
            assertEquals(ParticipantRole.MONSTER_DEPLOYED, session.snapshot().orElseThrow().participants().get(player).role());
            session.monsterDeath(round, player, 3, 5);
            session.monsterDeath(round, player, 3, 5);
            pump(() -> !session.mutationPending());
            assertEquals(P1LobbySession.State.OPEN, session.state(), session.problem());
            assertEquals(30_000_000_000L, session.snapshot().orElseThrow().participants().get(player).monsterCooldownNanos());
            clock.addAndGet(30_000_000_000L);
            request(reply -> session.requestMonsterDeploy(round, player, 3, 5, reply));
            session.monsterDeath(round, player, 3, 5);
            pump(() -> !session.mutationPending());
            assertEquals(ParticipantRole.MONSTER_DEPLOYED, session.snapshot().orElseThrow().participants().get(player).role());
            assertEquals(6, session.snapshot().orElseThrow().participants().get(player).monsterDeploymentSequence());
            request(reply -> session.requestMonsterReturn(round, player, 3, 6, reply));
            var saved = session.snapshot().orElseThrow().participants().get(player);
            assertEquals(MonsterRole.CREEPER, saved.monsterRole());
            assertEquals(MonsterDimension.NETHER, saved.monsterDimension());
            assertTrue(saved.revivalEntitled());
        }
        try (var store = new SqliteRoundStore(path)) {
            var saved = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
            assertTrue(saved.cleanShutdown());
            assertEquals(6, saved.participants().get(player).monsterDeploymentSequence());
            assertEquals(30_000_000_000L, saved.participants().get(player).monsterCooldownNanos());
        }
    }

    @Test void adapterCancellationKeepsOpportunityAndStaleRequestsCannotMutateTheNextOuting() throws Exception {
        try (var session = open(seed())) {
            request(reply -> session.requestMonsterDeploy(round, player, 3, 4, reply));
            request(reply -> session.requestMonsterDeployCancelled(round, player, 3, 5, reply));
            var cancelled = session.snapshot().orElseThrow().participants().get(player);
            assertEquals(ParticipantRole.MONSTER_LOBBY, cancelled.role());
            assertEquals(0, cancelled.monsterCooldownNanos());
            request(reply -> session.requestMonsterDeploy(round, player, 3, 5, reply));
            var stale = new AtomicReference<P1LobbySession.Reply>();
            session.requestMonsterDeployCancelled(round, player, 3, 5, stale::set);
            assertFalse(stale.get().success());
            assertEquals(6, session.snapshot().orElseThrow().participants().get(player).monsterDeploymentSequence());
            assertEquals(P1LobbySession.State.OPEN, session.state());
        }
    }

    private Path seed() throws Exception {
        var path = temporary.toRealPath().resolve("monster.sqlite");
        var participant = new Participant(player, "Monster", ParticipantRole.MONSTER_LOBBY, false, false,
                123, 0, 3, true, true, MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 4);
        var snapshot = new RoundSnapshot(round, 1, 123, 0, RulesConfig.defaults(), RoundPhase.RUNNING,
                PauseReason.SERVER_STOPPED, 123, 0, Map.of(player, participant), true, false, null);
        try (var store = new SqliteRoundStore(path)) {
            store.commit(new SnapshotWrite(round, -1, 0, RoundSnapshotCodec.encode(snapshot), UUID.randomUUID(), "FIXTURE"));
        }
        return path;
    }

    private P1LobbySession open(Path path) throws Exception {
        var session = new P1LobbySession(path, clock::get, callbacks::add);
        pump(() -> session.state() == P1LobbySession.State.OPEN);
        session.adaptersReady(round);
        pump(() -> !session.mutationPending());
        session.onOnline(player, "Monster", true);
        pump(() -> !session.mutationPending());
        return session;
    }

    private void request(Consumer<Consumer<P1LobbySession.Reply>> action) throws Exception {
        var answer = new AtomicReference<P1LobbySession.Reply>();
        action.accept(answer::set);
        pump(() -> answer.get() != null);
        assertTrue(answer.get().success(), answer.get().message());
    }

    private void pump(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            var callback = callbacks.poll(100, TimeUnit.MILLISECONDS);
            if (callback != null) callback.run();
        }
        assertTrue(done.getAsBoolean(), "server callback timeout");
    }
}
