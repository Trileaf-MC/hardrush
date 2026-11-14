package dev.hardrush.core.lobby.plan;

import java.util.Locale;

/** How a lobby function is bound in the world; tagged as {@code hardrush.as.<lowercase name>}. */
public enum Binding {
    /** A position and facing. */
    POINT,
    /** A position and facing where the runtime shows a packet model. */
    MODEL,
    /** Right-click on any block or tagged entity. */
    CLICK,
    /** A block of a specific type. */
    BLOCK,
    /** Players standing inside. */
    ZONE,
    /** A sign or text display whose content the mod writes. */
    TEXT;

    public String tagName() { return name().toLowerCase(Locale.ROOT); }
}
