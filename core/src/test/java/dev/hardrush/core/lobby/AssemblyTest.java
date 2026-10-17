package dev.hardrush.core.lobby;

import static org.junit.jupiter.api.Assertions.*;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AssemblyTest {
    final AtomicLong clock = new AtomicLong();
    final UUID id = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
    final RoundEngine engine = RoundEngine.create(id, 1, 7, RulesConfig.defaults(), clock::get);
    void accept(RoundTransition transition) { assertTrue(engine.accept(transition)); }
    void open() { accept(engine.proposeWorldReady(UUID.randomUUID())); }
    void ready(UUID player) { accept(engine.proposeAssembly(UUID.randomUUID(), id, player, "Human", true)); }
    @Test void registrationAndReadyAreOneCommitAndOtherPlayersNeverResetClock() {
        open(); var transition = engine.proposeAssembly(UUID.randomUUID(), id, a, "A", true);
        assertTrue(engine.committed().participants().isEmpty()); assertTrue(transition.after().participants().get(a).ready());
        accept(transition); clock.addAndGet(7_000_000_000L); ready(b);
        assertEquals(23_000, engine.committed().remainingPhaseMillis());
        accept(engine.proposeAssembly(UUID.randomUUID(), id, a, "A", false));
        assertEquals(RoundPhase.COUNTDOWN, engine.committed().phase());
        accept(engine.proposeAssembly(UUID.randomUUID(), id, b, "B", false));
        assertEquals(RoundPhase.WAITING, engine.committed().phase()); ready(a);
        assertEquals(30_000, engine.committed().remainingPhaseMillis());
    }
    @Test void finalPositionProofCanCancelEvenAtZeroAndSingleValidHumanStarts() {
        open(); ready(a); clock.addAndGet(30_000_000_000L);
        accept(engine.proposeAdmissionTick(UUID.randomUUID(), Set.of()));
        assertEquals(RoundPhase.WAITING, engine.committed().phase());
        ready(a); clock.addAndGet(30_000_000_000L);
        accept(engine.proposeAdmissionTick(UUID.randomUUID(), Set.of(a)));
        assertEquals(ParticipantRole.HUMAN_ACTIVE, engine.committed().participants().get(a).role());
        assertFalse(engine.committed().participants().get(a).online());
    }
    @Test void ordinaryDisconnectClearsOnlyItsReadinessAndDeadIdentityNeverRejoins() {
        open(); ready(a); ready(b);
        accept(engine.proposeOnline(UUID.randomUUID(), a, "A", false));
        assertFalse(engine.committed().participants().get(a).ready());
        assertTrue(engine.committed().participants().get(b).ready());
        clock.addAndGet(30_000_000_000L); accept(engine.proposeAdmissionTick(UUID.randomUUID(), Set.of(b)));
        assertThrows(IllegalStateException.class, () -> engine.proposeAssembly(UUID.randomUUID(), id, b, "B", true));
    }
    @Test void unsafeOriginalDoesNotDiscardOtherAbsentOriginalAndOneProofPreservesRemainder() {
        open(); ready(a); ready(b); clock.addAndGet(9_000_000_000L);
        accept(engine.proposeCheckpoint(UUID.randomUUID(), true));
        var restored = RoundEngine.restore(engine.committed(), clock::get); clock.addAndGet(900_000_000_000L);
        assertTrue(restored.accept(restored.proposeRestoredReady(UUID.randomUUID(), id, a, "A", false)));
        assertEquals(RoundPhase.COUNTDOWN, restored.committed().phase()); assertTrue(restored.committed().participants().get(b).ready());
        assertTrue(restored.accept(restored.proposeRestoredReady(UUID.randomUUID(), id, b, "B", true)));
        assertEquals(21_000, restored.committed().remainingPhaseMillis());
        assertThrows(IllegalStateException.class, () -> restored.proposeRestoredReady(UUID.randomUUID(), UUID.randomUUID(), b, "B", true));
    }
    @Test void allFacilitiesAndLandingsFitTheClaimedCampAndSpawnCannotAutoPrepare() {
        for (var f : LobbyLayout.Facility.values()) { var p = LobbyLayout.facility(f); assertTrue(p.x() >= LobbyLayout.MIN_X && p.x() <= LobbyLayout.MAX_X); assertTrue(p.z() >= LobbyLayout.MIN_Z && p.z()+2 <= LobbyLayout.MAX_Z); }
        assertFalse(LobbyLayout.ASSEMBLY.contains(LobbyLayout.landing(LobbyLayout.Landing.CENTRAL).x(), LobbyLayout.landing(LobbyLayout.Landing.CENTRAL).z()));
        assertTrue(LobbyLayout.ASSEMBLY.contains(LobbyLayout.landing(LobbyLayout.Landing.RESTORE_READY).x(), LobbyLayout.landing(LobbyLayout.Landing.RESTORE_READY).z()));
    }
}
