package dev.hardrush.core.round;

import dev.hardrush.core.admin.AdminText;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.combat.CombatEvent;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Single-thread rule arbiter. The adapter must durably commit a proposal before accept().
 * While a write is in flight conflicting mutations are rejected, not queued against old state.
 */
public final class RoundEngine {
    public static final long MAX_SUCCESS_CELEBRATION_MILLIS = 180_000;
    public static final long MONSTER_COOLDOWN_NANOS = 30_000_000_000L;

    /** Live server milliseconds, shared by every completion path and its player-facing duration. */
    public static long successCelebrationMillis(RulesConfig rules) {
        return Math.min(rules.successCelebrationMillis(), MAX_SUCCESS_CELEBRATION_MILLIS);
    }

    private final MonotonicClock clock;
    private RoundSnapshot committed;
    private RoundTransition pending;
    private long boundary;
    // Native effects cannot survive a process restart. Only this process's actual reservations
    // can be compensated; preserve their last deadline to distinguish same-millisecond retries.
    private final Map<SkillKey, SkillReservation> skillReservations = new LinkedHashMap<>();
    private record SkillKey(UUID player, MonsterSkill skill) {}
    private record SkillReservation(int life, int deployment, long consumedReadyAt, long previousReadyAt) {}

    private RoundEngine(RoundSnapshot snapshot, MonotonicClock clock) {
        this.committed = snapshot;
        this.clock = Objects.requireNonNull(clock);
        this.boundary = clock.nanoTime();
    }

    public static RoundEngine create(UUID id, long sequence, long seed, RulesConfig rules, MonotonicClock clock) {
        return new RoundEngine(new RoundSnapshot(id, sequence, seed, 0, rules, RoundPhase.PREPARING,
                PauseReason.NOT_RUNNING, 0, 0, Map.of(), false, false, null), clock);
    }

    public static RoundEngine restore(RoundSnapshot stored, MonotonicClock clock) {
        Map<UUID, Participant> players = new LinkedHashMap<>();
        stored.participants().forEach((id, p) -> players.put(id, p.withSession(p.name(), false)));
        boolean uncertain = stored.timingUncertain() || !stored.cleanShutdown();
        // A dirty shutdown is never silently presented as a complete timing interval.
        RoundPhase phase = stored.cleanShutdown() && !requiresLegacyClosure(stored)
                ? stored.phase() : RoundPhase.RECOVERY_REQUIRED;
        RoundSnapshot restored = new RoundSnapshot(stored.roundId(), stored.sequence(), stored.seed(), stored.version(),
                stored.rules(), phase, pause(phase, players), stored.challengeNanos(), stored.remainingPhaseNanos(),
                players, stored.cleanShutdown(), uncertain, stored.result());
        return new RoundEngine(restored, clock);
    }

    /** An old active round must finish on the old build or be closed through audited recovery. */
    public static boolean requiresLegacyClosure(RoundSnapshot stored) {
        return stored.rules().huntRule() == RulesConfig.HuntRule.LEGACY_VOTE
                && stored.result() == null && stored.phase() != RoundPhase.ROTATING;
    }

    /** A repaired database proves durable facts, never that native world/profile effects completed. */
    public static RoundEngine restoreForRecovery(RoundSnapshot stored, MonotonicClock clock) {
        return restore(new RoundSnapshot(stored.roundId(), stored.sequence(), stored.seed(), stored.version(), stored.rules(),
                stored.phase(), stored.pauseReason(), stored.challengeNanos(), stored.remainingPhaseNanos(),
                stored.participants(), false, true, stored.result()), clock);
    }

    /** Only a complete, verified world + profile + database checkpoint permits crash continuation. */
    public static RoundEngine restoreVerifiedCheckpoint(RoundSnapshot stored, MonotonicClock clock) {
        if (stored.phase() != RoundPhase.RUNNING || stored.result() != null || requiresLegacyClosure(stored)) {
            throw new IllegalArgumentException("Only a current active round can resume from a full checkpoint");
        }
        var players = new LinkedHashMap<UUID, Participant>();
        stored.participants().forEach((id, p) -> players.put(id, p.withSession(p.name(), false)));
        return new RoundEngine(new RoundSnapshot(stored.roundId(), stored.sequence(), stored.seed(), stored.version(),
                stored.rules(), RoundPhase.RUNNING, PauseReason.NO_ONLINE_HUMANS, stored.challengeNanos(),
                stored.remainingPhaseNanos(), players, false, true, null), clock);
    }

    /** A full checkpoint pauses native simulation; its disk-copy interval is not challenge time. */
    public void resumeAfterCheckpoint() {
        if (pending != null) throw new IllegalStateException("Cannot resume a pending checkpoint");
        boundary = clock.nanoTime();
    }

    public void excludeFailedCheckpointPause(long pausedNanos) {
        if (pending != null || pausedNanos < 0) throw new IllegalStateException("Invalid checkpoint pause");
        boundary = Math.addExact(boundary, pausedNanos);
    }

    public RoundSnapshot committed() { return committed; }
    public boolean pending() { return pending != null; }
    public RoundSnapshot view() { return pending == null ? project(committed, boundary, clock.nanoTime()) : pending.before(); }

    /**
     * Admission guard independent of view()'s frozen pending snapshot. Ordinary writes cannot
     * extend rescue; a valid pending revival/join has already reserved the return to RUNNING.
     * The adapter still permits an already captured terminal damage batch to finish.
     */
    public boolean rescueExpiredAt(long now) {
        RoundSnapshot base = committed;
        long start = boundary;
        if (pending != null) {
            if (pending.before().phase() == RoundPhase.RESCUE && pending.after().phase() == RoundPhase.RUNNING) {
                return false;
            }
            if (pending.after().phase() == RoundPhase.RESCUE) {
                base = pending.after();
            } else {
                base = pending.before();
            }
            start = pending.capturedNanos();
        }
        if (base.phase() != RoundPhase.RESCUE) return false;
        long elapsed = now - start;
        if (elapsed < 0) return false;
        return base.remainingPhaseNanos() == 0
                || !base.cleanShutdown() && elapsed >= base.remainingPhaseNanos();
    }

