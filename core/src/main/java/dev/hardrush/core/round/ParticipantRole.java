package dev.hardrush.core.round;

/** Server identity, independent of presentation or Minecraft game mode. */
public enum ParticipantRole {
    LOBBY, HUMAN_ACTIVE, HUMAN_RESPAWNING, MONSTER_LOBBY, MONSTER_DEPLOYED, TRANSITION_PENDING,
    WITHDRAWN, DISQUALIFIED;

    public boolean isActiveHuman() { return this == HUMAN_ACTIVE; }
    public boolean isMonster() { return this == MONSTER_LOBBY || this == MONSTER_DEPLOYED; }
    public boolean isExcluded() { return this == WITHDRAWN || this == DISQUALIFIED; }
}
