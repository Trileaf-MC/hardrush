package dev.hardrush.persistence;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.book.HistoryBookComposer;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.record.ProgressWrite;
import dev.hardrush.core.round.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rule transitions and the real SQLite indexes share each death/revival transaction. */
class RevivalStoreTest {
    @TempDir Path directory;
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String SET = "a".repeat(64);
    private static final Set<String> ADVANCEMENTS = Set.of("minecraft:story/mine_stone", "minecraft:story/upgrade_tools");

    @Test void humanKillChargesStackSurviveSqliteReopenAndPayForLaterHumanLivesWithoutReplayRewards() throws Exception {
        try (var fixture = new Fixture(directory.toRealPath().resolve("human-kills.sqlite"))) {
            fixture.start();
            var first = fixture.engine.proposeDeathBatch(UUID.randomUUID(), fixture.round(),
                    List.of(death(fixture.player(BOB), Optional.of(lethal(fixture.round(),
                            fixture.player(ALICE), BOB, AttackKind.MELEE)))), null);
            var replay = write(first);
            fixture.commit(first);
            assertEquals(1, fixture.player(ALICE).revivalCharges());
            assertEquals(CommitResult.DUPLICATE, fixture.store.commit(replay));

            UUID newcomer = UUID.randomUUID();
            fixture.commit(fixture.engine.proposeJoin(UUID.randomUUID(), newcomer, "Newcomer"));
            fixture.commit(fixture.engine.proposeDeathBatch(UUID.randomUUID(), fixture.round(),
                    List.of(death(fixture.player(newcomer), Optional.of(lethal(fixture.round(),
                            fixture.player(ALICE), newcomer, AttackKind.ARROW)))), null));
            fixture.reopen();
            assertEquals(2, fixture.player(ALICE).revivalCharges());
            assertEquals(0, fixture.store.participantRecord(fixture.round(), ALICE).orElseThrow().revivalCount(),
                    "Earning charges is separate from actually reviving");

            for (int remaining = 1; remaining >= 0; remaining--) {
                fixture.commit(fixture.engine.proposeDeathBatch(UUID.randomUUID(), fixture.round(),
                        List.of(death(fixture.player(ALICE), Optional.empty())), null));
                assertEquals(RoundPhase.RESCUE, fixture.engine.committed().phase());
                fixture.reopen();
                var alice = fixture.player(ALICE);
                assertEquals(remaining + 1, alice.revivalCharges());
                fixture.commit(fixture.engine.proposeRevival(UUID.randomUUID(), fixture.round(), ALICE,
                        alice.lifeSequence(), alice.monsterDeploymentSequence()));
                fixture.reopen();
                assertEquals(remaining, fixture.player(ALICE).revivalCharges());
                assertEquals(ParticipantRole.HUMAN_ACTIVE, fixture.player(ALICE).role());
                assertEquals(2 - remaining, fixture.store.participantRecord(fixture.round(), ALICE).orElseThrow().revivalCount());
                assertEquals(CommitResult.DUPLICATE, fixture.store.commit(replay));
                assertEquals(remaining, fixture.player(ALICE).revivalCharges());
            }
            assertEquals(2, fixture.store.participantRecord(fixture.round(), ALICE).orElseThrow().deathCount());
            assertEquals(1, fixture.store.participantRecord(fixture.round(), BOB).orElseThrow().deathCount());
        }
    }