    /** Only a verified world/profile adapter may supply this event; no admin bypass in the P1 lobby. */
    public RoundTransition proposeWorldReady(UUID eventId) {
        RoundSnapshot before = capture();
        requirePhase(before, RoundPhase.PREPARING);
        return proposal(eventId, "WORLD_READY", before, RoundPhase.WAITING, 0, before.participants(), before.result(), false);
    }

    public RoundTransition proposeJoin(UUID eventId, UUID playerId, String name) {
        return proposeJoin(eventId, playerId, name, false);
    }

    /** Platform admission is durable before play opens; online timing begins at the later unlock event. */
    public RoundTransition proposeAdmissionJoin(UUID eventId, UUID playerId, String name) {
        return proposeJoin(eventId, playerId, name, true);
    }

    private RoundTransition proposeJoin(UUID eventId, UUID playerId, String name, boolean admissionPending) {
        RoundSnapshot before = capture();
        requirePhase(before, RoundPhase.WAITING, RoundPhase.COUNTDOWN, RoundPhase.RUNNING, RoundPhase.RESCUE);
        requireOpenRescue(before);
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        Participant existing = players.get(playerId);
        if (existing != null && existing.role() != ParticipantRole.LOBBY) {
            throw new IllegalStateException("UUID already participating; reconnect cannot restore a dead human");
        }
        Participant player = existing == null
                ? new Participant(playerId, name, ParticipantRole.LOBBY, true, false, 0, -1, 0, false)
                : existing.withSession(name, true);
        RoundPhase phase = before.phase();
        long remaining = before.remainingPhaseNanos();
        if (phase == RoundPhase.RUNNING || phase == RoundPhase.RESCUE) {
            player = player.start(before.challengeNanos());
            if (admissionPending) player = player.withSession(name, false);
            phase = RoundPhase.RUNNING;
            remaining = 0;
        }
        players.put(playerId, player);
        return proposal(eventId, "JOIN", before, phase, remaining, players, before.result(), false);
    }

    /** One spatial intention: registration and readiness share a single durable transition. */
    public RoundTransition proposeAssembly(UUID eventId, UUID roundId, UUID playerId, String name, boolean ready) {
        RoundSnapshot before = capture();
        if (!before.roundId().equals(roundId)) throw new IllegalStateException("Stale assembly round");
        requirePhase(before, RoundPhase.WAITING, RoundPhase.COUNTDOWN);
        var players = new LinkedHashMap<>(before.participants());
        var player = players.get(playerId);
        if (player != null && player.role() != ParticipantRole.LOBBY) throw new IllegalStateException("Dead or participating UUID cannot assemble");
        if (player == null) player = new Participant(playerId, name, ParticipantRole.LOBBY, true, false, 0, -1, 0, false);
        players.put(playerId, (ready ? player.withSession(name, true) : player).withReady(ready));
        var phase = before.phase(); long remaining = before.remainingPhaseNanos();
        if (readyOnline(players) == 0) { phase = RoundPhase.WAITING; remaining = 0; }
        else if (phase == RoundPhase.WAITING) { phase = RoundPhase.COUNTDOWN; remaining = millisToNanos(before.rules().startCountdownMillis()); }
        return proposal(eventId, ready ? "ASSEMBLE" : "LEAVE_ASSEMBLY", before, phase, remaining, players, before.result(), false);
    }

    /** Cancel or prove one restored readiness without discarding other absent original UUIDs. */
    public RoundTransition proposeRestoredReady(UUID eventId, UUID roundId, UUID id, String name, boolean safe) {
        var before = capture(); requirePhase(before, RoundPhase.COUNTDOWN);
        var p = requirePlayer(before, id);
        if (!before.roundId().equals(roundId) || !p.ready() || p.role() != ParticipantRole.LOBBY)
            throw new IllegalStateException("Stale restored readiness");
        var players = new LinkedHashMap<>(before.participants());
        players.put(id, p.withSession(name, safe).withReady(safe));
        boolean any = players.values().stream().anyMatch(v -> v.ready() && v.role() == ParticipantRole.LOBBY);
        return proposal(eventId, "RESTORE_ASSEMBLY", before, any ? RoundPhase.COUNTDOWN : RoundPhase.WAITING,
                any ? before.remainingPhaseNanos() : 0, players, before.result(), false);
    }

    public RoundTransition proposeOnline(UUID eventId, UUID playerId, String name, boolean online) {
        RoundSnapshot before = capture();
        if (before.phase() == RoundPhase.RECOVERY_REQUIRED) throw new IllegalStateException("Recovery required");
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        Participant player = requirePlayer(before, playerId);
        Participant connected = player.withSession(name, online);
        if (!online && player.role() == ParticipantRole.LOBBY) connected = connected.withReady(false);
        if (player.role() == ParticipantRole.MONSTER_DEPLOYED && player.online() != online) {
            connected = connected.monsterLobby(MONSTER_COOLDOWN_NANOS);
        }
        players.put(playerId, connected);
        RoundPhase phase = before.phase();
        long remaining = before.remainingPhaseNanos();
        if (phase == RoundPhase.COUNTDOWN && readyOnline(players) == 0) {
            phase = RoundPhase.WAITING;
            remaining = 0;
            players.replaceAll((id, p) -> p.withReady(false));
        }
        return proposal(eventId, online ? "ONLINE" : "OFFLINE", before, phase, remaining, players, before.result(), false);
    }

    public RoundTransition proposeReady(UUID eventId, UUID playerId, boolean ready) {
        RoundSnapshot before = capture();
        requirePhase(before, RoundPhase.WAITING, RoundPhase.COUNTDOWN);
        Participant player = requirePlayer(before, playerId);
        if (!player.online() || player.role() != ParticipantRole.LOBBY) throw new IllegalStateException("Not an online lobby participant");
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        players.put(playerId, player.withReady(ready));
        RoundPhase phase = before.phase();
        long remaining = before.remainingPhaseNanos();
        if (phase == RoundPhase.WAITING && readyOnline(players) > 0) {
            phase = RoundPhase.COUNTDOWN;
            remaining = millisToNanos(before.rules().startCountdownMillis());
        } else if (phase == RoundPhase.COUNTDOWN && readyOnline(players) == 0) {
            phase = RoundPhase.WAITING;
            remaining = 0;
            players.replaceAll((id, p) -> p.withReady(false));
        }
        return proposal(eventId, ready ? "READY" : "UNREADY", before, phase, remaining, players, before.result(), false);
    }

