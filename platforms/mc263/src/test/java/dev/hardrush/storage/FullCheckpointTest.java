package dev.hardrush.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.persistence.*;
import dev.hardrush.world.RoundWorldPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FullCheckpointTest {
    @TempDir Path root;
    UUID round = UUID.randomUUID();
    UUID player = UUID.randomUUID();
    Path database;
    FullCheckpoint checkpoints;
    RoundSnapshot initial;

    @BeforeEach void prepare() throws Exception {
        root = root.toRealPath();
        database = root.resolve(dev.hardrush.storage.RoundStoragePaths.DATABASE);
        checkpoints = new FullCheckpoint(root);
        initial = snapshot(round, 0, false, null);
        for (String dimension : java.util.List.of("overworld", "nether", "end")) {
            Path file = RoundWorldPaths.world(root, round, dimension).resolve("region/r.0.0.mca");
            Files.createDirectories(file.getParent()); Files.writeString(file, "world-before-" + dimension);
        }
        Path manifest = RoundWorldPaths.manifest(root, round);
        Files.createDirectories(manifest.getParent()); Files.writeString(manifest, "owned formal manifest");
        Files.createDirectories(root.resolve("hardrush/profiles/" + player));
        Files.writeString(root.resolve("hardrush/profiles/" + player + "/transition.bin"), "profile-before");
        Files.createDirectories(root.resolve("players/data"));
        Files.writeString(root.resolve("players/data/" + player + ".dat"), "inventory-before");
        try (var store = new SqliteRoundStore(database)) { commit(store, initial, -1); }
    }

    @Test void completeImageRollsWorldPlayersJournalAndWalBackTogetherAndKeepsCrashOriginals() throws Exception {
        Path image = save();
        Files.writeString(world(), "world-after");
        Files.writeString(root.resolve("players/data/" + player + ".dat"), "inventory-after");
        Files.writeString(root.resolve("hardrush/profiles/" + player + "/transition.bin"), "profile-after");
        try (var store = new SqliteRoundStore(database)) {
            commit(store, snapshot(round, 1, false, null), 0);
            // Keep a live WAL reader attached across restore eligibility inspection, then close before swaps.
            assertEquals(1, SqliteRoundStore.inspectLatest(database).orElseThrow().version());
        }
        var result = checkpoints.restore();
        assertTrue(result.restored(), result.message());
        assertEquals("world-before-overworld", Files.readString(world()));
        assertEquals("inventory-before", Files.readString(root.resolve("players/data/" + player + ".dat")));
        assertEquals("profile-before", Files.readString(root.resolve("hardrush/profiles/" + player + "/transition.bin")));
        assertEquals(initial, SqliteRoundStore.inspectLatest(database).orElseThrow());
        try (var files = Files.walk(root.resolve("hardrush/full-checkpoints/restores"))) {
            assertTrue(files.filter(Files::isRegularFile).anyMatch(p -> {
                try { return Files.readString(p).equals("inventory-after"); } catch (Exception ignored) { return false; }
            }));
        }
        assertTrue(Files.isRegularFile(image.resolve("complete.properties")));
        assertFalse(Files.exists(root.resolve("hardrush/full-checkpoints/restoring")));
        assertTrue(checkpoints.restore().restored(), "A second crash before first login reuses the same complete point");
    }

    @Test void interruptedRestoreReplaysWithoutInspectingPartiallyReplacedLiveDatabase() throws Exception {
        Path image = save();
        Files.writeString(root.resolve("hardrush/full-checkpoints/restoring"), image.getFileName() + "\n");
        Files.writeString(database, "half replaced or corrupt database");
        Files.writeString(world(), "half restored world");
        var result = checkpoints.restore();
        assertTrue(result.restored(), result.message());
        assertEquals(initial, SqliteRoundStore.inspectLatest(database).orElseThrow());
        assertEquals("world-before-overworld", Files.readString(world()));
    }

    @Test void corruptImageNeverChangesAnyLiveWorldOrDatabase() throws Exception {
        Path image = save();
        Path savedWorld = image.resolve("data").resolve(root.relativize(world()));
        Files.writeString(savedWorld, "corrupt");
        Files.writeString(world(), "live-world");
        byte[] original = Files.readAllBytes(database);
        var result = checkpoints.restore();
        assertFalse(result.restored());
        assertTrue(result.message().contains("损坏"), result.message());
        assertEquals("live-world", Files.readString(world()));
        assertArrayEquals(original, Files.readAllBytes(database));
        assertFalse(Files.exists(root.resolve("hardrush/full-checkpoints/restoring")));
    }

    @Test void missingFilesAndSymlinksFailClosed() throws Exception {
        Path image = save();
        Path savedPlayer = image.resolve("data/players/data/" + player + ".dat");
        Files.delete(savedPlayer);
        assertFalse(checkpoints.restore().restored());
        Files.createSymbolicLink(savedPlayer, root.resolve("players/data/" + player + ".dat"));
        assertFalse(checkpoints.restore().restored());
        assertEquals("inventory-before", Files.readString(root.resolve("players/data/" + player + ".dat")));
    }

    @Test void onlineSearchSkipsDamagedNewestPointWithoutChangingLiveFiles() throws Exception {
        Path older = save();
        try (var store = new SqliteRoundStore(database)) { commit(store, snapshot(round, 1, false, null), 0); }
        initial = snapshot(round, 1, false, null);
        Path newer = save();
        Files.writeString(newer.resolve("data").resolve(root.relativize(world())), "damaged newest");
        Files.writeString(world(), "live-state");
        var found = checkpoints.search(initial, java.util.Set.of());
        assertEquals(1, found.candidates().size());
        assertEquals(UUID.fromString(older.getFileName().toString()), found.candidates().getFirst().id());
        assertEquals(1, found.failures().size());
        assertEquals("live-state", Files.readString(world()));
        assertEquals(initial, SqliteRoundStore.inspectLatest(database).orElseThrow());
        checkpoints.restoreCandidate(found.candidates().getFirst(), initial);
        assertEquals(0, SqliteRoundStore.inspectLatest(database).orElseThrow().version());
    }

    @Test void onlineConfirmationRechecksImmutableImageAndCannotCrossRoundsOrUndoSanctions() throws Exception {
        Path image = save();
        var point = checkpoints.search(initial, java.util.Set.of()).candidates().getFirst();
        assertTrue(checkpoints.search(initial, java.util.Set.of(point.id())).candidates().isEmpty());
        assertTrue(checkpoints.search(snapshot(UUID.randomUUID(), 1, false, null), java.util.Set.of()).candidates().isEmpty());
        Files.writeString(image.resolve("data").resolve(root.relativize(world())), "changed after confirmation");
        assertThrows(Exception.class, () -> checkpoints.restoreCandidate(point, initial));
        assertEquals("world-before-overworld", Files.readString(world()));
        assertFalse(Files.exists(root.resolve("hardrush/full-checkpoints/restoring")));
    }

    @Test void closingRecoveryRetiresInterruptedTransactionSoRestartCannotRewindTheNewRound() throws Exception {
        Path image = save();
        Path marker = root.resolve("hardrush/full-checkpoints/restoring");
        Files.writeString(marker, image.getFileName().toString());
        checkpoints.abandonRecoveryTransaction();
        assertFalse(Files.exists(marker));
        try (var files = Files.list(marker.getParent())) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("abandoned-")));
        }
    }

    @Test void lostCommitCallbackCannotLetOnlineRollbackEraseADurableTerminal() throws Exception {
        save();
        var point = checkpoints.search(initial, java.util.Set.of()).candidates().getFirst();
        var victory = new RoundResult(RoundResult.Kind.COMPLETED, player, "durable terminal before callback failure");
        try (var store = new SqliteRoundStore(database)) { commit(store, snapshot(round, 1, false, victory), 0); }
        assertThrows(Exception.class, () -> checkpoints.restoreCandidate(point, initial));
        assertTrue(checkpoints.search(initial, java.util.Set.of()).candidates().isEmpty());
        assertEquals(victory, SqliteRoundStore.inspectLatest(database).orElseThrow().result());
    }

    @Test void cleanStopDoesNotRollBackOrDependOnCheckpointIntegrity() throws Exception {
        Path image = save();
        Files.delete(image.resolve("complete.properties"));
        try (var store = new SqliteRoundStore(database)) { commit(store, snapshot(round, 1, true, null), 0); }
        Files.writeString(world(), "latest-clean-world");
        var result = checkpoints.restore();
        assertFalse(result.restored()); assertEquals("", result.message());
        assertEquals("latest-clean-world", Files.readString(world()));
        assertTrue(SqliteRoundStore.inspectLatest(database).orElseThrow().cleanShutdown());
    }

    @Test void previousRoundAndCommittedVictoryCannotBeRewound() throws Exception {
        save();
        var victory = new RoundResult(RoundResult.Kind.COMPLETED, player, "verified dragon");
        try (var store = new SqliteRoundStore(database)) { commit(store, snapshot(round, 1, false, victory), 0); }
        assertFalse(checkpoints.restore().restored());
        assertEquals(victory, SqliteRoundStore.inspectLatest(database).orElseThrow().result());
        UUID next = UUID.randomUUID();
        try (var store = new SqliteRoundStore(database)) { commit(store, snapshot(next, 0, false, null), -1); }
        var result = checkpoints.restore();
        assertFalse(result.restored()); assertTrue(result.message().contains("跨轮"), result.message());
        assertEquals(next, SqliteRoundStore.inspectLatest(database).orElseThrow().roundId());
    }

    @Test void incompleteNewCopyKeepsPreviousCompletePointAndThreeCopiesRetainOnlyTwo() throws Exception {
        Path first = save();
        Path incomplete = checkpoints.begin();
        assertThrows(Exception.class, () -> checkpoints.publish(incomplete, initial, System.nanoTime() - 1));
        assertEquals(first.getFileName().toString(), Files.readString(root.resolve("hardrush/full-checkpoints/latest")).strip());
        Path second = save(); Path third = save();
        assertFalse(Files.exists(first)); assertFalse(Files.exists(incomplete));
        assertTrue(Files.exists(second)); assertTrue(Files.exists(third));
    }

    @Test void unchangedFilesComeFromThePreviousImageAsIndependentCopiesAndChangesAreCopiedAgain() throws Exception {
        // Fresh writes are never trusted: their timestamp could survive a same-size write right after the barrier.
        Path first = save();
        var fresh = stage();
        assertEquals(0, fresh.reused());
        checkpoints.discardIncomplete(fresh.pending());
        long old = System.currentTimeMillis() - 3_600_000;
        try (var files = java.util.stream.Stream.concat(Files.walk(root.resolve("dimensions")), Files.walk(root.resolve("hardrush/round-worlds")))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(old));
        }
        Path trusted = save();
        var reuse = stage();
        assertEquals(4, reuse.reused(), "three dimensions and the world manifest are unchanged");
        try (var store = new SqliteRoundStore(database)) { store.exportCheckpoint(checkpoints.database(reuse.pending())); }
        checkpoints.complete(reuse, Map.of());
        Path reused = reuse.pending();
        Path saved = reused.resolve("data").resolve(root.relativize(world()));
        assertEquals("world-before-overworld", Files.readString(saved));
        assertFalse(Files.isSameFile(saved, trusted.resolve("data").resolve(root.relativize(world()))), "images never share a writable file");
        assertFalse(Files.exists(first), "only two images are retained");

        // Same size, older but different timestamp: a real change the size alone cannot reveal.
        Files.writeString(world(), "world-change-overworld");
        Files.setLastModifiedTime(world(), java.nio.file.attribute.FileTime.fromMillis(old - 60_000));
        var changed = stage();
        assertEquals(3, changed.reused());
        try (var store = new SqliteRoundStore(database)) { store.exportCheckpoint(checkpoints.database(changed.pending())); }
        checkpoints.complete(changed, Map.of());
        Files.writeString(world(), "world-after-crash");
        try (var store = new SqliteRoundStore(database)) { commit(store, snapshot(round, 1, false, null), 0); }
        assertTrue(checkpoints.restore().restored());
        assertEquals("world-change-overworld", Files.readString(world()));
    }

    @Test void damagedPreviousImageFailsTheNewImageAndAFullCopyRecovers() throws Exception {
        save();
        long old = System.currentTimeMillis() - 3_600_000;
        Files.setLastModifiedTime(world(), java.nio.file.attribute.FileTime.fromMillis(old));
        Path latest = save();
        Files.writeString(latest.resolve("data").resolve(root.relativize(world())), "truncated");
        var damaged = stage();
        assertEquals(1, damaged.reused());
        try (var store = new SqliteRoundStore(database)) { store.exportCheckpoint(checkpoints.database(damaged.pending())); }
        assertThrows(java.io.IOException.class, () -> checkpoints.complete(damaged, Map.of()));
        checkpoints.discardIncomplete(damaged.pending());
        assertEquals(latest.getFileName().toString(), Files.readString(root.resolve("hardrush/full-checkpoints/latest")).strip());
        var full = checkpoints.stage(checkpoints.begin(), initial, Long.MAX_VALUE, false);
        assertEquals(0, full.reused());
        try (var store = new SqliteRoundStore(database)) { store.exportCheckpoint(checkpoints.database(full.pending())); }
        checkpoints.complete(full, Map.of());
        assertEquals("world-before-overworld", Files.readString(full.pending().resolve("data").resolve(root.relativize(world()))));
    }

    @Test void checkpointCannotRestoreANewerNonPlayableIdentityAfterItWasSaved() throws Exception {
        save();
        // Exercise an optional sanction role when installed; the base adapter also retains pending transitions.
        var retainedRole = java.util.Arrays.stream(ParticipantRole.values()).filter(role -> role.name().equals("DISQUALIFIED"))
                .findFirst().orElse(ParticipantRole.TRANSITION_PENDING);
        var excluded = new Participant(player, "ResumeHuman", retainedRole, true, false, 700, 0, 1, false);
        var latest = new RoundSnapshot(round, 1, 123, 1, initial.rules(), RoundPhase.RUNNING, PauseReason.NO_ONLINE_HUMANS,
                900, 0, Map.of(player, excluded), false, false, null);
        try (var store = new SqliteRoundStore(database)) { commit(store, latest, 0); }
        var result = checkpoints.restore();
        assertFalse(result.restored()); assertTrue(result.message().contains("资格取消"), result.message());
        assertEquals(retainedRole, SqliteRoundStore.inspectLatest(database).orElseThrow().participants().get(player).role());
    }

    @Test void noCheckpointAndMissingDatabaseExplainWhyRecoveryIsBlocked() throws Exception {
        checkpoints.begin();
        assertTrue(checkpoints.restore().message().contains("尚未形成"));
        save(); Files.delete(database);
        assertTrue(checkpoints.restore().message().contains("数据库缺失"));
    }

    private Path save() throws Exception {
        Path image = checkpoints.begin();
        try (var store = new SqliteRoundStore(database)) { store.exportCheckpoint(checkpoints.database(image)); }
        checkpoints.publish(image, initial, Long.MAX_VALUE);
        return image;
    }
    private FullCheckpoint.Staged stage() throws Exception { return checkpoints.stage(checkpoints.begin(), initial, Long.MAX_VALUE, true); }
    private Path world() { return RoundWorldPaths.world(root, round, "overworld").resolve("region/r.0.0.mca"); }
    private RoundSnapshot snapshot(UUID id, long version, boolean clean, RoundResult result) {
        var participant = new Participant(player, "ResumeHuman", ParticipantRole.HUMAN_ACTIVE, true, false, 700, 0, 1, false);
        return new RoundSnapshot(id, id.equals(round) ? 1 : 2, 123, version, RulesConfig.defaults(),
                result == null ? RoundPhase.RUNNING : RoundPhase.CELEBRATING, PauseReason.NONE, 900, 0,
                Map.of(player, participant), clean, false, result);
    }
    private static void commit(SqliteRoundStore store, RoundSnapshot snapshot, long expected) throws Exception {
        assertEquals(CommitResult.APPLIED, store.commit(new SnapshotWrite(snapshot.roundId(), expected, snapshot.version(),
                RoundSnapshotCodec.encode(snapshot), UUID.randomUUID(), "CHECKPOINT_TEST")));
    }
}
