package dev.hardrush.core.round;

/**
 * A monster's paid creeper sortie (docs/plan/苦力怕付费解锁.md). {@link #PLAIN} is the unlocked ticket; {@link #CHARGED}
 * is the same ticket upgraded, by paying the charge at the creeper booth or by a lightning strike out in the field.
 * The grade rides the ticket, so whatever keeps the ticket keeps its charge, and whatever spends it spends both.
 */
public enum CreeperTicket {
    NONE, PLAIN, CHARGED;

    /** Whether this monster may deploy as a paid creeper. */
    public boolean unlocked() { return this != NONE; }

    /** Whether its next creeper sortie starts powered, as a lightning strike leaves a native creeper. */
    public boolean charged() { return this == CHARGED; }
}
