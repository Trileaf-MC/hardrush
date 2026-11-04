package dev.hardrush.core.round;

/**
 * Why a rule rejected a player's own request, for the few cases where the player must be told apart.
 * Every other rejection stays a plain {@link IllegalStateException}: its message is diagnostic text for logs and
 * administrators, never player copy. Player wording lives in the platform layer.
 */
public enum Refusal {
    /** The server is still committing an earlier change or saving a checkpoint; retrying shortly will work. */
    BUSY,
    /** World or profile adapters are not attached yet. */
    NOT_READY,
    /** After a restart, the countdown waits for players who were ready before it. */
    RESTART_WAIT,
    /** The round is settling or shutting down; no new competitive action is accepted. */
    FROZEN,
    /** Storage or adapter failure isolated the round until an administrator acts. */
    SUSPENDED,
    /** The player left the assembly circle while the request was being recorded. */
    LEFT_ASSEMBLY,
    /** A camp monster holding a revival totem must revive before hunting, choosing or trading. */
    REVIVAL_FIRST,
    NOT_ENOUGH_COINS,
    SKILL_COOLDOWN,
    /** Any other rule rejection; the caller supplies the wording for its own context. */
    REJECTED;

    /** A rule rejection that carries its {@link Refusal}; the message remains diagnostic only. */
    public static final class Exception extends IllegalStateException {
        private final Refusal refusal;
        public Exception(Refusal refusal, String diagnostic) {
            super(diagnostic);
            this.refusal = java.util.Objects.requireNonNull(refusal);
        }
        public Refusal refusal() { return refusal; }
    }
}
