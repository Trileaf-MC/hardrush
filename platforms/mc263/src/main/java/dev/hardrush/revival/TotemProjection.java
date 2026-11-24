package dev.hardrush.revival;

import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundSnapshot;
import java.util.Objects;
import java.util.UUID;

/**
 * What a player's inventory may hold of the revival totem right now (design 2.2, "投影资格分两级"). Pure, so the three
 * states are decided in one place and tested without a server:
 *
 * <ul>
 *   <li>{@link State#PROJECT}: the committed balance minus in-flight in-place revivals is shown;</li>
 *   <li>{@link State#FREEZE}: still the same committed life of the same identity, only briefly not eligible
 *       (competition locked, profile not verified or unlocked, offline, awaiting admission, recovery). Copies
 *       projected for this identity stay exactly where they are; copies of any other identity are removed;</li>
 *   <li>{@link State#CLEAR}: not a participant of this round any more, or the identity really changed
 *       (death, leaving the round, disqualification, round change, end of the round, becoming a spectator).</li>
 * </ul>
 *
 * <p>"Same identity" is the round, role, life and monster sortie the copies were last projected for. That identity is
 * stamped on the copies themselves ({@link RevivalTotems}), so it survives a restart: the profile restores the
 * body with its copies, and the first ticks after a restart (still locked by the checkpoint resume) freeze them in
 * place instead of clearing the off-hand and re-adding the stack to the first free slot.
 */
final class TotemProjection {
    private TotemProjection() {}

    enum State { CLEAR, FREEZE, PROJECT }

    enum Holder { HUMAN, CAMP_MONSTER }

    /** The committed life a set of copies belongs to. */
    record Identity(UUID round, ParticipantRole role, int life, int sortie) {
        Identity {
            Objects.requireNonNull(round); Objects.requireNonNull(role);
            if (role != ParticipantRole.HUMAN_ACTIVE && role != ParticipantRole.MONSTER_LOBBY)
                throw new IllegalArgumentException("Only active humans and camp monsters hold revival totems");
        }
        static Identity of(UUID round, Participant participant) {
            return new Identity(round, participant.role(), participant.lifeSequence(), participant.monsterDeploymentSequence());
        }
        Holder holder() { return role == ParticipantRole.HUMAN_ACTIVE ? Holder.HUMAN : Holder.CAMP_MONSTER; }
    }

    /** {@code identity} is null only for {@link State#CLEAR}; {@code charges} is meaningful only for {@link State#PROJECT}. */
    record Decision(State state, Identity identity, int charges) {
        static final Decision CLEAR = new Decision(State.CLEAR, null, 0);
    }

    /**
     * @param spectator   the body is a spectator (an observing camp monster, an administrative spectator)
     * @param locked      the session's competition lock ({@code RoundSession.competitionLocked})
     * @param bodyReady   the live body is the verified, unlocked body of the participant's committed identity
     * @param pendingSaves in-place revivals already spent in the world but not yet committed
     */
    static Decision decide(RoundSnapshot round, UUID player, boolean spectator, boolean locked, boolean bodyReady,
                           int pendingSaves) {
        var participant = round == null ? null : round.participants().get(player);
        if (participant == null || spectator) return Decision.CLEAR;
        var role = participant.role();
        if (role != ParticipantRole.HUMAN_ACTIVE && role != ParticipantRole.MONSTER_LOBBY) return Decision.CLEAR;
        boolean live = round.phase() == RoundPhase.RUNNING || round.phase() == RoundPhase.RESCUE;
        // Recovery may still resume this very life; every other phase has ended (or not yet started) the round.
        if (!live && round.phase() != RoundPhase.RECOVERY_REQUIRED) return Decision.CLEAR;
        var identity = Identity.of(round.roundId(), participant);
        if (live && !locked && participant.online() && !participant.entering() && bodyReady) {
            return new Decision(State.PROJECT, identity, Math.max(0, participant.revivalCharges() - Math.max(0, pendingSaves)));
        }
        return new Decision(State.FREEZE, identity, 0);
    }
}
