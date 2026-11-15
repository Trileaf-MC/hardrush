package dev.hardrush.lobby.pack;

import dev.hardrush.world.LobbyDimension;
import dev.hardrush.world.RoundWorldPaths;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The installed lobby's files under the world root (design 10.1): {@code hardrush/lobby/packs/} (the pack library),
 * {@code installed/<seq>.hrlobby} (copies of the current and previous install) and {@code current.json}. Every write
 * goes to a temporary file in the same directory and is moved into place atomically. IO only: call off the server
 * thread.
 */
public final class LobbyStorage {
    private static final Pattern INSTALLED = Pattern.compile("([1-9][0-9]{0,8})\\.hrlobby");

    private final Path root;

    public LobbyStorage(Path root) { this.root = root.toAbsolutePath().normalize(); }
    /** The production location, {@code <world>/hardrush/lobby}. */
    public static LobbyStorage forWorld(Path worldRoot) { return new LobbyStorage(worldRoot.resolve("hardrush/lobby")); }

    public Path packs() { return root.resolve("packs"); }
    public Path installed(int seq) { return root.resolve("installed").resolve(seq + dev.hardrush.core.lobby.plan.PackManifest.EXTENSION); }
    public Path current() { return root.resolve("current.json"); }

    public Optional<LobbyInstallation> readCurrent() throws IOException, PackException {
        var file = current();
        RoundWorldPaths.checkAncestors(file, false);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        return Optional.of(LobbyPlanJson.installationFromJson(LobbyPlanJson.parse(Files.readString(file, StandardCharsets.UTF_8))));
    }

    /**
     * Copies the pack to {@code installed/<seq>.hrlobby}, then records the install in {@code current.json}; only after
     * that do older copies go, keeping {@code previousSeq}'s for rolling back. Returns the installation with the copy's
     * SHA-256 filled in.
     */
    public LobbyInstallation commit(LobbyInstall.Source source, LobbyInstallation installation, OptionalInt previousSeq) throws IOException {
        var copy = installed(installation.seq());
        try (var in = source.open()) { writeAtomically(copy, in); }
        var p = installation.pack();
        var signed = new LobbyInstallation(installation.seq(), installation.dimension(),
                new LobbyInstallation.Pack(p.id(), p.name(), p.version(), p.authors(), PackFile.sha256(copy)), installation.plan(), installation.state());
        var json = LobbyPlanJson.write(LobbyPlanJson.installationToJson(signed)).getBytes(StandardCharsets.UTF_8);
        writeAtomically(current(), new java.io.ByteArrayInputStream(json));
        var keep = new HashSet<Integer>();
        keep.add(installation.seq());
        previousSeq.ifPresent(keep::add);
        // Committed by now: a copy that will not go away only takes space, so it must not fail the install.
        try {
            for (var entry : installedCopies().entrySet()) if (!keep.contains(entry.getKey())) Files.deleteIfExists(entry.getValue());
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger("hardrush-lobby").warn("清理旧的已安装大厅包副本失败", e);
        }
        return signed;
    }

    /** {@code installed/} copies by sequence. */
    public SortedMap<Integer, Path> installedCopies() throws IOException {
        var directory = root.resolve("installed");
        var copies = new TreeMap<Integer, Path>();
        RoundWorldPaths.checkAncestors(directory, true);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return copies;
        try (var files = Files.list(directory)) {
            for (var file : files.toList()) {
                var m = INSTALLED.matcher(file.getFileName().toString());
                if (m.matches() && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) copies.put(Integer.parseInt(m.group(1)), file);
            }
        }
        return copies;
    }

    /**
     * Startup cleanup (design 10.2): deletes every {@code dimensions/hardrush/lobby_<n>} except {@code keep} and the
     * {@code loaded} ones, the old flat camp's {@code dimensions/hardrush/lobby} and its {@code hardrush/lobby-layout.txt}.
     * Returns the highest lobby sequence seen anywhere, so a new install never reuses a number. Workshop directories are
     * the workshop's ({@code WorkshopStorage.cleanup}).
     */
    public int cleanup(Path worldRoot, OptionalInt keep, Set<String> loaded) throws IOException {
        worldRoot = worldRoot.toAbsolutePath().normalize();
        int highest = keep.orElse(0);
        for (var seq : installedCopies().keySet()) highest = Math.max(highest, seq);
        var dimensions = LobbyDimension.namespaceFolder(worldRoot);
        RoundWorldPaths.checkAncestors(dimensions, true);
        if (Files.isDirectory(dimensions, LinkOption.NOFOLLOW_LINKS)) {
            try (var children = Files.list(dimensions)) {
                for (var child : children.toList()) {
                    var name = child.getFileName().toString();
                    var seq = LobbyDimension.seq(name);
                    if (seq.isPresent()) {
                        highest = Math.max(highest, seq.getAsInt());
                        if (keep.isPresent() && keep.getAsInt() == seq.getAsInt() || loaded.contains(name)) continue;
                        deleteTree(child);
                    } else if (name.equals("lobby") && !loaded.contains(name)) deleteTree(child);
                }
            }
        }
        var legacyManifest = worldRoot.resolve("hardrush/lobby-layout.txt");
        RoundWorldPaths.checkAncestors(legacyManifest.getParent(), true);
        Files.deleteIfExists(legacyManifest);
        return highest;
    }

    /** Deletes a dimension directory left by an earlier run; refuses links rather than following them. */
    public static void deleteTree(Path directory) throws IOException {
        RoundWorldPaths.checkAncestors(directory, true);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void writeAtomically(Path target, InputStream content) throws IOException {
        RoundWorldPaths.checkAncestors(target, false);
        var directory = target.getParent();
        Files.createDirectories(directory);
        var temporary = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                var out = Channels.newOutputStream(channel);
                content.transferTo(out);
                out.flush();
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }
}
