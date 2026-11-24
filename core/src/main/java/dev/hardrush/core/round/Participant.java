package dev.hardrush.core.round;

import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

public record Participant(UUID id, String name, ParticipantRole role, boolean online, boolean ready,
                          long humanNanos, long joinedAtChallengeNanos, int lifeSequence,
                          int revivalCharges,
                          MonsterRole monsterRole, MonsterDimension monsterDimension,
                          long monsterCooldownNanos, int monsterDeploymentSequence,
                          Map<MonsterSkill, Long> monsterSkillReadyAtMillis, MonsterWallet wallet,
                          long lifeHumanNanos, boolean entering, HumanThreat threat, Map<UUID, Long> huntLocks,
                          WitchRecord witch, Sortie sortie) {
    public Participant {
        Objects.requireNonNull(threat); Objects.requireNonNull(witch); Objects.requireNonNull(sortie);
        Objects.requireNonNull(wallet); Objects.requireNonNull(role);
        // Only a monster out hunting knows its quarry; only a paused sortie's body keeps the tier in the camp.
        if (role == ParticipantRole.MONSTER_LOBBY) sortie = wallet.hasParkedBody() ? sortie.paused() : Sortie.NONE;
        else if (role != ParticipantRole.MONSTER_DEPLOYED) sortie = Sortie.NONE;
        huntLocks = Map.copyOf(Objects.requireNonNull(huntLocks));
        if (huntLocks.size() > MAX_HUNT_LOCKS || huntLocks.values().stream().anyMatch(remaining -> remaining <= 0)) {
            throw new IllegalArgumentException("Invalid hunt locks");
        }
        if (entering && online) throw new IllegalArgumentException("An admitted session is no longer entering");
        Objects.requireNonNull(wallet);
        if (lifeHumanNanos < 0 || lifeHumanNanos > humanNanos) throw new IllegalArgumentException("Invalid life survival time");
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
                || revivalCharges < 0 || monsterCooldownNanos < 0 || monsterDeploymentSequence < 0) {
            throw new IllegalArgumentException("Invalid participant counters");
        }
        if ((role == ParticipantRole.HUMAN_ACTIVE || role == ParticipantRole.HUMAN_RESPAWNING)
                && (joinedAtChallengeNanos < 0 || lifeSequence == 0)) {
            throw new IllegalArgumentException("Human identity requires an admitted life");
        }
        if (ready && role != ParticipantRole.LOBBY) throw new IllegalArgumentException("Only lobby players can be ready");
    }

    /** A participant out on no personal hunt. */
    public Participant(UUID id, String name, ParticipantRole role, boolean online, boolean ready,
                       long humanNanos, long joinedAtChallengeNanos, int lifeSequence,
                       int revivalCharges,
                       MonsterRole monsterRole, MonsterDimension monsterDimension,
                       long monsterCooldownNanos, int monsterDeploymentSequence,
                       Map<MonsterSkill, Long> monsterSkillReadyAtMillis, MonsterWallet wallet,
                       long lifeHumanNanos, boolean entering, HumanThreat threat, Map<UUID, Long> huntLocks,
                       WitchRecord witch) {
        this(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos, lifeSequence, revivalCharges,
                monsterRole, monsterDimension, monsterCooldownNanos, monsterDeploymentSequence,
                monsterSkillReadyAtMillis, wallet, lifeHumanNanos, entering, threat, huntLocks, witch, Sortie.NONE);
    }

    /** Bounded like the snapshot's participant table: one lock per human at most. */
    public static final int MAX_HUNT_LOCKS = 10_000;

    /** A participant the camp witch remembers nothing of. */
    public Participant(UUID id, String name, ParticipantRole role, boolean online, boolean ready,
                       long humanNanos, long joinedAtChallengeNanos, int lifeSequence,
                       int revivalCharges,
                       MonsterRole monsterRole, MonsterDimension monsterDimension,
                       long monsterCooldownNanos, int monsterDeploymentSequence,
                       Map<MonsterSkill, Long> monsterSkillReadyAtMillis, MonsterWallet wallet,
                       long lifeHumanNanos, boolean entering, HumanThreat threat, Map<UUID, Long> huntLocks) {
        this(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos, lifeSequence, revivalCharges,
                monsterRole, monsterDimension, monsterCooldownNanos, monsterDeploymentSequence,
                monsterSkillReadyAtMillis, wallet, lifeHumanNanos, entering, threat, huntLocks, WitchRecord.NONE);
    }

    /** A participant that holds no hunt lock. */
    public Participant(UUID id, String name, ParticipantRole role, boolean online, boolean ready,
                       long humanNanos, long joinedAtChallengeNanos, int lifeSequence,
                       int revivalCharges,
                       MonsterRole monsterRole, MonsterDimension monsterDimension,
                       long monsterCooldownNanos, int monsterDeploymentSequence,
                       Map<MonsterSkill, Long> monsterSkillReadyAtMillis, MonsterWallet wallet,
                       long lifeHumanNanos, boolean entering, HumanThreat threat) {
        this(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos, lifeSequence, revivalCharges,
                monsterRole, monsterDimension, monsterCooldownNanos, monsterDeploymentSequence,
                monsterSkillReadyAtMillis, wallet, lifeHumanNanos, entering, threat, Map.of(), WitchRecord.NONE);
    }

    /** A participant whose current life has shown no threat yet. */
    public Participant(UUID id, String name, ParticipantRole role, boolean online, boolean ready,
                       long humanNanos, long joinedAtChallengeNanos, int lifeSequence,
                       int revivalCharges,
                       MonsterRole monsterRole, MonsterDimension monsterDimension,
                       long monsterCooldownNanos, int monsterDeploymentSequence,
                       Map<MonsterSkill, Long> monsterSkillReadyAtMillis, MonsterWallet wallet,
                       long lifeHumanNanos, boolean entering) {
        this(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos, lifeSequence, revivalCharges,
                monsterRole, monsterDimension, monsterCooldownNanos, monsterDeploymentSequence,
                monsterSkillReadyAtMillis, wallet, lifeHumanNanos, entering, HumanThreat.NONE);
    }

    /** A participant that is not waiting for the profile adapter; stored snapshots never are. */
    public Participant(UUID id, String name, ParticipantRole role, boolean online, boolean ready,
                       long humanNanos, long joinedAtChallengeNanos, int lifeSequence,
                       int revivalCharges,
                       MonsterRole monsterRole, MonsterDimension monsterDimension,
                       long monsterCooldownNanos, int monsterDeploymentSequence,
                       Map<MonsterSkill, Long> monsterSkillReadyAtMillis, MonsterWallet wallet, long lifeHumanNanos) {
        this(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos, lifeSequence, revivalCharges,
                monsterRole, monsterDimension, monsterCooldownNanos, monsterDeploymentSequence,
                monsterSkillReadyAtMillis, wallet, lifeHumanNanos, false);
    }

    /** A participant whose current human life has not yet accrued survival time. */
    public Participant(UUID id, String name, ParticipantRole role, boolean online, boolean ready,
                       long humanNanos, long joinedAtChallengeNanos, int lifeSequence,
                       int revivalCharges,
                       MonsterRole monsterRole, MonsterDimension monsterDimension,
                       long monsterCooldownNanos, int monsterDeploymentSequence,
                       Map<MonsterSkill, Long> monsterSkillReadyAtMillis, MonsterWallet wallet) {
        this(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos, lifeSequence, revivalCharges,
                monsterRole, monsterDimension, monsterCooldownNanos, monsterDeploymentSequence,
                monsterSkillReadyAtMillis, wallet, 0);
    }

    /** Compatibility for callers and snapshots from before the monster coin economy. */
    public Participant(UUID id, String name, ParticipantRole role, boolean online, boolean ready,
                       long humanNanos, long joinedAtChallengeNanos, int lifeSequence,
                       int revivalCharges,
                       MonsterRole monsterRole, MonsterDimension monsterDimension,
                       long monsterCooldownNanos, int monsterDeploymentSequence,
                       Map<MonsterSkill, Long> monsterSkillReadyAtMillis) {
        this(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos, lifeSequence, revivalCharges,
                monsterRole, monsterDimension, monsterCooldownNanos, monsterDeploymentSequence,
                monsterSkillReadyAtMillis, MonsterWallet.EMPTY);
    }

    /** Compatibility for the former single, non-stacking entitlement. */
    public Participant(UUID id, String name, ParticipantRole role, boolean online, boolean ready,
                       long humanNanos, long joinedAtChallengeNanos, int lifeSequence,
                       boolean revivalEntitled, MonsterRole monsterRole,
                       MonsterDimension monsterDimension, long monsterCooldownNanos, int monsterDeploymentSequence,
                       Map<MonsterSkill, Long> monsterSkillReadyAtMillis) {
        this(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos, lifeSequence,
                revivalEntitled ? 1 : 0, monsterRole, monsterDimension, monsterCooldownNanos,
                monsterDeploymentSequence, monsterSkillReadyAtMillis);
    }

    public boolean revivalEntitled() { return revivalCharges > 0; }

    /**
     * Became a human after the challenge clock had started. A join while the clock still stood at zero (humans
     * still entering, or all of them away) counts as an opening entrant, like those who joined at the start: that
     * clock only stands still before anyone plays, which is always inside the dusk pact, so nothing is lost.
     */
    public boolean lateEntrant() { return joinedAtChallengeNanos > 0; }

    /**
     * Present on the server. {@link #online} additionally means the profile adapter has admitted this
     * body; a life still {@link #entering} is present but not yet playing, so it accrues no time.
     */
    public boolean connected() { return online || entering; }

    /** A committed human life whose connected player still waits for the profile adapter's unlock. */
    Participant awaitingAdmission() {
        return new Participant(id, name, role, false, ready, humanNanos, joinedAtChallengeNanos, lifeSequence,
                revivalCharges, monsterRole, monsterDimension, monsterCooldownNanos, monsterDeploymentSequence,
                monsterSkillReadyAtMillis, wallet, lifeHumanNanos, connected(), threat, huntLocks, witch, sortie);
    }

    Participant withThreat(HumanThreat value) {
        return new Participant(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos, lifeSequence,
                revivalCharges, monsterRole, monsterDimension, monsterCooldownNanos,
                monsterDeploymentSequence, monsterSkillReadyAtMillis, wallet, lifeHumanNanos, entering, value, huntLocks, witch, sortie);
    }

    Participant withWallet(MonsterWallet value) {
        return new Participant(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos, lifeSequence,
                revivalCharges, monsterRole, monsterDimension, monsterCooldownNanos,
                monsterDeploymentSequence, monsterSkillReadyAtMillis, value, lifeHumanNanos, entering, threat, huntLocks, witch, sortie);
    }

    /** Preserve participation history; this generation grants no further life or monster actions. */
    public Participant exitCompetition(ParticipantRole destination) {
        if (!destination.isExcluded()) throw new IllegalArgumentException("退出身份必须为主动退出或取消资格");
        return new Participant(id, name, destination, connected(), false, humanNanos, joinedAtChallengeNanos,
                lifeSequence, 0, monsterRole, monsterDimension, 0,
                monsterDeploymentSequence, Map.of());
    }

    /** Compatibility for snapshots/callers before durable per-skill cooldowns. */
    public Participant(UUID id, String name, ParticipantRole role, boolean online, boolean ready,
                       long humanNanos, long joinedAtChallengeNanos, int lifeSequence,
                       boolean revivalEntitled, MonsterRole monsterRole,
                       MonsterDimension monsterDimension, long monsterCooldownNanos, int monsterDeploymentSequence) {
        this(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos, lifeSequence,
                revivalEntitled, monsterRole, monsterDimension, monsterCooldownNanos,
                monsterDeploymentSequence, Map.of());
    }

    /** New participants and identity callers retain the default selections until a lobby choice is committed. */
    public Participant(UUID id, String name, ParticipantRole role, boolean online, boolean ready,
                       long humanNanos, long joinedAtChallengeNanos, int lifeSequence,
                       boolean revivalEntitled) {
        this(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos, lifeSequence,
                revivalEntitled, MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0);
    }

    public Participant withSession(String newName, boolean connected) {
        return new Participant(id, newName, role, connected, ready, humanNanos,
                joinedAtChallengeNanos, lifeSequence, revivalCharges,
                monsterRole, monsterDimension, monsterCooldownNanos, monsterDeploymentSequence, monsterSkillReadyAtMillis, wallet, lifeHumanNanos, false, threat, huntLocks, witch, sortie);
    }

    public Participant withReady(boolean value) {
        return new Participant(id, name, role, online, value, humanNanos,
                joinedAtChallengeNanos, lifeSequence, revivalCharges,
                monsterRole, monsterDimension, monsterCooldownNanos, monsterDeploymentSequence, monsterSkillReadyAtMillis, wallet, lifeHumanNanos, entering, threat, huntLocks, witch, sortie);
    }

    public Participant withRole(ParticipantRole value) {
        return new Participant(id, name, value, online, false, humanNanos,
                joinedAtChallengeNanos, lifeSequence, revivalCharges,
                monsterRole, monsterDimension, monsterCooldownNanos, monsterDeploymentSequence, monsterSkillReadyAtMillis, wallet, lifeHumanNanos, entering, threat, huntLocks, witch,
                sortie);
    }

    Participant accrue(long elapsed) {
        return new Participant(id, name, role, online, ready, Math.addExact(humanNanos, elapsed),
                joinedAtChallengeNanos, lifeSequence, revivalCharges,
                monsterRole, monsterDimension, monsterCooldownNanos, monsterDeploymentSequence, monsterSkillReadyAtMillis, wallet,
                Math.addExact(lifeHumanNanos, elapsed), entering, threat, huntLocks, witch, sortie);
    }

    Participant start(long joinedAt) {
        // Survival time is per life: the opening start and a mid-round join begin from zero.
        return new Participant(id, name, ParticipantRole.HUMAN_ACTIVE, online, false, humanNanos,
                joinedAt, Math.max(1, lifeSequence), revivalCharges,
                monsterRole, monsterDimension, monsterCooldownNanos, monsterDeploymentSequence, monsterSkillReadyAtMillis, wallet, 0, false, threat, huntLocks, witch, Sortie.NONE);
    }

    Participant selectMonster(MonsterRole selection, MonsterDimension dimension) {
        return new Participant(id, name, role, online, false, humanNanos, joinedAtChallengeNanos,
                lifeSequence, revivalCharges, selection, dimension,
                monsterCooldownNanos, monsterDeploymentSequence, monsterSkillReadyAtMillis, wallet, lifeHumanNanos, entering, threat, huntLocks, witch, sortie);
    }

    /** PERSONAL_TARGET never changes the historical dimension preference. */
    Participant selectMonsterRole(MonsterRole selection) { return selectMonster(selection, monsterDimension); }

    Participant monsterLobby(long cooldown) {
        return new Participant(id, name, ParticipantRole.MONSTER_LOBBY, online, false, humanNanos,
                joinedAtChallengeNanos, lifeSequence, revivalCharges,
                monsterRole, monsterDimension, cooldown, monsterDeploymentSequence, monsterSkillReadyAtMillis, wallet, lifeHumanNanos, false, threat, huntLocks, witch, sortie);
    }

    Participant withRevivalEntitlement(boolean entitled) {
        return new Participant(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos,
                lifeSequence, entitled ? Math.max(1, revivalCharges) : 0, monsterRole, monsterDimension,
                monsterCooldownNanos, monsterDeploymentSequence, monsterSkillReadyAtMillis, wallet, lifeHumanNanos, entering, threat, huntLocks, witch, sortie);
    }

    Participant addRevivalCharges(int count) {
        if (count <= 0 || count > Integer.MAX_VALUE - revivalCharges) {
            throw new IllegalArgumentException("发放次数必须为正整数，且累计次数不能超过 " + Integer.MAX_VALUE + "。");
        }
        return new Participant(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos,
                lifeSequence, revivalCharges + count, monsterRole, monsterDimension,
                monsterCooldownNanos, monsterDeploymentSequence, monsterSkillReadyAtMillis, wallet, lifeHumanNanos, entering, threat, huntLocks, witch, sortie);
    }

    /** One charge spent where the human stands (a revival totem saved this life); the life itself goes on. */
    Participant spendRevivalCharge() {
        if (revivalCharges == 0) throw new IllegalStateException("No revival charge to spend");
        return new Participant(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos,
                lifeSequence, revivalCharges - 1, monsterRole, monsterDimension,
                monsterCooldownNanos, monsterDeploymentSequence, monsterSkillReadyAtMillis, wallet, lifeHumanNanos, entering, threat, huntLocks, witch, sortie);
    }

    Participant revive(boolean admissionPending) {
        int life = Math.addExact(lifeSequence, 1);
        // Revival supplies are the one purchase that outlives the monster: they go with the life the altar starts.
        var supplies = new java.util.EnumMap<ShopOffer, Integer>(ShopOffer.class);
        wallet.order().forEach((offer, count) -> { if (offer.revival()) supplies.put(offer, count); });
        var record = supplies.isEmpty() ? witch : witch.supplied(life, supplies);
        return new Participant(id, name, ParticipantRole.HUMAN_ACTIVE, online && !admissionPending,
                false, humanNanos, joinedAtChallengeNanos, life, revivalCharges - 1,
                // A revived human starts a new life with no survival time; coins, other paid goods and hunt locks never
                // outlive the monster.
                monsterRole, monsterDimension, 0, monsterDeploymentSequence, monsterSkillReadyAtMillis, MonsterWallet.EMPTY, 0,
                online && admissionPending, threat, Map.of(), record);
    }

    Participant deployMonster() {
        return new Participant(id, name, ParticipantRole.MONSTER_DEPLOYED, online, false, humanNanos,
                joinedAtChallengeNanos, lifeSequence, revivalCharges,
                monsterRole, monsterDimension, 0, Math.addExact(monsterDeploymentSequence, 1), monsterSkillReadyAtMillis, wallet, lifeHumanNanos, false, threat, huntLocks, witch, Sortie.NONE);
    }

    Participant elapseMonsterCooldown(long elapsed) {
        return new Participant(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos,
                lifeSequence, revivalCharges, monsterRole, monsterDimension,
                Math.max(0, monsterCooldownNanos - Math.min(monsterCooldownNanos, elapsed)), monsterDeploymentSequence, monsterSkillReadyAtMillis, wallet, lifeHumanNanos, entering, threat, huntLocks, witch, sortie);
    }

    /** Remaining nanoseconds before this monster may hunt {@code human} again; zero when it may. */
    public long huntLockNanos(UUID human) {
        return huntLocks.getOrDefault(Objects.requireNonNull(human), 0L);
    }

    /** Locks {@code human} for {@code nanos} from now; a fresh defeat restarts the wait, never stacks it. */
    Participant withHuntLock(UUID human, long nanos) {
        Objects.requireNonNull(human);
        if (nanos <= 0) return this;
        var locks = new LinkedHashMap<>(huntLocks);
        locks.put(human, nanos);
        return withHuntLocks(locks);
    }

    /** Live server time passes for every lock; one that runs out is forgotten. */
    Participant elapseHuntLocks(long elapsed) {
        if (huntLocks.isEmpty() || elapsed <= 0) return this;
        var locks = new LinkedHashMap<UUID, Long>();
        huntLocks.forEach((human, remaining) -> { if (remaining > elapsed) locks.put(human, remaining - elapsed); });
        return withHuntLocks(locks);
    }

    Participant withSortie(Sortie value) {
        return new Participant(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos, lifeSequence,
                revivalCharges, monsterRole, monsterDimension, monsterCooldownNanos,
                monsterDeploymentSequence, monsterSkillReadyAtMillis, wallet, lifeHumanNanos, entering, threat, huntLocks, witch, value);
    }

    /** The human this deployed monster was sent onto from the camp; empty for any other outing. */
    public Optional<UUID> quarry() { return sortie.quarry(); }

    Participant withWitch(WitchRecord value) {
        return new Participant(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos, lifeSequence,
                revivalCharges, monsterRole, monsterDimension, monsterCooldownNanos,
                monsterDeploymentSequence, monsterSkillReadyAtMillis, wallet, lifeHumanNanos, entering, threat, huntLocks, value, sortie);
    }

    Participant withHuntLocks(Map<UUID, Long> locks) {
        return new Participant(id, name, role, online, ready, humanNanos, joinedAtChallengeNanos,
                lifeSequence, revivalCharges, monsterRole, monsterDimension, monsterCooldownNanos,
                monsterDeploymentSequence, monsterSkillReadyAtMillis, wallet, lifeHumanNanos, entering, threat, locks, witch, sortie);
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
                lifeSequence, revivalCharges, monsterRole, monsterDimension,
                monsterCooldownNanos, monsterDeploymentSequence, cooldowns, wallet, lifeHumanNanos, entering, threat, huntLocks, witch, sortie);
    }
}
