package dev.hardrush.core.round;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.config.GameMode;
import java.util.Objects;
import java.util.UUID;

/**
 * Whether a death drops a revival totem (docs/plan/复生图腾掉落.md, 1.1). The platform asks at the moment of
 * death, before the batch is committed, and the engine asks again on commit, so the answer depends on the
 * arguments only.
 */
public final class TotemDrop {
    private TotemDrop() {}

    /**
     * A hardcore human of {@code before} killed by a direct attack another participant of this round launched
     * while a human. Only the identity at the attack counts: the killer may have died since (a mutual kill, an
     * arrow still in flight). Whether the batch ends the round is the engine's business, not this test's.
     */
    public static boolean qualifies(RoundSnapshot before, RoundDeath death) {
        Objects.requireNonNull(before); Objects.requireNonNull(death);
        if (before.rules().mode() != GameMode.HARDCORE) return false;
        Participant victim = before.participants().get(death.playerId());
        if (victim == null || !victim.role().isActiveHuman() || death.killCredit().isEmpty()) return false;
        var origin = death.killCredit().get();
        UUID killer = origin.actor().playerId();
        return origin.roundId().equals(before.roundId()) && origin.actor().identity().isActiveHuman()
                && directHumanAttack(origin.kind()) && before.participants().containsKey(killer)
                && !killer.equals(death.playerId());
    }

    /** The attacks by which a human may kill another human: melee, arrows, beds, respawn anchors, TNT and end crystals. */
    public static boolean directHumanAttack(AttackKind kind) {
        return switch (kind) {
            case MELEE, ARROW, BED, RESPAWN_ANCHOR, TNT, END_CRYSTAL -> true;
            default -> false;
        };
    }
}
