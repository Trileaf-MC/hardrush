package dev.hardrush.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

final class SafeDatabasePath {
    private SafeDatabasePath() {}

    static Path prepare(Path requested) throws IOException {
        var path = requested.toAbsolutePath().normalize();
        if (path.getFileName() == null || path.toString().indexOf('?') >= 0) {
            throw new IOException("Database path must name a file without JDBC query syntax");
        }
        validateParents(path, true);
        validateFile(path);
        validateFile(sidecar(path, "-wal"));
        validateFile(sidecar(path, "-shm"));
        validateFile(sidecar(path, "-journal"));
        return path;
    }

    static void validate(Path path) throws IOException {
        validateParents(path, false);
        validateFile(path);
        validateFile(sidecar(path, "-wal"));
        validateFile(sidecar(path, "-shm"));
        validateFile(sidecar(path, "-journal"));
    }

    private static Path sidecar(Path path, String suffix) {
        return path.resolveSibling(path.getFileName() + suffix);
    }

    private static void validateParents(Path path, boolean create) throws IOException {
        var current = path.getRoot();
        for (var component : path.getParent()) {
            current = current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS) && create) Files.createDirectory(current);
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
                throw new IOException("Database parent is missing, is not a directory, or is a symbolic link: " + current);
            }
        }
    }

    private static void validateFile(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Database or SQLite sidecar is not a regular owned path: " + path);
        }
    }
}
