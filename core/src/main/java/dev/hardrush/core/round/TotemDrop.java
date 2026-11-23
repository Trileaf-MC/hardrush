package dev.hardrush.core.round;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.config.GameMode;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Where the revival totem goes that every ended human life, and every revival totem that saved one, releases
 * (docs/plan/复生图腾守恒.md 1.1): exactly one, so the round's total of humans and totems never leaks. The platform asks
 * at the moment of the lethal hit, before its event is committed, and the engine asks again on commit, so the answer
 * depends on the arguments only.
 */
public final class TotemDrop {
    private TotemDrop() {}

    /** Where one released totem goes. */
    public sealed interface Release {
        /** A qualifying monster kill or forced-out totem: the monster gains it directly. */
        record ToMonster(UUID owner) implements Release {}
        /** Into the world at the victim, for others to take; {@code killer} is present for a human's attack. */
        record Dropped(LooseTotem.Source source, Optional<UUID> killer) implements Release {
            public Dropped {
                Objects.requireNonNull(source); Objects.requireNonNull(killer);
                if (killer.isPresent() != (source == LooseTotem.Source.HUMAN)) throw new IllegalArgumentException("Killer mismatch");
            }
        }
    }

    /**
     * The release of a lethal hit on {@code victimId}, a hardcore human of {@code before}, credited to {@code
     * killCredit}: the same for a death and for a revival totem the hit forces out. A qualifying monster gains it; a
     * qualifying human's attack drops it ({@link LooseTotem.Source#HUMAN}); anything else (no credit, a suicide, an
     * unlisted attack kind, an attacker who left the round or attacked from a later life) drops it unclaimed. Empty when
     * nothing is released: a normal round, or a victim who is not an active human.
     */
    public static Optional<Release> release(RoundSnapshot before, UUID victimId, Optional<CombatOrigin> killCredit) {
        Objects.requireNonNull(before); Objects.requireNonNull(victimId); Objects.requireNonNull(killCredit);
        if (before.rules().mode() != GameMode.HARDCORE) return Optional.empty();
        Participant victim = before.participants().get(victimId);
        if (victim == null || !victim.role().isActiveHuman()) return Optional.empty();
        var monster = monsterKiller(before, victimId, killCredit);
        if (monster.isPresent()) return Optional.of(new Release.ToMonster(monster.get()));
        if (qualifies(before, victimId, killCredit)) {
            return Optional.of(new Release.Dropped(LooseTotem.Source.HUMAN, Optional.of(killCredit.get().actor().playerId())));
        }
        return Optional.of(new Release.Dropped(LooseTotem.Source.UNCLAIMED, Optional.empty()));
    }

    /** {@link #release} of an actual death. */
    public static Optional<Release> release(RoundSnapshot before, RoundDeath death) {
        Objects.requireNonNull(death);
        return release(before, death.playerId(), death.killCredit());
    }

    /**
     * A hardcore human of {@code before} hit by a direct attack another participant of this round launched while a
     * human. Only the identity at the attack counts: the killer may have died since (a mutual kill, an arrow still in
     * flight).
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

    /**
     * The player monster credited with hitting {@code victimId}, a hardcore human of {@code before}, by {@code
     * killCredit}: a direct monster attack launched by this round's monster, from no later life or sortie than its
     * current one.
     */
    static Optional<UUID> monsterKiller(RoundSnapshot before, UUID victimId, Optional<CombatOrigin> killCredit) {
        if (before.rules().mode() != GameMode.HARDCORE || killCredit.isEmpty()) return Optional.empty();
        Participant victim = before.participants().get(victimId);
        var origin = killCredit.get();
        if (victim == null || !victim.role().isActiveHuman() || !origin.roundId().equals(before.roundId())
                || origin.actor().identity().isActiveHuman()) return Optional.empty();
        Participant owner = before.participants().get(origin.actor().playerId());
        if (owner == null || owner.id().equals(victimId)) return Optional.empty();
        boolean directMonsterAttack = switch (origin.kind()) {
            case MELEE, ARROW, CREEPER, SUMMON -> true;
            default -> false;
        };
        if (!directMonsterAttack || !owner.role().isMonster()
                || origin.actor().lifeSequence() > owner.lifeSequence()
                || origin.actor().deploymentSequence() > owner.monsterDeploymentSequence()) return Optional.empty();
        return Optional.of(owner.id());
    }

    /** The attacks by which a human may kill another human: melee, arrows, beds, respawn anchors, TNT and end crystals. */
    public static boolean directHumanAttack(AttackKind kind) {
        return switch (kind) {
            case MELEE, ARROW, BED, RESPAWN_ANCHOR, TNT, END_CRYSTAL -> true;
            default -> false;
        };
    }
}
