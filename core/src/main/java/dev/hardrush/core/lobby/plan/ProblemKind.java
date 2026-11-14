package dev.hardrush.core.lobby.plan;

public enum ProblemKind {
    /** A required function has no binding at all; installing places its fallback when it has one. */
    MISSING,
    /** A binding count outside the function's range. */
    COUNT,
    /** A marker's tags do not parse. */
    MARKER,
    /** A bound block is missing or of the wrong type. */
    BLOCK,
    /** A point or model has nowhere safe to stand. */
    STANDING,
    /** An empty zone, overlapping zones, or a landing inside a zone it must avoid. */
    ZONE,
    /** Content outside the pack bounds, or bounds too large. */
    BOUNDS,
    /** Content that installing will strip. */
    SANITIZED,
    /** Too few display slots for a comfortable round. */
    CAPACITY
}
