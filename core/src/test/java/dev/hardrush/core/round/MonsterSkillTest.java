package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MonsterSkillTest {
    private final UUID round = UUID.randomUUID(), player = UUID.randomUUID();
    private final Clock clock = new Clock();
    private static final long NOW = 1_800_000_000_000L;
    private static class Clock implements MonotonicClock {
        long now;
        public long nanoTime() { return now; }
        void seconds(long seconds) { now += seconds * 1_000_000_000L; }
    }
    private static void apply(RoundEngine engine, RoundTransition transition) { assertTrue(engine.accept(transition)); }
    private Participant current(RoundEngine engine) { return engine.view().participants().get(player); }
    private RoundEngine fixture(ParticipantRole role, RulesConfig rules, Map<MonsterSkill, Long> cooldowns) {
        var participant = new Participant(player, "Skill", role, false, false, 123, 0, 3, true, true,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 4, cooldowns);
        var snapshot = new RoundSnapshot(round, 1, 123, 0, rules, RoundPhase.RUNNING,
                PauseReason.SERVER_STOPPED, 123, 0, Map.of(player, participant), true, false, null);
        var engine = RoundEngine.restore(snapshot, clock);
        apply(engine, engine.proposeOnline(UUID.randomUUID(), player, "Skill", true));
        return engine;
    }
    private RoundEngine deployed() {
        var engine = fixture(ParticipantRole.MONSTER_LOBBY, RulesConfig.defaults(), Map.of());
        apply(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, player, 3, 4));
        return engine;
    }
    private RoundTransition skill(RoundEngine engine, MonsterSkill skill, long now) {
        return engine.proposeMonsterSkill(UUID.randomUUID(), round, player, 3,
                current(engine).monsterDeploymentSequence(), skill, now);
    }

    @Test void reservationRemainsInvisibleUntilAcceptAndPendingOrRepeatedUsesCannotResetIt() {
        var engine = deployed();
        var pending = skill(engine, MonsterSkill.ZOMBIE_SUMMON, NOW);
        assertEquals(0, current(engine).monsterSkillRemainingMillis(MonsterSkill.ZOMBIE_SUMMON, NOW));
        assertEquals(NOW + 90_000, pending.after().participants().get(player).monsterSkillReadyAtMillis().get(MonsterSkill.ZOMBIE_SUMMON));
        assertThrows(IllegalStateException.class, () -> skill(engine, MonsterSkill.ZOMBIE_SUMMON, NOW));
        apply(engine, pending);
        assertEquals(90_000, current(engine).monsterSkillRemainingMillis(MonsterSkill.ZOMBIE_SUMMON, NOW));
        assertThrows(IllegalStateException.class, () -> skill(engine, MonsterSkill.ZOMBIE_SUMMON, NOW + 89_999));
        apply(engine, skill(engine, MonsterSkill.ZOMBIE_SUMMON, NOW + 90_000));
        assertEquals(NOW + 180_000, current(engine).monsterSkillReadyAtMillis().get(MonsterSkill.ZOMBIE_SUMMON));
    }

    @Test void rejectedReservationCannotExposeDeadlineOrBeCompensatedAndReopenUsesStoredState() {
        var engine = deployed();
        apply(engine, engine.proposeCheckpoint(UUID.randomUUID(), true));
        var stored = engine.committed();
        var reservation = skill(engine, MonsterSkill.ZOMBIE_SUMMON, NOW);
        assertTrue(engine.reject(reservation, "fixture disk failure"));
        assertEquals(RoundPhase.RECOVERY_REQUIRED, engine.committed().phase());
        assertTrue(current(engine).monsterSkillReadyAtMillis().isEmpty());
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterSkillCancelled(UUID.randomUUID(), round, player,
                3, 5, MonsterSkill.ZOMBIE_SUMMON, NOW + 90_000, 0));
        var reopened = RoundEngine.restore(RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(stored)), clock);
        assertTrue(current(reopened).monsterSkillReadyAtMillis().isEmpty());
        apply(reopened, reopened.proposeOnline(UUID.randomUUID(), player, "Skill", true));
        clock.seconds(30);
        apply(reopened, reopened.proposeMonsterDeploy(UUID.randomUUID(), round, player, 3, 5));
        apply(reopened, skill(reopened, MonsterSkill.ZOMBIE_SUMMON, NOW));
        assertEquals(NOW + 90_000, current(reopened).monsterSkillReadyAtMillis().get(MonsterSkill.ZOMBIE_SUMMON));
    }

    @Test void roleRoundLifeDeploymentOnlineAndRunningPhaseAreAllRequired() {
        var engine = deployed();
        assertThrows(IllegalStateException.class, () -> skill(engine, MonsterSkill.SKELETON_BURST, NOW));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterSkill(UUID.randomUUID(), UUID.randomUUID(), player, 3, 5, MonsterSkill.ZOMBIE_SUMMON, NOW));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterSkill(UUID.randomUUID(), round, player, 4, 5, MonsterSkill.ZOMBIE_SUMMON, NOW));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterSkill(UUID.randomUUID(), round, player, 3, 4, MonsterSkill.ZOMBIE_SUMMON, NOW));
        apply(engine, engine.proposeOnline(UUID.randomUUID(), player, "Skill", false));
        assertThrows(IllegalStateException.class, () -> skill(engine, MonsterSkill.ZOMBIE_SUMMON, NOW));
        apply(engine, engine.proposeFinish(UUID.randomUUID(), new RoundResult(RoundResult.Kind.FAILED, null, "fixture")));
        assertThrows(IllegalStateException.class, () -> skill(engine, MonsterSkill.ZOMBIE_SUMMON, NOW));
        assertFalse(engine.pending());
        var human = fixture(ParticipantRole.HUMAN_ACTIVE, RulesConfig.defaults(), Map.of());
        assertThrows(IllegalStateException.class, () -> skill(human, MonsterSkill.ZOMBIE_SUMMON, NOW));
    }

    @Test void eachSkillUsesRoundConfigAndKeepsItsOwnDeadlineAcrossOutingsRolesAndDeath() {
        var values = new LinkedHashMap<>(RulesConfig.defaults().combatValues());
        values.put("zombie.summonCooldownMillis", 123_000.0);
        values.put("skeleton.burstCooldownMillis", 7_000.0);
        var base = RulesConfig.defaults();
        var rules = new RulesConfig(base.schemaVersion(), base.mode(), base.minecraftVersion(), base.difficulty(),
                base.generationRule(), values, base.protectionRadius(), base.startCountdownMillis(),
                base.successCelebrationMillis(), base.failureDisplayMillis());
        var engine = fixture(ParticipantRole.MONSTER_LOBBY, rules, Map.of());
        apply(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, player, 3, 4));
        apply(engine, skill(engine, MonsterSkill.ZOMBIE_SUMMON, NOW));
        apply(engine, engine.proposeMonsterDeath(UUID.randomUUID(), round, player, 3, 5));
        apply(engine, engine.proposeMonsterRole(UUID.randomUUID(), round, player, 3, 5, MonsterRole.SKELETON));
        clock.seconds(30);
        apply(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, player, 3, 5));
        apply(engine, skill(engine, MonsterSkill.SKELETON_BURST, NOW + 30_000));
        assertEquals(Map.of(MonsterSkill.ZOMBIE_SUMMON, NOW + 123_000,
                MonsterSkill.SKELETON_BURST, NOW + 37_000), current(engine).monsterSkillReadyAtMillis());
        apply(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, player, 3, 6));
        apply(engine, engine.proposeMonsterRole(UUID.randomUUID(), round, player, 3, 6, MonsterRole.ZOMBIE));
        clock.seconds(30);
        apply(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, player, 3, 6));
        assertThrows(IllegalStateException.class, () -> skill(engine, MonsterSkill.ZOMBIE_SUMMON, NOW + 60_000));
        assertEquals(63_000, current(engine).monsterSkillRemainingMillis(MonsterSkill.ZOMBIE_SUMMON, NOW + 60_000));
    }

    @Test void normalNewHumanLifeAndHardcoreDeathKeepTheSameUuidSkillDeadlines() {
        var cooldowns = Map.of(MonsterSkill.ZOMBIE_SUMMON, NOW + 90_000);
        var normal = fixture(ParticipantRole.HUMAN_ACTIVE, RulesConfig.defaults().withMode(GameMode.NORMAL), cooldowns);
        apply(normal, normal.proposeNormalDeath(UUID.randomUUID(), player, 3));
        apply(normal, normal.proposeNormalRespawn(UUID.randomUUID(), player, 3));
        assertEquals(4, current(normal).lifeSequence());
        assertEquals(cooldowns, current(normal).monsterSkillReadyAtMillis());
        var hardcore = fixture(ParticipantRole.HUMAN_ACTIVE, RulesConfig.defaults(), cooldowns);
        apply(hardcore, hardcore.proposeHardcoreDeath(UUID.randomUUID(), player, 3));
        assertEquals(cooldowns, current(hardcore).monsterSkillReadyAtMillis());
        var p = current(hardcore);
        assertEquals(cooldowns, p.withSession("Copy", false).monsterSkillReadyAtMillis());
        assertEquals(cooldowns, p.withReady(false).monsterSkillReadyAtMillis());
        assertEquals(cooldowns, p.withRole(ParticipantRole.HUMAN_RESPAWNING).monsterSkillReadyAtMillis());
        assertEquals(cooldowns, p.leaveProtection().monsterSkillReadyAtMillis());
        assertEquals(cooldowns, p.accrue(1).monsterSkillReadyAtMillis());
        assertEquals(cooldowns, p.start(1).monsterSkillReadyAtMillis());
        assertEquals(cooldowns, p.selectMonster(MonsterRole.CREEPER, MonsterDimension.END).monsterSkillReadyAtMillis());
        assertEquals(cooldowns, p.monsterLobby(1).monsterSkillReadyAtMillis());
        assertEquals(cooldowns, p.deployMonster().monsterSkillReadyAtMillis());
        assertEquals(cooldowns, p.elapseMonsterCooldown(1).monsterSkillReadyAtMillis());
    }

    @Test void codecAndRestartKeepWallDeadlineWhileOfflineAndDowntimeAdvanceRemaining() {
        var engine = deployed();
        apply(engine, skill(engine, MonsterSkill.ZOMBIE_SUMMON, NOW));
        apply(engine, engine.proposeCheckpoint(UUID.randomUUID(), true));
        var saved = RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(engine.committed()));
        assertEquals(engine.committed(), saved);
        clock.seconds(500);
        var reopened = RoundEngine.restore(saved, clock);
        assertEquals(45_000, current(reopened).monsterSkillRemainingMillis(MonsterSkill.ZOMBIE_SUMMON, NOW + 45_000));
        assertEquals(0, current(reopened).monsterSkillRemainingMillis(MonsterSkill.ZOMBIE_SUMMON, NOW + 90_000));
        apply(reopened, reopened.proposeOnline(UUID.randomUUID(), player, "Skill", true));
        assertEquals(NOW + 90_000, current(reopened).monsterSkillReadyAtMillis().get(MonsterSkill.ZOMBIE_SUMMON));
        clock.seconds(30);
        apply(reopened, reopened.proposeMonsterDeploy(UUID.randomUUID(), round, player, 3, 5));
        apply(reopened, skill(reopened, MonsterSkill.ZOMBIE_SUMMON, NOW + 500_000));
    }

    @Test void failedEffectCompensationRestoresOldDeadlineAndRejectsStaleNonceOrInvalidRefund() {
        var previous = NOW - 10;
        var engine = fixture(ParticipantRole.MONSTER_LOBBY, RulesConfig.defaults(), Map.of(MonsterSkill.ZOMBIE_SUMMON, previous));
        apply(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, player, 3, 4));
        apply(engine, skill(engine, MonsterSkill.ZOMBIE_SUMMON, NOW));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterSkillCancelled(UUID.randomUUID(), round, player, 3, 5,
                MonsterSkill.ZOMBIE_SUMMON, NOW + 90_000, NOW + 1));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterSkillCancelled(UUID.randomUUID(), round, player, 3, 5,
                MonsterSkill.ZOMBIE_SUMMON, NOW + 90_000, 0));
        apply(engine, engine.proposeMonsterSkillCancelled(UUID.randomUUID(), round, player, 3, 5,
                MonsterSkill.ZOMBIE_SUMMON, NOW + 90_000, previous));
        assertEquals(previous, current(engine).monsterSkillReadyAtMillis().get(MonsterSkill.ZOMBIE_SUMMON));
        apply(engine, skill(engine, MonsterSkill.ZOMBIE_SUMMON, NOW));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterSkillCancelled(UUID.randomUUID(), round, player, 3, 5,
                MonsterSkill.ZOMBIE_SUMMON, NOW + 90_000, previous));
        assertEquals(NOW + 90_001, current(engine).monsterSkillReadyAtMillis().get(MonsterSkill.ZOMBIE_SUMMON));
    }

    @Test void invalidWallTimesAndOverflowFailWithoutPendingMutation() {
        var engine = deployed();
        assertThrows(IllegalArgumentException.class, () -> skill(engine, MonsterSkill.ZOMBIE_SUMMON, -1));
        assertThrows(IllegalArgumentException.class, () -> skill(engine, MonsterSkill.ZOMBIE_SUMMON, Long.MAX_VALUE));
        assertFalse(engine.pending());
        assertThrows(IllegalArgumentException.class, () -> current(engine).monsterSkillRemainingMillis(MonsterSkill.ZOMBIE_SUMMON, -1));
    }
}
