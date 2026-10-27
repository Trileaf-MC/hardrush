package dev.hardrush.combat;

import dev.hardrush.core.combat.CombatOrigin;
import java.util.Optional;

/** Added to Entity by the combat mixin; the first creation decision is permanent. */
public interface CombatOriginHolder {
    Optional<CombatOrigin> hardrush$combatOrigin();
    boolean hardrush$combatOriginSealed();
    void hardrush$sealCombatOrigin(Optional<CombatOrigin> origin);
}
