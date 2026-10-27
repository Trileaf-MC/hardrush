package dev.hardrush.record;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ProgressTrackerTest {
    @Test void statisticalSetExcludesRecipesRootsAndUndisplayedTechnicalIds() {
        var catalog = AdvancementCatalog.from(List.of(
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
        var catalog = AdvancementCatalog.from(List.of(first, second));
        assertEquals(catalog.fingerprint(), AdvancementCatalog.from(List.of(second, first,
                entry("custom:recipes/extra", true, false))).fingerprint());
        assertNotEquals(catalog.fingerprint(), AdvancementCatalog.from(List.of(first)).fingerprint());
        assertNotEquals(catalog.fingerprint(), AdvancementCatalog.from(List.of(first,
                entry(second.id(), false, false))).fingerprint());
    }

    @Test void reconnectRespawnAndEndReentryRetainOneUuidInTheSameRound() {
        var tracker = new ProgressTracker();
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
        var tracker = new ProgressTracker();
        var player = UUID.randomUUID();
        tracker.observe(UUID.randomUUID(), player, "Human", catalog(), catalog().ids(), true, 100);
        var next = tracker.observe(UUID.randomUUID(), player, "Human", catalog(), Set.of(), false, 0).orElseThrow();
        assertFalse(next.enteredEnd());
        assertTrue(next.completedAdvancementIds().isEmpty());
    }

    @Test void dataPackRemovalPublishesTheNewStatisticalSetFingerprint() {
        var tracker = new ProgressTracker();
        var round = UUID.randomUUID();
        var player = UUID.randomUUID();
        tracker.observe(round, player, "Human", catalog(), catalog().ids(), false, 0);
        var empty = AdvancementCatalog.from(List.of());
        var changed = tracker.observe(round, player, "Human", empty, Set.of(), false, 1).orElseThrow();
        assertTrue(changed.completedAdvancementIds().isEmpty());
        assertEquals(empty.fingerprint(), changed.advancementSetFingerprint());
    }

    private static AdvancementCatalog.Entry entry(String id, boolean display, boolean root) {
        return new AdvancementCatalog.Entry(id, display, root);
    }
    private static AdvancementCatalog catalog() {
        return AdvancementCatalog.from(List.of(entry("minecraft:story/mine_stone", true, false)));
    }
}
