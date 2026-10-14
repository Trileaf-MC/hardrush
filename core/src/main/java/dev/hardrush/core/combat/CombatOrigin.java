package dev.hardrush.core.combat;

import dev.hardrush.core.api.DamagePort.AttackKind;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable causal node. Every descendant carries its complete creation identity across unload/restart. */
public record CombatOrigin(UUID roundId, UUID originId, CombatActor actor, AttackKind kind,
                           Optional<UUID> directEntityId, List<UUID> causalParents) {
    public static final int MAX_CHAIN = 64;
    public CombatOrigin {
        Objects.requireNonNull(roundId); Objects.requireNonNull(originId); Objects.requireNonNull(actor);
        Objects.requireNonNull(kind); Objects.requireNonNull(directEntityId);
        causalParents = List.copyOf(causalParents);
        if (!actor.mayCreateAttack()) throw new IllegalArgumentException("Inactive source identity");
        if (kind == AttackKind.ENVIRONMENT) throw new IllegalArgumentException("Environment cannot claim a player actor");
        if ((kind == AttackKind.ARROW || kind == AttackKind.TNT || kind == AttackKind.END_CRYSTAL)
                && directEntityId.isEmpty()) throw new IllegalArgumentException("Entity attack requires a direct entity");
        if (causalParents.size() > MAX_CHAIN || causalParents.contains(originId)
                || causalParents.stream().distinct().count() != causalParents.size()) {
            throw new IllegalArgumentException("Invalid causal chain");
        }
    }
    public CombatOrigin child(UUID id, AttackKind childKind, Optional<UUID> directEntity) {
        var parents = new java.util.ArrayList<>(causalParents);
        parents.add(originId);
        return new CombatOrigin(roundId, id, actor, childKind, directEntity, parents);
    }
    public boolean isHumanAttack(UUID currentRound) {
        return roundId.equals(currentRound) && actor.identity().isActiveHuman();
    }
}
