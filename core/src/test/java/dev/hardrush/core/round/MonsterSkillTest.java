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
        return fixture(role, MonsterRole.SKELETON, rules, cooldowns);
    }
    private RoundEngine fixture(ParticipantRole role, MonsterRole monsterRole, RulesConfig rules, Map<MonsterSkill, Long> cooldowns) {
        var participant = new Participant(player, "Skill", role, false, false, 123, 0, 3, true, true,
                monsterRole, MonsterDimension.OVERWORLD, 0, 4, cooldowns);
        var snapshot = new RoundSnapshot(round, 1, 123, 0, rules, RoundPhase.RUNNING,
                PauseReason.SERVER_STOPPED, 123, 0, Map.of(player, participant), true, false, null);
        var engine = RoundEngine.restore(snapshot, clock);
        apply(engine, engine.proposeOnline(UUID.randomUUID(), player, "Skill", true));
        return engine;
    }
    /** Keep the reservation tests' historical 90-second window independent of production defaults. */
    private static RulesConfig skillRules(long burstCooldown) {
        var base = RulesConfig.defaults();
        var values = new LinkedHashMap<>(base.combatValues());
        values.put("skeleton.burstCooldownMillis", (double)burstCooldown);
        return new RulesConfig(base.schemaVersion(), base.mode(), base.minecraftVersion(), base.difficulty(),
                base.generationRule(), values, base.protectionRadius(), base.startCountdownMillis(),
                base.successCelebrationMillis(), base.failureDisplayMillis());
    }
    private RoundEngine deployed() {
        var engine = fixture(ParticipantRole.MONSTER_LOBBY, skillRules(90_000), Map.of());
        apply(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, player, 3, 4));
        return engine;
    }
    private RoundTransition skill(RoundEngine engine, MonsterSkill skill, long now) {
        return engine.proposeMonsterSkill(UUID.randomUUID(), round, player, 3,
                current(engine).monsterDeploymentSequence(), skill, now);
    }

    @Test void reservationRemainsInvisibleUntilAcceptAndPendingOrRepeatedUsesCannotResetIt() {
        var engine = deployed();
        var pending = skill(engine, MonsterSkill.SKELETON_BURST, NOW);
        assertEquals(0, current(engine).monsterSkillRemainingMillis(MonsterSkill.SKELETON_BURST, NOW));
        assertEquals(NOW + 90_000, pending.after().participants().get(player).monsterSkillReadyAtMillis().get(MonsterSkill.SKELETON_BURST));
        assertThrows(IllegalStateException.class, () -> skill(engine, MonsterSkill.SKELETON_BURST, NOW));
        apply(engine, pending);
        assertEquals(90_000, current(engine).monsterSkillRemainingMillis(MonsterSkill.SKELETON_BURST, NOW));
        assertThrows(IllegalStateException.class, () -> skill(engine, MonsterSkill.SKELETON_BURST, NOW + 89_999));
        apply(engine, skill(engine, MonsterSkill.SKELETON_BURST, NOW + 90_000));
        assertEquals(NOW + 180_000, current(engine).monsterSkillReadyAtMillis().get(MonsterSkill.SKELETON_BURST));
    }

    @Test void rejectedReservationCannotExposeDeadlineOrBeCompensatedAndReopenUsesStoredState() {
        var engine = deployed();
        apply(engine, engine.proposeCheckpoint(UUID.randomUUID(), true));
        var stored = engine.committed();
        var reservation = skill(engine, MonsterSkill.SKELETON_BURST, NOW);
        assertTrue(engine.reject(reservation, "fixture disk failure"));
        assertEquals(RoundPhase.RECOVERY_REQUIRED, engine.committed().phase());
        assertTrue(current(engine).monsterSkillReadyAtMillis().isEmpty());
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterSkillCancelled(UUID.randomUUID(), round, player,
                3, 5, MonsterSkill.SKELETON_BURST, NOW + 90_000, 0));
        var reopened = RoundEngine.restore(RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(stored)), clock);
        assertTrue(current(reopened).monsterSkillReadyAtMillis().isEmpty());
        apply(reopened, reopened.proposeOnline(UUID.randomUUID(), player, "Skill", true));
        clock.seconds(30);
        apply(reopened, reopened.proposeMonsterDeploy(UUID.randomUUID(), round, player, 3, 5));
        apply(reopened, skill(reopened, MonsterSkill.SKELETON_BURST, NOW));
        assertEquals(NOW + 90_000, current(reopened).monsterSkillReadyAtMillis().get(MonsterSkill.SKELETON_BURST));
    }

    @Test void roleRoundLifeDeploymentOnlineAndRunningPhaseAreAllRequired() {
        var engine = deployed();
        var wrongRole = fixture(ParticipantRole.MONSTER_LOBBY, MonsterRole.ZOMBIE, skillRules(90_000), Map.of());
        apply(wrongRole, wrongRole.proposeMonsterDeploy(UUID.randomUUID(), round, player, 3, 4));
        assertThrows(IllegalStateException.class, () -> skill(wrongRole, MonsterSkill.SKELETON_BURST, NOW));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterSkill(UUID.randomUUID(), UUID.randomUUID(), player, 3, 5, MonsterSkill.SKELETON_BURST, NOW));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterSkill(UUID.randomUUID(), round, player, 4, 5, MonsterSkill.SKELETON_BURST, NOW));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterSkill(UUID.randomUUID(), round, player, 3, 4, MonsterSkill.SKELETON_BURST, NOW));
        apply(engine, engine.proposeOnline(UUID.randomUUID(), player, "Skill", false));
        assertThrows(IllegalStateException.class, () -> skill(engine, MonsterSkill.SKELETON_BURST, NOW));
        apply(engine, engine.proposeFinish(UUID.randomUUID(), new RoundResult(RoundResult.Kind.FAILED, null, "fixture")));
        assertThrows(IllegalStateException.class, () -> skill(engine, MonsterSkill.SKELETON_BURST, NOW));
        assertFalse(engine.pending());
        var human = fixture(ParticipantRole.HUMAN_ACTIVE, RulesConfig.defaults(), Map.of());
        assertThrows(IllegalStateException.class, () -> skill(human, MonsterSkill.SKELETON_BURST, NOW));
    }

    @Test void activeSkillUsesRoundConfigWhileLegacyDeadlinesSurviveRolesOutingsAndDeath() {
        var engine = fixture(ParticipantRole.MONSTER_LOBBY, skillRules(7_000),
                Map.of(MonsterSkill.ZOMBIE_SUMMON, NOW + 123_000));
        apply(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, player, 3, 4));
        apply(engine, skill(engine, MonsterSkill.SKELETON_BURST, NOW));
        apply(engine, engine.proposeMonsterDeath(UUID.randomUUID(), round, player, 3, 5));
        apply(engine, engine.proposeMonsterRole(UUID.randomUUID(), round, player, 3, 5, MonsterRole.ZOMBIE));
        clock.seconds(30);
        apply(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, player, 3, 5));
        assertThrows(IllegalStateException.class, () -> skill(engine, MonsterSkill.SKELETON_BURST, NOW + 30_000));
        assertThrows(IllegalStateException.class, () -> skill(engine, MonsterSkill.ZOMBIE_SUMMON, NOW + 30_000));
        assertEquals(Map.of(MonsterSkill.ZOMBIE_SUMMON, NOW + 123_000,
                MonsterSkill.SKELETON_BURST, NOW + 7_000), current(engine).monsterSkillReadyAtMillis());
        apply(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, player, 3, 6));
        apply(engine, engine.proposeMonsterRole(UUID.randomUUID(), round, player, 3, 6, MonsterRole.SKELETON));
        clock.seconds(30);
        apply(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, player, 3, 6));
        apply(engine, skill(engine, MonsterSkill.SKELETON_BURST, NOW + 60_000));
        assertEquals(NOW + 67_000, current(engine).monsterSkillReadyAtMillis().get(MonsterSkill.SKELETON_BURST));
        assertEquals(63_000, current(engine).monsterSkillRemainingMillis(MonsterSkill.ZOMBIE_SUMMON, NOW + 60_000));
    }

    @Test void retiredSummonIdAndCooldownCodecRemainReadableButNoNewReservationCanBeMade() {
        assertEquals(MonsterSkill.ZOMBIE_SUMMON, MonsterSkill.fromId("zombie.summon"));
        assertTrue(MonsterSkill.ZOMBIE_SUMMON.retired());
        assertFalse(MonsterSkill.SKELETON_BURST.retired());
        var engine = fixture(ParticipantRole.MONSTER_LOBBY, MonsterRole.ZOMBIE, RulesConfig.defaults(),
                Map.of(MonsterSkill.ZOMBIE_SUMMON, NOW + 90_000));
        apply(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, player, 3, 4));
        var before = engine.committed();
        var decoded = RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(before));
        assertEquals(before, decoded);
        assertEquals(45_000, decoded.participants().get(player).monsterSkillRemainingMillis(MonsterSkill.ZOMBIE_SUMMON, NOW + 45_000));
        var rejected = assertThrows(IllegalStateException.class, () -> skill(engine, MonsterSkill.ZOMBIE_SUMMON, NOW + 90_000));
        assertTrue(rejected.getMessage().contains("已退役"));
        assertFalse(engine.pending());
        assertEquals(before, engine.committed());
    }

    @Test void normalNewHumanLifeAndHardcoreDeathKeepTheSameUuidSkillDeadlines() {
        var cooldowns = Map.of(MonsterSkill.ZOMBIE_SUMMON, NOW + 90_000, MonsterSkill.SKELETON_BURST, NOW + 20_000);
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
        apply(engine, skill(engine, MonsterSkill.SKELETON_BURST, NOW));
        apply(engine, engine.proposeCheckpoint(UUID.randomUUID(), true));
        var saved = RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(engine.committed()));
        assertEquals(engine.committed(), saved);
        clock.seconds(500);
        var reopened = RoundEngine.restore(saved, clock);
        assertEquals(45_000, current(reopened).monsterSkillRemainingMillis(MonsterSkill.SKELETON_BURST, NOW + 45_000));
        assertEquals(0, current(reopened).monsterSkillRemainingMillis(MonsterSkill.SKELETON_BURST, NOW + 90_000));
        apply(reopened, reopened.proposeOnline(UUID.randomUUID(), player, "Skill", true));
        assertEquals(NOW + 90_000, current(reopened).monsterSkillReadyAtMillis().get(MonsterSkill.SKELETON_BURST));
        clock.seconds(30);
        apply(reopened, reopened.proposeMonsterDeploy(UUID.randomUUID(), round, player, 3, 5));
        apply(reopened, skill(reopened, MonsterSkill.SKELETON_BURST, NOW + 500_000));
    }

    @Test void failedEffectCompensationRestoresOldDeadlineAndRejectsStaleNonceOrInvalidRefund() {
        var previous = NOW - 10;
        var engine = fixture(ParticipantRole.MONSTER_LOBBY, skillRules(90_000), Map.of(MonsterSkill.SKELETON_BURST, previous));
        apply(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, player, 3, 4));
        apply(engine, skill(engine, MonsterSkill.SKELETON_BURST, NOW));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterSkillCancelled(UUID.randomUUID(), round, player, 3, 5,
                MonsterSkill.SKELETON_BURST, NOW + 90_000, NOW + 1));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterSkillCancelled(UUID.randomUUID(), round, player, 3, 5,
                MonsterSkill.SKELETON_BURST, NOW + 90_000, 0));
        apply(engine, engine.proposeMonsterSkillCancelled(UUID.randomUUID(), round, player, 3, 5,
                MonsterSkill.SKELETON_BURST, NOW + 90_000, previous));
        assertEquals(previous, current(engine).monsterSkillReadyAtMillis().get(MonsterSkill.SKELETON_BURST));
        apply(engine, skill(engine, MonsterSkill.SKELETON_BURST, NOW));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterSkillCancelled(UUID.randomUUID(), round, player, 3, 5,
                MonsterSkill.SKELETON_BURST, NOW + 90_000, previous));
        assertEquals(NOW + 90_001, current(engine).monsterSkillReadyAtMillis().get(MonsterSkill.SKELETON_BURST));
    }

    @Test void invalidWallTimesAndOverflowFailWithoutPendingMutation() {
        var engine = deployed();
        assertThrows(IllegalArgumentException.class, () -> skill(engine, MonsterSkill.SKELETON_BURST, -1));
        assertThrows(IllegalArgumentException.class, () -> skill(engine, MonsterSkill.SKELETON_BURST, Long.MAX_VALUE));
        assertFalse(engine.pending());
        assertThrows(IllegalArgumentException.class, () -> current(engine).monsterSkillRemainingMillis(MonsterSkill.SKELETON_BURST, -1));
    }
}
