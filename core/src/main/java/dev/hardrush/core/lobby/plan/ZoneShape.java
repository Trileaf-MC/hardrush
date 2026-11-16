package dev.hardrush.core.lobby.plan;

import java.util.Locale;

/** A zone marker's shape; tagged as {@code hardrush.shape.<lowercase name>}. */
public enum ZoneShape {
    /** Contiguous floor of the start block's material (plus extra materials) on the marker's layer. */
    FLOOD,
    /** A box whose bottom face is centred on the marker's cell. */
    BOX;

    public String tagName() { return name().toLowerCase(Locale.ROOT); }
}