    @Test void twentyRewardAndRevivalCyclesKeepExactCountsProgressAndCooldownsAcrossSqliteReopen() throws Exception {
        var base = RulesConfig.defaults();
        var values = new java.util.LinkedHashMap<>(base.combatValues());
        values.put("skeleton.burstCooldownMillis", 90_000.0);
        var rules = new RulesConfig(base.schemaVersion(), base.mode(), base.minecraftVersion(), base.difficulty(),
                base.generationRule(), values, base.protectionRadius(), base.startCountdownMillis(),
                base.successCelebrationMillis(), base.failureDisplayMillis());
        try (var fixture = new Fixture(directory.toRealPath().resolve("revival.sqlite"), rules)) {
            fixture.start();
            // Bob's first actual death opens his monster life; Alice keeps this initial batch running.
            fixture.commit(fixture.engine.proposeDeathBatch(UUID.randomUUID(), fixture.round(),
                    List.of(death(fixture.player(BOB), Optional.empty())), null));
            assertEquals(RoundPhase.RUNNING, fixture.engine.committed().phase());

            Map<UUID, Long> skillDeadlines = new java.util.HashMap<>();
            Map<UUID, Long> firstEnd = new java.util.HashMap<>();
            UUID human = ALICE, monster = BOB;
            for (int cycle = 0; cycle < 20; cycle++) {
                fixture.clock.millis(30_000);
                fixture.commit(fixture.engine.proposeTick(UUID.randomUUID()));
                Participant attacker = fixture.player(monster);
                if (attacker.monsterRole() != MonsterRole.SKELETON) {
                    fixture.commit(fixture.engine.proposeMonsterRole(UUID.randomUUID(), fixture.round(), monster,
                            attacker.lifeSequence(), attacker.monsterDeploymentSequence(), MonsterRole.SKELETON));
                    attacker = fixture.player(monster);
                }
                fixture.commit(fixture.engine.proposeMonsterDeploy(UUID.randomUUID(), fixture.round(), monster,
                        attacker.lifeSequence(), attacker.monsterDeploymentSequence()));
                attacker = fixture.player(monster);
                if (!skillDeadlines.containsKey(monster)) {
                    fixture.commit(fixture.engine.proposeMonsterSkill(UUID.randomUUID(), fixture.round(), monster,
                            attacker.lifeSequence(), attacker.monsterDeploymentSequence(), MonsterSkill.SKELETON_BURST, 1_000));
                    skillDeadlines.put(monster, fixture.player(monster).monsterSkillReadyAtMillis().get(MonsterSkill.SKELETON_BURST));
                }
                fixture.clock.millis(1_000);
                fixture.commit(fixture.engine.proposeCheckpoint(UUID.randomUUID(), false));
                long challenge = fixture.engine.committed().challengeNanos();
                var progress = new ProgressWrite(fixture.round(), UUID.randomUUID(), human, fixture.player(human).name(),
                        ADVANCEMENTS, SET, true, challenge);
                assertEquals(CommitResult.APPLIED, fixture.store.recordProgress(progress));
                assertEquals(CommitResult.DUPLICATE, fixture.store.recordProgress(progress));
                firstEnd.putIfAbsent(human, challenge);

                var cause = lethal(fixture.round(), fixture.player(monster), human,
                        List.of(AttackKind.MELEE, AttackKind.ARROW, AttackKind.SUMMON, AttackKind.CREEPER).get(cycle % 4));
                var transition = fixture.engine.proposeDeathBatch(UUID.randomUUID(), fixture.round(),
                        List.of(death(fixture.player(human), Optional.of(cause))), null);
                SnapshotWrite deathWrite = write(transition);
                fixture.commit(transition);
                assertEquals(CommitResult.DUPLICATE, fixture.store.commit(deathWrite));
                assertEquals(RoundPhase.RESCUE, fixture.engine.committed().phase());
                assertTrue(fixture.player(monster).revivalEntitled());
                assertFalse(fixture.player(human).revivalEntitled());
                assertEquals(CommitResult.STALE, fixture.store.recordProgress(new ProgressWrite(fixture.round(),
                        UUID.randomUUID(), human, "Ignored monster progress", Set.of("minecraft:story/enter_the_nether"),
                        SET, true, challenge)));

                attacker = fixture.player(monster);
                fixture.commit(fixture.engine.proposeMonsterReturn(UUID.randomUUID(), fixture.round(), monster,
                        attacker.lifeSequence(), attacker.monsterDeploymentSequence()));
                fixture.clock.millis(2_000);
                fixture.commit(fixture.engine.proposeCheckpoint(UUID.randomUUID(), false));
                assertEquals(58_000, fixture.engine.committed().remainingPhaseMillis());
                assertEquals(challenge, fixture.engine.committed().challengeNanos(), "Rescue is outside human race time");
                if (cycle == 9) fixture.reopen();

                attacker = fixture.player(monster);
                int oldLife = attacker.lifeSequence(), oldDeployment = attacker.monsterDeploymentSequence();
                fixture.commit(fixture.engine.proposeAdmissionRevival(UUID.randomUUID(), fixture.round(), monster,
                        oldLife, oldDeployment));
                Participant revived = fixture.player(monster);
                assertEquals(RoundPhase.RUNNING, fixture.engine.committed().phase());
                assertEquals(ParticipantRole.HUMAN_ACTIVE, revived.role());
                assertEquals(oldLife + 1, revived.lifeSequence());
                assertFalse(revived.online(), "Native admission must finish before human time runs");
                assertFalse(revived.revivalEntitled());
                assertFalse(revived.hasLeftProtection());
                assertEquals(skillDeadlines.get(monster), revived.monsterSkillReadyAtMillis().get(MonsterSkill.SKELETON_BURST));
                final UUID consumedBy = monster;
                assertThrows(IllegalStateException.class, () -> fixture.engine.proposeAdmissionRevival(UUID.randomUUID(),
                        fixture.round(), consumedBy, oldLife, oldDeployment));
                fixture.commit(fixture.engine.proposeOnline(UUID.randomUUID(), monster, revived.name(), true));
                assertEquals(CommitResult.DUPLICATE, fixture.store.commit(deathWrite), "Late death replay cannot undo a consumed life");
                UUID previousHuman = human; human = monster; monster = previousHuman;
            }

            assertEquals(620_000, fixture.engine.committed().challengeMillis());
            assertRecord(fixture, ALICE, 10, 10, firstEnd.get(ALICE));
            assertRecord(fixture, BOB, 11, 10, firstEnd.get(BOB));
            assertEquals(2, fixture.store.endEntrantCount(fixture.round()));
            fixture.commit(fixture.engine.proposeFinish(UUID.randomUUID(),
                    new RoundResult(RoundResult.Kind.COMPLETED, human, "persistence lifecycle fixture")));
            var history = fixture.store.roundHistory(fixture.round()).orElseThrow();
            var roster = fixture.store.participantRecords(fixture.round(), 10, 0);
            String book = String.join("", HistoryBookComposer.compose(history, history.rulesFingerprint(),
                    1, 2, roster, List.of()).pages()).replace("\n", "");
            assertTrue(book.contains("进度 2 · 死亡 10 · 复生 10"));
            assertTrue(book.contains("进度 2 · 死亡 11 · 复生 10"));
            fixture.reopen();
            assertRecord(fixture, ALICE, 10, 10, firstEnd.get(ALICE));
            assertRecord(fixture, BOB, 11, 10, firstEnd.get(BOB));
            assertEquals(1, fixture.store.history(10, 0).size());
        }
    }

