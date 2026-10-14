package dev.hardrush.core.combat;

import java.io.*;
import java.util.Optional;
import java.util.UUID;

/** Canonical durable evidence format; entity origin and event schemas version independently. */
public final class CombatEventCodec {
    private CombatEventCodec() {}
    public static byte[] encode(CombatEvent value) {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(2); uuid(out, value.roundId()); uuid(out, value.eventId()); uuid(out, value.batchId());
            out.writeLong(value.sequence()); uuid(out, value.victimId());
            out.writeUTF(value.origin().map(CombatOriginCodec::encode).orElse(""));
            out.writeUTF(value.attribution().name()); out.writeUTF(value.rawDamageType());
            optionalUuid(out, value.rawDirectEntityId()); optionalUuid(out, value.rawCausingEntityId());
            out.writeFloat(value.appliedDamage()); out.writeBoolean(value.lethal()); out.writeLong(value.observedNanoTime());
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }
    public static CombatEvent decode(byte[] encoded) {
        if (encoded == null || encoded.length > 16384) throw new IllegalArgumentException("Invalid combat evidence size");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(encoded));
            int version = in.readInt();
            if (version != 1 && version != 2) throw new IllegalArgumentException("Unknown combat evidence version");
            var round = uuid(in); var event = uuid(in); var batch = uuid(in); long sequence = in.readLong(); var victim = uuid(in);
            var originText = in.readUTF();
            Optional<CombatOrigin> origin = originText.isEmpty() ? Optional.empty() : CombatOriginCodec.decode(originText);
            if (!originText.isEmpty() && origin.isEmpty()) throw new IllegalArgumentException("Invalid evidence origin");
            var attribution = CombatEvent.Attribution.valueOf(in.readUTF()); var rawType = in.readUTF();
            var rawDirect = version == 2 ? optionalUuid(in) : Optional.<UUID>empty();
            var rawCausing = version == 2 ? optionalUuid(in) : Optional.<UUID>empty();
            var result = new CombatEvent(round, event, batch, sequence, victim, origin,
                    attribution, rawType, rawDirect, rawCausing, in.readFloat(), in.readBoolean(), in.readLong());
            if (in.available() != 0) throw new IllegalArgumentException("Trailing combat evidence data");
            return result;
        } catch (IOException malformed) { throw new IllegalArgumentException("Malformed combat evidence", malformed); }
    }
    private static void uuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void optionalUuid(DataOutputStream out, Optional<UUID> value) throws IOException {
        out.writeBoolean(value.isPresent()); if (value.isPresent()) uuid(out, value.get());
    }
    private static Optional<UUID> optionalUuid(DataInputStream in) throws IOException { return in.readBoolean() ? Optional.of(uuid(in)) : Optional.empty(); }
}
