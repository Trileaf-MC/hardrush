package dev.hardrush.round;

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
class MonsterSessionTest {
    @TempDir Path temporary;
    private final UUID round = UUID.randomUUID(), player = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong();
    private final LinkedBlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();

    @Test void choiceAdmissionReturnAndDuplicateDeathsAreDurableAndOutingScoped() throws Exception {
        Path path = seed();
        try (var session = open(path)) {
            var answer = new AtomicReference<RoundSession.Reply>();
            session.requestMonsterRole(round, player, 3, 4, MonsterRole.CREEPER, answer::set);
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
            assertEquals(RoundSession.State.OPEN, session.state(), session.problem());
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
            assertEquals(MonsterDimension.OVERWORLD, saved.monsterDimension());
            assertFalse(saved.revivalEntitled());
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
            var stale = new AtomicReference<RoundSession.Reply>();
            session.requestMonsterDeployCancelled(round, player, 3, 5, stale::set);
            assertFalse(stale.get().success());
            assertEquals(6, session.snapshot().orElseThrow().participants().get(player).monsterDeploymentSequence());
            assertEquals(RoundSession.State.OPEN, session.state());
        }
    }

    @Test void totemThrowAndPickupCommitBeforeRepliesAndRejectOverlappingWrites() throws Exception {
        Path path = seed(true);
        try (var session = open(path)) {
            var thrown = new AtomicReference<RoundSession.Reply>();
            session.requestRevivalTotemDrop(round, player, 3, 4, 1, thrown::set);
            assertNull(thrown.get(), "The floor stack waits for the durable write");
            var overlapping = new AtomicReference<RoundSession.Reply>();
            session.requestRevivalTotemPickup(round, player, 3, 4, 1, overlapping::set);
            assertFalse(overlapping.get().success());
            pump(() -> thrown.get() != null);
            assertTrue(thrown.get().success(), thrown.get().message());
            assertEquals(0, session.snapshot().orElseThrow().participants().get(player).revivalCharges());
            var overdraw = new AtomicReference<RoundSession.Reply>();
            session.requestRevivalTotemDrop(round, player, 3, 4, 1, overdraw::set);
            assertFalse(overdraw.get().success());
            request(reply -> session.requestRevivalTotemPickup(round, player, 3, 4, 2, reply));
        }
        try (var session = open(path)) {
            assertEquals(2, session.snapshot().orElseThrow().participants().get(player).revivalCharges());
        }
    }

    @Test void bufferedDamagePaysCoinsThatBuyGoodsForTheNextSortie() throws Exception {
        UUID human = UUID.randomUUID();
        Path path = seed(new Participant(human, "Human", ParticipantRole.HUMAN_ACTIVE, false, false, 100, 50, 1, false, true));
        try (var session = open(path)) {
            session.onOnline(human, "Human", true);
            pump(() -> !session.mutationPending());
            request(reply -> session.requestMonsterRole(round, player, 3, 4, MonsterRole.ZOMBIE, reply));
            var broke = new AtomicReference<RoundSession.Reply>();
            session.requestShopPurchase(round, player, 3, 4, ShopOffer.ADULT_EGG, 6, broke::set);
            pump(() -> broke.get() != null);
            assertFalse(broke.get().success(), "No coins yet");
            request(reply -> session.requestMonsterDeploy(round, player, 3, 4, reply));
            session.recordMonsterDamage(round, player, 3, 5, human, MonsterDimension.OVERWORLD, 4_000);
            session.recordMonsterDamage(round, player, 3, 5, human, MonsterDimension.OVERWORLD, 3_500);
            session.tick();
            assertEquals(0, session.snapshot().orElseThrow().participants().get(player).wallet().coins(), "Hits are batched");
            clock.addAndGet(1_000_000_000L);
            session.tick();
            pump(() -> !session.mutationPending());
            assertEquals(7, session.snapshot().orElseThrow().participants().get(player).wallet().coins());
            request(reply -> session.requestMonsterReturn(round, player, 3, 5, reply));
            request(reply -> session.requestShopPurchase(round, player, 3, 5, ShopOffer.ADULT_EGG, 6, reply));
            assertEquals(RoundSession.State.OPEN, session.state(), session.problem());
        }
        try (var session = open(path)) {
            var wallet = session.snapshot().orElseThrow().participants().get(player).wallet();
            assertEquals(1, wallet.coins());
            assertEquals(1, wallet.ordered(ShopOffer.ADULT_EGG));
        }
    }

    private Path seed(Participant... others) throws Exception { return seed(false, others); }

    /** A camp monster holding a revival may only revive or transfer it, never hunt. */
    private Path seed(boolean entitled, Participant... others) throws Exception {
        var path = temporary.toRealPath().resolve("monster.sqlite");
        var participant = new Participant(player, "Monster", ParticipantRole.MONSTER_LOBBY, false, false,
                123, 0, 3, entitled, true, MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 4);
        var players = new java.util.LinkedHashMap<UUID, Participant>();
        players.put(player, participant);
        for (var other : others) players.put(other.id(), other);
        var snapshot = new RoundSnapshot(round, 1, 123, 0, RulesConfig.defaults(), RoundPhase.RUNNING,
                PauseReason.SERVER_STOPPED, 123, 0, players, true, false, null);
        try (var store = new SqliteRoundStore(path)) {
            store.commit(new SnapshotWrite(round, -1, 0, RoundSnapshotCodec.encode(snapshot), UUID.randomUUID(), "FIXTURE"));
        }
        return path;
    }

    private RoundSession open(Path path) throws Exception {
        var session = new RoundSession(path, clock::get, callbacks::add);
        pump(() -> session.state() == RoundSession.State.OPEN);
        session.adaptersReady(round);
        pump(() -> !session.mutationPending());
        session.onOnline(player, "Monster", true);
        pump(() -> !session.mutationPending());
        return session;
    }

    private void request(Consumer<Consumer<RoundSession.Reply>> action) throws Exception {
        var answer = new AtomicReference<RoundSession.Reply>();
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