    @Test void oneExplosionBatchCommitsAllDeathsAndOneEntitlementBeforeRescueWithoutDoubleCountingReplay() throws Exception {
        try (var fixture = new Fixture(directory.toRealPath().resolve("multi-kill.sqlite"))) {
            fixture.start();
            fixture.commit(fixture.engine.proposeDeathBatch(UUID.randomUUID(), fixture.round(),
                    List.of(death(fixture.player(BOB), Optional.empty())), null));
            UUID newcomer = UUID.randomUUID();
            fixture.commit(fixture.engine.proposeJoin(UUID.randomUUID(), newcomer, "Newcomer"));
            fixture.clock.millis(30_000);
            fixture.commit(fixture.engine.proposeTick(UUID.randomUUID()));
            var bob = fixture.player(BOB);
            fixture.commit(fixture.engine.proposeMonsterDeploy(UUID.randomUUID(), fixture.round(), BOB,
                    bob.lifeSequence(), bob.monsterDeploymentSequence()));
            bob = fixture.player(BOB);
            var aliceCause = lethal(fixture.round(), bob, ALICE, AttackKind.CREEPER);
            var newcomerCause = new CombatEvent(fixture.round(), UUID.randomUUID(), aliceCause.batchId(), 2, newcomer,
                    aliceCause.origin(), aliceCause.attribution(), "minecraft:explosion", aliceCause.rawDirectEntityId(),
                    aliceCause.rawCausingEntityId(), 20, true, 0);
            var transition = fixture.engine.proposeDeathBatch(UUID.randomUUID(), fixture.round(),
                    List.of(death(fixture.player(ALICE), Optional.of(aliceCause)),
                            death(fixture.player(newcomer), Optional.of(newcomerCause)), death(bob, Optional.empty())), null);
            var write = write(transition);
            fixture.commit(transition);
            assertEquals(CommitResult.DUPLICATE, fixture.store.commit(write));
            assertEquals(RoundPhase.RESCUE, fixture.engine.committed().phase());
            assertEquals(1, fixture.engine.committed().participants().values().stream().filter(Participant::revivalEntitled).count());
            for (UUID id : List.of(ALICE, BOB, newcomer)) {
                assertEquals(1, fixture.store.participantRecord(fixture.round(), id).orElseThrow().deathCount());
                assertEquals(0, fixture.store.participantRecord(fixture.round(), id).orElseThrow().revivalCount());
            }
            fixture.reopen();
            bob = fixture.player(BOB);
            fixture.commit(fixture.engine.proposeRevival(UUID.randomUUID(), fixture.round(), BOB,
                    bob.lifeSequence(), bob.monsterDeploymentSequence()));
            assertEquals(1, fixture.store.participantRecord(fixture.round(), BOB).orElseThrow().revivalCount());
            assertFalse(fixture.player(BOB).revivalEntitled());
        }
    }

