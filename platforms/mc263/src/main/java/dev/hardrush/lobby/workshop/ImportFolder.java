package dev.hardrush.lobby.workshop;

import dev.hardrush.world.RoundWorldPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code hardrush/workshop/import/} (design 19): what the server owner put there to start drafts from. A directory is a
 * save; a {@code .nbt}, {@code .schem} or {@code .litematic} file is a building file. Symbolic links are ignored, and
 * entries are looked up again by name whenever they are used.
 */
final class ImportFolder {
    static final String DIRECTORY = "import";
    private static final List<String> STRUCTURES = List.of(".nbt", ".schem", ".litematic");

    enum Kind { SAVE, STRUCTURE }
    record Entry(String name, Path path, Kind kind) {}

    private final Path root;

    ImportFolder(Path worldRoot) { root = worldRoot.toAbsolutePath().normalize().resolve("hardrush/workshop").resolve(DIRECTORY); }

    Path root() { return root; }

    /** Every usable entry, saves first, then by name; empty when the folder does not exist. */
    List<Entry> list() {
        var out = new ArrayList<Entry>();
        try {
            RoundWorldPaths.checkAncestors(root, true);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return out;
            try (var children = Files.list(root)) {
                for (var child : children.toList()) entry(child).ifPresent(out::add);
            }
        } catch (IOException | RuntimeException e) {
            return out;
        }
        out.sort(Comparator.comparing(Entry::kind).thenComparing(Entry::name));
        return out;
    }

    /** The entry called {@code name}, checked again: names come back from menu buttons. */
    Optional<Entry> find(String name) {
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) return Optional.empty();
        try {
            RoundWorldPaths.checkAncestors(root, true);
            return entry(root.resolve(name));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<Entry> entry(Path path) {
        var name = path.getFileName().toString();
        if (name.startsWith(".")) return Optional.empty();
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return Optional.of(new Entry(name, path, Kind.SAVE));
        var lower = name.toLowerCase(Locale.ROOT);
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && STRUCTURES.stream().anyMatch(lower::endsWith))
            return Optional.of(new Entry(name, path, Kind.STRUCTURE));
        return Optional.empty();
    }
}