    public RoundTransition proposeNormalDeath(UUID eventId, UUID playerId, int expectedLifeSequence) {
        return normalIdentity(eventId, playerId, expectedLifeSequence, ParticipantRole.HUMAN_ACTIVE, ParticipantRole.HUMAN_RESPAWNING, "NORMAL_DEATH", false);
    }

    public RoundTransition proposeNormalRespawn(UUID eventId, UUID playerId, int expectedLifeSequence) {
        return normalIdentity(eventId, playerId, expectedLifeSequence, ParticipantRole.HUMAN_RESPAWNING, ParticipantRole.HUMAN_ACTIVE, "NORMAL_RESPAWN", false);
    }

    public RoundTransition proposeAdmissionRespawn(UUID eventId, UUID playerId, int expectedLifeSequence) {
        return normalIdentity(eventId, playerId, expectedLifeSequence, ParticipantRole.HUMAN_RESPAWNING, ParticipantRole.HUMAN_ACTIVE, "NORMAL_RESPAWN", true);
    }

    /** Vanilla owns death drops; the committed server identity owns every later admission. */
    public RoundTransition proposeHardcoreDeath(UUID eventId, UUID playerId, int expectedLifeSequence) {
        RoundSnapshot before = capture();
        requirePhase(before, RoundPhase.RUNNING);
        if (before.rules().mode() != GameMode.HARDCORE) throw new IllegalStateException("Hardcore death requires HARDCORE mode");
        Participant player = requirePlayer(before, playerId);
        requireLife(player, ParticipantRole.HUMAN_ACTIVE, expectedLifeSequence);
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        players.put(playerId, new Participant(player.id(), player.name(), ParticipantRole.MONSTER_LOBBY,
                player.online(), false, player.humanNanos(), player.joinedAtChallengeNanos(),
                player.lifeSequence(), player.revivalCharges(), player.hasLeftProtection(), MonsterRole.ZOMBIE,
                MonsterDimension.OVERWORLD, MONSTER_COOLDOWN_NANOS, player.monsterDeploymentSequence(), player.monsterSkillReadyAtMillis()));
        return proposal(eventId, "HARDCORE_DEATH", before, before.phase(), 0, players, before.result(), false);
    }

    /**
     * Atomically commits all actual deaths, their revival rewards and the resulting phase.
     * completion is supplied only by the adapter's verified first-dragon arbiter for this batch.
     */
    public RoundTransition proposeDeathBatch(UUID eventId, UUID roundId, List<RoundDeath> deaths,
            RoundResult completion) {
        RoundSnapshot before = capture();
        requirePhase(before, RoundPhase.RUNNING, RoundPhase.RESCUE);
        requireOpenRescue(before);
        if (!before.roundId().equals(roundId)) throw new IllegalStateException("Stale death round");
        deaths = List.copyOf(deaths);
        if (deaths.isEmpty() && completion == null) throw new IllegalArgumentException("Empty death batch");
        if (completion != null && completion.kind() != RoundResult.Kind.COMPLETED) {
            throw new IllegalArgumentException("Only verified completion may override death settlement");
        }
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        var victims = new java.util.HashSet<UUID>();
        UUID damageBatch = null;
        for (RoundDeath death : deaths) {
            if (!victims.add(death.playerId())) throw new IllegalArgumentException("Duplicate batch victim");
            Participant player = requirePlayer(before, death.playerId());
            if (player.lifeSequence() != death.lifeSequence()
                    || player.monsterDeploymentSequence() != death.deploymentSequence()
                    || player.role() != ParticipantRole.HUMAN_ACTIVE && player.role() != ParticipantRole.MONSTER_DEPLOYED) {
                throw new IllegalStateException("Stale death identity");
            }
            if (death.cause().isPresent()) {
                CombatEvent cause = death.cause().get();
                if (!cause.roundId().equals(roundId)) throw new IllegalStateException("Stale lethal event");
                if (damageBatch != null && !damageBatch.equals(cause.batchId())) {
                    throw new IllegalArgumentException("Deaths must belong to one native damage batch");
                }
                damageBatch = cause.batchId();
            }
            if (player.role().isActiveHuman()) {
                players.put(player.id(), before.rules().mode() == GameMode.NORMAL
                        ? player.withRole(ParticipantRole.HUMAN_RESPAWNING)
                        : new Participant(player.id(), player.name(), ParticipantRole.MONSTER_LOBBY,
                                player.online(), false, player.humanNanos(), player.joinedAtChallengeNanos(),
                                player.lifeSequence(), player.revivalCharges(), player.hasLeftProtection(), MonsterRole.ZOMBIE,
                                MonsterDimension.OVERWORLD, MONSTER_COOLDOWN_NANOS,
                                player.monsterDeploymentSequence(), player.monsterSkillReadyAtMillis()));
            } else {
                players.put(player.id(), player.monsterLobby(MONSTER_COOLDOWN_NANOS));
            }
        }
        // Qualify against identity at lethal collection, before applying this batch's human deaths.
        // Human PvP adds one charge per victim; monster attacks keep their existing entitlement rule.
        if (before.rules().mode() == GameMode.HARDCORE) {
            for (RoundDeath death : deaths) {
                if (!before.participants().get(death.playerId()).role().isActiveHuman() || death.cause().isEmpty()) continue;
                CombatEvent cause = death.cause().get();
                if (cause.origin().isEmpty()) continue;
                var origin = cause.origin().get();
                if (!origin.roundId().equals(roundId)) continue;
                Participant owner = before.participants().get(origin.actor().playerId());
                if (owner == null || owner.id().equals(death.playerId())) continue;
                if (cause.attribution() == CombatEvent.Attribution.HUMAN) {
                    boolean directHumanAttack = switch (origin.kind()) {
                        case MELEE, ARROW, BED, RESPAWN_ANCHOR, TNT, END_CRYSTAL -> true;
                        default -> false;
                    };
                    if (directHumanAttack && owner.role().isActiveHuman()
                            && origin.actor().lifeSequence() == owner.lifeSequence()
                            && origin.actor().deploymentSequence() == owner.monsterDeploymentSequence()) {
                        Participant rewarded = players.get(owner.id());
                        if (rewarded.revivalCharges() < Integer.MAX_VALUE) {
                            players.put(owner.id(), rewarded.addRevivalCharges(1));
                        }
                    }
                    continue;
                }
                if (cause.attribution() != CombatEvent.Attribution.MONSTER) continue;
                boolean directMonsterAttack = switch (origin.kind()) {
                    case MELEE, ARROW, CREEPER, SUMMON -> true;
                    default -> false;
                };
                if (!directMonsterAttack || !owner.role().isMonster()
                        || origin.actor().lifeSequence() > owner.lifeSequence()
                        || origin.actor().deploymentSequence() > owner.monsterDeploymentSequence()) continue;
                Participant rewarded = players.get(owner.id());
                players.put(owner.id(), rewarded.withRevivalEntitlement(true));
            }
        }
        if (completion != null) {
            return proposal(eventId, "DEATH_BATCH", before, RoundPhase.SETTLING,
                    millisToNanos(successCelebrationMillis(before.rules())), withoutEntitlements(players), completion, false);
        }
        if (before.rules().mode() == GameMode.HARDCORE && !hasHuman(players)) {
            if (players.values().stream().anyMatch(p -> p.role().isMonster() && p.revivalEntitled())) {
                long remaining = before.phase() == RoundPhase.RESCUE ? before.remainingPhaseNanos()
                        : millisToNanos(before.rules().combatValues().getOrDefault("round.rescueMillis", 60_000.0).longValue());
                return proposal(eventId, "DEATH_BATCH", before, RoundPhase.RESCUE, remaining, players, null, false);
            }
            return failureProposal(eventId, "DEATH_BATCH", before, players, "ALL_HUMANS_DEAD");
        }
        return proposal(eventId, "DEATH_BATCH", before, RoundPhase.RUNNING, 0, players, null, false);
    }

