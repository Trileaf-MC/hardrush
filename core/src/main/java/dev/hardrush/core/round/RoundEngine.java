package dev.hardrush.core.round;

import dev.hardrush.core.admin.AdminText;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.combat.CombatOrigin;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Single-thread rule arbiter. The adapter must durably commit a proposal before accept().
 * While a write is in flight conflicting mutations are rejected, not queued against old state.
 */
public final class RoundEngine {
    public static final long MAX_SUCCESS_CELEBRATION_MILLIS = 180_000;
    public static final long MONSTER_COOLDOWN_NANOS = 30_000_000_000L;
    public static final long RETRACK_COOLDOWN_MILLIS = 10_000;
    public static final long RETURN_CHANNEL_MILLIS = 4_000;
    /** Bare-handed stone: hardness 1.5 x 100 ticks without the correct tool. */
    public static final long DIG_MILLIS = 7_500;

    /** Wait before hunting after a disconnect, a recall or a first monster life; also how long a defeat locks a human. */
    public static long deployCooldownNanos(RulesConfig rules) {
        Double millis = rules.combatValues().get("monster.deployCooldownMillis");
        return millis == null ? MONSTER_COOLDOWN_NANOS : Math.multiplyExact(Math.round(millis), 1_000_000L);
    }

    /** Wait after a deliberate return; rounds stored before paused sorties keep the deploy cooldown. */
    public static long returnCooldownNanos(RulesConfig rules) {
        Double millis = rules.combatValues().get("monster.returnCooldownMillis");
        return millis == null ? deployCooldownNanos(rules) : Math.multiplyExact(Math.round(millis), 1_000_000L);
    }

    /** The tracking compass's rest after a landing; rounds stored before the setting keep its default. */
    public static long retrackCooldownMillis(RulesConfig rules) {
        Double millis = rules.combatValues().get("monster.retrackCooldownMillis");
        return millis == null ? RETRACK_COOLDOWN_MILLIS : Math.round(millis);
    }

    /** The deliberate return's channel; walking does not break it. */
    public static long returnChannelMillis(RulesConfig rules) {
        Double millis = rules.combatValues().get("monster.returnChannelMillis");
        return millis == null ? RETURN_CHANNEL_MILLIS : Math.round(millis);
    }

    /** The tracking compass's channel; rounds stored before the setting share the return's. */
    public static long retrackChannelMillis(RulesConfig rules) {
        Double millis = rules.combatValues().get("monster.retrackChannelMillis");
        return millis == null ? returnChannelMillis(rules) : Math.round(millis);
    }

