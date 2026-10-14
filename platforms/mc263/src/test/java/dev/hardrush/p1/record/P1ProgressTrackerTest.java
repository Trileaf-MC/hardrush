package dev.hardrush.p1.record;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class P1ProgressTrackerTest {
    @Test void statisticalSetExcludesRecipesRootsAndUndisplayedTechnicalIds() {
        var catalog = P1AdvancementCatalog.from(List.of(
                entry("minecraft:story/mine_stone", true, false),
                entry("custom:recipes/pretend_display", true, false),
                entry("custom:story/start", true, true),
                entry("custom:technical/flag", false, false)));
        assertEquals(Set.of("minecraft:story/mine_stone"), catalog.ids());
        assertTrue(catalog.fingerprint().matches("[0-9a-f]{64}"));
    }

    @Test void setFingerprintChangesWithDataPacksButIgnoresOrderAndExcludedEntries() {
        var first = entry("minecraft:story/mine_stone", true, false);
        var second = entry("custom:story/extra", true, false);
        var catalog = P1AdvancementCatalog.from(List.of(first, second));
        assertEquals(catalog.fingerprint(), P1AdvancementCatalog.from(List.of(second, first,
                entry("custom:recipes/extra", true, false))).fingerprint());
        assertNotEquals(catalog.fingerprint(), P1AdvancementCatalog.from(List.of(first)).fingerprint());
        assertNotEquals(catalog.fingerprint(), P1AdvancementCatalog.from(List.of(first,
                entry(second.id(), false, false))).fingerprint());
    }

    @Test void reconnectRespawnAndEndReentryRetainOneUuidInTheSameRound() {
        var tracker = new P1ProgressTracker();
        var catalog = catalog();
        var round = UUID.randomUUID();
        var player = UUID.randomUUID();
        var original = tracker.observe(round, player, "Human", catalog, catalog.ids(), true, 100).orElseThrow();
        assertTrue(original.enteredEnd());
        assertEquals(catalog.ids(), original.completedAdvancementIds());
        assertTrue(tracker.observe(round, player, "Human", catalog, catalog.ids(), true, 200).isEmpty());
        assertTrue(tracker.observe(round, player, "Human", catalog, Set.of(), false, 300).isEmpty());
        var renamed = tracker.observe(round, player, "Renamed", catalog, Set.of(), false, 400).orElseThrow();
        assertTrue(renamed.enteredEnd());
        assertEquals(catalog.ids(), renamed.completedAdvancementIds());
        assertNotEquals(original.eventId(), renamed.eventId());
    }

    @Test void nextRoundDoesNotInheritAchievementsOrEndEntry() {
        var tracker = new P1ProgressTracker();
        var player = UUID.randomUUID();
        tracker.observe(UUID.randomUUID(), player, "Human", catalog(), catalog().ids(), true, 100);
        var next = tracker.observe(UUID.randomUUID(), player, "Human", catalog(), Set.of(), false, 0).orElseThrow();
        assertFalse(next.enteredEnd());
        assertTrue(next.completedAdvancementIds().isEmpty());
    }

    @Test void dataPackRemovalPublishesTheNewStatisticalSetFingerprint() {
        var tracker = new P1ProgressTracker();
        var round = UUID.randomUUID();
        var player = UUID.randomUUID();
        tracker.observe(round, player, "Human", catalog(), catalog().ids(), false, 0);
        var empty = P1AdvancementCatalog.from(List.of());
        var changed = tracker.observe(round, player, "Human", empty, Set.of(), false, 1).orElseThrow();
        assertTrue(changed.completedAdvancementIds().isEmpty());
        assertEquals(empty.fingerprint(), changed.advancementSetFingerprint());
    }

    private static P1AdvancementCatalog.Entry entry(String id, boolean display, boolean root) {
        return new P1AdvancementCatalog.Entry(id, display, root);
    }
    private static P1AdvancementCatalog catalog() {
        return P1AdvancementCatalog.from(List.of(entry("minecraft:story/mine_stone", true, false)));
    }
}
