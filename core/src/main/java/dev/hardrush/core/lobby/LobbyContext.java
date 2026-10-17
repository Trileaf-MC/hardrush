package dev.hardrush.core.lobby;

/** Service-specific availability: recovery can allow RULES without enabling competition/history. */
public enum LobbyContext {
    RULES, HISTORY, DISPLAY, COMPETITION;
    public boolean readOnly() { return this == RULES || this == HISTORY; }
}
