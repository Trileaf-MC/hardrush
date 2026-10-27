package dev.hardrush.record;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

/** Canonical statistical set, independent of registration order and a player's criterion progress. */
public record AdvancementCatalog(Set<String> ids, String fingerprint) {
    public record Entry(String id, boolean hasDisplay, boolean root) {}

    public AdvancementCatalog {
        ids = Set.copyOf(ids);
    }

    public static AdvancementCatalog from(Collection<Entry> entries) {
        var ids = new TreeSet<String>();
        for (var entry : entries) {
            var separator = entry.id().indexOf(':');
            var path = separator < 0 ? entry.id() : entry.id().substring(separator + 1);
            if (entry.hasDisplay() && !entry.root() && !path.startsWith("recipes/")
                    && !path.equals("root") && !path.endsWith("/root")) ids.add(entry.id());
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("hardrush-advancement-set-v1\n".getBytes(StandardCharsets.UTF_8));
            for (var id : ids) {
                digest.update(id.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return new AdvancementCatalog(ids, HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