    /** The longest a monster takes to dig one block; rounds stored before the setting keep its default. */
    public static long digMillis(RulesConfig rules) {
        Double millis = rules.combatValues().get("monster.digMillis");
        return millis == null ? DIG_MILLIS : Math.round(millis);
    }

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
        long remaining = stored.remainingPhaseNanos();
        // The winner's celebration lives only in the running process; a restart rotates instead of resuming it.
        if (phase != RoundPhase.RECOVERY_REQUIRED && stored.result() != null && stored.result().kind() == RoundResult.Kind.COMPLETED
                && (phase == RoundPhase.SETTLING || phase == RoundPhase.CELEBRATING)) {
            phase = RoundPhase.ROTATING;
            remaining = 0;
        }
        // Nobody could reconnect while the server was down: an open wait for absent humans restarts in full
        // once the first participant is back (see live()). A wait that had already expired stays expired for
        // the next tick to settle, and a rescue with no human left keeps its deadline.
        if (phase == RoundPhase.RESCUE && remaining > 0 && hasHuman(players)) {
            phase = RoundPhase.RUNNING;
            remaining = 0;
        }
        RoundSnapshot restored = new RoundSnapshot(stored.roundId(), stored.sequence(), stored.seed(), stored.version(),
                stored.rules(), phase, pause(phase, players), stored.challengeNanos(), remaining,
                players, stored.cleanShutdown(), uncertain, stored.result(), stored.looseTotems());
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
                stored.participants(), false, true, stored.result(), stored.looseTotems()), clock);
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
                stored.remainingPhaseNanos(), players, false, true, null, stored.looseTotems()), clock);
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

    /** Only a verified world/profile adapter may supply this event; no administrative bypass in the competition lobby. */
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
            if (admissionPending) player = player.awaitingAdmission();
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
            connected = endSortie(connected, deployCooldownNanos(before.rules()));
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
        var defeats = new java.util.ArrayList<RoundDeath>();
        UUID damageBatch = null;
        for (RoundDeath death : deaths) {
            if (!victims.add(death.playerId())) throw new IllegalArgumentException("Duplicate batch victim");
            Participant player = requirePlayer(before, death.playerId());
            // An earlier commit (a kill's recall, a disconnect) already ended this exact sortie after
            // the death was captured: that outing is settled, so this body's death changes nothing more.
            if (player.role() == ParticipantRole.MONSTER_LOBBY && death.lifeSequence() == player.lifeSequence()
                    && death.deploymentSequence() != 0 && death.deploymentSequence() == player.monsterDeploymentSequence()) continue;
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
                players.put(player.id(), new Participant(player.id(), player.name(), ParticipantRole.MONSTER_LOBBY,
                        player.online(), false, player.humanNanos(), player.joinedAtChallengeNanos(),
                        player.lifeSequence(), player.revivalCharges(), MonsterRole.ZOMBIE,
                        MonsterDimension.OVERWORLD, deployCooldownNanos(before.rules()),
                        player.monsterDeploymentSequence(), player.monsterSkillReadyAtMillis(),
                        becameMonster(before.rules(), player.wallet())).withWitch(player.witch()));
            } else {
                // A defeat no longer benches the monster: only the humans it locks get their respite (applied below).
                players.put(player.id(), endSortie(player, 0));
                defeats.add(death);
            }
        }
        // Judge against identity at lethal collection, before applying this batch's human deaths. The credit is
        // the vanilla kill credit, so a fall or burn shortly after a player's hit still counts. Every ended human
        // life releases one totem: a qualifying monster gains it, anything else drops it where the human fell (a
        // human's kill, or unclaimed). A settling batch keeps no registration.
        var looseTotems = new LinkedHashMap<>(before.looseTotems());
        for (RoundDeath death : deaths) {
            TotemDrop.release(before, death).ifPresent(release ->
                    release(before, players, looseTotems, release, death.playerId(), death.lifeSequence(), death.dropToken()));
        }
        // Use vanilla's player-damage memory captured at death, including subsequent environmental damage.
        for (RoundDeath death : deaths) {
            if (before.participants().get(death.playerId()).role() != ParticipantRole.MONSTER_DEPLOYED
                    || !death.recentlyHurtByPlayer()) continue;
            players.put(death.playerId(), credit(before.rules(), players.get(death.playerId()), MonsterCoins.KILLED_BY_HUMAN));
        }
        for (RoundDeath death : defeats) {
            for (UUID human : lockedBy(before, players, victims, death)) {
                players.put(death.playerId(), players.get(death.playerId())
                        .withHuntLock(human, deployCooldownNanos(before.rules())));
            }
        }
        if (Bounty.enabled(before.rules())) {
            for (RoundDeath death : deaths) {
                humanKiller(before, players, victims, death).ifPresent(killer -> players.put(killer.id(),
                        killer.withThreat(killer.threat().killedMonster(killer.lifeSequence()))));
            }
        }
        if (completion != null) {
            return proposal(eventId, "DEATH_BATCH", before, RoundPhase.SETTLING,
                    millisToNanos(successCelebrationMillis(before.rules())), withoutEntitlements(players), completion, false);
        }
        if (!hasHuman(players)) {
            return lastHumanGone(eventId, "DEATH_BATCH", before, players, "ALL_HUMANS_DEAD", false, looseTotems);
        }
        // With humans left the phase follows from who is still connected; see live().
        return proposal(eventId, "DEATH_BATCH", before, RoundPhase.RUNNING, 0, players, null, false,
                before.timingUncertain(), looseTotems);
    }

    /**
     * Applies one released totem (docs/plan/复生图腾守恒.md 1.1): a monster's is paid as a kill; a dropped one registers
     * the adapter's {@code dropToken}, the totem it spawned in the world. Without a token nothing lies there to take.
     */
    private static void release(RoundSnapshot before, Map<UUID, Participant> players, Map<UUID, LooseTotem> looseTotems,
            TotemDrop.Release release, UUID victim, int life, Optional<UUID> dropToken) {
        switch (release) {
            case TotemDrop.Release.ToMonster monster -> rewardMonsterKill(before.rules(), players, monster.owner());
            case TotemDrop.Release.Dropped dropped -> dropToken.ifPresent(token -> looseTotems.putIfAbsent(token,
                    new LooseTotem(victim, life, dropped.source(), dropped.killer(), before.challengeNanos())));
        }
    }

    /**
     * A player of this round takes a registered loose totem (docs/plan/复生图腾守恒.md 1.4): an online active human of
     * this life, or an online monster out on this sortie, but never the very human life that released it. The token
     * leaves the registry; a human gains one charge, a monster is paid exactly as for a kill (one charge, and in a coin
     * round the sortie ends so it revives first). A balance already at {@link Integer#MAX_VALUE} stays there.
     */
    public RoundTransition proposeLooseTotemPickup(UUID eventId, UUID roundId, UUID token, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence) {
        Objects.requireNonNull(token);
        RoundSnapshot before = capture();
        requirePhase(before, RoundPhase.RUNNING, RoundPhase.RESCUE);
        requireOpenRescue(before);
        if (!before.roundId().equals(roundId)) throw new IllegalStateException("轮次已改变，请重新拾取。");
        Participant player = requirePlayer(before, playerId);
        boolean monster = player.role() == ParticipantRole.MONSTER_DEPLOYED;
        requireLife(player, monster ? ParticipantRole.MONSTER_DEPLOYED : ParticipantRole.HUMAN_ACTIVE, expectedLifeSequence);
        if (monster && player.monsterDeploymentSequence() != expectedDeploymentSequence) {
            throw new IllegalStateException("这次出击已经结束。");
        }
        if (!player.online()) throw new IllegalStateException("离线玩家不能拾取复生图腾。");
        LooseTotem totem = before.looseTotem(token).orElseThrow(() -> new IllegalStateException("这个复生图腾已被拾取或已消失。"));
        if (totem.ownedBy(player)) throw new IllegalStateException("用掉复生图腾的这条命不能把它捡回来。");
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        if (monster) rewardMonsterKill(before.rules(), players, playerId);
        else if (player.revivalCharges() < Integer.MAX_VALUE) players.put(playerId, player.addRevivalCharges(1));
        var looseTotems = new LinkedHashMap<>(before.looseTotems());
        looseTotems.remove(token);
        return proposal(eventId, "TOTEM_PICKUP", before, before.phase(), before.remainingPhaseNanos(), players,
                before.result(), false, before.timingUncertain(), looseTotems);
    }

    /**
     * A revival totem already saved this human in the world; the ledger follows. Total by design: the event
     * sits in the adapter's critical queue, so any stale or unqualified state (another round or phase, a
     * changed life, no charge left) commits {@code TOTEM_SAVE_VOID}, which changes no participant, instead of
     * failing. A valid save spends one charge without ending the life and releases one totem as the same hit's death
     * would ({@link TotemDrop#release}): a monster it credits is paid exactly as for killing this human; otherwise
     * {@code dropToken}, the totem the adapter dropped at the save, is registered for others to take, whether a human
     * forced it out or the environment did. Nothing is registered by a void save, so it leaves no takeable drop.
     */
    public RoundTransition proposeTotemSave(UUID eventId, UUID roundId, UUID playerId, int expectedLifeSequence,
            Optional<CombatOrigin> killCredit, Optional<UUID> dropToken) {
        Objects.requireNonNull(eventId); Objects.requireNonNull(roundId);
        Objects.requireNonNull(playerId); Objects.requireNonNull(killCredit); Objects.requireNonNull(dropToken);
        RoundSnapshot before = capture();
        Participant player = before.participants().get(playerId);
        boolean open = before.phase() == RoundPhase.RUNNING
                || before.phase() == RoundPhase.RESCUE && before.remainingPhaseNanos() > 0;
        if (!before.roundId().equals(roundId) || !open
                || player == null || player.role() != ParticipantRole.HUMAN_ACTIVE
                || player.lifeSequence() != expectedLifeSequence || player.revivalCharges() == 0) {
            return proposal(eventId, "TOTEM_SAVE_VOID", before, before.phase(), before.remainingPhaseNanos(),
                    before.participants(), before.result(), false);
        }
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        players.put(playerId, player.spendRevivalCharge());
        var looseTotems = new LinkedHashMap<>(before.looseTotems());
        TotemDrop.release(before, playerId, killCredit).ifPresent(release ->
                release(before, players, looseTotems, release, playerId, expectedLifeSequence, dropToken));
        return proposal(eventId, "TOTEM_SAVE", before, before.phase(), before.remainingPhaseNanos(), players,
                before.result(), false, before.timingUncertain(), looseTotems);
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
        requireNoRevival(before.rules(), player);
        return monsterProposal(eventId, "MONSTER_SELECTION", before,
                chooseRole(before.rules(), abandonPause(player, role).selectMonster(role, dimension)));
    }

    public RoundTransition proposeMonsterRole(UUID eventId, UUID roundId, UUID playerId,
            int life, int sortie, MonsterRole role) {
        RoundSnapshot before = capture();
        var player = requireMonster(before, roundId, playerId, life, sortie, ParticipantRole.MONSTER_LOBBY);
        requireNoRevival(before.rules(), player);
        return monsterProposal(eventId, "MONSTER_SELECTION", before,
                chooseRole(before.rules(), abandonPause(player, Objects.requireNonNull(role)).selectMonsterRole(role)));
    }

    public RoundTransition proposePersonalHunt(UUID eventId, dev.hardrush.core.hunt.PersonalHuntRequest request, int sortie) {
        RoundSnapshot before = capture();
        if (before.rules().huntRule() != RulesConfig.HuntRule.PERSONAL_TARGET
                || !request.hunterMatches(before, ParticipantRole.MONSTER_LOBBY, sortie)
                || !request.targetMatches(before) || sortie < request.initialSortie())
            throw new IllegalStateException("追猎目标、生命或发起身份已改变。");
        if (before.participants().get(request.hunter()).huntLockNanos(request.target()) > 0)
            throw new Refusal.Exception(Refusal.HUNT_LOCKED, "这名人类刚击败了该怪物，锁定尚未结束。");
        var deployed = deployMonster(before, request.round(), request.hunter(), request.hunterLife(), sortie);
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        players.put(deployed.id(), deployed);
        // Being hunted from the camp is a mark of standing, whatever the outing then achieves.
        if (Bounty.enabled(before.rules())) {
            Participant target = players.get(request.target());
            players.put(target.id(), target.withThreat(target.threat().huntedFromCamp(target.lifeSequence(),
                    request.hunter(), before.challengeNanos())));
        }
        return proposal(eventId, "MONSTER_DEPLOY", before, before.phase(), before.remainingPhaseNanos(), players, before.result(), false);
    }

    public RoundTransition proposeMonsterDeploy(UUID eventId, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence) {
        RoundSnapshot before = capture();
        return monsterProposal(eventId, "MONSTER_DEPLOY", before,
                deployMonster(before, roundId, playerId, expectedLifeSequence, expectedDeploymentSequence));
    }

    private static Participant deployMonster(RoundSnapshot before, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence) {
        requireOpenRescue(before);
        Participant player = requireMonster(before, roundId, playerId, expectedLifeSequence, expectedDeploymentSequence,
                ParticipantRole.MONSTER_LOBBY);
        requireNoRevival(before.rules(), player);
        if (player.monsterCooldownNanos() != 0) throw new IllegalStateException("Monster deployment cooldown has not elapsed");
        var deployed = player.deployMonster();
        if (MonsterCoins.enabled(before.rules()))
            deployed = deployed.withWallet(player.wallet().deploy(before.rules(), player.monsterRole()));
        return deployed;
    }

    /**
     * A human life reached a native advancement. Only scored milestones change anything; one already counted
     * for this life, an earlier life's late report, or a round without bounties leaves the round untouched.
     */
    public RoundTransition proposeThreatMilestone(UUID eventId, UUID roundId, UUID playerId, int expectedLifeSequence,
            String advancement) {
        RoundSnapshot before = capture();
        requirePhase(before, RoundPhase.RUNNING, RoundPhase.RESCUE);
        if (!before.roundId().equals(roundId)) throw new IllegalStateException("Stale milestone round");
        Participant player = requirePlayer(before, playerId);
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        if (Bounty.enabled(before.rules()) && player.role() == ParticipantRole.HUMAN_ACTIVE
                && player.lifeSequence() == expectedLifeSequence) {
            players.put(playerId, player.withThreat(player.threat().reached(expectedLifeSequence, advancement)));
        }
        return proposal(eventId, "THREAT_MILESTONE", before, before.phase(), before.remainingPhaseNanos(), players, before.result(), false);
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
            throw new Refusal.Exception(Refusal.SKILL_COOLDOWN, "技能仍在冷却。");
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
        return proposeMonsterReturn(eventId, roundId, playerId, expectedLifeSequence, expectedDeploymentSequence, "");
    }

    /**
     * A deliberate return. With the platform's encoded {@code parkedBody} the sortie only pauses:
     * the body, worth, damage caps and price ladder continue at the next deployment. Without one the
     * sortie ends as before, still with the return cooldown.
     */
    public RoundTransition proposeMonsterReturn(UUID eventId, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence, String parkedBody) {
        Objects.requireNonNull(parkedBody);
        RoundSnapshot before = capture();
        Participant player = requireMonster(before, roundId, playerId, expectedLifeSequence, expectedDeploymentSequence,
                ParticipantRole.MONSTER_DEPLOYED);
        long cooldown = returnCooldownNanos(before.rules());
        var returned = parkedBody.isEmpty() ? endSortie(player, cooldown)
                : player.monsterLobby(cooldown).withWallet(player.wallet().park(parkedBody));
        return monsterProposal(eventId, "MONSTER_RETURN", before, returned);
    }

    /** Adapter-only compensation after durable admission but before an outing actually opens. */
    public RoundTransition proposeMonsterDeployCancelled(UUID eventId, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence) {
        RoundSnapshot before = capture();
        Participant player = requireMonster(before, roundId, playerId, expectedLifeSequence, expectedDeploymentSequence,
                ParticipantRole.MONSTER_DEPLOYED);
        return monsterProposal(eventId, "MONSTER_DEPLOY_CANCELLED", before,
                player.monsterLobby(0).withWallet(player.wallet().cancelDeploy()));
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
        return monsterProposal(eventId, type, before, endSortie(player, deployCooldownNanos(before.rules())));
    }

    private RoundTransition monsterProposal(UUID eventId, String type, RoundSnapshot before, Participant player) {
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        players.put(player.id(), player);
        return proposal(eventId, type, before, before.phase(), before.remainingPhaseNanos(), players, before.result(), false);
    }

    /**
     * Buffered damage credit from the adapter. Entries whose monster has since left that sortie,
     * or whose victim is no longer a living human of this round, are skipped rather than failing
     * the queued event.
     */
    public RoundTransition proposeMonsterDamageCoins(UUID eventId, UUID roundId, List<DamageCoins> entries) {
        RoundSnapshot before = capture();
        return proposal(eventId, "MONSTER_DAMAGE_COINS", before, before.phase(), before.remainingPhaseNanos(),
                creditDamageCoins(before, roundId, entries), before.result(), false);
    }

    /**
     * The participants after crediting {@code entries} onto {@code before}. The adapter also uses
     * this to project damage it has not committed yet, so a monster's worth never lags its hits.
     */
    public static Map<UUID, Participant> creditDamageCoins(RoundSnapshot before, UUID roundId, List<DamageCoins> entries) {
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        boolean open = before.roundId().equals(roundId) && MonsterCoins.enabled(before.rules())
                && (before.phase() == RoundPhase.RUNNING || before.phase() == RoundPhase.RESCUE);
        if (!open) return players;
        long cap = Math.multiplyExact((long) MonsterCoins.amount(before.rules(), MonsterCoins.DAMAGE_CAP), 1000L);
        for (DamageCoins entry : List.copyOf(entries)) {
            Participant owner = players.get(entry.owner());
            Participant victim = before.participants().get(entry.victim());
            // A sortie ended by return, disconnect or recall still owns the damage it dealt before
            // ending; the next deployment advances the sequence and clears that sortie's credit.
            if (owner == null || victim == null || owner.id().equals(victim.id())
                    || owner.role() != ParticipantRole.MONSTER_DEPLOYED && owner.role() != ParticipantRole.MONSTER_LOBBY
                    || owner.lifeSequence() != entry.ownerLife() || owner.monsterDeploymentSequence() != entry.sortie()
                    || !victim.role().isActiveHuman() && victim.role() != ParticipantRole.MONSTER_LOBBY) continue;
            // The weight was fixed where the hit landed: a killing blow keeps the stars its victim died with.
            int bounty = Bounty.enabled(before.rules()) ? Math.min(entry.bountyPermille(), Bounty.maxPermille(before.rules())) : 1000;
            players.put(owner.id(), owner.withWallet(owner.wallet().creditDamage(entry.victim(), entry.damageMilli(), bounty, cap)));
        }
        return players;
    }

    /**
     * Read-only replay of credit the adapter has not committed yet, on the formal path's clock: the
     * pending transition counts as accepted, each queued batch is credited at its own event time as
     * drainEvent's capture() would, and still-buffered entries at {@code now}. Engine state is untouched.
     */
    public RoundSnapshot projectDamageCoins(List<QueuedDamageCoins> queued, UUID roundId, List<DamageCoins> buffered, long now) {
        RoundSnapshot base = pending == null ? committed : pending.after();
        long start = pending == null ? boundary : pending.capturedNanos();
        for (QueuedDamageCoins batch : List.copyOf(queued)) {
            long time = Math.max(start, batch.time());
            base = credited(project(base, start, time), batch.roundId(), batch.entries());
            start = time;
        }
        return credited(project(base, start, Math.max(start, now)), roundId, buffered);
    }

    private static RoundSnapshot credited(RoundSnapshot base, UUID roundId, List<DamageCoins> entries) {
        if (entries.isEmpty()) return base;
        return new RoundSnapshot(base.roundId(), base.sequence(), base.seed(), base.version(), base.rules(), base.phase(),
                base.pauseReason(), base.challengeNanos(), base.remainingPhaseNanos(),
                creditDamageCoins(base, roundId, entries), base.cleanShutdown(), base.timingUncertain(), base.result(),
                base.looseTotems());
    }

    /** A flushed damage batch waiting in the adapter's event queue, credited at its own event time. */
    public record QueuedDamageCoins(long time, UUID roundId, List<DamageCoins> entries) {
        public QueuedDamageCoins {
            Objects.requireNonNull(roundId);
            entries = List.copyOf(entries);
        }
    }

    /**
     * One buffered damage observation: actual milli-health a sortie took from one human, weighted by the
     * victim's bounty at the moment of the hit.
     */
    public record DamageCoins(UUID owner, int ownerLife, int sortie, UUID victim, int bountyPermille, long damageMilli) {
        public DamageCoins {
            Objects.requireNonNull(owner); Objects.requireNonNull(victim);
            if (damageMilli <= 0) throw new IllegalArgumentException("Damage credit must be positive");
            if (bountyPermille < 1000) throw new IllegalArgumentException("Bounty weight below the plain rate");
        }
    }

    public RoundTransition proposeShopPurchase(UUID eventId, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence, ShopOffer offer, int expectedPrice) {
        RoundSnapshot before = capture();
        if (!MonsterCoins.enabled(before.rules())) throw new IllegalStateException("本轮没有开放怪物币商店。");
        Participant player = requireMonster(before, roundId, playerId, expectedLifeSequence,
                expectedDeploymentSequence, ParticipantRole.MONSTER_LOBBY);
        requireOpenRescue(before);
        // A totem in hand leaves only the way back to the race: the witch then sells revival supplies, and only them.
        if (player.revivalEntitled() != offer.revival()) {
            if (player.revivalEntitled()) throw new Refusal.Exception(Refusal.REVIVAL_FIRST, REVIVAL_FIRST);
            throw new IllegalStateException("复生补给只卖给持有复生图腾的怪物。");
        }
        return monsterProposal(eventId, "SHOP_PURCHASE_" + offer.name(), before, player.withWallet(
                player.wallet().buy(before.rules(), player.monsterRole(), offer, expectedPrice)));
    }

    /** The witch's guide was shown to this player; it is shown once per round, across lives. */
    public RoundTransition proposeWitchGuideSeen(UUID eventId, UUID roundId, UUID playerId) {
        RoundSnapshot before = capture();
        if (!before.roundId().equals(roundId)) throw new IllegalStateException("Stale witch guide round");
        Participant player = requirePlayer(before, playerId);
        if (player.witch().guideSeen()) throw new IllegalStateException("女巫的碎碎念本轮已经看过。");
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        players.put(playerId, player.withWitch(player.witch().withGuideSeen()));
        return proposal(eventId, "WITCH_GUIDE_SEEN", before, before.phase(), before.remainingPhaseNanos(), players, before.result(), false);
    }

    /** The revived life {@code life} has received its landing supplies: the bonus chest and any longer blessing. */
    public RoundTransition proposeRevivalSupplyLanded(UUID eventId, UUID roundId, UUID playerId, int life) {
        RoundSnapshot before = capture();
        if (!before.roundId().equals(roundId)) throw new IllegalStateException("Stale revival supply round");
        Participant player = requirePlayer(before, playerId);
        if (player.lifeSequence() != life || player.role() != ParticipantRole.HUMAN_ACTIVE || !player.witch().landingPending(life))
            throw new IllegalStateException("复生补给已交付或已不属于当前生命。");
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        players.put(playerId, player.withWitch(player.witch().withLanded()));
        return proposal(eventId, "REVIVAL_SUPPLY_LANDED", before, before.phase(), before.remainingPhaseNanos(), players, before.result(), false);
    }

    public RoundTransition proposeMonsterCoinDrop(UUID eventId, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence, int count) {
        RoundSnapshot before = capture();
        Participant player = requireCoinHolder(before, roundId, playerId, expectedLifeSequence, expectedDeploymentSequence);
        return monsterProposal(eventId, "MONSTER_COIN_DROP", before, player.withWallet(player.wallet().spendCoins(count)));
    }

    public RoundTransition proposeMonsterCoinPickup(UUID eventId, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence, int count) {
        RoundSnapshot before = capture();
        Participant player = requireCoinHolder(before, roundId, playerId, expectedLifeSequence, expectedDeploymentSequence);
        if (count <= 0 || (long) player.wallet().coins() + count > Integer.MAX_VALUE)
            throw new IllegalArgumentException("怪物币数量无效或超出余额上限。");
        return monsterProposal(eventId, "MONSTER_COIN_PICKUP", before, player.withWallet(player.wallet().credit(count)));
    }

    private static Participant requireCoinHolder(RoundSnapshot before, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence) {
        if (!MonsterCoins.enabled(before.rules())) throw new IllegalStateException("本轮没有开放怪物币商店。");
        // A gift is not a purchase: a monster holding a revival may still hand its coins on before the altar.
        Participant player = requireMonster(before, roundId, playerId, expectedLifeSequence,
                expectedDeploymentSequence, ParticipantRole.MONSTER_LOBBY);
        requireOpenRescue(before);
        return player;
    }

    /** The adapter rolls the native item first; this commit spends the key and keeps the result. */
    public RoundTransition proposeVaultOpen(UUID eventId, UUID roundId, UUID playerId, int expectedLifeSequence,
            int expectedDeploymentSequence, ShopOffer key, MonsterWallet.GearSlot slot,
            java.util.Set<MonsterRole> roles, String item) {
        RoundSnapshot before = capture();
        Participant player = requireCampMonster(before, roundId, playerId, expectedLifeSequence, expectedDeploymentSequence);
        if (!key.sellableTo(player.monsterRole())) throw new IllegalStateException("当前角色不能使用宝库。");
        return monsterProposal(eventId, "VAULT_OPEN_" + key.name(), before,
                player.withWallet(player.wallet().openVault(key, slot, roles, item)));
    }

    private static Participant requireCampMonster(RoundSnapshot before, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedDeploymentSequence) {
        if (!MonsterCoins.enabled(before.rules())) throw new IllegalStateException("本轮没有开放怪物币商店。");
        Participant player = requireMonster(before, roundId, playerId, expectedLifeSequence,
                expectedDeploymentSequence, ParticipantRole.MONSTER_LOBBY);
        requireOpenRescue(before);
        requireNoRevival(before.rules(), player);
        return player;
    }

    /** Diagnostic text of {@link Refusal#REVIVAL_FIRST}; player wording lives in the platform layer. */
    public static final String REVIVAL_FIRST = "你持有复生次数，请先走上营地北侧的复生祭坛站立 4 秒复生。";

    /** In a coin round, a monster holding a revival returns to the race instead of hunting on. */
    private static void requireNoRevival(RulesConfig rules, Participant player) {
        if (MonsterCoins.enabled(rules) && player.revivalEntitled()) throw new Refusal.Exception(Refusal.REVIVAL_FIRST, REVIVAL_FIRST);
    }

    private static Participant revivalFirst(RulesConfig rules, Participant player) {
        return MonsterCoins.enabled(rules) && player.revivalEntitled() && player.role() == ParticipantRole.MONSTER_DEPLOYED
                ? endSortie(player, deployCooldownNanos(rules)) : player;
    }

    private static MonsterWallet becameMonster(RulesConfig rules, MonsterWallet wallet) {
        return MonsterCoins.enabled(rules) ? wallet.becameMonster(MonsterCoins.amount(rules, MonsterCoins.STARTER)) : wallet;
    }

    /** Choosing another role gives up a paused sortie: its parked body could not become the new one. */
    private static Participant abandonPause(Participant player, MonsterRole role) {
        return player.wallet().hasParkedBody() && role != player.monsterRole()
                ? player.withWallet(player.wallet().endSortie()) : player;
    }

    private static Participant endSortie(Participant player, long cooldown) {
        var lobby = player.monsterLobby(cooldown);
        return player.role() == ParticipantRole.MONSTER_DEPLOYED ? lobby.withWallet(player.wallet().endSortie()) : lobby;
    }

    private static Participant chooseRole(RulesConfig rules, Participant player) {
        return MonsterCoins.enabled(rules) ? player.withWallet(player.wallet().chooseRole(rules, player.monsterRole())) : player;
    }

    private static Participant credit(RulesConfig rules, Participant player, String reward) {
        return MonsterCoins.enabled(rules) ? player.withWallet(player.wallet().credit(MonsterCoins.amount(rules, reward))) : player;
    }

    /**
     * The human credited with killing a deployed monster player in this batch: the vanilla kill credit names a
     * human of this round who is still that same living life after the batch.
     */
    private static Optional<Participant> humanKiller(RoundSnapshot before, Map<UUID, Participant> after,
            java.util.Set<UUID> victims, RoundDeath death) {
        if (before.participants().get(death.playerId()).role() != ParticipantRole.MONSTER_DEPLOYED
                || death.killCredit().isEmpty()) return Optional.empty();
        var origin = death.killCredit().get();
        if (!origin.roundId().equals(before.roundId()) || !origin.actor().identity().isActiveHuman()
                || victims.contains(origin.actor().playerId())) return Optional.empty();
        Participant killer = after.get(origin.actor().playerId());
        if (killer == null || killer.role() != ParticipantRole.HUMAN_ACTIVE
                || killer.lifeSequence() != origin.actor().lifeSequence()) return Optional.empty();
        return Optional.of(killer);
    }

    /**
     * The humans a deployed monster's defeat locks (docs/plan/击败锁定与怪物平衡.md §1): its own blast locks the
     * sortie's quarry, and the human who lit the fuse when one did; any other death locks the human credited with
     * the kill. A death nobody is credited with locks no one. Only living humans of this round are locked.
     */
    static java.util.Set<UUID> lockedBy(RoundSnapshot before, Map<UUID, Participant> after,
            java.util.Set<UUID> victims, RoundDeath death) {
        var locked = new java.util.LinkedHashSet<UUID>();
        death.detonation().ifPresentOrElse(blast -> {
            blast.quarry().ifPresent(locked::add);
            blast.igniter().filter(igniter -> !victims.contains(igniter)
                    && after.get(igniter) != null && after.get(igniter).role() == ParticipantRole.HUMAN_ACTIVE).ifPresent(locked::add);
        }, () -> humanKiller(before, after, victims, death).ifPresent(killer -> locked.add(killer.id())));
        locked.remove(death.playerId());
        return locked;
    }

    /** A kill pays a revival, never coins; an owner still out hunting is sent back to the altar. */
    private static void rewardMonsterKill(RulesConfig rules, Map<UUID, Participant> players, UUID owner) {
        Participant rewarded = players.get(owner);
        if (rewarded.revivalCharges() < Integer.MAX_VALUE) rewarded = rewarded.addRevivalCharges(1);
        players.put(owner, revivalFirst(rules, rewarded));
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

    /** Admin credits are additive, like ordinary kill rewards. */
    public RoundTransition proposeAdminGrantRevival(UUID eventId, UUID roundId, UUID playerId,
            int expectedLifeSequence, int expectedCharges, int count) {
        RoundSnapshot before = capture();
        requirePhase(before, RoundPhase.RUNNING, RoundPhase.RESCUE);
        requireOpenRescue(before);
        if (!before.roundId().equals(roundId)) throw new IllegalStateException("轮次已改变，请重新申请。");
        Participant player = requirePlayer(before, playerId);
        if ((!player.role().isMonster() && !player.role().isActiveHuman())
                || player.lifeSequence() == 0 || player.joinedAtChallengeNanos() < 0) {
            throw new IllegalStateException("只能向本轮已参赛的人类或怪物发放复生次数。");
        }
        if (player.lifeSequence() != expectedLifeSequence || player.revivalCharges() != expectedCharges) {
            throw new IllegalStateException("玩家生命或剩余次数已改变，请重新申请。");
        }
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        players.put(playerId, revivalFirst(before.rules(), player.addRevivalCharges(count)));
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
        boolean disqualified = destination == ParticipantRole.DISQUALIFIED;
        String type = disqualified ? "DISQUALIFY" : "WITHDRAW";
        if (phase == RoundPhase.COUNTDOWN && readyOnline(players) == 0) {
            phase = RoundPhase.WAITING; remaining = 0;
        } else if ((phase == RoundPhase.RUNNING || phase == RoundPhase.RESCUE) && !hasHuman(players)) {
            return lastHumanGone(eventId, type, before, players, "NO_ELIGIBLE_PARTICIPANTS", disqualified);
        }
        return proposal(eventId, type, before, phase, remaining, players, null, false, disqualified);
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
            return failureProposal(eventId, "TICK", before, players, hasHuman(players) ? "HUMANS_OFFLINE" : "RESCUE_EXPIRED");
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
                before.remainingPhaseNanos(), before.participants(), false, true, before.result(), before.looseTotems());
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
        return proposal(id, type, before, phase, remaining, players, result, clean, uncertain, before.looseTotems());
    }

    private RoundTransition proposal(UUID id, String type, RoundSnapshot before, RoundPhase phase, long remaining,
                                     Map<UUID, Participant> players, RoundResult result, boolean clean, boolean uncertain,
                                     Map<UUID, LooseTotem> looseTotems) {
        // capture() and proposal() share one event timestamp, even if the clock advances between calls.
        long captured = capturedTime;
        // Every transition of a live round passes here, so no path can leave it running without a
        // connected human and without a deadline. A clean stop only marks the boundary.
        if (!clean && result == null && (phase == RoundPhase.RUNNING || phase == RoundPhase.RESCUE)) {
            var live = live(before, phase, remaining, players);
            phase = live.phase();
            remaining = live.remaining();
        }
        RoundSnapshot after = new RoundSnapshot(before.roundId(), before.sequence(), before.seed(),
                Math.addExact(before.version(), 1), before.rules(), phase, clean ? PauseReason.SERVER_STOPPED : pause(phase, players),
                before.challengeNanos(), remaining, players, clean, before.timingUncertain() || uncertain, result,
                retainedLooseTotems(phase, result, looseTotems));
        pending = new RoundTransition(id, type, before, after, captured);
        return pending;
    }

    /**
     * The loose-totem registry a transition leaves, applied to every transition alike: a settled round keeps
     * none; otherwise every entry stays (a registered totem never expires), and beyond
     * {@link LooseTotem#MAX_REGISTERED} the earliest drops (then the lowest tokens) give way. It never fails.
     */
    private static Map<UUID, LooseTotem> retainedLooseTotems(RoundPhase phase, RoundResult result,
                                                             Map<UUID, LooseTotem> looseTotems) {
        boolean terminal = phase == RoundPhase.SETTLING || phase == RoundPhase.CELEBRATING || phase == RoundPhase.ROTATING;
        if (terminal || result != null || looseTotems.isEmpty()) return Map.of();
        var retained = new LinkedHashMap<UUID, LooseTotem>(looseTotems);
        if (retained.size() > LooseTotem.MAX_REGISTERED) {
            retained.entrySet().stream()
                    .sorted(Comparator.comparingLong((Map.Entry<UUID, LooseTotem> e) -> e.getValue().droppedAtChallengeNanos())
                            .thenComparing(Map.Entry::getKey))
                    .limit(retained.size() - LooseTotem.MAX_REGISTERED)
                    .map(Map.Entry::getKey).toList()
                    .forEach(retained::remove);
        }
        return retained;
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
            // A defeat's lock runs while the monster is connected, whether it waits in the camp or hunts others.
            players.replaceAll((id, p) -> p.online() && p.role().isMonster() ? p.elapseHuntLocks(elapsed) : p);
        }
        long remaining = base.remainingPhaseNanos();
        if (!base.cleanShutdown() && (base.phase() == RoundPhase.COUNTDOWN && readyOnline(players) > 0
                || base.phase() == RoundPhase.SETTLING || base.phase() == RoundPhase.CELEBRATING || base.phase() == RoundPhase.RESCUE)) {
            remaining = Math.max(0, remaining - Math.min(elapsed, remaining));
        }
        return new RoundSnapshot(base.roundId(), base.sequence(), base.seed(), base.version(), base.rules(), base.phase(),
                base.cleanShutdown() ? PauseReason.SERVER_STOPPED : pause(base.phase(), players), challenge, remaining,
                players, base.cleanShutdown(), base.timingUncertain(), base.result(), base.looseTotems());
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
        return failureProposal(eventId, type, before, players, reason, false);
    }

    private RoundTransition failureProposal(UUID eventId, String type, RoundSnapshot before,
            Map<UUID, Participant> players, String reason, boolean uncertain) {
        return proposal(eventId, type, before, RoundPhase.SETTLING,
                millisToNanos(before.rules().failureDisplayMillis()), withoutEntitlements(players),
                new RoundResult(RoundResult.Kind.FAILED, null, reason), false, uncertain);
    }

    /**
     * No human is left in the race. A monster holding a revival opens (or keeps) the rescue; with
     * nobody able to return the round fails for {@code reason}.
     */
    private RoundTransition lastHumanGone(UUID eventId, String type, RoundSnapshot before,
            Map<UUID, Participant> players, String reason, boolean uncertain) {
        return lastHumanGone(eventId, type, before, players, reason, uncertain, before.looseTotems());
    }

    private RoundTransition lastHumanGone(UUID eventId, String type, RoundSnapshot before,
            Map<UUID, Participant> players, String reason, boolean uncertain, Map<UUID, LooseTotem> looseTotems) {
        if (players.values().stream().noneMatch(p -> p.role().isMonster() && p.revivalEntitled())) {
            return failureProposal(eventId, type, before, players, reason, uncertain);
        }
        long remaining = rescueNanos(before.rules());
        if (before.phase() == RoundPhase.RESCUE) {
            // A wait for absent humans may have longer left than a rescue is allowed; never extend either.
            remaining = before.awaitingReconnect() ? Math.min(before.remainingPhaseNanos(), remaining) : before.remainingPhaseNanos();
        }
        return proposal(eventId, type, before, RoundPhase.RESCUE, remaining, players, null, false, uncertain, looseTotems);
    }

    private static Map<UUID, Participant> withoutEntitlements(Map<UUID, Participant> players) {
        Map<UUID, Participant> result = new LinkedHashMap<>(players);
        result.replaceAll((id, p) -> p.revivalEntitled() ? p.withRevivalEntitlement(false) : p);
        return result;
    }

    private static boolean hasHuman(Map<UUID, Participant> players) {
        return players.values().stream().anyMatch(p -> p.role().isActiveHuman());
    }

    /** A human present on the server, playing or entering. */
    private static boolean hasConnectedHuman(Map<UUID, Participant> players) {
        return players.values().stream().anyMatch(p -> p.connected() && p.role().isActiveHuman());
    }

    /** A player still competing in this round is present; spectators and lobby-only entries do not count. */
    private static boolean competitorConnected(Map<UUID, Participant> players) {
        return players.values().stream().anyMatch(p -> p.connected() && (p.role().isActiveHuman() || p.role().isMonster()
                || p.role() == ParticipantRole.TRANSITION_PENDING));
    }

    /** How long a rescue waits for a revival or a mid-round join after the last human died. */
    public static long rescueMillis(RulesConfig rules) {
        return rules.combatValues().getOrDefault("round.rescueMillis", 60_000.0).longValue();
    }

    /** How long a round waits for its absent humans; rounds stored before this value use the default. */
    public static long reconnectMillis(RulesConfig rules) {
        return rules.combatValues().getOrDefault("round.reconnectMillis", 180_000.0).longValue();
    }

    private static long rescueNanos(RulesConfig rules) { return millisToNanos(rescueMillis(rules)); }

    private record LivePhase(RoundPhase phase, long remaining) {}

    /**
     * The one rule for a live round that still has humans: it runs while one of them is connected and
     * otherwise waits, on a deadline, for a reconnect, a revival or a mid-round join. It is a rule about
     * the resulting state, not about the event that produced it, so a restart, a life that leaves while
     * entering and any later path are covered alike. Two limits: an expired wait is closed only by the
     * tick, and the wait starts only when a competitor (human or monster of this round) is, or just was,
     * on the server, so a restored round stays paused until its first competitor returns.
     * <p>
     * With no human left the caller's phase stands: {@link #lastHumanGone} decides between rescue and failure.
     */
    private static LivePhase live(RoundSnapshot before, RoundPhase phase, long remaining, Map<UUID, Participant> players) {
        boolean waiting = before.phase() == RoundPhase.RESCUE;
        if (waiting && before.remainingPhaseNanos() == 0) return new LivePhase(RoundPhase.RESCUE, 0);
        if (!hasHuman(players)) return new LivePhase(phase, remaining);
        if (hasConnectedHuman(players)) return new LivePhase(RoundPhase.RUNNING, 0);
        if (waiting) return new LivePhase(RoundPhase.RESCUE, before.remainingPhaseNanos());
        if (competitorConnected(players) || competitorConnected(before.participants())) {
            return new LivePhase(RoundPhase.RESCUE, millisToNanos(reconnectMillis(before.rules())));
        }
        return new LivePhase(RoundPhase.RUNNING, 0);
    }

    private static void requireOpenRescue(RoundSnapshot snapshot) {
        if (snapshot.phase() == RoundPhase.RESCUE && snapshot.remainingPhaseNanos() == 0) {
            throw new IllegalStateException("Rescue deadline has passed");
        }
    }

    private static void startReady(Map<UUID, Participant> players, boolean admissionPending) {
        players.replaceAll((id, p) -> p.ready() && p.online() && p.role() == ParticipantRole.LOBBY
                ? admissionPending ? p.start(0).awaitingAdmission() : p.start(0)
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
