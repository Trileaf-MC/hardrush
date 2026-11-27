package dev.hardrush.lobby.pack;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BundledPacksTest {
    /** Every indexed pack is in the jar under the name its own manifest gives, and the default camp is among them. */
    @Test void indexMatchesTheShippedPacks() throws Exception {
        var entries = BundledPacks.index();
        assertTrue(entries.stream().anyMatch(e -> e.resource().equals("default.hrlobby")), "the default camp is offered too");
        var names = new HashSet<String>();
        for (var entry : entries) {
            assertTrue(names.add(entry.fileName()), "duplicate " + entry.fileName());
            try (var in = BundledPacks.class.getResourceAsStream(BundledPacks.ROOT + entry.resource())) {
                assertNotNull(in, entry.resource());
                // pack.json is the first entry; the full reader needs the game bootstrapped.
                var zip = new java.util.zip.ZipInputStream(in);
                assertEquals("pack.json", zip.getNextEntry().getName(), entry.resource());
                var manifest = com.google.gson.JsonParser.parseString(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals(manifest.get("id").getAsString() + "-v" + manifest.get("version").getAsInt() + ".hrlobby",
                        entry.fileName(), entry.resource());
            }
        }
    }

    @Test void releaseAddsMissingPacksAndKeepsTheOwnersFiles(@TempDir Path library) throws Exception {
        var entries = BundledPacks.index();
        var first = entries.getFirst().fileName();
        Files.writeString(library.resolve(first), "the owner's own file", StandardCharsets.UTF_8);

        var added = BundledPacks.release(library);

        assertEquals(entries.stream().map(BundledPacks.Entry::fileName).filter(n -> !n.equals(first)).toList(), added);
        assertEquals("the owner's own file", Files.readString(library.resolve(first), StandardCharsets.UTF_8));
        for (var entry : entries) assertTrue(Files.isRegularFile(library.resolve(entry.fileName())), entry.fileName());
        assertEquals(List.of(), BundledPacks.release(library), "a second start adds nothing");
    }
}