    public RoundTransition proposeRevival(UUID eventId, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence) {
        return revival(eventId, roundId, playerId, expectedLifeSequence, expectedDeploymentSequence, false);
    }

    /** Adapter must safely install the empty human profile before unlocking the online session. */
    public RoundTransition proposeAdmissionRevival(UUID eventId, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence) {
        return revival(eventId, roundId, playerId, expectedLifeSequence, expectedDeploymentSequence, true);
    }

    private RoundTransition revival(UUID eventId, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence, boolean admissionPending) {
        RoundSnapshot before = capture();
        if (before.rules().mode() != GameMode.HARDCORE) throw new IllegalStateException("Revival requires HARDCORE mode");
        Participant player = requireMonster(before, roundId, playerId, expectedLifeSequence,
                expectedDeploymentSequence, ParticipantRole.MONSTER_LOBBY);
        requireOpenRescue(before);
        if (!player.revivalEntitled()) throw new IllegalStateException("No revival entitlement");
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        players.put(playerId, player.revive(admissionPending));
        return proposal(eventId, "REVIVAL", before, RoundPhase.RUNNING, 0, players, null, false);
    }

    public RoundTransition proposeMonsterSelection(UUID eventId, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence, MonsterRole role, MonsterDimension dimension) {
        Objects.requireNonNull(role); Objects.requireNonNull(dimension);
        RoundSnapshot before = capture();
        Participant player = requireMonster(before, roundId, playerId, expectedLifeSequence, expectedDeploymentSequence,
                ParticipantRole.MONSTER_LOBBY);
        if (before.rules().huntRule() == RulesConfig.HuntRule.PERSONAL_TARGET && dimension != player.monsterDimension())
            throw new IllegalStateException("个人追猎由系统决定维度。");
        return monsterProposal(eventId, "MONSTER_SELECTION", before, player.selectMonster(role, dimension));
    }

    public RoundTransition proposeMonsterRole(UUID eventId, UUID roundId, UUID playerId,
            int life, int sortie, MonsterRole role) {
        RoundSnapshot before = capture();
        var player = requireMonster(before, roundId, playerId, life, sortie, ParticipantRole.MONSTER_LOBBY);
        return monsterProposal(eventId, "MONSTER_SELECTION", before, player.selectMonsterRole(Objects.requireNonNull(role)));
    }

    public RoundTransition proposePersonalHunt(UUID eventId, dev.hardrush.core.hunt.PersonalHuntRequest request, int sortie) {
        RoundSnapshot before = capture();
        if (before.rules().huntRule() != RulesConfig.HuntRule.PERSONAL_TARGET
                || !request.hunterMatches(before, ParticipantRole.MONSTER_LOBBY, sortie)
                || !request.targetMatches(before) || sortie < request.initialSortie())
            throw new IllegalStateException("追猎目标、生命或发起身份已改变。");
        return proposeMonsterDeploy(eventId, request.round(), request.hunter(), request.hunterLife(), sortie);
    }

    public RoundTransition proposeMonsterDeploy(UUID eventId, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence) {
        RoundSnapshot before = capture();
        requireOpenRescue(before);
        Participant player = requireMonster(before, roundId, playerId, expectedLifeSequence, expectedDeploymentSequence,
                ParticipantRole.MONSTER_LOBBY);
        if (player.monsterCooldownNanos() != 0) throw new IllegalStateException("Monster deployment cooldown has not elapsed");
        return monsterProposal(eventId, "MONSTER_DEPLOY", before, player.deployMonster());
    }

