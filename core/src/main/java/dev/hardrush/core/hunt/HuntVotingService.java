package dev.hardrush.core.hunt;

import dev.hardrush.core.round.ParticipantRole;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Historical LEGACY_VOTE implementation only; PERSONAL_TARGET has no scheduler or runtime instance.
 * Ephemeral ballots for one round. The platform supplies a fresh authoritative participant snapshot
 * before handling commands, periodic settlement, or deployment. No positions enter this service.
 * Persisted monster dimension choices can be restored through {@link #selectDimension} after sync.
 */
public final class HuntVotingService {
    public static final long SETTLEMENT_INTERVAL_NANOS = 15_000_000_000L;

    public enum VoteStatus { ACCEPTED, INELIGIBLE_VOTER, INVALID_DIMENSION, NO_DIMENSION, INVALID_TARGET }

    public record ParticipantView(UUID playerId, UUID roundId, ParticipantRole role, boolean online,
                                  String dimension, boolean protectedArea) {
        public ParticipantView {
            Objects.requireNonNull(playerId); Objects.requireNonNull(roundId);
            Objects.requireNonNull(role); requireDimension(dimension);
        }
    }

    public record Ballot(UUID voterId, String dimension, UUID targetId) {
        public Ballot {
            Objects.requireNonNull(voterId); requireDimension(dimension); Objects.requireNonNull(targetId);
        }
    }

    /** Settled public information contains player IDs and vote totals, never target coordinates. */
    public record Tally(String dimension, Optional<UUID> target, Map<UUID, Integer> votes,
                        long computedAtNanos) {
        public Tally {
            requireDimension(dimension); Objects.requireNonNull(target);
            votes = Map.copyOf(votes);
        }

        public int validVoteCount() { return votes.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    private final UUID roundId;
    private final List<String> dimensions;
    private final RandomGenerator random;
    private final Map<UUID, String> selections = new HashMap<>();
    private final Map<UUID, Ballot> ballots = new HashMap<>();
    private final Map<String, Tally> tallies = new LinkedHashMap<>();
    private Map<UUID, ParticipantView> participants = Map.of();
    private long lastSettlementNanos;
    private boolean settled;

    public HuntVotingService(UUID roundId, Collection<String> dimensions, long nowNanos,
                             RandomGenerator random) {
        this.roundId = Objects.requireNonNull(roundId);
        this.random = Objects.requireNonNull(random);
        this.dimensions = List.copyOf(dimensions);
        if (this.dimensions.isEmpty() || this.dimensions.stream().distinct().count() != this.dimensions.size()) {
            throw new IllegalArgumentException("Round dimensions must be nonempty and distinct");
        }
        for (String dimension : this.dimensions) {
            requireDimension(dimension);
            tallies.put(dimension, new Tally(dimension, Optional.empty(), Map.of(), nowNanos));
        }
        lastSettlementNanos = nowNanos;
    }

    /** Replaces the snapshot atomically; duplicate UUIDs reject the entire update. */
    public synchronized void synchronizeParticipants(Collection<ParticipantView> current) {
        Map<UUID, ParticipantView> next = new HashMap<>();
        for (ParticipantView participant : current) {
            Objects.requireNonNull(participant);
            if (next.putIfAbsent(participant.playerId(), participant) != null) {
                throw new IllegalArgumentException("Duplicate participant UUID");
            }
        }
        participants = Map.copyOf(next);
        selections.keySet().removeIf(id -> !participants.containsKey(id)
                || !roundId.equals(participants.get(id).roundId()));
        pruneBallots();
    }

    /** Lobby-only changes are enforced by the platform; both online monster roles may vote. */
    public synchronized VoteStatus selectDimension(UUID voterId, String dimension) {
        Objects.requireNonNull(voterId); Objects.requireNonNull(dimension);
        if (!isEligibleVoter(voterId)) return VoteStatus.INELIGIBLE_VOTER;
        if (!dimensions.contains(dimension)) return VoteStatus.INVALID_DIMENSION;
        String previous = selections.put(voterId, dimension);
        if (!dimension.equals(previous)) ballots.remove(voterId);
        return VoteStatus.ACCEPTED;
    }

    /** Replaces this UUID's one ballot; rejected requests leave an existing valid ballot intact. */
    public synchronized VoteStatus castVote(UUID voterId, UUID targetId) {
        Objects.requireNonNull(voterId); Objects.requireNonNull(targetId);
        pruneBallots();
        if (!isEligibleVoter(voterId)) return VoteStatus.INELIGIBLE_VOTER;
        String dimension = selections.get(voterId);
        if (dimension == null) return VoteStatus.NO_DIMENSION;
        if (!isEligibleTarget(targetId, dimension)) return VoteStatus.INVALID_TARGET;
        ballots.put(voterId, new Ballot(voterId, dimension, targetId));
        return VoteStatus.ACCEPTED;
    }

    /**
     * First refresh settles immediately, then at most once per 15 elapsed seconds. A delayed call
     * settles current state once, without consuming random draws for missed intervals.
     */
    public synchronized boolean refresh(long nowNanos) {
        long elapsed = elapsedSinceSettlement(nowNanos);
        pruneBallots();
        if (settled && elapsed < SETTLEMENT_INTERVAL_NANOS) return false;
        for (String dimension : dimensions) {
            List<UUID> candidates = candidates(dimension);
            Map<UUID, Integer> votes = countVotes(dimension);
            Optional<UUID> target = chooseAnchor(candidates, votes);
            tallies.put(dimension, new Tally(dimension, target, votes, nowNanos));
        }
        lastSettlementNanos = nowNanos;
        settled = true;
        return true;
    }

    /**
     * Revalidates the settled anchor immediately before deployment. An invalid or absent anchor is
     * reselected from current legal humans in the chosen dimension using the round random source.
     * This per-departure fallback does not rewrite the last published 15-second tally.
     */
    public synchronized Optional<UUID> deploymentTarget(UUID voterId) {
        Objects.requireNonNull(voterId);
        pruneBallots();
        if (!isEligibleVoter(voterId)) return Optional.empty();
        String dimension = selections.get(voterId);
        if (dimension == null) return Optional.empty();
        Optional<UUID> anchor = tallies.get(dimension).target();
        if (anchor.isPresent() && isEligibleTarget(anchor.orElseThrow(), dimension)) return anchor;
        return chooseRandom(candidates(dimension));
    }

    public synchronized Optional<String> selectedDimension(UUID playerId) {
        return Optional.ofNullable(selections.get(Objects.requireNonNull(playerId)));
    }

    public synchronized Optional<Ballot> ballot(UUID playerId) {
        return Optional.ofNullable(ballots.get(Objects.requireNonNull(playerId)));
    }

    public synchronized Tally tally(String dimension) {
        Tally tally = tallies.get(Objects.requireNonNull(dimension));
        if (tally == null) throw new IllegalArgumentException("Unknown round dimension");
        return tally;
    }

    public synchronized long remainingNanos(long nowNanos) {
        long elapsed = elapsedSinceSettlement(nowNanos);
        return settled ? Math.max(0, SETTLEMENT_INTERVAL_NANOS - elapsed) : 0;
    }

    private void pruneBallots() {
        ballots.entrySet().removeIf(entry -> !isEligibleVoter(entry.getKey())
                || !entry.getValue().dimension().equals(selections.get(entry.getKey()))
                || !isEligibleTarget(entry.getValue().targetId(), entry.getValue().dimension()));
    }

    private boolean isEligibleVoter(UUID id) {
        ParticipantView participant = participants.get(id);
        return participant != null && roundId.equals(participant.roundId())
                && participant.online() && participant.role().isMonster();
    }

    private boolean isEligibleTarget(UUID id, String dimension) {
        ParticipantView participant = participants.get(id);
        return participant != null && roundId.equals(participant.roundId()) && participant.online()
                && participant.role().isActiveHuman() && !participant.protectedArea()
                && dimension.equals(participant.dimension());
    }

    private List<UUID> candidates(String dimension) {
        return participants.keySet().stream().filter(id -> isEligibleTarget(id, dimension))
                .sorted(Comparator.naturalOrder()).toList();
    }

    private Map<UUID, Integer> countVotes(String dimension) {
        Map<UUID, Integer> votes = new HashMap<>();
        ballots.values().stream().filter(ballot -> dimension.equals(ballot.dimension()))
                .forEach(ballot -> votes.merge(ballot.targetId(), 1, Integer::sum));
        return votes;
    }

    private Optional<UUID> chooseAnchor(List<UUID> candidates, Map<UUID, Integer> votes) {
        if (votes.isEmpty()) return chooseRandom(candidates);
        int highest = votes.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        List<UUID> tied = new ArrayList<>();
        for (UUID candidate : candidates) if (votes.getOrDefault(candidate, 0) == highest) tied.add(candidate);
        return chooseRandom(tied);
    }

    private Optional<UUID> chooseRandom(List<UUID> choices) {
        if (choices.isEmpty()) return Optional.empty();
        if (choices.size() == 1) return Optional.of(choices.getFirst());
        return Optional.of(choices.get(random.nextInt(choices.size())));
    }

    private long elapsedSinceSettlement(long nowNanos) {
        long elapsed = nowNanos - lastSettlementNanos;
        if (elapsed < 0) throw new IllegalArgumentException("Monotonic time moved backwards");
        return elapsed;
    }

    private static void requireDimension(String dimension) {
        Objects.requireNonNull(dimension);
        if (dimension.isBlank()) throw new IllegalArgumentException("Dimension must not be blank");
    }
}
