package dev.hardrush.core.combat;

import dev.hardrush.core.round.ParticipantRole;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Who owes whom a revenge (docs/plan/死亡消息.md): a human's death is owed to the player credited with it, human or
 * monster. When that human, now a deployed monster, is credited with killing its killer while the killer is a human, the
 * debt is settled once and announced. Every human death replaces the victim's own debt, or clears it when nobody is
 * credited; a revival needs no hook, since becoming a monster again takes another human death.
 *
 * <p>One round at a time: the first death of another round forgets every debt. Single-thread; not persisted.</p>
 */
public final class RevengeLedger {
    private UUID roundId;
    private final Map<UUID, UUID> killers = new HashMap<>();

    /**
     * Books {@code victim}'s death as an active human of {@code round}, credited to {@code credit}. Returns the avenger:
     * the deployed monster credited with this kill when the victim was the one who caused that monster's last human death.
     */
    public Optional<UUID> humanDied(UUID round, UUID victim, Optional<CombatOrigin> credit) {
        Objects.requireNonNull(round); Objects.requireNonNull(victim); Objects.requireNonNull(credit);
        if (!round.equals(roundId)) { roundId = round; killers.clear(); }
        var current = credit.filter(origin -> origin.roundId().equals(round))
                .filter(origin -> !origin.actor().playerId().equals(victim));
        var avenger = current.filter(origin -> origin.actor().identity() == ParticipantRole.MONSTER_DEPLOYED)
                .map(origin -> origin.actor().playerId())
                .filter(monster -> victim.equals(killers.get(monster)));
        avenger.ifPresent(killers::remove);
        if (current.isPresent()) killers.put(victim, current.get().actor().playerId());
        else killers.remove(victim);
        return avenger;
    }
}