    /** The platform may apply the effect only after this cooldown reservation is durably accepted. */
    public RoundTransition proposeMonsterSkill(UUID eventId, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence, MonsterSkill skill, long nowEpochMillis) {
        Objects.requireNonNull(skill);
        if (skill.retired()) throw new IllegalStateException("僵尸召唤技能已退役，不能预约新的召唤。");
        if (nowEpochMillis < 0) throw new IllegalArgumentException("Invalid epoch millis");
        RoundSnapshot before = capture();
        Participant player = requireMonster(before, roundId, playerId, expectedLifeSequence,
                expectedDeploymentSequence, ParticipantRole.MONSTER_DEPLOYED);
        requireOpenRescue(before);
        if (player.monsterRole() != skill.role()) throw new IllegalStateException("当前怪物角色不能使用此技能。");
        if (player.monsterSkillRemainingMillis(skill, nowEpochMillis) > 0) {
            throw new IllegalStateException("技能仍在冷却。");
        }
        var key = new SkillKey(playerId, skill);
        var preceding = skillReservations.get(key);
        long readyAt;
        try {
            readyAt = Math.addExact(nowEpochMillis, skill.cooldownMillis(before.rules()));
            if (preceding != null) readyAt = Math.max(readyAt, Math.addExact(preceding.consumedReadyAt(), 1));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Skill cooldown deadline overflow", overflow);
        }
        var transition = monsterProposal(eventId, "MONSTER_SKILL_" + skill.name(), before,
                player.withMonsterSkillReadyAt(skill, readyAt));
        skillReservations.put(key, new SkillReservation(expectedLifeSequence, expectedDeploymentSequence,
                readyAt, player.monsterSkillReadyAtMillis().getOrDefault(skill, 0L)));
        return transition;
    }

    /** Adapter-only compensation when a reserved effect could not start; never refunds a newer use. */
    public RoundTransition proposeMonsterSkillCancelled(UUID eventId, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence, MonsterSkill skill,
            long consumedReadyAtMillis, long previousReadyAtMillis) {
        Objects.requireNonNull(skill);
        RoundSnapshot before = capture();
        Participant player = requireMonster(before, roundId, playerId, expectedLifeSequence,
                expectedDeploymentSequence, ParticipantRole.MONSTER_DEPLOYED);
        var reservation = skillReservations.get(new SkillKey(playerId, skill));
        if (player.monsterRole() != skill.role() || reservation == null
                || reservation.life() != expectedLifeSequence || reservation.deployment() != expectedDeploymentSequence
                || reservation.consumedReadyAt() != consumedReadyAtMillis
                || reservation.previousReadyAt() != previousReadyAtMillis
                || !Objects.equals(player.monsterSkillReadyAtMillis().get(skill), consumedReadyAtMillis)) {
            throw new IllegalStateException("技能退款标签已过期或无效。");
        }
        return monsterProposal(eventId, "MONSTER_SKILL_CANCELLED_" + skill.name(), before,
                player.withMonsterSkillReadyAt(skill, previousReadyAtMillis));
    }

    public RoundTransition proposeMonsterReturn(UUID eventId, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence) {
        return monsterReturn(eventId, "MONSTER_RETURN", roundId, playerId, expectedLifeSequence, expectedDeploymentSequence);
    }

    /** Adapter-only compensation after durable admission but before an outing actually opens. */
    public RoundTransition proposeMonsterDeployCancelled(UUID eventId, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence) {
        RoundSnapshot before = capture();
        Participant player = requireMonster(before, roundId, playerId, expectedLifeSequence, expectedDeploymentSequence,
                ParticipantRole.MONSTER_DEPLOYED);
        return monsterProposal(eventId, "MONSTER_DEPLOY_CANCELLED", before, player.monsterLobby(0));
    }

    public RoundTransition proposeMonsterDeath(UUID eventId, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence) {
        return monsterReturn(eventId, "MONSTER_DEATH", roundId, playerId, expectedLifeSequence, expectedDeploymentSequence);
    }

    private RoundTransition monsterReturn(UUID eventId, String type, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence) {
        RoundSnapshot before = capture();
        Participant player = requireMonster(before, roundId, playerId, expectedLifeSequence, expectedDeploymentSequence,
                ParticipantRole.MONSTER_DEPLOYED);
        return monsterProposal(eventId, type, before, player.monsterLobby(MONSTER_COOLDOWN_NANOS));
    }

    private RoundTransition monsterProposal(UUID eventId, String type, RoundSnapshot before, Participant player) {
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        players.put(player.id(), player);
        return proposal(eventId, type, before, before.phase(), before.remainingPhaseNanos(), players, before.result(), false);
    }

    private static Participant requireMonster(RoundSnapshot before, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence, ParticipantRole role) {
        requirePhase(before, RoundPhase.RUNNING, RoundPhase.RESCUE);
        if (!before.roundId().equals(roundId)) throw new IllegalStateException("Stale monster round");
        Participant player = requirePlayer(before, playerId);
        requireLife(player, role, expectedLifeSequence);
        if (!player.online() || player.monsterDeploymentSequence() != expectedDeploymentSequence) {
            throw new IllegalStateException("Stale or offline monster deployment");
        }
        return player;
    }

    /**
     * Called before the adapter permits the first exit of this life. Repetition preserves the
     * existing restriction; a callback from a previous life must not consume a new life's exit.
     */
    public RoundTransition proposeLeaveProtection(UUID eventId, UUID playerId, int expectedLifeSequence) {
        RoundSnapshot before = capture();
        requirePhase(before, RoundPhase.RUNNING);
        Participant player = requirePlayer(before, playerId);
        requireLife(player, ParticipantRole.HUMAN_ACTIVE, expectedLifeSequence);
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        players.put(playerId, player.leaveProtection());
        return proposal(eventId, "LEAVE_PROTECTION", before, before.phase(), 0, players, before.result(), false);
    }

