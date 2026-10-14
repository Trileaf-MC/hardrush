package dev.hardrush.persistence;

import dev.hardrush.core.record.ParticipantRecord;
import dev.hardrush.core.record.ProgressWrite;
import dev.hardrush.core.round.ParticipantRole;
import java.io.*;
import java.util.*;

/** Deterministic, bounded encodings used by record rows and their integrity digests. */
final class RecordEncoding {
    private RecordEncoding() {}
    static byte[] progress(ProgressWrite value) {
        return encode(out -> {
            out.writeUTF("HR_PROGRESS_1"); uuid(out, value.roundId()); uuid(out, value.eventId()); uuid(out, value.playerId());
            out.writeUTF(value.playerName()); strings(out, value.completedAdvancementIds());
            out.writeUTF(value.advancementSetFingerprint()); out.writeBoolean(value.enteredEnd()); out.writeLong(value.challengeNanos());
        });
    }
    static ProgressWrite progress(byte[] payload) {
        return decode(payload, in -> {
            if (!in.readUTF().equals("HR_PROGRESS_1")) throw new IOException("Invalid progress format");
            return new ProgressWrite(uuid(in), uuid(in), uuid(in), in.readUTF(), strings(in), in.readUTF(), in.readBoolean(), in.readLong());
        });
    }
    static byte[] participant(ParticipantRecord value) {
        return encode(out -> {
            out.writeUTF("HR_PARTICIPANT_1"); uuid(out, value.playerId()); out.writeUTF(value.playerName());
            out.writeLong(value.joinedAtChallengeNanos()); out.writeLong(value.humanNanos());
            out.writeInt(value.deathCount()); out.writeInt(value.revivalCount()); out.writeInt(value.advancementCount());
            out.writeBoolean(value.firstEndNanos() != null); if (value.firstEndNanos() != null) out.writeLong(value.firstEndNanos());
            out.writeUTF(value.finalRole().name()); strings(out, value.advancementSetFingerprints());
        });
    }
    static ParticipantRecord participant(byte[] payload) {
        return decode(payload, in -> {
            if (!in.readUTF().equals("HR_PARTICIPANT_1")) throw new IOException("Invalid participant format");
            return new ParticipantRecord(uuid(in), in.readUTF(), in.readLong(), in.readLong(), in.readInt(), in.readInt(), in.readInt(),
                    in.readBoolean() ? in.readLong() : null, ParticipantRole.valueOf(in.readUTF()), strings(in));
        });
    }
    static byte[] historyDigest(byte[] snapshot, boolean trusted) {
        return WriteDigest.payload(encode(out -> { out.writeUTF("HR_HISTORY_1"); out.writeBoolean(trusted); out.write(snapshot); }));
    }
    static byte[] participantDigest(UUID round, int lifeSequence, byte[] payload) {
        return WriteDigest.payload(encode(out -> { uuid(out, round); out.writeInt(lifeSequence); out.write(payload); }));
    }
    static byte[] claimDigest(UUID playerId, UUID roundId, int edition, int volume, boolean delivered) {
        return WriteDigest.payload(encode(out -> {
            out.writeUTF("HR_CLAIM_1"); uuid(out, playerId); uuid(out, roundId); out.writeInt(edition); out.writeInt(volume); out.writeBoolean(delivered);
        }));
    }
    private static void uuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits());
    }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void strings(DataOutputStream out, Set<String> values) throws IOException {
        out.writeInt(values.size()); for (String value : new TreeSet<>(values)) out.writeUTF(value);
    }
    private static Set<String> strings(DataInputStream in) throws IOException {
        int count = in.readInt(); if (count < 0 || count > 10_000) throw new IOException("Unbounded set");
        var result = new LinkedHashSet<String>();
        for (int index = 0; index < count; index++) if (!result.add(in.readUTF())) throw new IOException("Duplicate set value");
        return result;
    }
    private static byte[] encode(Encoder encoder) {
        try {
            var bytes = new ByteArrayOutputStream(); try (var out = new DataOutputStream(bytes)) { encoder.write(out); }
            if (bytes.size() > 8 * 1024 * 1024) throw new IllegalArgumentException("Record too large");
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static <T> T decode(byte[] bytes, Decoder<T> decoder) {
        if (bytes == null || bytes.length > 8 * 1024 * 1024) throw new IllegalArgumentException("Invalid record size");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            T value = decoder.read(in); if (in.available() != 0) throw new IOException("Trailing record bytes"); return value;
        } catch (IOException | IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid record payload", invalid); }
    }
    @FunctionalInterface private interface Encoder { void write(DataOutputStream out) throws IOException; }
    @FunctionalInterface private interface Decoder<T> { T read(DataInputStream in) throws IOException; }
}
