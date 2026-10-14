package dev.hardrush.core.combat;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.round.ParticipantRole;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatCausalityTest {
    private final UUID round = UUID.randomUUID();
    private CombatOrigin source(ParticipantRole role, AttackKind kind) {
        return new CombatOrigin(round, UUID.randomUUID(), new CombatActor(UUID.randomUUID(), role, 4, 2),
                kind, Optional.of(UUID.randomUUID()), List.of());
    }
    private CombatEvent event(UUID id, Optional<CombatOrigin> origin) {
        return new CombatEvent(round, id, UUID.randomUUID(), 1, UUID.randomUUID(), origin,
                CombatEvent.classify(round, origin), "minecraft:explosion", Optional.of(UUID.randomUUID()),
                Optional.of(UUID.randomUUID()), 4, true, 15);
    }
    @Test void humanArrowAndMonsterArrowKeepCreationIdentityThroughTntCrystalChain() {
        for (var role : List.of(ParticipantRole.HUMAN_ACTIVE, ParticipantRole.MONSTER_DEPLOYED)) {
            var arrow = source(role, AttackKind.ARROW);
            var tnt = arrow.child(UUID.randomUUID(), AttackKind.TNT, Optional.of(UUID.randomUUID()));
            var crystal = tnt.child(UUID.randomUUID(), AttackKind.END_CRYSTAL, Optional.of(UUID.randomUUID()));
            var reloaded = CombatOriginCodec.decode(CombatOriginCodec.encode(crystal)).orElseThrow();
            assertEquals(crystal, reloaded);
            assertEquals(arrow.actor(), reloaded.actor());
            assertEquals(List.of(arrow.originId(), tnt.originId()), reloaded.causalParents());
            assertEquals(role.isActiveHuman(), reloaded.isHumanAttack(round));
        }
    }
    @Test void unknownAndStaleAttacksNeverBecomeHumanCredit() {
        assertEquals(CombatEvent.Attribution.PENDING_VERIFICATION, event(UUID.randomUUID(), Optional.empty()).attribution());
        var origin = source(ParticipantRole.HUMAN_ACTIVE, AttackKind.BED);
        assertEquals(CombatEvent.Attribution.STALE_ROUND, CombatEvent.classify(UUID.randomUUID(), Optional.of(origin)));
        assertFalse(origin.isHumanAttack(UUID.randomUUID()));
    }
    @Test void invalidOrInactiveSnapshotsFailClosed() {
        for (var text : List.of("", "!", "AAAAAg==", "a".repeat(4097))) assertTrue(CombatOriginCodec.decode(text).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> source(ParticipantRole.LOBBY, AttackKind.TNT));
        assertThrows(IllegalArgumentException.class, () -> source(ParticipantRole.HUMAN_ACTIVE, AttackKind.ENVIRONMENT));
        assertThrows(IllegalArgumentException.class, () -> new CombatActor(UUID.randomUUID(), ParticipantRole.HUMAN_ACTIVE, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CombatActor(UUID.randomUUID(), ParticipantRole.MONSTER_DEPLOYED, 1, 0));
        var origin = source(ParticipantRole.HUMAN_ACTIVE, AttackKind.RESPAWN_ANCHOR);
        assertTrue(CombatOriginCodec.decode(CombatOriginCodec.encode(origin) + "AAAA").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> origin.child(origin.originId(), AttackKind.TNT, Optional.empty()));
    }
    @Test void allRequestedKindsPersistTheirDirectEntityAndLife() {
        for (var kind : List.of(AttackKind.MELEE, AttackKind.ARROW, AttackKind.BED, AttackKind.RESPAWN_ANCHOR, AttackKind.TNT, AttackKind.END_CRYSTAL)) {
            var origin = source(ParticipantRole.HUMAN_ACTIVE, kind);
            assertEquals(origin, CombatOriginCodec.decode(CombatOriginCodec.encode(origin)).orElseThrow());
        }
    }
    @Test void duplicateIdsAreIdempotentButChangedPayloadIsAnError() {
        var ledger = new CombatLedger(round);
        var event = event(UUID.randomUUID(), Optional.of(source(ParticipantRole.HUMAN_ACTIVE, AttackKind.MELEE)));
        assertEquals(CombatLedger.Result.ACCEPTED, ledger.accept(event));
        assertEquals(CombatLedger.Result.DUPLICATE, ledger.accept(event));
        assertEquals(1, ledger.size());
        assertThrows(IllegalStateException.class, () -> ledger.accept(event(event.eventId(), Optional.empty())));
        assertEquals(CombatLedger.Result.STALE_ROUND, new CombatLedger(UUID.randomUUID()).accept(event));
    }
    @Test void durableEvidenceKeepsUnknownAndStaleProofWithoutInventingAnOwner() {
        for (var origin : List.of(Optional.<CombatOrigin>empty(), Optional.of(source(ParticipantRole.MONSTER_DEPLOYED, AttackKind.TNT)))) {
            var evidence = event(UUID.randomUUID(), origin);
            assertEquals(evidence, CombatEventCodec.decode(CombatEventCodec.encode(evidence)));
        }
        assertThrows(IllegalArgumentException.class, () -> CombatEventCodec.decode(new byte[]{0, 0, 0, 3}));
        assertThrows(IllegalArgumentException.class, () -> CombatEventCodec.decode(new byte[]{0}));
    }
    @Test void versionOneEvidenceRemainsReadableWithoutInventingRawEntities() throws Exception {
        var value = event(UUID.randomUUID(), Optional.empty());
        var bytes = new java.io.ByteArrayOutputStream(); var out = new java.io.DataOutputStream(bytes);
        out.writeInt(1);
        for (var id : List.of(value.roundId(), value.eventId(), value.batchId())) {
            out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
        }
        out.writeLong(value.sequence()); out.writeLong(value.victimId().getMostSignificantBits()); out.writeLong(value.victimId().getLeastSignificantBits());
        out.writeUTF(""); out.writeUTF(value.attribution().name()); out.writeUTF(value.rawDamageType());
        out.writeFloat(value.appliedDamage()); out.writeBoolean(value.lethal()); out.writeLong(value.observedNanoTime());
        var restored = CombatEventCodec.decode(bytes.toByteArray());
        assertEquals(value.eventId(), restored.eventId()); assertEquals(value.attribution(), restored.attribution());
        assertTrue(restored.rawDirectEntityId().isEmpty()); assertTrue(restored.rawCausingEntityId().isEmpty());
    }
}