    private RoundTransition normalIdentity(UUID eventId, UUID playerId, int expectedLifeSequence, ParticipantRole expected, ParticipantRole target, String type, boolean admissionPending) {
        RoundSnapshot before = capture();
        requirePhase(before, RoundPhase.RUNNING);
        if (before.rules().mode() != GameMode.NORMAL) throw new IllegalStateException("Normal identity event requires NORMAL mode");
        Participant player = requirePlayer(before, playerId);
        requireLife(player, expected, expectedLifeSequence);
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        Participant changed = player.withRole(target);
        if (target == ParticipantRole.HUMAN_ACTIVE) {
            changed = new Participant(changed.id(), changed.name(), target, changed.online(), false,
                    changed.humanNanos(), changed.joinedAtChallengeNanos(), Math.addExact(changed.lifeSequence(), 1), false, false,
                    changed.monsterRole(), changed.monsterDimension(), changed.monsterCooldownNanos(), changed.monsterDeploymentSequence(), changed.monsterSkillReadyAtMillis());
        }
        if (admissionPending) changed = changed.withSession(changed.name(), false);
        players.put(playerId, changed);
        return proposal(eventId, type, before, before.phase(), 0, players, before.result(), false);
    }

    /** Terminal batch hook, deliberately not exposed as a command or a dragon attribution substitute. */
    public RoundTransition proposeFinish(UUID eventId, RoundResult result) {
        RoundSnapshot before = capture();
        requirePhase(before, RoundPhase.RUNNING, RoundPhase.RESCUE);
        if (before.result() != null) throw new IllegalStateException("Round already settled");
        if (result.winner() != null && !before.participants().containsKey(result.winner())) {
            throw new IllegalArgumentException("Winner must be a participant");
        }
        if (result.winner() != null) {
            Participant winner = before.participants().get(result.winner());
            if (winner.lifeSequence() == 0 || winner.joinedAtChallengeNanos() < 0) {
                throw new IllegalArgumentException("A lobby-only participant cannot win a race");
            }
        }
        long duration = switch (result.kind()) {
            case COMPLETED -> successCelebrationMillis(before.rules());
            case FAILED -> before.rules().failureDisplayMillis();
            case ABORTED -> 0;
        };
        return proposal(eventId, "FINISH", before, RoundPhase.SETTLING, millisToNanos(duration),
                withoutEntitlements(before.participants()), result, false);
    }

    public RoundTransition proposeTick(UUID eventId) {
        return proposeTick(eventId, false);
    }

    /** The world adapter has verified every currently connected player is durably back in the lobby. */
    public RoundTransition proposeCelebrationComplete(UUID eventId) {
        RoundSnapshot before = capture();
        requirePhase(before, RoundPhase.SETTLING, RoundPhase.CELEBRATING);
        if (before.result() == null || before.result().kind() != RoundResult.Kind.COMPLETED) {
            throw new IllegalStateException("Only a successful celebration can finish early");
        }
        return proposal(eventId, "CELEBRATION_COMPLETE", before, RoundPhase.ROTATING, 0,
                before.participants(), before.result(), false);
    }

    public RoundTransition proposeAdmissionTick(UUID eventId) { return proposeTick(eventId, true); }

    /**
     * Starts the already verified waiting round without its remaining countdown. Admission,
     * integration, and mode gates remain the session adapter's responsibility, as for normal ticks.
     * An administrator cannot turn an unready or offline lobby participant into a racing human.
     */
    public RoundTransition proposeAdminStart(UUID eventId) { return proposeAdminStart(eventId, false); }

    /** The platform must durably isolate admitted profiles before unlocking their online sessions. */
    public RoundTransition proposeAdmissionAdminStart(UUID eventId) { return proposeAdminStart(eventId, true); }

    private RoundTransition proposeAdminStart(UUID eventId, boolean admissionPending) {
        RoundSnapshot before = capture();
        requirePhase(before, RoundPhase.WAITING, RoundPhase.COUNTDOWN);
        if (readyOnline(before.participants()) == 0) {
            throw new IllegalStateException("At least one online ready lobby participant is required");
        }
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        startReady(players, admissionPending);
        return proposal(eventId, "ADMIN_START", before, RoundPhase.RUNNING, 0,
                players, null, false, true);
    }

    /** Admin credits are additive; ordinary kill rewards still only ensure at least one credit. */
    public RoundTransition proposeAdminGrantRevival(UUID eventId, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedCharges, int count) {
        RoundSnapshot before = capture();
        requirePhase(before, RoundPhase.RUNNING, RoundPhase.RESCUE);
        requireOpenRescue(before);
        if (!before.roundId().equals(roundId)) throw new IllegalStateException("轮次已改变，请重新申请。");
        if (before.rules().mode() != GameMode.HARDCORE) throw new IllegalStateException("仅极限模式支持发放复生次数。");
        Participant player = requirePlayer(before, playerId);
        if ((!player.role().isMonster() && !player.role().isActiveHuman())
                || player.lifeSequence() == 0 || player.joinedAtChallengeNanos() < 0) {
            throw new IllegalStateException("只能向本轮已参赛的人类或怪物发放复生次数。");
        }
        if (player.lifeSequence() != expectedLifeSequence || player.revivalCharges() != expectedCharges) {
            throw new IllegalStateException("玩家生命或剩余次数已改变，请重新申请。");
        }
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        players.put(playerId, player.addRevivalCharges(count));
        return proposal(eventId, "ADMIN_GRANT_REVIVAL", before, before.phase(), before.remainingPhaseNanos(),
                players, before.result(), false, true);
    }

