package dev.hardrush.core.round;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.config.GameMode;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Whether a death, or a revival totem forced out of a human's hand, drops a revival totem (docs/plan/复生图腾掉落.md,
 * 1.1 and 1.5). The platform asks at the moment of the lethal hit, before its event is committed, and the engine asks
 * again on commit, so the answer depends on the arguments only.
 */
public final class TotemDrop {
    private TotemDrop() {}

    /**
     * A hardcore human of {@code before} killed by a direct attack another participant of this round launched
     * while a human. Only the identity at the attack counts: the killer may have died since (a mutual kill, an
     * arrow still in flight). Whether the batch ends the round is the engine's business, not this test's.
     */
    public static boolean qualifies(RoundSnapshot before, RoundDeath death) {
        Objects.requireNonNull(death);
        return qualifies(before, death.playerId(), death.killCredit());
    }

    /**
     * Whether a lethal hit on {@code victimId}, credited to {@code killCredit}, drops a revival totem: the same test for
     * a death and for a revival totem forced out of a human's hand by such a hit (docs/plan/复生图腾掉落.md 1.5).
     */
    public static boolean qualifies(RoundSnapshot before, UUID victimId, Optional<CombatOrigin> killCredit) {
        Objects.requireNonNull(before); Objects.requireNonNull(victimId); Objects.requireNonNull(killCredit);
        if (before.rules().mode() != GameMode.HARDCORE) return false;
        Participant victim = before.participants().get(victimId);
        if (victim == null || !victim.role().isActiveHuman() || killCredit.isEmpty()) return false;
        var origin = killCredit.get();
        UUID killer = origin.actor().playerId();
        return origin.roundId().equals(before.roundId()) && origin.actor().identity().isActiveHuman()
                && directHumanAttack(origin.kind()) && before.participants().containsKey(killer)
                && !killer.equals(victimId);
    }

    /** The attacks by which a human may kill another human: melee, arrows, beds, respawn anchors, TNT and end crystals. */
    public static boolean directHumanAttack(AttackKind kind) {
        return switch (kind) {
            case MELEE, ARROW, BED, RESPAWN_ANCHOR, TNT, END_CRYSTAL -> true;
            default -> false;
        };
    }
}
