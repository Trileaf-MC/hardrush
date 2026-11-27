package dev.hardrush.lobby.pack.imports;

import dev.hardrush.core.lobby.plan.Cell;

/**
 * Something a building import found wrong without failing (design “建筑清单与大厅包构建” 1.3, 1.5): the workshop shows
 * {@link #message} in its completion notice; the headless build reports it by {@link #kind}, an error when
 * {@link Kind#failsBuild}. {@code world} and {@code manifest} are the cell it concerns, when there is one ({@code manifest}
 * in the building manifest's own frame).
 */
public record ImportIssue(Kind kind, String message, Cell world, Cell manifest) {
    public enum Kind {
        /** Block ids, properties or values this game does not know; vanilla loads them as air or drops the property. */
        UNKNOWN_BLOCK(true),
        /** A manifest sign entry whose cell holds no sign; skipped. */
        NOT_A_SIGN(false);

        private final boolean failsBuild;

        Kind(boolean failsBuild) { this.failsBuild = failsBuild; }

        /** A headless build with such an issue reports an error and writes no pack. */
        public boolean failsBuild() { return failsBuild; }
    }

    public static ImportIssue of(Kind kind, String message) { return new ImportIssue(kind, message, null, null); }
}