    /** A shared, non-lethal exit for voluntary withdrawal and trusted administrative/automatic sanctions. */
    public RoundTransition proposeParticipationExit(UUID eventId, UUID roundId, UUID playerId,
            ParticipantRole expectedRole, int expectedLife, int expectedDeployment, ParticipantRole destination) {
        var before = capture();
        requirePhase(before, RoundPhase.WAITING, RoundPhase.COUNTDOWN, RoundPhase.RUNNING, RoundPhase.RESCUE);
        requireOpenRescue(before);
        if (!before.roundId().equals(roundId)) throw new IllegalStateException("轮次已改变，请重新操作。");
        if (!destination.isExcluded()) throw new IllegalArgumentException("未知的参赛退出类型。");
        var player = requirePlayer(before, playerId);
        if (player.role() != expectedRole || player.lifeSequence() != expectedLife
                || player.monsterDeploymentSequence() != expectedDeployment)
            throw new IllegalStateException("玩家身份或生命已改变，请重新操作。");
        if (player.role() == ParticipantRole.DISQUALIFIED || player.role() == destination)
            throw new IllegalStateException("玩家本轮已经退出或被取消资格。");
        if (player.role() == ParticipantRole.TRANSITION_PENDING) throw new IllegalStateException("玩家正在转换身份，请稍后重试。");
        var players = new LinkedHashMap<>(before.participants());
        players.put(playerId, player.exitCompetition(destination));
        var phase = before.phase();
        long remaining = before.remainingPhaseNanos();
        RoundResult result = null;
        if (phase == RoundPhase.COUNTDOWN && readyOnline(players) == 0) {
            phase = RoundPhase.WAITING; remaining = 0;
        } else if ((phase == RoundPhase.RUNNING || phase == RoundPhase.RESCUE) && !hasHuman(players)) {
            if (players.values().stream().anyMatch(p -> p.role().isMonster() && p.revivalEntitled())) {
                remaining = phase == RoundPhase.RESCUE ? remaining
                        : millisToNanos(before.rules().combatValues().getOrDefault("round.rescueMillis", 60_000.0).longValue());
                phase = RoundPhase.RESCUE;
            } else {
                phase = RoundPhase.SETTLING;
                remaining = millisToNanos(before.rules().failureDisplayMillis());
                players = new LinkedHashMap<>(withoutEntitlements(players));
                result = new RoundResult(RoundResult.Kind.FAILED, null, "NO_ELIGIBLE_PARTICIPANTS");
            }
        }
        return proposal(eventId, destination == ParticipantRole.DISQUALIFIED ? "DISQUALIFY" : "WITHDRAW",
                before, phase, remaining, players, result, false, destination == ParticipantRole.DISQUALIFIED);
    }

    /** Captures the stop boundary and skips display time; rotation still owns safe world release. */
    public RoundTransition proposeAbort(UUID eventId, String reason) {
        RoundSnapshot before = capture();
        requirePhase(before, RoundPhase.PREPARING, RoundPhase.WAITING, RoundPhase.COUNTDOWN,
                RoundPhase.RUNNING, RoundPhase.RESCUE);
        if (before.result() != null) throw new IllegalStateException("Round already settled");
        RoundResult result = new RoundResult(RoundResult.Kind.ABORTED, null, AdminText.reason(reason));
        return proposal(eventId, "ADMIN_ABORT", before, RoundPhase.ROTATING, 0,
                withoutEntitlements(before.participants()), result, false, true);
    }

    /** Retire an uncertain round without replaying any life, entitlement or native effect. */
    public RoundTransition proposeCloseRecovery(UUID eventId, String reason) {
        RoundSnapshot before = capture();
        requirePhase(before, RoundPhase.RECOVERY_REQUIRED);
        String checkedReason = AdminText.reason(reason);
        RoundResult result = before.result() == null
                ? new RoundResult(RoundResult.Kind.ABORTED, null, checkedReason) : before.result();
        return proposal(eventId, "RECOVERY_CLOSE", before, RoundPhase.ROTATING, 0,
                before.participants(), result, false, true);
    }

    /** The server supplies current verified positions immediately before countdown arbitration. */
    public RoundTransition proposeAdmissionTick(UUID eventId, java.util.Set<UUID> eligible) {
        return proposeTick(eventId, true, eligible);
    }
    private RoundTransition proposeTick(UUID eventId, boolean admissionPending) {
        return proposeTick(eventId, admissionPending, null);
    }
    private RoundTransition proposeTick(UUID eventId, boolean admissionPending, java.util.Set<UUID> eligible) {
        RoundSnapshot before = capture();
        RoundPhase phase = before.phase();
        long remaining = before.remainingPhaseNanos();
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        if (phase == RoundPhase.COUNTDOWN) {
            if (eligible != null) players.replaceAll((id, p) -> p.ready() && p.online() && !eligible.contains(id) ? p.withReady(false) : p);
            if (readyOnline(players) == 0) {
                phase = RoundPhase.WAITING;
                remaining = 0;
                players.replaceAll((id, p) -> p.withReady(false));
            } else if (remaining == 0) {
                phase = RoundPhase.RUNNING;
                startReady(players, admissionPending);
            }
        } else if (phase == RoundPhase.RESCUE && remaining == 0) {
            return failureProposal(eventId, "TICK", before, players, "RESCUE_EXPIRED");
        } else if (phase == RoundPhase.SETTLING || phase == RoundPhase.CELEBRATING) {
            // Failed rounds display their result in the lobby, including legacy CELEBRATING snapshots.
            phase = remaining == 0 ? RoundPhase.ROTATING
                    : before.result().kind() == RoundResult.Kind.COMPLETED ? RoundPhase.CELEBRATING : RoundPhase.SETTLING;
        }
        return proposal(eventId, "TICK", before, phase, remaining, players, before.result(), false);
    }

    public RoundTransition proposeCheckpoint(UUID eventId, boolean cleanShutdown) {
        RoundSnapshot before = capture();
        return proposal(eventId, cleanShutdown ? "STOP" : "CHECKPOINT", before, before.phase(),
                before.remainingPhaseNanos(), before.participants(), before.result(), cleanShutdown);
    }

    public boolean accept(RoundTransition transition) {
        if (!matches(transition)) return false;
        committed = transition.after();
        boundary = transition.capturedNanos();
        pending = null;
        return true;
    }

    public boolean reject(RoundTransition transition, String reason) {
        Objects.requireNonNull(reason);
        if (!matches(transition)) return false;
        RoundSnapshot before = transition.before();
        committed = new RoundSnapshot(before.roundId(), before.sequence(), before.seed(), before.version(), before.rules(),
                RoundPhase.RECOVERY_REQUIRED, PauseReason.RECOVERY_REQUIRED, before.challengeNanos(),
                before.remainingPhaseNanos(), before.participants(), false, true, before.result());
        boundary = transition.capturedNanos();
        pending = null;
        return true;
    }

    private boolean matches(RoundTransition transition) {
        return pending == transition && committed.roundId().equals(transition.before().roundId())
                && committed.version() == transition.before().version();
    }

