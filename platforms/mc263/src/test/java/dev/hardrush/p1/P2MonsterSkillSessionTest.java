package dev.hardrush.p1;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.persistence.SnapshotWrite;
import dev.hardrush.persistence.SqliteRoundStore;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite/main-thread arbitration without Minecraft clients or visual acceptance. */
class P2MonsterSkillSessionTest {
    @TempDir Path temporary;
    private final UUID round = UUID.randomUUID(), player = UUID.randomUUID();
    private static final long NOW = 1_800_000_000_000L;
    private final AtomicLong clock = new AtomicLong();
    private final LinkedBlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();

    @Test void effectCallbackFollowsDurableReservationAndRepeatedUseIsRejectedAcrossReopen() throws Exception {
        Path path = seed();
        var effects = new AtomicInteger();
        try (var session = open(path)) {
            request(reply -> session.requestMonsterDeploy(round, player, 3, 4, reply));
            var answer = new AtomicReference<P1LobbySession.Reply>();
            session.requestMonsterSkill(round, player, 3, 5, MonsterSkill.SKELETON_BURST, NOW, reply -> {
                if (reply.success()) {
                    try (var store = new SqliteRoundStore(path)) {
                        var saved = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
                        assertEquals(NOW + 90_000, saved.participants().get(player).monsterSkillReadyAtMillis().get(MonsterSkill.SKELETON_BURST));
                    } catch (Exception error) { throw new AssertionError(error); }
                    effects.incrementAndGet();
                }
                answer.set(reply);
            });
            assertNull(answer.get());
            assertEquals(0, effects.get());
            assertTrue(session.snapshot().orElseThrow().participants().get(player).monsterSkillReadyAtMillis().isEmpty());
            var pending = new AtomicReference<P1LobbySession.Reply>();
            session.requestMonsterSkill(round, player, 3, 5, MonsterSkill.SKELETON_BURST, NOW, pending::set);
            assertFalse(pending.get().success());
            pump(() -> answer.get() != null);
            assertTrue(answer.get().success(), answer.get().message());
            assertEquals(1, effects.get());
            reject(reply -> session.requestMonsterSkill(round, player, 3, 5, MonsterSkill.SKELETON_BURST, NOW, reply));
        }
        try (var session = open(path)) {
            var saved = session.snapshot().orElseThrow().participants().get(player);
            assertEquals(ParticipantRole.MONSTER_LOBBY, saved.role());
            assertEquals(45_000, saved.monsterSkillRemainingMillis(MonsterSkill.SKELETON_BURST, NOW + 45_000));
            clock.addAndGet(30_000_000_000L);
            request(reply -> session.requestMonsterDeploy(round, player, 3, 5, reply));
            reject(reply -> session.requestMonsterSkill(round, player, 3, 6, MonsterSkill.SKELETON_BURST, NOW + 45_000, reply));
            request(reply -> session.requestMonsterSkill(round, player, 3, 6, MonsterSkill.SKELETON_BURST, NOW + 90_000, reply));
            assertEquals(NOW + 180_000, session.snapshot().orElseThrow().participants().get(player).monsterSkillReadyAtMillis().get(MonsterSkill.SKELETON_BURST));
        }
    }

    @Test void sqliteSkillWriteFailureCannotEmitSuccessOrChangeTheDurableDeadline() throws Exception {
        Path path = seed();
        try (var session = open(path)) {
            request(reply -> session.requestMonsterDeploy(round, player, 3, 4, reply));
            long version = session.snapshot().orElseThrow().version();
            try (var raw = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = raw.createStatement()) {
                sql.execute("CREATE TRIGGER fail_skill BEFORE INSERT ON store_events "
                        + "WHEN NEW.event_type='MONSTER_SKILL_SKELETON_BURST' "
                        + "BEGIN SELECT RAISE(FAIL, 'fixture skill storage failure'); END");
                var effects = new AtomicInteger();
                var answer = new AtomicReference<P1LobbySession.Reply>();
                session.requestMonsterSkill(round, player, 3, 5, MonsterSkill.SKELETON_BURST, NOW, reply -> {
                    if (reply.success()) effects.incrementAndGet();
                    answer.set(reply);
                });
                assertNull(answer.get());
                pump(() -> answer.get() != null);
                assertFalse(answer.get().success());
                assertEquals(0, effects.get());
                assertEquals(P1LobbySession.State.FAILED, session.state());
                assertEquals(RoundPhase.RECOVERY_REQUIRED, session.snapshot().orElseThrow().phase());
                assertTrue(session.snapshot().orElseThrow().participants().get(player).monsterSkillReadyAtMillis().isEmpty());
                try (var store = new SqliteRoundStore(path)) {
                    var saved = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
                    assertEquals(version, saved.version());
                    assertTrue(saved.participants().get(player).monsterSkillReadyAtMillis().isEmpty());
                    assertFalse(saved.cleanShutdown());
                }
                sql.execute("DROP TRIGGER fail_skill");
            }
        }
    }

