package dev.hardrush.core.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class AdapterContractTest {
    @Test void delayedCompletionRetainsItsOriginalRoundAndVersionForArbitration() {
        UUID round = UUID.randomUUID();
        var tag = new OperationTag(round, UUID.randomUUID(), 3);
        var callback = new CompletableFuture<OperationResult<String>>();
        callback.complete(OperationResult.verified(tag, "saved"));
        var result = callback.join();
        assertTrue(result.tag().matches(round, 3));
        assertFalse(result.tag().matches(UUID.randomUUID(), 3));
        assertFalse(result.tag().matches(round, 4));
        assertEquals("saved", result.value().orElseThrow());
    }

    @Test void unavailableOrFailedAdapterDoesNotClaimAnAppliedResult() {
        var tag = new OperationTag(UUID.randomUUID(), UUID.randomUUID(), 0);
        var result = OperationResult.<ReplayPort.SavedRecordings>failed(tag, OperationResult.Outcome.UNAVAILABLE, "Replay adapter absent");
        assertTrue(result.value().isEmpty());
        assertEquals(OperationResult.Outcome.UNAVAILABLE, result.outcome());
        assertThrows(IllegalArgumentException.class, () -> new OperationResult<>(tag,
                OperationResult.Outcome.VERIFIED, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new OperationResult<>(tag,
                OperationResult.Outcome.RETRYABLE_FAILURE, Optional.of("not saved"), Optional.of("write failed")));
        assertThrows(IllegalArgumentException.class, () -> OperationResult.failed(tag, OperationResult.Outcome.REJECTED, " "));
    }

    @Test void worldBookAndCausalChainValuesDoNotShareMutableCollectionsWithAdapters() {
        UUID round = UUID.randomUUID();
        var worlds = new EnumMap<WorldPort.Dimension, String>(WorldPort.Dimension.class);
        worlds.put(WorldPort.Dimension.OVERWORLD, "hardrush:round/overworld");
        worlds.put(WorldPort.Dimension.NETHER, "hardrush:round/nether");
        worlds.put(WorldPort.Dimension.END, "hardrush:round/end");
        var spawn = new WorldPosition("hardrush:round/overworld", 0, 80, 0, 0, 0);
        var set = new WorldPort.WorldSet(round, 42, worlds, spawn);
        worlds.clear();
        assertEquals(3, set.worldKeys().size());
        assertThrows(UnsupportedOperationException.class, () -> set.worldKeys().clear());
        var pages = new ArrayList<>(List.of("上一轮历史"));
        var book = new BookPort.Book(round, 1, 1, "战绩", "HardRush", pages);
        pages.clear();
        assertEquals(List.of("上一轮历史"), book.pages());
        var cause = UUID.randomUUID();
        var chain = new ArrayList<>(List.of(cause));
        var origin = new DamagePort.AttackOrigin(round, UUID.randomUUID(), Optional.of(UUID.randomUUID()),
                1, DamagePort.SourceIdentity.HUMAN, DamagePort.AttackKind.TNT, chain);
        chain.clear();
        assertEquals(List.of(cause), origin.causalChain());
    }

    @Test void damageEvidenceRejectsCrossRoundOriginAndMissingPlayerOwner() {
        var origin = new DamagePort.AttackOrigin(UUID.randomUUID(), UUID.randomUUID(), Optional.of(UUID.randomUUID()),
                1, DamagePort.SourceIdentity.HUMAN, DamagePort.AttackKind.ARROW, List.of());
        assertThrows(IllegalArgumentException.class, () -> new DamagePort.LethalEvent(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Optional.of(origin), 10));
        assertThrows(IllegalArgumentException.class, () -> new DamagePort.AttackOrigin(UUID.randomUUID(),
                UUID.randomUUID(), Optional.empty(), 1, DamagePort.SourceIdentity.MONSTER, DamagePort.AttackKind.CREEPER, List.of()));
        assertDoesNotThrow(() -> new DamagePort.LethalEvent(origin.roundId(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Optional.empty(), -10)); // System.nanoTime may legitimately be negative.
    }
}
