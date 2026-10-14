package dev.hardrush.core.combat;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.round.ParticipantRole;
import java.io.*;
import java.util.*;

/** Bounded versioned encoding used in persistent entity NBT. Invalid data fails closed. */
public final class CombatOriginCodec {
    private CombatOriginCodec() {}
    public static String encode(CombatOrigin value) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(1); uuid(out, value.roundId()); uuid(out, value.originId());
            uuid(out, value.actor().playerId()); out.writeUTF(value.actor().identity().name());
            out.writeLong(value.actor().lifeSequence()); out.writeLong(value.actor().deploymentSequence());
            out.writeUTF(value.kind().name()); out.writeBoolean(value.directEntityId().isPresent());
            if (value.directEntityId().isPresent()) uuid(out, value.directEntityId().get());
            out.writeInt(value.causalParents().size());
            for (var parent : value.causalParents()) uuid(out, parent);
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }
    public static Optional<CombatOrigin> decode(String encoded) {
        if (encoded == null || encoded.length() > 4096) return Optional.empty();
        try {
            var in = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
            if (in.readInt() != 1) return Optional.empty();
            var round = uuid(in); var id = uuid(in); var player = uuid(in);
            var actor = new CombatActor(player, ParticipantRole.valueOf(in.readUTF()), in.readLong(), in.readLong());
            var kind = AttackKind.valueOf(in.readUTF());
            var entity = in.readBoolean() ? Optional.of(uuid(in)) : Optional.<UUID>empty();
            int size = in.readInt();
            if (size < 0 || size > CombatOrigin.MAX_CHAIN) return Optional.empty();
            var chain = new ArrayList<UUID>(size);
            for (int i = 0; i < size; i++) chain.add(uuid(in));
            if (in.available() != 0) return Optional.empty();
            return Optional.of(new CombatOrigin(round, id, actor, kind, entity, chain));
        } catch (IOException | IllegalArgumentException invalid) { return Optional.empty(); }
    }
    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
    }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
}
