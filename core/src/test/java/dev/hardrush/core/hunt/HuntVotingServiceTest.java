package dev.hardrush.core.hunt;

import static dev.hardrush.core.hunt.HuntVotingService.VoteStatus.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.hunt.HuntVotingService.ParticipantView;
import dev.hardrush.core.round.ParticipantRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HuntVotingServiceTest {
    private static final UUID ROUND = id(100);
    private static final String OVERWORLD = "overworld", NETHER = "nether", END = "end";
    private static final long INTERVAL = HuntVotingService.SETTLEMENT_INTERVAL_NANOS;

    @Test void eachUuidHasOneReplaceableVoteInItsChosenDimension() {
        var service = service(new SequenceRandom(0));
        service.synchronizeParticipants(List.of(monster(1), human(10, OVERWORLD), human(11, OVERWORLD),
                human(12, NETHER)));
        assertEquals(NO_DIMENSION, service.castVote(id(1), id(10)));
        assertEquals(ACCEPTED, service.selectDimension(id(1), OVERWORLD));
        assertEquals(ACCEPTED, service.castVote(id(1), id(10)));
        assertEquals(ACCEPTED, service.castVote(id(1), id(11)));
        assertEquals(id(11), service.ballot(id(1)).orElseThrow().targetId());
        assertEquals(INVALID_TARGET, service.castVote(id(1), id(12)));
        service.refresh(0);
        assertEquals(1, service.tally(OVERWORLD).validVoteCount());
        assertEquals(id(11), service.tally(OVERWORLD).target().orElseThrow());
        assertEquals(0, service.tally(NETHER).validVoteCount());
    }

    @Test void changingDimensionClearsOldVoteButRepeatedSelectionKeepsIt() {
        var service = service(new SequenceRandom(0));
        service.synchronizeParticipants(List.of(monster(1), human(10, OVERWORLD), human(11, NETHER)));
        service.selectDimension(id(1), OVERWORLD);
        service.castVote(id(1), id(10));
        assertEquals(ACCEPTED, service.selectDimension(id(1), OVERWORLD));
        assertTrue(service.ballot(id(1)).isPresent());
        assertEquals(INVALID_DIMENSION, service.selectDimension(id(1), "other"));
        assertTrue(service.ballot(id(1)).isPresent());
        assertEquals(ACCEPTED, service.selectDimension(id(1), NETHER));
        assertTrue(service.ballot(id(1)).isEmpty());
        assertEquals(INVALID_TARGET, service.castVote(id(1), id(10)));
        assertEquals(ACCEPTED, service.castVote(id(1), id(11)));
    }

    @Test void separateDimensionsChooseIndependentWinners() {
        var service = service(new SequenceRandom(0));
        service.synchronizeParticipants(List.of(monster(1), monster(2), monster(3), human(10, OVERWORLD),
                human(11, OVERWORLD), human(12, NETHER), human(13, END)));
        service.selectDimension(id(1), OVERWORLD);
        service.selectDimension(id(2), OVERWORLD);
        service.selectDimension(id(3), NETHER);
        service.castVote(id(1), id(11));
        service.castVote(id(2), id(11));
        service.castVote(id(3), id(12));
        assertTrue(service.refresh(0));
        assertEquals(id(11), service.tally(OVERWORLD).target().orElseThrow());
        assertEquals(id(12), service.tally(NETHER).target().orElseThrow());
        assertEquals(id(13), service.tally(END).target().orElseThrow());
    }

    @Test void settlesOnlyEveryFifteenMonotonicSecondsAfterInitialRefresh() {
        var random = new SequenceRandom(0, 1, 0);
        var service = service(random);
        service.synchronizeParticipants(List.of(monster(1), human(10, OVERWORLD), human(11, OVERWORLD)));
        assertEquals(0, service.remainingNanos(0));
        assertTrue(service.refresh(0));
        assertEquals(id(10), service.tally(OVERWORLD).target().orElseThrow());
        assertFalse(service.refresh(INTERVAL - 1));
        assertEquals(1, service.remainingNanos(INTERVAL - 1));
        assertEquals(1, random.calls);
        assertTrue(service.refresh(INTERVAL));
        assertEquals(id(11), service.tally(OVERWORLD).target().orElseThrow());
        assertEquals(2, random.calls);
        assertEquals(INTERVAL, service.remainingNanos(INTERVAL));
        assertTrue(service.refresh(10 * INTERVAL));
        assertEquals(3, random.calls, "Delayed ticks must not replay missed random settlements");
    }

    @Test void tiedHighestVotesUseOnlyTiedCandidatesAndInjectedRoundRandom() {
        var random = new SequenceRandom(1);
        var service = service(random);
        service.synchronizeParticipants(List.of(monster(1), monster(2), human(10, OVERWORLD),
                human(11, OVERWORLD), human(12, OVERWORLD)));
        service.selectDimension(id(1), OVERWORLD);
        service.selectDimension(id(2), OVERWORLD);
        service.castVote(id(1), id(10));
        service.castVote(id(2), id(12));
        service.refresh(0);
        assertEquals(id(12), service.tally(OVERWORLD).target().orElseThrow());
        assertEquals(List.of(2), random.bounds);
    }

    @Test void noVotesUseAllLegalHumansAndNoCandidatesProduceNoAnchor() {
        var random = new SequenceRandom(1);
        var service = service(random);
        service.synchronizeParticipants(List.of(monster(1), human(10, OVERWORLD), human(11, OVERWORLD)));
        service.selectDimension(id(1), NETHER);
        service.refresh(0);
        assertEquals(id(11), service.tally(OVERWORLD).target().orElseThrow());
        assertTrue(service.tally(NETHER).target().isEmpty());
        assertTrue(service.deploymentTarget(id(1)).isEmpty());
        assertEquals(List.of(2), random.bounds);
    }

    @Test void onlyOnlineMonstersOfThisRoundCanVoteIncludingDeployedMonsters() {
        var service = service(new SequenceRandom(0));
        var deployed = view(2, ROUND, ParticipantRole.MONSTER_DEPLOYED, true, END, false);
        service.synchronizeParticipants(List.of(monster(1), deployed, human(10, OVERWORLD),
                view(3, ROUND, ParticipantRole.MONSTER_LOBBY, false, OVERWORLD, false),
                view(4, id(101), ParticipantRole.MONSTER_LOBBY, true, OVERWORLD, false),
                view(5, ROUND, ParticipantRole.LOBBY, true, OVERWORLD, false)));
        for (long ineligible : List.of(3L, 4L, 5L, 10L, 99L)) {
            assertEquals(INELIGIBLE_VOTER, service.selectDimension(id(ineligible), OVERWORLD));
            assertEquals(INELIGIBLE_VOTER, service.castVote(id(ineligible), id(10)));
        }
        assertEquals(ACCEPTED, service.selectDimension(id(2), OVERWORLD));
        assertEquals(ACCEPTED, service.castVote(id(2), id(10)));
    }

    @Test void targetMustBeOnlineActiveHumanOfThisRoundOutsideProtectionInChosenDimension() {
        var service = service(new SequenceRandom(0));
        service.synchronizeParticipants(List.of(monster(1), human(10, OVERWORLD),
                view(11, ROUND, ParticipantRole.HUMAN_ACTIVE, false, OVERWORLD, false),
                view(12, ROUND, ParticipantRole.HUMAN_RESPAWNING, true, OVERWORLD, false),
                view(13, id(101), ParticipantRole.HUMAN_ACTIVE, true, OVERWORLD, false),
                view(14, ROUND, ParticipantRole.HUMAN_ACTIVE, true, OVERWORLD, true),
                human(15, NETHER), monster(16)));
        service.selectDimension(id(1), OVERWORLD);
        for (long invalid : List.of(11L, 12L, 13L, 14L, 15L, 16L, 99L)) {
            assertEquals(INVALID_TARGET, service.castVote(id(1), id(invalid)));
        }
        assertEquals(ACCEPTED, service.castVote(id(1), id(10)));
        service.refresh(0);
        assertEquals(id(10), service.tally(OVERWORLD).target().orElseThrow());
        assertEquals(1, service.tally(OVERWORLD).validVoteCount());
    }

    @Test void disconnectRevivalAndRoundDepartureRemoveVotesWithoutResurrection() {
        for (ParticipantView changed : List.of(
                view(1, ROUND, ParticipantRole.MONSTER_LOBBY, false, OVERWORLD, false),
                human(1, OVERWORLD),
                view(1, id(101), ParticipantRole.MONSTER_LOBBY, true, OVERWORLD, false))) {
            var service = service(new SequenceRandom(0));
            service.synchronizeParticipants(List.of(monster(1), human(10, OVERWORLD)));
            service.selectDimension(id(1), OVERWORLD);
            service.castVote(id(1), id(10));
            service.synchronizeParticipants(List.of(changed, human(10, OVERWORLD)));
            assertTrue(service.ballot(id(1)).isEmpty());
            service.synchronizeParticipants(List.of(monster(1), human(10, OVERWORLD)));
            assertTrue(service.ballot(id(1)).isEmpty());
            service.refresh(0);
            assertEquals(0, service.tally(OVERWORLD).validVoteCount());
        }
    }

    @Test void invalidatedTargetVotesAreRemovedBeforeNextSettlement() {
        for (ParticipantView changed : List.of(
                view(10, ROUND, ParticipantRole.HUMAN_ACTIVE, false, OVERWORLD, false),
                view(10, ROUND, ParticipantRole.HUMAN_ACTIVE, true, OVERWORLD, true),
                human(10, NETHER), monster(10))) {
            var service = service(new SequenceRandom(0));
            service.synchronizeParticipants(List.of(monster(1), human(10, OVERWORLD), human(11, OVERWORLD)));
            service.selectDimension(id(1), OVERWORLD);
            service.castVote(id(1), id(10));
            service.refresh(0);
            service.synchronizeParticipants(List.of(monster(1), changed, human(11, OVERWORLD)));
            assertTrue(service.ballot(id(1)).isEmpty());
            service.refresh(INTERVAL);
            assertEquals(0, service.tally(OVERWORLD).validVoteCount());
            assertEquals(id(11), service.tally(OVERWORLD).target().orElseThrow());
        }
    }

    @Test void deploymentRechecksTargetAndReselectsWithoutWaitingForNextVote() {
        var random = new SequenceRandom(1);
        var service = service(random);
        service.synchronizeParticipants(List.of(monster(1), human(10, OVERWORLD),
                human(11, OVERWORLD), human(12, OVERWORLD)));
        service.selectDimension(id(1), OVERWORLD);
        service.castVote(id(1), id(10));
        service.refresh(0);
        assertEquals(id(10), service.deploymentTarget(id(1)).orElseThrow());
        assertEquals(0, random.calls);
        service.synchronizeParticipants(List.of(monster(1), human(10, NETHER),
                human(11, OVERWORLD), human(12, OVERWORLD)));
        assertEquals(id(12), service.deploymentTarget(id(1)).orElseThrow());
        assertEquals(List.of(2), random.bounds);
        assertEquals(id(10), service.tally(OVERWORLD).target().orElseThrow(), "Departure fallback preserves published tally");
        service.synchronizeParticipants(List.of(monster(1), human(10, NETHER)));
        assertTrue(service.deploymentTarget(id(1)).isEmpty());
    }

    @Test void deploymentRejectsOfflineNonMonsterAndMissingSelection() {
        var service = service(new SequenceRandom(0));
        service.synchronizeParticipants(List.of(monster(1), human(10, OVERWORLD)));
        assertTrue(service.deploymentTarget(id(1)).isEmpty());
        service.selectDimension(id(1), OVERWORLD);
        service.synchronizeParticipants(List.of(human(1, OVERWORLD), human(10, OVERWORLD)));
        assertTrue(service.deploymentTarget(id(1)).isEmpty());
        assertTrue(service.deploymentTarget(id(99)).isEmpty());
    }

    @Test void publicTalliesAreImmutableAndDuplicateSnapshotsFailAtomically() {
        var service = service(new SequenceRandom(0));
        var monster = monster(1);
        service.synchronizeParticipants(List.of(monster, human(10, OVERWORLD)));
        service.selectDimension(id(1), OVERWORLD);
        service.castVote(id(1), id(10));
        assertThrows(IllegalArgumentException.class, () -> service.synchronizeParticipants(List.of(monster, monster)));
        service.refresh(0);
        assertEquals(1, service.tally(OVERWORLD).validVoteCount());
        assertThrows(UnsupportedOperationException.class, () -> service.tally(OVERWORLD).votes().clear());
        assertThrows(IllegalArgumentException.class, () -> service.tally("other"));
    }

    @Test void monotonicTimerAllowsNegativeOriginsAndNanoTimeWraparound() {
        var service = new HuntVotingService(ROUND, List.of(OVERWORLD), -100, new SequenceRandom(0));
        service.refresh(-100);
        assertFalse(service.refresh(-1));
        assertThrows(IllegalArgumentException.class, () -> service.refresh(-101));
        var wrapped = new HuntVotingService(ROUND, List.of(OVERWORLD), Long.MAX_VALUE - 10,
                new SequenceRandom(0));
        wrapped.refresh(Long.MAX_VALUE - 10);
        assertFalse(wrapped.refresh(Long.MIN_VALUE + 10));
        assertEquals(INTERVAL - 21, wrapped.remainingNanos(Long.MIN_VALUE + 10));
    }

    private static HuntVotingService service(Random random) {
        return new HuntVotingService(ROUND, List.of(OVERWORLD, NETHER, END), 0, random);
    }

    private static ParticipantView monster(long player) {
        return view(player, ROUND, ParticipantRole.MONSTER_LOBBY, true, OVERWORLD, false);
    }

    private static ParticipantView human(long player, String dimension) {
        return view(player, ROUND, ParticipantRole.HUMAN_ACTIVE, true, dimension, false);
    }

    private static ParticipantView view(long player, UUID round, ParticipantRole role, boolean online,
                                        String dimension, boolean protectedArea) {
        return new ParticipantView(id(player), round, role, online, dimension, protectedArea);
    }

    private static UUID id(long value) { return new UUID(0, value); }

    private static final class SequenceRandom extends Random {
        private final int[] values;
        private final List<Integer> bounds = new ArrayList<>();
        private int calls;

        private SequenceRandom(int... values) { this.values = values; }

        @Override public int nextInt(int bound) {
            bounds.add(bound);
            return Math.floorMod(values[calls++ % values.length], bound);
        }
    }
}