    private RoundSnapshot capture() {
        if (pending != null) throw new IllegalStateException("A durable transition is pending");
        capturedTime = clock.nanoTime();
        return project(committed, boundary, capturedTime);
    }

    private RoundTransition proposal(UUID id, String type, RoundSnapshot before, RoundPhase phase, long remaining,
                                     Map<UUID, Participant> players, RoundResult result, boolean clean) {
        return proposal(id, type, before, phase, remaining, players, result, clean, before.timingUncertain());
    }

    private RoundTransition proposal(UUID id, String type, RoundSnapshot before, RoundPhase phase, long remaining,
                                     Map<UUID, Participant> players, RoundResult result, boolean clean, boolean uncertain) {
        // capture() and proposal() share one event timestamp, even if the clock advances between calls.
        long captured = capturedTime;
        RoundSnapshot after = new RoundSnapshot(before.roundId(), before.sequence(), before.seed(),
                Math.addExact(before.version(), 1), before.rules(), phase, clean ? PauseReason.SERVER_STOPPED : pause(phase, players),
                before.challengeNanos(), remaining, players, clean, before.timingUncertain() || uncertain, result);
        pending = new RoundTransition(id, type, before, after, captured);
        return pending;
    }

    private long capturedTime;
    private RoundSnapshot project(RoundSnapshot base, long start, long now) {
        long elapsed = now - start;
        if (elapsed < 0) throw new IllegalStateException("Monotonic clock moved backwards");
        long challenge = base.challengeNanos();
        Map<UUID, Participant> players = new LinkedHashMap<>(base.participants());
        if (!base.cleanShutdown() && base.phase() == RoundPhase.RUNNING && base.onlineHumans() > 0) {
            challenge = Math.addExact(challenge, elapsed);
            players.replaceAll((id, p) -> p.online() && p.role().isActiveHuman() ? p.accrue(elapsed) : p);
        }
        // Cooldown is live server time for the connected monster, independent of human race timing.
        // A clean shutdown marker freezes the captured remainder throughout server downtime.
        if (!base.cleanShutdown() && (base.phase() == RoundPhase.RUNNING || base.phase() == RoundPhase.RESCUE)) {
            players.replaceAll((id, p) -> p.online() && p.role() == ParticipantRole.MONSTER_LOBBY
                    ? p.elapseMonsterCooldown(elapsed) : p);
        }
        long remaining = base.remainingPhaseNanos();
        if (!base.cleanShutdown() && (base.phase() == RoundPhase.COUNTDOWN && readyOnline(players) > 0
                || base.phase() == RoundPhase.SETTLING || base.phase() == RoundPhase.CELEBRATING || base.phase() == RoundPhase.RESCUE)) {
            remaining = Math.max(0, remaining - Math.min(elapsed, remaining));
        }
        return new RoundSnapshot(base.roundId(), base.sequence(), base.seed(), base.version(), base.rules(), base.phase(),
                base.cleanShutdown() ? PauseReason.SERVER_STOPPED : pause(base.phase(), players), challenge, remaining,
                players, base.cleanShutdown(), base.timingUncertain(), base.result());
    }

    private static PauseReason pause(RoundPhase phase, Map<UUID, Participant> players) {
        return switch (phase) {
            case RUNNING -> players.values().stream().anyMatch(p -> p.online() && p.role().isActiveHuman())
                    ? PauseReason.NONE : PauseReason.NO_ONLINE_HUMANS;
            case RESCUE -> PauseReason.RESCUE;
            case SETTLING, CELEBRATING, ROTATING -> PauseReason.SETTLED;
            case RECOVERY_REQUIRED -> PauseReason.RECOVERY_REQUIRED;
            default -> PauseReason.NOT_RUNNING;
        };
    }

    private static long readyOnline(Map<UUID, Participant> players) {
        return players.values().stream().filter(p -> p.online() && p.ready() && p.role() == ParticipantRole.LOBBY).count();
    }

    private RoundTransition failureProposal(UUID eventId, String type, RoundSnapshot before,
            Map<UUID, Participant> players, String reason) {
        return proposal(eventId, type, before, RoundPhase.SETTLING,
                millisToNanos(before.rules().failureDisplayMillis()), withoutEntitlements(players),
                new RoundResult(RoundResult.Kind.FAILED, null, reason), false);
    }

    private static Map<UUID, Participant> withoutEntitlements(Map<UUID, Participant> players) {
        Map<UUID, Participant> result = new LinkedHashMap<>(players);
        result.replaceAll((id, p) -> p.revivalEntitled() ? p.withRevivalEntitlement(false) : p);
        return result;
    }

    private static boolean hasHuman(Map<UUID, Participant> players) {
        return players.values().stream().anyMatch(p -> p.role().isActiveHuman()
                || p.role() == ParticipantRole.HUMAN_RESPAWNING);
    }

    private static void requireOpenRescue(RoundSnapshot snapshot) {
        if (snapshot.phase() == RoundPhase.RESCUE && snapshot.remainingPhaseNanos() == 0) {
            throw new IllegalStateException("Rescue deadline has passed");
        }
    }

    private static void startReady(Map<UUID, Participant> players, boolean admissionPending) {
        players.replaceAll((id, p) -> p.ready() && p.online() && p.role() == ParticipantRole.LOBBY
                ? admissionPending ? p.start(0).withSession(p.name(), false) : p.start(0)
                : p.withReady(false));
    }

    private static Participant requirePlayer(RoundSnapshot snapshot, UUID id) {
        Participant player = snapshot.participants().get(id);
        if (player == null) throw new IllegalStateException("Player must join first");
        return player;
    }

    private static void requireLife(Participant player, ParticipantRole role, int expectedLifeSequence) {
        if (player.role() != role || player.lifeSequence() != expectedLifeSequence) throw new IllegalStateException("Stale life event");
    }

    private static void requirePhase(RoundSnapshot snapshot, RoundPhase... phases) {
        for (RoundPhase phase : phases) if (snapshot.phase() == phase) return;
        throw new IllegalStateException("Round phase does not allow this action: " + snapshot.phase());
    }

    private static long millisToNanos(long millis) { return Math.multiplyExact(millis, 1_000_000); }
}
