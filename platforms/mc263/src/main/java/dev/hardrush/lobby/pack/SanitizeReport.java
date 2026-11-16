package dev.hardrush.lobby.pack;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * What {@link TileSanitizer} took out. {@code removedIds} names the blocks and entities that were dropped for
 * being unsafe, for the "removed on install" warning; marker armor stands dropped on install are counted in
 * {@code removedEntities} but not named, since removing them is the point of installing.
 */
public record SanitizeReport(int removedBlocks, int clearedContainers, int strippedTexts, int removedEntities, Set<String> removedIds) {
    public static final SanitizeReport EMPTY = new SanitizeReport(0, 0, 0, 0, Set.of());

    public SanitizeReport { removedIds = Collections.unmodifiableSortedSet(new TreeSet<>(removedIds)); }

    public SanitizeReport plus(SanitizeReport other) {
        var ids = new TreeSet<>(removedIds); ids.addAll(other.removedIds);
        return new SanitizeReport(removedBlocks + other.removedBlocks, clearedContainers + other.clearedContainers,
                strippedTexts + other.strippedTexts, removedEntities + other.removedEntities, ids);
    }
    public boolean isEmpty() { return removedBlocks == 0 && clearedContainers == 0 && strippedTexts == 0 && removedEntities == 0; }
}
