package dev.hardrush.player;

import net.minecraft.network.DisconnectionDetails;

/** A play listener's memory of the reason the server gave for closing it; see {@code ServerKickMixin}. */
public interface ServerKicked {
    /** What the server's first {@code disconnect(DisconnectionDetails)} was given, or null when it never called it. */
    DisconnectionDetails hardrush$serverKick();
}
