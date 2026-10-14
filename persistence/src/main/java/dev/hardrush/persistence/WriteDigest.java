package dev.hardrush.persistence;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.UUID;

final class WriteDigest {
    private WriteDigest() {}

    static byte[] snapshot(SnapshotWrite write) {
        return encoded(out -> {
            string(out, "ROUND");
            uuid(out, write.roundId());
            out.writeLong(write.expectedVersion());
            out.writeLong(write.newVersion());
            string(out, write.eventType());
            bytes(out, write.payload());
            out.writeInt(write.operations().size());
            for (var operation : write.operations().stream()
                    .sorted(Comparator.comparing(value -> value.operationId().toString())).toList()) {
                uuid(out, operation.operationId());
                string(out, operation.operationType());
                out.writeLong(operation.expectedVersion());
                bytes(out, operation.payload());
            }
        });
    }

    static byte[] rules(byte[] payload, String actor) {
        return encoded(out -> {
            string(out, "RULES");
            string(out, actor);
            bytes(out, payload);
        });
    }

    static byte[] payload(byte[] payload) { return sha256().digest(payload); }

    static byte[] evidence(EvidenceWrite write) {
        return encoded(out -> {
            string(out, "EVIDENCE"); uuid(out, write.roundId());
            string(out, write.kind()); bytes(out, write.payload());
        });
    }

    private static byte[] encoded(Encoding encoding) {
        var digest = sha256();
        try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            encoding.write(out);
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory digest encoding failed", impossible);
        }
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void uuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        bytes(out, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    @FunctionalInterface
    private interface Encoding { void write(DataOutputStream out) throws IOException; }
}
