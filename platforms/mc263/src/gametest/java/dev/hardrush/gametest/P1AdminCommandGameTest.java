package dev.hardrush.gametest;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.PauseReason;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundResult;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.core.round.RoundSnapshotCodec;
import dev.hardrush.p1.P1LobbySession;
import dev.hardrush.p1.admin.P1AdminCommands;
import dev.hardrush.persistence.SnapshotWrite;
import dev.hardrush.persistence.SqliteRoundStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;

/** Headless native Brigadier permissions plus isolated SQLite confirmation execution. */
public final class P1AdminCommandGameTest {
    @GameTest(maxTicks = 100)
    public void registeredNativeCommandTreeSeparatesPlayerReadOperationsAndThreeOperatorLevels(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var dispatcher = server.getCommands().getDispatcher();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        var player = permission(source, 0);
        var reader = permission(source, 2);
        var admin = permission(source, 3);
        var owner = permission(source, 4);
        for (String command : new String[]{"hardrush status", "hardrush history"}) {
            parses(test, dispatcher, player, command, true);
        }
        for (String command : new String[]{"hardrush admin status", "hardrush admin rules",
                "hardrush admin audit current", "hardrush verify 1"}) {
            parses(test, dispatcher, player, command, false);
            parses(test, dispatcher, reader, command, true);
        }
        for (String command : new String[]{"hardrush admin start 测试原因", "hardrush admin abort 测试原因",
                "hardrush admin retry-world 测试原因", "hardrush admin next-mode normal 测试原因",
                "hardrush admin recheck-storage 测试原因",
                "hardrush retry-world 测试原因", "hardrush config next-mode normal 测试原因"}) {
            parses(test, dispatcher, player, command, false);
            parses(test, dispatcher, reader, command, false);
            parses(test, dispatcher, admin, command, true);
            parses(test, dispatcher, owner, command, true);
        }
        for (String command : new String[]{"hardrush admin correct 1 5000 none 测试原因",
                "hardrush admin validate 1 测试原因", "hardrush admin close-recovery 测试原因"}) {
            parses(test, dispatcher, player, command, false);
            parses(test, dispatcher, reader, command, false);
            parses(test, dispatcher, admin, command, false);
            parses(test, dispatcher, owner, command, true);
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void storageRepairAndRecoveryCloseRequireCurrentContextPermissionsAndOneConfirmation(GameTestHelper test) throws Exception {
        var server = test.getLevel().getServer();
        Path database = Files.createTempDirectory("hardrush-recovery-command-").toRealPath().resolve("rounds.sqlite");
        seedHistoricalFixture(database);
        var callbacks = new LinkedBlockingQueue<Runnable>(); var output = new RecordingSource();
        try (var session = new P1LobbySession(database, () -> 0, callbacks::add)) {
            pumpUntil(test, callbacks, () -> session.state() == P1LobbySession.State.OPEN && !session.mutationPending());
            var commands = new P1AdminCommands(ignored -> session, ignored -> Optional.empty(), (ignored, failure) -> {});
            var dispatcher = new CommandDispatcher<CommandSourceStack>();
            dispatcher.register(commands.attach(Commands.literal("hardrush")));
            var source = server.createCommandSourceStack().withSource(output);
            var admin = permission(source, 3); var owner = permission(source, 4);
            session.adapterFailure("测试存储故障", new IllegalStateException("first"));
            execute(dispatcher, admin, "hardrush admin recheck-storage 存储已修复");
            UUID stale = output.lastTicket();
            session.adapterFailure("故障原因改变", new IllegalStateException("second"));
            execute(dispatcher, admin, "hardrush admin confirm " + stale);
            test.assertTrue(output.last().contains("不再匹配") || output.last().contains("no longer matches"), "Changed failure invalidates its preview");
            test.assertValueEqual(session.state(), P1LobbySession.State.FAILED, "Stale ticket leaves the failure intact");
            execute(dispatcher, admin, "hardrush admin recheck-storage 存储已修复");
            UUID repair = output.lastTicket();
            execute(dispatcher, permission(source, 2), "hardrush admin confirm " + repair);
            test.assertTrue(output.last().contains("权限不足"), "Downgraded OP2 cannot repair storage");
            execute(dispatcher, admin, "hardrush admin confirm " + repair);
            pumpUntil(test, callbacks, () -> session.state() != P1LobbySession.State.RECHECKING);
            test.assertValueEqual(session.snapshot().orElseThrow().phase(), RoundPhase.RECOVERY_REQUIRED, "Successful repair never resumes competition");
            test.assertTrue(session.competitionLocked(), "Repaired state remains quarantined");
            execute(dispatcher, owner, "hardrush admin close-recovery 审查后结束恢复轮");
            UUID close = output.lastTicket();
            execute(dispatcher, admin, "hardrush admin confirm " + close);
            test.assertTrue(output.last().contains("权限不足"), "OP3 cannot confirm an OP4 recovery decision");
            execute(dispatcher, owner, "hardrush admin confirm " + close);
            pumpUntil(test, callbacks, () -> !session.mutationPending());
            test.assertValueEqual(session.snapshot().orElseThrow().phase(), RoundPhase.ROTATING, "Decision awaits the separate world release gate");
            test.assertValueEqual(session.snapshot().orElseThrow().sequence(), 2L, "Decision alone does not create another round");
            execute(dispatcher, owner, "hardrush admin confirm " + close);
            test.assertTrue(output.last().contains("已消费"), "Recovery decision is single use");
            test.assertValueEqual(query(test, callbacks, session.audits(null, 20, 0)).size(), 2, "Exactly repair and close are audited");
            commands.forget(server);
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void downgradedConfirmationSourceCannotChangeRulesOrReviseHistoryAndApprovedTicketsExecuteOnlyOnce(
            GameTestHelper test) throws Exception {
        var server = test.getLevel().getServer();
        // Canonicalize macOS /var -> /private/var in this fixture; production still rejects symlink parents.
        Path database = Files.createTempDirectory("hardrush-admin-command-test-").toRealPath().resolve("rounds.sqlite");
        UUID historyId = seedHistoricalFixture(database);
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var output = new RecordingSource();
        try (var session = new P1LobbySession(database, () -> 0, callbacks::add)) {
            pumpUntil(test, callbacks, () -> session.state() == P1LobbySession.State.OPEN && !session.mutationPending());
            var commands = new P1AdminCommands(ignored -> session, ignored -> Optional.empty(),
                    (ignored, failure) -> { throw new IllegalStateException("No world retry belongs to this fixture"); });
            var dispatcher = new CommandDispatcher<CommandSourceStack>();
            dispatcher.register(commands.attach(Commands.literal("hardrush")));
            var source = server.createCommandSourceStack().withSource(output);
            var reader = permission(source, 2);
            var admin = permission(source, 3);
            var owner = permission(source, 4);

            var oldRules = session.nextRules().orElseThrow();
            GameMode target = oldRules.mode() == GameMode.NORMAL ? GameMode.HARDCORE : GameMode.NORMAL;
            String targetName = target == GameMode.NORMAL ? "normal" : "hardcore";
            test.assertValueEqual(execute(dispatcher, admin, "hardrush admin next-mode " + targetName + " 权限变更测试"),
                    1, "OP3 receives a concrete next-mode preview ticket");
            UUID rulesTicket = output.lastTicket();
            test.assertValueEqual(execute(dispatcher, reader, "hardrush admin confirm " + rulesTicket), 0,
                    "The same operator downgraded to OP2 cannot confirm an OP3 ticket");
            test.assertTrue(output.last().contains("当前权限不足"), "Denied confirmation explicitly reports current permissions");
            test.assertValueEqual(session.nextRules().orElseThrow(), oldRules, "Permission downgrade does not change pending rules");
            test.assertFalse(session.mutationPending(), "Denied confirmation starts no SQLite mutation");
            test.assertValueEqual(query(test, callbacks, session.audits(null, 20, 0)).size(), 0,
                    "A rejected confirmation creates no successful-operation audit");
            execute(dispatcher, admin, "hardrush admin confirm " + rulesTicket);
            pumpUntil(test, callbacks, () -> !session.mutationPending() && session.nextRules().orElseThrow().mode() == target);
            var rulesAudits = query(test, callbacks, session.audits(null, 20, 0));
            test.assertValueEqual(rulesAudits.size(), 1, "Authorized confirmation commits exactly one rule-change audit");
            test.assertValueEqual(rulesAudits.getFirst().action(), "NEXT_RULES", "Actual command execution persists its action");
            execute(dispatcher, admin, "hardrush admin confirm " + rulesTicket);
            test.assertTrue(output.last().contains("已消费"), "Consumed ticket cannot execute a second time");
            test.assertValueEqual(query(test, callbacks, session.audits(null, 20, 0)).size(), 1,
                    "Repeated confirmation cannot append a duplicate rule-change audit");

            long oldEdition = query(test, callbacks, session.history(historyId)).orElseThrow().edition();
            int previousMessages = output.messages.size();
            execute(dispatcher, owner, "hardrush admin correct " + historyId + " 5000 none 修订权限测试");
            pumpUntil(test, callbacks, () -> output.hasTicketAfter(previousMessages));
            UUID revisionTicket = output.lastTicket();
            execute(dispatcher, admin, "hardrush admin confirm " + revisionTicket);
            test.assertTrue(output.last().contains("当前权限不足"), "OP4 to OP3 downgrade cannot confirm a history correction");
            test.assertValueEqual(query(test, callbacks, session.history(historyId)).orElseThrow().edition(), oldEdition,
                    "Downgraded correction keeps the historical book edition");
            execute(dispatcher, owner, "hardrush admin confirm " + revisionTicket);
            pumpUntil(test, callbacks, () -> !session.mutationPending() && output.last().contains("已持久化"));
            var revised = query(test, callbacks, session.history(historyId)).orElseThrow();
            test.assertValueEqual(revised.edition(), oldEdition + 1, "OP4 correction persists a new historical book edition");
            test.assertValueEqual(revised.challengeMillis(), 5000L, "OP4 command commits its exact reviewed duration");
            test.assertTrue(revised.timingUncertain(), "Corrected result remains pending verification");
            test.assertValueEqual(query(test, callbacks, session.audits(null, 20, 0)).size(), 2,
                    "Successful correction atomically adds exactly one audit after the rule change");
            execute(dispatcher, owner, "hardrush admin confirm " + revisionTicket);
            test.assertTrue(output.last().contains("已消费"), "History correction ticket is single use");
            test.assertValueEqual(query(test, callbacks, session.history(historyId)).orElseThrow().edition(), oldEdition + 1,
                    "Repeated correction confirmation cannot bump another edition");
            commands.forget(server);
        }
        test.succeed();
    }

    private static CommandSourceStack permission(CommandSourceStack source, int level) {
        return source.withPermission(LevelBasedPermissionSet.forLevel(PermissionLevel.byId(level)));
    }

    private static void parses(GameTestHelper test, CommandDispatcher<CommandSourceStack> dispatcher,
            CommandSourceStack source, String command, boolean expected) {
        var parsed = dispatcher.parse(command, source);
        boolean complete = !parsed.getReader().canRead() && parsed.getContext().getCommand() != null;
        test.assertValueEqual(complete, expected, "Native permission parsing for " + command);
        if (!expected) {
            try {
                dispatcher.execute(parsed);
                test.fail("Permission-denied native command must not execute: " + command);
            } catch (CommandSyntaxException denied) {
                // Brigadier must reject before invoking application code, including the legacy aliases.
            }
        }
    }

    private static int execute(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source, String command) {
        try { return dispatcher.execute(command, source); }
        catch (CommandSyntaxException error) { throw new IllegalStateException("Fixture command failed: " + command, error); }
    }

    private static <T> T query(GameTestHelper test, LinkedBlockingQueue<Runnable> callbacks, CompletableFuture<T> future)
            throws Exception {
        pumpUntil(test, callbacks, future::isDone);
        return future.get();
    }

    private static void pumpUntil(GameTestHelper test, LinkedBlockingQueue<Runnable> callbacks, BooleanSupplier complete)
            throws Exception {
        long started = System.nanoTime();
        while (!complete.getAsBoolean() && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5)) {
            Runnable callback = callbacks.poll(20, TimeUnit.MILLISECONDS);
            if (callback != null) callback.run();
        }
        test.assertTrue(complete.getAsBoolean(), "Isolated SQLite callbacks complete within the headless test budget");
    }

    /** Synthetic historical completion only; this fixture neither simulates dragon causality nor enters a world. */
    private static UUID seedHistoricalFixture(Path database) throws Exception {
        UUID history = UUID.randomUUID(), player = UUID.randomUUID();
        var rules = RulesConfig.defaults().withMode(GameMode.NORMAL);
        var participant = new Participant(player, "Historical Player", ParticipantRole.HUMAN_ACTIVE,
                false, false, 9_000_000_000L, 0, 1, false);
        var running = new RoundSnapshot(history, 1, 123, 0, rules, RoundPhase.RUNNING, PauseReason.NO_ONLINE_HUMANS,
                9_000_000_000L, 0, Map.of(player, participant), false, false, null);
        var settled = new RoundSnapshot(history, 1, 123, 1, rules, RoundPhase.SETTLING, PauseReason.SETTLED,
                9_000_000_000L, 600_000_000_000L, running.participants(), true, false,
                new RoundResult(RoundResult.Kind.COMPLETED, player, "synthetic historical completion without dragon evidence"));
        var current = new RoundSnapshot(UUID.randomUUID(), 2, 456, 0, rules, RoundPhase.PREPARING,
                PauseReason.SERVER_STOPPED, 0, 0, Map.of(), true, false, null);
        try (var store = new SqliteRoundStore(database)) {
            store.commit(new SnapshotWrite(history, -1, 0, RoundSnapshotCodec.encode(running), UUID.randomUUID(), "ROUND_CREATED"));
            store.commit(new SnapshotWrite(history, 0, 1, RoundSnapshotCodec.encode(settled), UUID.randomUUID(), "FINISH"));
            store.commit(new SnapshotWrite(current.roundId(), -1, 0, RoundSnapshotCodec.encode(current), UUID.randomUUID(), "ROUND_CREATED"));
        }
        return history;
    }

    private static final class RecordingSource implements CommandSource {
        final ArrayList<String> messages = new ArrayList<>();
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
        String last() { return messages.getLast(); }
        boolean hasTicketAfter(int first) {
            return messages.subList(first, messages.size()).stream().anyMatch(value -> value.contains("/hardrush admin confirm "));
        }
        UUID lastTicket() {
            var preview = messages.stream().filter(value -> value.contains("/hardrush admin confirm "))
                    .reduce((first, second) -> second).orElseThrow();
            return UUID.fromString(preview.substring(preview.lastIndexOf("/hardrush admin confirm ")
                    + "/hardrush admin confirm ".length()).strip());
        }
    }
}
