package dev.hardrush.player;

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

    @Test void participationExitEvidenceSurvivesReconnectAndCannotBeOverwrittenByAnEmptyBody() throws Exception {
        var player = UUID.randomUUID();
        var target = generation(1);
        var journal = new ProfileJournal(directory);
        journal.sealParticipationExit(player, target, bytes("competition inventory"));
        var path = directory.resolve(player.toString()).resolve("archives/exit-" + target.roundId() + ".bin");
        var first = Files.readAllBytes(path);
        new ProfileJournal(directory).sealParticipationExit(player, target, bytes("empty spectator"));
        assertArrayEquals(first, Files.readAllBytes(path));
        var damaged = first.clone(); damaged[damaged.length - 1] ^= 1; Files.write(path, damaged);
        assertThrows(IOException.class, () -> journal.sealParticipationExit(player, target, bytes("replacement")));
        assertArrayEquals(damaged, Files.readAllBytes(path), "Corruption is retained for diagnosis");
    }

    @Test void celebrationRosterIsImmutableAndPlayerStateSurvivesReopeningPerRound() throws Exception {
        var player = UUID.randomUUID();
        var first = generation(1);
        var second = generation(2);
        var journal = new ProfileJournal(directory);
        assertArrayEquals(bytes("A,B in End"), journal.celebrationRoster(first.roundId(), bytes("A,B in End")));
        assertArrayEquals(bytes("A,B in End"), journal.celebrationRoster(first.roundId(), bytes("late C")));
        journal.celebration(player, first, bytes("original + rockets=15 + returned=false"));
        var reopened = new ProfileJournal(directory);
        assertArrayEquals(bytes("original + rockets=15 + returned=false"), reopened.celebration(player, first).orElseThrow());
        assertTrue(reopened.celebration(player, second).isEmpty());
        reopened.celebration(player, first, bytes("original + returned=true"));
        assertArrayEquals(bytes("original + returned=true"), new ProfileJournal(directory).celebration(player, first).orElseThrow());
        var path = directory.resolve(player.toString()).resolve("celebration-" + first.roundId() + ".bin");
        var damaged = Files.readAllBytes(path);
        damaged[damaged.length - 1] ^= 1;
        Files.write(path, damaged);
        assertThrows(IOException.class, () -> reopened.celebration(player, first));
    }

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

    @Test void recoveryRequiresTheExactGenerationAndACompleteTargetSnapshot() throws Exception {
        var player = UUID.randomUUID();
        var target = generation(1);
        var journal = new ProfileJournal(directory);
        journal.prepare(player, Optional.empty(), target, bytes("sealed original"));
        assertThrows(IOException.class, () -> journal.recovery(player, target));
        assertEquals(ProfileJournal.Phase.PREPARED, journal.transition(player).orElseThrow().phase());
        journal.suspend(player, target, bytes("durable reset"));
        assertThrows(IOException.class, () -> journal.recovery(player, generation(1)));
        var recovery = journal.recovery(player, target);
        assertEquals(ProfileJournal.Phase.PREPARED, recovery.transition().phase());
        assertArrayEquals(bytes("durable reset"), recovery.snapshot());
        recovery.snapshot()[0] ^= 1;
        assertArrayEquals(bytes("durable reset"), recovery.snapshot(), "Recovery payload cannot be changed by a caller");
    }

    @Test void corruptedAppliedSuspensionCannotRecoverOrAdvance() throws Exception {
        var player = UUID.randomUUID();
        var target = generation(1);
        var journal = new ProfileJournal(directory);
        journal.prepare(player, Optional.empty(), target, bytes("sealed original"));
        journal.suspend(player, target, bytes("durable reset"));
        journal.advance(player, target, ProfileJournal.Phase.PREPARED, ProfileJournal.Phase.APPLIED);
        var path = directory.resolve(player.toString()).resolve("suspended-" + target.roundId() + ".bin");
        var damaged = Files.readAllBytes(path);
        damaged[damaged.length - 1] ^= 1;
        Files.write(path, damaged);
        assertThrows(IOException.class, () -> new ProfileJournal(directory).recovery(player, target));
        assertEquals(ProfileJournal.Phase.APPLIED, journal.transition(player).orElseThrow().phase());
        assertArrayEquals(damaged, Files.readAllBytes(path));
        assertArrayEquals(bytes("sealed original"), journal.archived(player, Optional.empty()));
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