    @Test void failedNativeEffectCanRefundOnlyItsCurrentReservation() throws Exception {
        try (var session = open(seed())) {
            request(reply -> session.requestMonsterDeploy(round, player, 3, 4, reply));
            request(reply -> session.requestMonsterSkill(round, player, 3, 5, MonsterSkill.SKELETON_BURST, NOW, reply));
            request(reply -> session.requestMonsterSkillCancelled(round, player, 3, 5, MonsterSkill.SKELETON_BURST,
                    NOW + 90_000, 0, reply));
            assertEquals(0, session.snapshot().orElseThrow().participants().get(player).monsterSkillRemainingMillis(MonsterSkill.SKELETON_BURST, NOW));
            request(reply -> session.requestMonsterSkill(round, player, 3, 5, MonsterSkill.SKELETON_BURST, NOW, reply));
            reject(reply -> session.requestMonsterSkillCancelled(round, player, 3, 5, MonsterSkill.SKELETON_BURST,
                    NOW + 90_000, 0, reply));
            assertEquals(NOW + 90_001, session.snapshot().orElseThrow().participants().get(player).monsterSkillReadyAtMillis().get(MonsterSkill.SKELETON_BURST));
        }
    }

    @Test void staleIdentityRetiredSkillAndShutdownDoNotReserveCooldownOrCallSuccess() throws Exception {
        try (var session = open(seed())) {
            request(reply -> session.requestMonsterDeploy(round, player, 3, 4, reply));
            reject(reply -> session.requestMonsterSkill(UUID.randomUUID(), player, 3, 5, MonsterSkill.SKELETON_BURST, NOW, reply));
            reject(reply -> session.requestMonsterSkill(round, player, 4, 5, MonsterSkill.SKELETON_BURST, NOW, reply));
            reject(reply -> session.requestMonsterSkill(round, player, 3, 4, MonsterSkill.SKELETON_BURST, NOW, reply));
            reject(reply -> session.requestMonsterSkill(round, player, 3, 5, MonsterSkill.ZOMBIE_SUMMON, NOW, reply));
            assertTrue(session.snapshot().orElseThrow().participants().get(player).monsterSkillReadyAtMillis().isEmpty());
            session.prepareStop();
            reject(reply -> session.requestMonsterSkill(round, player, 3, 5, MonsterSkill.SKELETON_BURST, NOW, reply));
            assertEquals(P1LobbySession.State.OPEN, session.state());
        }
    }

    @Test void retiredZombieSkillKeepsHistoricalDeadlineButCannotWriteOrInvokeEffect() throws Exception {
        Path path = seed(MonsterRole.ZOMBIE, Map.of(MonsterSkill.ZOMBIE_SUMMON, NOW + 90_000));
        try (var session = open(path)) {
            request(reply -> session.requestMonsterDeploy(round, player, 3, 4, reply));
            var before = session.snapshot().orElseThrow();
            var effects = new AtomicInteger();
            var answer = new AtomicReference<P1LobbySession.Reply>();
            session.requestMonsterSkill(round, player, 3, 5, MonsterSkill.ZOMBIE_SUMMON, NOW + 90_000, reply -> {
                if (reply.success()) effects.incrementAndGet();
                answer.set(reply);
            });
            pump(() -> answer.get() != null);
            assertFalse(answer.get().success());
            assertTrue(answer.get().message().contains("已退役"));
            assertEquals(0, effects.get());
            assertFalse(session.mutationPending());
            assertEquals(before, session.snapshot().orElseThrow());
            // The active skill still separately enforces the deployed species requirement.
            reject(reply -> session.requestMonsterSkill(round, player, 3, 5, MonsterSkill.SKELETON_BURST, NOW, reply));
            try (var store = new SqliteRoundStore(path)) {
                var saved = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
                assertEquals(before.version(), saved.version());
                assertEquals(NOW + 90_000, saved.participants().get(player).monsterSkillReadyAtMillis().get(MonsterSkill.ZOMBIE_SUMMON));
            }
        }
        try (var session = open(path)) {
            assertEquals(45_000, session.snapshot().orElseThrow().participants().get(player)
                    .monsterSkillRemainingMillis(MonsterSkill.ZOMBIE_SUMMON, NOW + 45_000));
        }
    }

    private Path seed() throws Exception {
        return seed(MonsterRole.SKELETON, Map.of());
    }
    private Path seed(MonsterRole role, Map<MonsterSkill, Long> cooldowns) throws Exception {
        var path = temporary.toRealPath().resolve("monster-skills.sqlite");
        var participant = new Participant(player, "Monster", ParticipantRole.MONSTER_LOBBY, false, false,
                123, 0, 3, true, true, role, MonsterDimension.OVERWORLD, 0, 4, cooldowns);
        var base = RulesConfig.defaults();
        var values = new java.util.LinkedHashMap<>(base.combatValues());
        values.put("skeleton.burstCooldownMillis", 90_000.0);
        var rules = new RulesConfig(base.schemaVersion(), base.mode(), base.minecraftVersion(), base.difficulty(),
                base.generationRule(), values, base.protectionRadius(), base.startCountdownMillis(),
                base.successCelebrationMillis(), base.failureDisplayMillis());
        var snapshot = new RoundSnapshot(round, 1, 123, 0, rules, RoundPhase.RUNNING,
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
    private void reject(Consumer<Consumer<P1LobbySession.Reply>> action) throws Exception {
        var answer = new AtomicReference<P1LobbySession.Reply>();
        action.accept(answer::set);
        pump(() -> answer.get() != null);
        assertFalse(answer.get().success(), answer.get().message());
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
