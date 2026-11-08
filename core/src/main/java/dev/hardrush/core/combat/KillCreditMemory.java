package dev.hardrush.core.combat;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Vanilla kill credit for players. {@code LivingEntity.getKillCredit} names whoever last hurt the
 * victim within 100 ticks ({@code lastHurtByPlayerMemoryTime}), even when a fall, fire, lava or an
 * unattributed mob finishes the job. Each credited hit replaces the previous one, as each new player
 * hit overwrites {@code lastHurtByPlayer}; unattributed damage never clears it.
 *
 * <p>Single-thread, per server. Ticks are the caller's monotonic server tick count.
 */
public final class KillCreditMemory {
    /** {@code LivingEntity.resolvePlayerResponsibleForDamage}: {@code setLastHurtByPlayer(player, 100)}. */
    public static final long WINDOW_TICKS = 100;
    private record Hit(CombatOrigin credit, long tick) {}
    private final Map<UUID, Hit> hits = new HashMap<>();

    /** A hit the victim survived; the attacker stays credited for the next {@link #WINDOW_TICKS}. */
    public void hurt(UUID victim, CombatOrigin credit, long tick) {
        Objects.requireNonNull(victim); Objects.requireNonNull(credit);
        hits.values().removeIf(hit -> expired(hit, tick));
        hits.put(victim, new Hit(credit, tick));
    }

    /**
     * Resolves and forgets the victim's credit at death: the lethal hit's own credit when it has one,
     * otherwise the last credited hit that is still remembered.
     */
    public Optional<CombatOrigin> died(UUID victim, Optional<CombatOrigin> lethal, long tick) {
        Objects.requireNonNull(lethal);
        var hit = hits.remove(Objects.requireNonNull(victim));
        if (lethal.isPresent()) return lethal;
        return hit == null || expired(hit, tick) ? Optional.empty() : Optional.of(hit.credit());
    }

    /** A new body (respawn, revival, round change) starts with no memory, as a new vanilla entity would. */
    public void forget(UUID victim) { hits.remove(victim); }

    public void clear() { hits.clear(); }

    private static boolean expired(Hit hit, long tick) { return tick - hit.tick() > WINDOW_TICKS; }
}
