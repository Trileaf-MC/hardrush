package dev.hardrush.p1.player;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileJournalTest {
    @TempDir Path directory;

    @Test void offlinePlayerSkipsThreeRoundsWithoutReusingOrOverwritingOldState() throws Exception {
        var player = UUID.randomUUID();
        var first = generation(1);
        var fourth = generation(4);
        var old = bytes("inventory, ender chest, advancement dates, recipes, position, effects: round 1");
        var journal = new ProfileJournal(directory);
        journal.prepare(player, Optional.of(first), fourth, old);
        assertArrayEquals(old, journal.archived(player, Optional.of(first)));
        assertEquals(ProfileJournal.Phase.PREPARED, journal.transition(player).orElseThrow().phase());
        journal.advance(player, fourth, ProfileJournal.Phase.PREPARED, ProfileJournal.Phase.APPLIED);
        journal.advance(player, fourth, ProfileJournal.Phase.APPLIED, ProfileJournal.Phase.VERIFIED);
        var reopened = new ProfileJournal(directory);
        assertEquals(new ProfileJournal.Transition(fourth, ProfileJournal.Phase.VERIFIED), reopened.transition(player).orElseThrow());
        reopened.prepare(player, Optional.of(first), fourth, bytes("a repeated login saw an empty inventory"));
        assertArrayEquals(old, reopened.archived(player, Optional.of(first)), "Retry preserves the first sealed original");
    }

    @Test void everyPersistedBoundaryRestartsAndReplaysIdempotently() throws Exception {
        var player = UUID.randomUUID();
        var target = generation(1);
        new ProfileJournal(directory).prepare(player, Optional.empty(), target, bytes("pre-server survival data"));
        var applied = new ProfileJournal(directory);
        assertEquals(ProfileJournal.Phase.PREPARED, applied.transition(player).orElseThrow().phase());
        applied.advance(player, target, ProfileJournal.Phase.PREPARED, ProfileJournal.Phase.APPLIED);
        var verified = new ProfileJournal(directory);
        assertEquals(ProfileJournal.Phase.APPLIED, verified.transition(player).orElseThrow().phase());
        verified.advance(player, target, ProfileJournal.Phase.APPLIED, ProfileJournal.Phase.VERIFIED);
        assertEquals(ProfileJournal.Phase.VERIFIED,
                verified.advance(player, target, ProfileJournal.Phase.PREPARED, ProfileJournal.Phase.APPLIED).phase());
        assertEquals(ProfileJournal.Phase.VERIFIED,
                verified.advance(player, target, ProfileJournal.Phase.APPLIED, ProfileJournal.Phase.VERIFIED).phase());
    }

    @Test void oldWorkerCallbackCannotChangeNewGeneration() throws Exception {
        var player = UUID.randomUUID();
        var first = generation(1);
        var second = generation(2);
        var journal = new ProfileJournal(directory);
        journal.prepare(player, Optional.empty(), first, bytes("original"));
        journal.prepare(player, Optional.of(first), second, bytes("round 1"));
        assertThrows(IOException.class,
                () -> journal.advance(player, first, ProfileJournal.Phase.PREPARED, ProfileJournal.Phase.APPLIED));
        assertEquals(second, journal.transition(player).orElseThrow().target());
        assertThrows(IOException.class, () -> journal.prepare(player, Optional.empty(), first, bytes("late first round")));
        assertThrows(IOException.class, () -> journal.prepare(player, Optional.of(first), generation(2), bytes("UUID swap")));
    }

    @Test void sameRoundSuspensionSurvivesStopWithoutTouchingArchives() throws Exception {
        var player = UUID.randomUUID();
        var first = generation(1);
        var second = generation(2);
        var journal = new ProfileJournal(directory);
        journal.suspend(player, first, bytes("same-round location, health, inventory and progress"));
        journal.suspend(player, second, bytes("new-round empty state"));
        var reopened = new ProfileJournal(directory);
        assertArrayEquals(bytes("same-round location, health, inventory and progress"), reopened.suspended(player, first).orElseThrow());
        assertArrayEquals(bytes("new-round empty state"), reopened.suspended(player, second).orElseThrow());
        assertTrue(reopened.suspended(player, generation(3)).isEmpty());
    }

    @Test void damagedArchiveFailsClosedAndNeverAdvancesOrReplacesOriginal() throws Exception {
        var player = UUID.randomUUID();
        var first = generation(1);
        var second = generation(2);
        var journal = new ProfileJournal(directory);
        journal.prepare(player, Optional.of(first), second, bytes("original"));
        var archive = directory.resolve(player.toString()).resolve("archives").resolve("1-" + first.roundId() + ".bin");
        byte[] corrupted = Files.readAllBytes(archive);
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(archive, corrupted);
        var third = generation(3);
        assertThrows(IOException.class, () -> journal.prepare(player, Optional.of(first), third, bytes("empty")));
        assertArrayEquals(corrupted, Files.readAllBytes(archive));
        assertEquals(second, journal.transition(player).orElseThrow().target());
    }

    @Test void playerDirectorySymlinkCannotArchiveIntoAnotherDirectory() throws Exception {
        var player = UUID.randomUUID();
        Path outside = Files.createTempDirectory("hardrush-profile-outside");
        try {
            Files.createSymbolicLink(directory.resolve(player.toString()), outside);
            var journal = new ProfileJournal(directory);
            assertThrows(IOException.class, () -> journal.prepare(player, Optional.empty(), generation(1), bytes("old")));
            try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
        } finally { Files.deleteIfExists(outside); }
    }

    private static ProfileJournal.Generation generation(long sequence) {
        return new ProfileJournal.Generation(UUID.randomUUID(), sequence);
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
