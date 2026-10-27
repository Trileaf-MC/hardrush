package dev.hardrush.player;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/** Standalone writer deliberately killed by the parent, followed by a separate recovery JVM. */
public final class ProfileCrashProcessFixture {
    private static final UUID PLAYER = UUID.fromString("4bfe63d5-0853-4d40-8bb4-93012863613b");
    private static final ProfileJournal.Generation TARGET = new ProfileJournal.Generation(
            UUID.fromString("8f2a9670-a527-4c5d-b23e-269c07c7c8ae"), 7);
    private static final byte[] ORIGINAL = "sealed old inventory and progress".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RESET = "one durable reset with its original suspension identity".getBytes(StandardCharsets.UTF_8);

    private ProfileCrashProcessFixture() {}

    public static void main(String[] args) throws Exception {
        String operation = args[0];
        String boundary = args[1];
        Path root = Path.of(args[2]);
        var journal = new ProfileJournal(root);
        if (operation.equals("write")) {
            journal.prepare(PLAYER, Optional.empty(), TARGET, ORIGINAL);
            if (!boundary.equals("PREPARED")) journal.suspend(PLAYER, TARGET, RESET);
            if (boundary.equals("APPLIED") || boundary.equals("VERIFIED")) {
                journal.advance(PLAYER, TARGET, ProfileJournal.Phase.PREPARED, ProfileJournal.Phase.APPLIED);
            }
            if (boundary.equals("VERIFIED")) {
                journal.advance(PLAYER, TARGET, ProfileJournal.Phase.APPLIED, ProfileJournal.Phase.VERIFIED);
            }
            // Incomplete replacement files left by a killed writer must never be read as a checkpoint.
            Files.write(root.resolve(PLAYER.toString()).resolve("transition.bin.pending-interrupted"), new byte[]{1, 2});
            System.out.println("READY " + boundary + " pid=" + ProcessHandle.current().pid());
            System.out.flush();
            new CountDownLatch(1).await();
            throw new AssertionError("Writer must be terminated without a shutdown/close path");
        }
        if (!operation.equals("recover")) throw new IllegalArgumentException(operation);
        check(Arrays.equals(ORIGINAL, journal.archived(PLAYER, Optional.empty())), "Original archive survived termination");
        if (boundary.equals("PREPARED")) {
            boolean blocked = false;
            try { journal.recovery(PLAYER, TARGET); }
            catch (java.io.IOException expected) { blocked = expected.getMessage().contains("retain quarantine"); }
            check(blocked, "Uncertain PREPARED remains quarantined instead of resetting again");
            check(journal.transition(PLAYER).orElseThrow().phase() == ProfileJournal.Phase.PREPARED, "Blocked recovery cannot advance");
        } else {
            var recovery = journal.recovery(PLAYER, TARGET);
            check(Arrays.equals(RESET, recovery.snapshot()), "Exact durable reset is reused");
            if (recovery.transition().phase() == ProfileJournal.Phase.PREPARED) {
                journal.advance(PLAYER, TARGET, ProfileJournal.Phase.PREPARED, ProfileJournal.Phase.APPLIED);
            }
            journal.advance(PLAYER, TARGET, ProfileJournal.Phase.APPLIED, ProfileJournal.Phase.VERIFIED);
            check(journal.recovery(PLAYER, TARGET).transition().phase() == ProfileJournal.Phase.VERIFIED, "Recovery finishes once");
            journal.prepare(PLAYER, Optional.empty(), TARGET, "must never replace the archive".getBytes(StandardCharsets.UTF_8));
            check(Arrays.equals(ORIGINAL, journal.archived(PLAYER, Optional.empty())), "Repeated recovery keeps sealed original");
            check(Arrays.equals(RESET, journal.recovery(PLAYER, TARGET).snapshot()), "Repeated recovery keeps reset identity");
        }
        System.out.println("PASS recover " + boundary + " pid=" + ProcessHandle.current().pid());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
