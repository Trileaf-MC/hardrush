package dev.hardrush.core.round;

import java.util.Objects;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

public record Participant(UUID id, String name, ParticipantRole role, boolean online, boolean ready,
                          long humanNanos, long joinedAtChallengeNanos, int lifeSequence,
                          boolean revivalEntitled, boolean hasLeftProtection,
                          MonsterRole monsterRole, MonsterDimension monsterDimension,
                          long monsterCooldownNanos, int monsterDeploymentSequence,
                          Map<MonsterSkill, Long> monsterSkillReadyAtMillis) {
    public Participant {
        Objects.requireNonNull(id); Objects.requireNonNull(role); Objects.requireNonNull(name);
        Objects.requireNonNull(monsterRole); Objects.requireNonNull(monsterDimension);
        monsterSkillReadyAtMillis = Map.copyOf(Objects.requireNonNull(monsterSkillReadyAtMillis));
        if (monsterSkillReadyAtMillis.values().stream().anyMatch(readyAt -> readyAt < 0)) {
            throw new IllegalArgumentException("Invalid monster skill cooldown");
        }
        if (name.isBlank() || name.length() > 64 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid participant name");
        }
        if (humanNanos < 0 || joinedAtChallengeNanos < -1 || lifeSequence < 0
                || monsterCooldownNanos < 0 || monsterDeploymentSequence < 0) {
            throw new IllegalArgumentException("Invalid participant counters");
        }
        if ((role == ParticipantRole.HUMAN_ACTIVE || role == ParticipantRole.HUMAN_RESPAWNING)
                && (joinedAtChallengeNanos < 0 || lifeSequence == 0)) {
            throw new IllegalArgumentException("Human identity requires an admitted life");
        }
        if (ready && role != ParticipantRole.LOBBY) throw new IllegalArgumentException("Only lobby players can be ready");
    }

    /** Compatibility for snapshots/callers before durable per-skill cooldowns. */
    public Participant(UUID id, String name, ParticipantRole role, boolean online, boolean ready,
                       long humanNanos, long joinedAtChallengeNanos, int lifeSequence,
                       boolean revivalEntitled, boolean hasLeftProtection, MonsterRole monsterRole,
                       MonsterDimension monsterDimension, long monsterCooldownNanos, int monsterDeploymentSequence) {
        this(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos, lifeSequence,
                revivalEntitled, hasLeftProtection, monsterRole, monsterDimension, monsterCooldownNanos,
                monsterDeploymentSequence, Map.of());
    }

    /** Existing identity callers retain the default selections until a lobby choice is committed. */
    public Participant(UUID id, String name, ParticipantRole role, boolean online, boolean ready,
                       long humanNanos, long joinedAtChallengeNanos, int lifeSequence,
                       boolean revivalEntitled, boolean hasLeftProtection) {
        this(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos, lifeSequence,
                revivalEntitled, hasLeftProtection, MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0);
    }

    /** Compatibility for callers creating a new participant or a fresh protected life. */
    public Participant(UUID id, String name, ParticipantRole role, boolean online, boolean ready,
                       long humanNanos, long joinedAtChallengeNanos, int lifeSequence,
                       boolean revivalEntitled) {
        this(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos, lifeSequence,
                revivalEntitled, false);
    }

    public Participant withSession(String newName, boolean connected) {
        return new Participant(id, newName, role, connected, ready, humanNanos,
                joinedAtChallengeNanos, lifeSequence, revivalEntitled, hasLeftProtection,
                monsterRole, monsterDimension, monsterCooldownNanos, monsterDeploymentSequence, monsterSkillReadyAtMillis);
    }

    public Participant withReady(boolean value) {
        return new Participant(id, name, role, online, value, humanNanos,
                joinedAtChallengeNanos, lifeSequence, revivalEntitled, hasLeftProtection,
                monsterRole, monsterDimension, monsterCooldownNanos, monsterDeploymentSequence, monsterSkillReadyAtMillis);
    }

    public Participant withRole(ParticipantRole value) {
        return new Participant(id, name, value, online, false, humanNanos,
                joinedAtChallengeNanos, lifeSequence, revivalEntitled, hasLeftProtection,
                monsterRole, monsterDimension, monsterCooldownNanos, monsterDeploymentSequence, monsterSkillReadyAtMillis);
    }

    Participant leaveProtection() {
        return new Participant(id, name, role, online, ready, humanNanos,
                joinedAtChallengeNanos, lifeSequence, revivalEntitled, true,
                monsterRole, monsterDimension, monsterCooldownNanos, monsterDeploymentSequence, monsterSkillReadyAtMillis);
    }

    Participant accrue(long elapsed) {
        return new Participant(id, name, role, online, ready, Math.addExact(humanNanos, elapsed),
                joinedAtChallengeNanos, lifeSequence, revivalEntitled, hasLeftProtection,
                monsterRole, monsterDimension, monsterCooldownNanos, monsterDeploymentSequence, monsterSkillReadyAtMillis);
    }

    Participant start(long joinedAt) {
        return new Participant(id, name, ParticipantRole.HUMAN_ACTIVE, online, false, humanNanos,
                joinedAt, Math.max(1, lifeSequence), revivalEntitled, false,
                monsterRole, monsterDimension, monsterCooldownNanos, monsterDeploymentSequence, monsterSkillReadyAtMillis);
    }

    Participant selectMonster(MonsterRole selection, MonsterDimension dimension) {
        return new Participant(id, name, role, online, false, humanNanos, joinedAtChallengeNanos,
                lifeSequence, revivalEntitled, hasLeftProtection, selection, dimension,
                monsterCooldownNanos, monsterDeploymentSequence, monsterSkillReadyAtMillis);
    }

    Participant monsterLobby(long cooldown) {
        return new Participant(id, name, ParticipantRole.MONSTER_LOBBY, online, false, humanNanos,
                joinedAtChallengeNanos, lifeSequence, revivalEntitled, hasLeftProtection,
                monsterRole, monsterDimension, cooldown, monsterDeploymentSequence, monsterSkillReadyAtMillis);
    }

    Participant withRevivalEntitlement(boolean entitled) {
        return new Participant(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos,
                lifeSequence, entitled, hasLeftProtection, monsterRole, monsterDimension,
                monsterCooldownNanos, monsterDeploymentSequence, monsterSkillReadyAtMillis);
    }

    Participant revive(boolean admissionPending) {
        return new Participant(id, name, ParticipantRole.HUMAN_ACTIVE, online && !admissionPending,
                false, humanNanos, joinedAtChallengeNanos, Math.addExact(lifeSequence, 1), false,
                false, monsterRole, monsterDimension, 0, monsterDeploymentSequence, monsterSkillReadyAtMillis);
    }

    Participant deployMonster() {
        return new Participant(id, name, ParticipantRole.MONSTER_DEPLOYED, online, false, humanNanos,
                joinedAtChallengeNanos, lifeSequence, revivalEntitled, hasLeftProtection,
                monsterRole, monsterDimension, 0, Math.addExact(monsterDeploymentSequence, 1), monsterSkillReadyAtMillis);
    }

    Participant elapseMonsterCooldown(long elapsed) {
        return new Participant(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos,
                lifeSequence, revivalEntitled, hasLeftProtection, monsterRole, monsterDimension,
                Math.max(0, monsterCooldownNanos - Math.min(monsterCooldownNanos, elapsed)), monsterDeploymentSequence, monsterSkillReadyAtMillis);
    }

    /** Wall-clock deadline: disconnect, role changes, death and server downtime do not reset it. */
    public long monsterSkillRemainingMillis(MonsterSkill skill, long nowEpochMillis) {
        Objects.requireNonNull(skill);
        if (nowEpochMillis < 0) throw new IllegalArgumentException("Invalid epoch millis");
        return Math.max(0, monsterSkillReadyAtMillis.getOrDefault(skill, 0L) - nowEpochMillis);
    }

    Participant withMonsterSkillReadyAt(MonsterSkill skill, long readyAtEpochMillis) {
        var cooldowns = new LinkedHashMap<>(monsterSkillReadyAtMillis);
        Objects.requireNonNull(skill);
        if (readyAtEpochMillis == 0) cooldowns.remove(skill);
        else cooldowns.put(skill, readyAtEpochMillis);
        return new Participant(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos,
                lifeSequence, revivalEntitled, hasLeftProtection, monsterRole, monsterDimension,
                monsterCooldownNanos, monsterDeploymentSequence, cooldowns);
    }
}
