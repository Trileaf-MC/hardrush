package dev.hardrush.core.round;

import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Single-thread rule arbiter. The adapter must durably commit a proposal before accept().
 * While a write is in flight conflicting mutations are rejected, not queued against old state.
 */
public final class RoundEngine {
    private final MonotonicClock clock;
    private RoundSnapshot committed;
    private RoundTransition pending;
    private long boundary;

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
        RoundPhase phase = stored.cleanShutdown() ? stored.phase() : RoundPhase.RECOVERY_REQUIRED;
        RoundSnapshot restored = new RoundSnapshot(stored.roundId(), stored.sequence(), stored.seed(), stored.version(),
                stored.rules(), phase, pause(phase, players), stored.challengeNanos(), stored.remainingPhaseNanos(),
                players, stored.cleanShutdown(), uncertain, stored.result());
        return new RoundEngine(restored, clock);
    }

    public RoundSnapshot committed() { return committed; }
    public boolean pending() { return pending != null; }
    public RoundSnapshot view() { return pending == null ? project(committed, boundary, clock.nanoTime()) : pending.before(); }

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

    public RoundTransition proposeOnline(UUID eventId, UUID playerId, String name, boolean online) {
        RoundSnapshot before = capture();
        if (before.phase() == RoundPhase.RECOVERY_REQUIRED) throw new IllegalStateException("Recovery required");
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        Participant player = requirePlayer(before, playerId);
        players.put(playerId, player.withSession(name, online));
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

    private RoundTransition normalIdentity(UUID eventId, UUID playerId, int expectedLifeSequence, ParticipantRole expected, ParticipantRole target, String type, boolean admissionPending) {
        RoundSnapshot before = capture();
        requirePhase(before, RoundPhase.RUNNING);
        if (before.rules().mode() != GameMode.NORMAL) throw new IllegalStateException("Hardcore death requires P2 death-batch rules");
        Participant player = requirePlayer(before, playerId);
        if (player.role() != expected || player.lifeSequence() != expectedLifeSequence) throw new IllegalStateException("Stale life event");
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        Participant changed = player.withRole(target);
        if (target == ParticipantRole.HUMAN_ACTIVE) {
            changed = new Participant(changed.id(), changed.name(), target, changed.online(), false,
                    changed.humanNanos(), changed.joinedAtChallengeNanos(), Math.addExact(changed.lifeSequence(), 1), false);
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
            case COMPLETED -> before.rules().successCelebrationMillis();
            case FAILED -> before.rules().failureDisplayMillis();
            case ABORTED -> 0;
        };
        return proposal(eventId, "FINISH", before, RoundPhase.SETTLING, millisToNanos(duration), before.participants(), result, false);
    }

    public RoundTransition proposeTick(UUID eventId) {
        return proposeTick(eventId, false);
    }

    public RoundTransition proposeAdmissionTick(UUID eventId) { return proposeTick(eventId, true); }

    private RoundTransition proposeTick(UUID eventId, boolean admissionPending) {
        RoundSnapshot before = capture();
        RoundPhase phase = before.phase();
        long remaining = before.remainingPhaseNanos();
        Map<UUID, Participant> players = new LinkedHashMap<>(before.participants());
        if (phase == RoundPhase.COUNTDOWN) {
            if (readyOnline(players) == 0) {
                phase = RoundPhase.WAITING;
                remaining = 0;
                players.replaceAll((id, p) -> p.withReady(false));
            } else if (remaining == 0) {
                phase = RoundPhase.RUNNING;
                players.replaceAll((id, p) -> p.ready() && p.online()
                        ? admissionPending ? p.start(0).withSession(p.name(), false) : p.start(0)
                        : p.withReady(false));
            }
        } else if (phase == RoundPhase.SETTLING) {
            phase = remaining == 0 ? RoundPhase.ROTATING : RoundPhase.CELEBRATING;
        } else if (phase == RoundPhase.CELEBRATING && remaining == 0) {
            phase = RoundPhase.ROTATING;
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
        // capture() and proposal() share one event timestamp, even if the clock advances between calls.
        long captured = capturedTime;
        RoundSnapshot after = new RoundSnapshot(before.roundId(), before.sequence(), before.seed(),
                Math.addExact(before.version(), 1), before.rules(), phase, clean ? PauseReason.SERVER_STOPPED : pause(phase, players),
                before.challengeNanos(), remaining, players, clean, before.timingUncertain(), result);
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

    private static Participant requirePlayer(RoundSnapshot snapshot, UUID id) {
        Participant player = snapshot.participants().get(id);
        if (player == null) throw new IllegalStateException("Player must join first");
        return player;
    }

    private static void requirePhase(RoundSnapshot snapshot, RoundPhase... phases) {
        for (RoundPhase phase : phases) if (snapshot.phase() == phase) return;
        throw new IllegalStateException("Round phase does not allow this action: " + snapshot.phase());
    }

    private static long millisToNanos(long millis) { return Math.multiplyExact(millis, 1_000_000); }
}
