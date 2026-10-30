package dev.hardrush.player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/** Single-worker file journal. No Minecraft state or entity references cross into it. */
public final class ProfileJournal {
    public enum Phase { PREPARED, APPLIED, VERIFIED }
    public record Generation(UUID roundId, long sequence) {
        public Generation { if (roundId == null || sequence < 1) throw new IllegalArgumentException("Invalid generation"); }
    }
    public record Transition(Generation target, Phase phase) {}
    public record Recovery(Transition transition, byte[] snapshot) {
        public Recovery { snapshot = snapshot.clone(); }
        @Override public byte[] snapshot() { return snapshot.clone(); }
    }
    private static final int FORMAT = 1;
    private static final int MAX_SNAPSHOT = 16 * 1024 * 1024;
    private final Path root;

    public ProfileJournal(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        requireDirectory(this.root);
    }

    /** The old snapshot is sealed before PREPARED can exist. A retry never overwrites that archive. */
    public Transition prepare(UUID player, Optional<Generation> source, Generation target, byte[] oldSnapshot) throws IOException {
        requireSnapshot(oldSnapshot);
        var existing = transition(player);
        if (existing.isPresent() && existing.get().target().equals(target)) return existing.get();
        if (existing.isPresent() && existing.get().target().sequence() >= target.sequence()) {
            throw new IOException("Player generation moves backwards or changes round UUID");
        }
        Path archive = playerDirectory(player).resolve("archives");
        Files.createDirectories(archive);
        requireDirectory(archive);
        String name = source.map(g -> g.sequence() + "-" + g.roundId()).orElse("pre-hardrush");
        Path snapshot = archive.resolve(name + ".bin");
        if (!Files.exists(snapshot, LinkOption.NOFOLLOW_LINKS)) atomicWrite(snapshot, envelope(oldSnapshot));
        else readSnapshot(snapshot); // Never replace a damaged archive with empty/new state.
        Transition prepared = new Transition(target, Phase.PREPARED);
        writeTransition(player, prepared);
        return prepared;
    }

    public Transition advance(UUID player, Generation target, Phase expected, Phase next) throws IOException {
        if (next.ordinal() != expected.ordinal() + 1) throw new IllegalArgumentException("Non-adjacent phase");
        var current = transition(player).orElseThrow(() -> new IOException("Missing profile transition"));
        if (!current.target().equals(target)) throw new IOException("Stale profile callback generation");
        if (current.phase().ordinal() >= next.ordinal()) return current;
        if (current.phase() != expected) throw new IOException("Unexpected profile phase");
        var updated = new Transition(target, next);
        writeTransition(player, updated);
        return updated;
    }

    public Optional<Transition> transition(UUID player) throws IOException {
        Path path = playerDirectory(player).resolve("transition.bin");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try (var input = new DataInputStream(new ByteArrayInputStream(readSnapshot(path)))) {
            if (input.readInt() != FORMAT) throw new IOException("Unsupported profile journal format");
            var generation = new Generation(new UUID(input.readLong(), input.readLong()), input.readLong());
            int phase = input.readUnsignedByte();
            if (phase >= Phase.values().length || input.read() != -1) throw new IOException("Invalid profile journal phase");
            return Optional.of(new Transition(generation, Phase.values()[phase]));
        } catch (IllegalArgumentException error) { throw new IOException("Invalid profile journal", error); }
    }

    /** Durable same-round suspension: a stop/reconnect while held in the lobby cannot erase survival state. */
    public void suspend(UUID player, Generation generation, byte[] snapshot) throws IOException {
        requireSnapshot(snapshot);
        atomicWrite(playerDirectory(player).resolve("suspended-" + generation.roundId() + ".bin"), envelope(snapshot));
    }

    /** Immutable evidence of the first exit; later reconnects or sanctions cannot replace the equipment. */
    public void sealParticipationExit(UUID player, Generation generation, byte[] snapshot) throws IOException {
        requireSnapshot(snapshot);
        var directory = playerDirectory(player).resolve("archives");
        Files.createDirectories(directory);
        requireDirectory(directory);
        var path = directory.resolve("exit-" + generation.roundId() + ".bin");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) readSnapshot(path);
        else atomicWrite(path, envelope(snapshot));
    }

    public Optional<byte[]> suspended(UUID player, Generation generation) throws IOException {
        Path path = playerDirectory(player).resolve("suspended-" + generation.roundId() + ".bin");
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? Optional.of(readSnapshot(path)) : Optional.empty();
    }

    /** A restarted conversion may reuse only the durable target snapshot, never reset an uncertain player again. */
    public Recovery recovery(UUID player, Generation target) throws IOException {
        var current = transition(player).orElseThrow(() -> new IOException("Missing profile transition"));
        if (!current.target().equals(target)) throw new IOException("Stale profile recovery generation");
        var snapshot = suspended(player, target).orElseThrow(() ->
                new IOException("Profile transition " + current.phase() + " has no durable target snapshot; retain quarantine"));
        return new Recovery(current, snapshot);
    }

    public byte[] archived(UUID player, Optional<Generation> generation) throws IOException {
        String name = generation.map(g -> g.sequence() + "-" + g.roundId()).orElse("pre-hardrush");
        return readSnapshot(playerDirectory(player).resolve("archives").resolve(name + ".bin"));
    }

    private void writeTransition(UUID player, Transition transition) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(FORMAT);
            output.writeLong(transition.target().roundId().getMostSignificantBits());
            output.writeLong(transition.target().roundId().getLeastSignificantBits());
            output.writeLong(transition.target().sequence());
            output.writeByte(transition.phase().ordinal());
        }
        atomicWrite(playerDirectory(player).resolve("transition.bin"), envelope(bytes.toByteArray()));
    }

    private Path playerDirectory(UUID player) throws IOException {
        Path directory = root.resolve(player.toString());
        Files.createDirectories(directory);
        requireDirectory(directory);
        return directory;
    }

    private static void requireDirectory(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Profile path must be an owned directory: " + path);
        }
    }

    private static byte[] envelope(byte[] payload) throws IOException {
        requireSnapshot(payload);
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(payload.length);
            output.write(digest(payload));
            output.write(payload);
        }
        return bytes.toByteArray();
    }

    private static byte[] readSnapshot(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe profile file: " + path);
        }
        if (Files.size(path) > MAX_SNAPSHOT + 36) throw new IOException("Profile file exceeds limit");
        try (var input = new DataInputStream(Files.newInputStream(path))) {
            int length = input.readInt();
            if (length < 1 || length > MAX_SNAPSHOT) throw new IOException("Invalid profile size");
            byte[] checksum = input.readNBytes(32);
            byte[] payload = input.readNBytes(length);
            if (payload.length != length || input.read() != -1 || !Arrays.equals(checksum, digest(payload))) {
                throw new IOException("Profile checksum mismatch");
            }
            return payload;
        }
    }

    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void requireSnapshot(byte[] snapshot) throws IOException {
        if (snapshot == null || snapshot.length == 0 || snapshot.length > MAX_SNAPSHOT) throw new IOException("Invalid snapshot size");
    }

    private static void atomicWrite(Path path, byte[] bytes) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Refusing profile symlink: " + path);
        Path temporary = path.resolveSibling(path.getFileName() + ".pending-" + UUID.randomUUID());
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