    private static void assertRecord(Fixture fixture, UUID id, int deaths, int revivals, long firstEnd) throws Exception {
        var record = fixture.store.participantRecord(fixture.round(), id).orElseThrow();
        assertEquals(id.equals(ALICE) ? "Alice" : "Bob", record.playerName());
        assertEquals(deaths, record.deathCount());
        assertEquals(revivals, record.revivalCount());
        assertEquals(310_000_000_000L, record.humanNanos());
        assertEquals(2, record.advancementCount());
        assertEquals(firstEnd, record.firstEndNanos());
        assertEquals(Set.of(SET), record.advancementSetFingerprints());
    }

    private static RoundDeath death(Participant player, Optional<CombatEvent> cause) {
        return new RoundDeath(player.id(), player.lifeSequence(), player.monsterDeploymentSequence(), cause);
    }

    private static CombatEvent lethal(UUID round, Participant attacker, UUID victim, AttackKind kind) {
        UUID direct = UUID.randomUUID();
        var origin = new CombatOrigin(round, UUID.randomUUID(), new CombatActor(attacker.id(), attacker.role(),
                attacker.lifeSequence(), attacker.monsterDeploymentSequence()), kind, Optional.of(direct), List.of());
        return new CombatEvent(round, UUID.randomUUID(), UUID.randomUUID(), 1, victim, Optional.of(origin),
                CombatEvent.classify(round, Optional.of(origin)), "test:actual_lethal", Optional.of(direct), Optional.of(attacker.id()), 20, true, 0);
    }

    private static SnapshotWrite write(RoundTransition transition) {
        return new SnapshotWrite(transition.after().roundId(), transition.before().version(), transition.after().version(),
                RoundSnapshotCodec.encode(transition.after()), transition.eventId(), transition.eventType());
    }

    private static final class Clock implements MonotonicClock {
        long now;
        @Override public long nanoTime() { return now; }
        void millis(long millis) { now += millis * 1_000_000; }
    }

    private static final class Fixture implements AutoCloseable {
        final Path path;
        final Clock clock = new Clock();
        SqliteRoundStore store;
        RoundEngine engine;
        Fixture(Path path) throws Exception {
            this(path, RulesConfig.defaults());
        }
        Fixture(Path path, RulesConfig rules) throws Exception {
            this.path = path;
            store = new SqliteRoundStore(path);
            engine = RoundEngine.create(UUID.randomUUID(), 1, 123, rules, clock);
            assertEquals(CommitResult.APPLIED, store.commit(new SnapshotWrite(round(), -1, 0,
                    RoundSnapshotCodec.encode(engine.committed()), UUID.randomUUID(), "ROUND_CREATED")));
        }
        UUID round() { return engine.committed().roundId(); }
        Participant player(UUID id) { return engine.committed().participants().get(id); }
        void start() throws Exception {
            commit(engine.proposeWorldReady(UUID.randomUUID()));
            commit(engine.proposeJoin(UUID.randomUUID(), ALICE, "Alice"));
            commit(engine.proposeJoin(UUID.randomUUID(), BOB, "Bob"));
            commit(engine.proposeReady(UUID.randomUUID(), ALICE, true));
            commit(engine.proposeReady(UUID.randomUUID(), BOB, true));
            clock.millis(30_000);
            commit(engine.proposeTick(UUID.randomUUID()));
        }
        void commit(RoundTransition transition) throws Exception {
            assertEquals(CommitResult.APPLIED, store.commit(write(transition)));
            assertTrue(engine.accept(transition));
        }
        void reopen() throws Exception {
            commit(engine.proposeCheckpoint(UUID.randomUUID(), true));
            var saved = engine.committed();
            store.close();
            clock.millis(500_000);
            store = new SqliteRoundStore(path);
            engine = RoundEngine.restore(RoundSnapshotCodec.decode(store.load(saved.roundId()).orElseThrow().payload()), clock);
            assertEquals(saved.challengeNanos(), engine.committed().challengeNanos());
            assertEquals(saved.remainingPhaseNanos(), engine.committed().remainingPhaseNanos());
            if (saved.result() == null) {
                for (var player : saved.participants().values()) {
                    commit(engine.proposeOnline(UUID.randomUUID(), player.id(), player.name(), true));
                }
            }
        }
        @Override public void close() throws Exception { store.close(); }
    }
}
