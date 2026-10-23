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

/** Headless native Brigadier permissions plus isolated SQLite direct execution. */
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
                "hardrush admin grant-revival Tester 3 测试补偿",
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
        parses(test, dispatcher, owner, "hardrush admin confirm " + UUID.randomUUID(), false);
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void storageRepairAndRecoveryCloseExecuteDirectlyWithPermissionsAndAudit(GameTestHelper test) throws Exception {
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
            var reader = permission(source, 2); var admin = permission(source, 3); var owner = permission(source, 4);
            session.adapterFailure("测试存储故障", new IllegalStateException("retain"));
            parses(test, dispatcher, reader, "hardrush admin recheck-storage 越权", false);
            test.assertValueEqual(session.state(), P1LobbySession.State.FAILED, "Denied repair leaves the failure intact");
            execute(dispatcher, admin, "hardrush admin recheck-storage 存储已修复");
            test.assertValueEqual(session.state(), P1LobbySession.State.RECHECKING, "One repair command starts rechecking directly");
            pumpUntil(test, callbacks, () -> session.state() != P1LobbySession.State.RECHECKING);
            test.assertValueEqual(session.snapshot().orElseThrow().phase(), RoundPhase.RECOVERY_REQUIRED, "Successful repair never resumes competition");
            test.assertTrue(session.competitionLocked(), "Repaired state remains quarantined");
            parses(test, dispatcher, admin, "hardrush admin close-recovery 越权", false);
            execute(dispatcher, owner, "hardrush admin close-recovery 审查后结束恢复轮");
            pumpUntil(test, callbacks, () -> !session.mutationPending());
            test.assertValueEqual(session.snapshot().orElseThrow().phase(), RoundPhase.ROTATING, "Decision awaits the separate world release gate");
            test.assertValueEqual(session.snapshot().orElseThrow().sequence(), 2L, "Decision alone does not create another round");
            execute(dispatcher, owner, "hardrush admin close-recovery 重复请求");
            test.assertValueEqual(query(test, callbacks, session.audits(null, 20, 0)).size(), 2, "Exactly repair and close are audited");
            test.assertFalse(output.hasTicketAfter(0), "Recovery commands never request confirmation");
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void rulesAndHistoryExecuteDirectlyWithPermissionsAndDurableResults(GameTestHelper test) throws Exception {
        var server = test.getLevel().getServer();
        Path database = Files.createTempDirectory("hardrush-admin-command-test-").toRealPath().resolve("rounds.sqlite");
        UUID historyId = seedHistoricalFixture(database);
        var callbacks = new LinkedBlockingQueue<Runnable>(); var output = new RecordingSource();
        try (var session = new P1LobbySession(database, () -> 0, callbacks::add)) {
            pumpUntil(test, callbacks, () -> session.state() == P1LobbySession.State.OPEN && !session.mutationPending());
            var commands = new P1AdminCommands(ignored -> session, ignored -> Optional.empty(), (ignored, failure) -> {});
            var dispatcher = new CommandDispatcher<CommandSourceStack>();
            dispatcher.register(commands.attach(Commands.literal("hardrush")));
            var source = server.createCommandSourceStack().withSource(output);
            var reader = permission(source, 2); var admin = permission(source, 3); var owner = permission(source, 4);
            var oldRules = session.nextRules().orElseThrow();
            GameMode target = oldRules.mode() == GameMode.NORMAL ? GameMode.HARDCORE : GameMode.NORMAL;
            String targetName = target == GameMode.NORMAL ? "normal" : "hardcore";
            parses(test, dispatcher, reader, "hardrush admin next-mode " + targetName + " 越权", false);
            test.assertValueEqual(commands.nextMode(reader, targetName, "越权"), 0, "Alias handler also checks current permission");
            test.assertValueEqual(commands.nextMode(admin, targetName, " "), 0, "Blank reason remains invalid without tickets");
            test.assertValueEqual(session.nextRules().orElseThrow(), oldRules, "Denied requests preserve next rules");
            test.assertFalse(session.mutationPending(), "Denied requests start no SQLite mutation");
            execute(dispatcher, admin, "hardrush admin next-mode " + targetName + " 直接修改下轮");
            test.assertValueEqual(session.nextRules().orElseThrow(), oldRules, "Rules publish only after SQLite acknowledgement");
            execute(dispatcher, admin, "hardrush admin next-mode " + targetName + " 提交中重复请求");
            test.assertTrue(output.last().contains("上一项状态仍在提交"), "Concurrent rule command cannot duplicate pending write");
            pumpUntil(test, callbacks, () -> !session.mutationPending() && session.nextRules().orElseThrow().mode() == target);
            var rulesAudits = query(test, callbacks, session.audits(null, 20, 0));
            test.assertValueEqual(rulesAudits.size(), 1, "One direct rule command commits one audit");
            test.assertValueEqual(rulesAudits.getFirst().action(), "NEXT_RULES", "Actual execution persists its action");
            long oldEdition = query(test, callbacks, session.history(historyId)).orElseThrow().edition();
            parses(test, dispatcher, admin, "hardrush admin correct " + historyId + " 5000 none 越权", false);
            int beforeCorrection = output.messages.size();
            execute(dispatcher, owner, "hardrush admin correct " + historyId + " 5000 none 直接修订成绩");
            pumpUntil(test, callbacks, () -> output.messages.size() > beforeCorrection
                    && output.last().contains("已持久化") && !session.mutationPending());
            var revised = query(test, callbacks, session.history(historyId)).orElseThrow();
            test.assertValueEqual(revised.edition(), oldEdition + 1, "One correction directly persists a new book edition");
            test.assertValueEqual(revised.challengeMillis(), 5000L, "Correction commits its exact requested duration");
            test.assertTrue(revised.timingUncertain(), "Corrected result remains pending verification");
            parses(test, dispatcher, admin, "hardrush admin validate " + historyId + " 越权", false);
            int beforeValidation = output.messages.size();
            execute(dispatcher, owner, "hardrush admin validate " + historyId + " 无可信首龙证据");
            pumpUntil(test, callbacks, () -> output.messages.size() > beforeValidation && !session.mutationPending());
            test.assertTrue(output.last().contains("不符合原始证据"), "Direct validation still refuses untrusted original evidence");
            test.assertValueEqual(query(test, callbacks, session.history(historyId)).orElseThrow().edition(), oldEdition + 1,
                    "Rejected validation leaves the book edition unchanged");
            test.assertValueEqual(query(test, callbacks, session.audits(null, 20, 0)).size(), 2,
                    "Only the successful rule and correction writes are audited");
            test.assertFalse(output.hasTicketAfter(0), "Rule, correction and validation commands never request confirmation");
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void startAndAbortExecuteDirectlyAndStillRequireReadyAdapters(GameTestHelper test) throws Exception {
        var server = test.getLevel().getServer();
        Path database = Files.createTempDirectory("hardrush-start-abort-command-").toRealPath().resolve("rounds.sqlite");
        UUID player = UUID.randomUUID();
        var rules = RulesConfig.defaults().withMode(GameMode.NORMAL);
        var initial = new RoundSnapshot(UUID.randomUUID(), 1, 123, 0, rules, RoundPhase.WAITING, PauseReason.NOT_RUNNING,
                0, 0, Map.of(player, new Participant(player, "Human", ParticipantRole.LOBBY, true, true, 0, -1, 0, false)), true, false, null);
        try (var store = new SqliteRoundStore(database)) {
            store.savePendingRules(rules.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8), UUID.randomUUID(), "fixture");
            store.commit(new SnapshotWrite(initial.roundId(), -1, 0, RoundSnapshotCodec.encode(initial), UUID.randomUUID(), "ROUND_CREATED"));
        }
        var callbacks = new LinkedBlockingQueue<Runnable>(); var output = new RecordingSource();
        try (var session = new P1LobbySession(database, () -> 0, callbacks::add)) {
            pumpUntil(test, callbacks, () -> session.state() == P1LobbySession.State.OPEN && !session.mutationPending());
            var commands = new P1AdminCommands(ignored -> session, ignored -> Optional.empty(), (ignored, failure) -> {});
            var dispatcher = new CommandDispatcher<CommandSourceStack>();
            dispatcher.register(commands.attach(Commands.literal("hardrush")));
            var source = server.createCommandSourceStack().withSource(output); var admin = permission(source, 3);
            execute(dispatcher, admin, "hardrush admin start 适配器未就绪");
            test.assertFalse(session.mutationPending(), "Unready adapters reject direct start");
            test.assertValueEqual(session.snapshot().orElseThrow().phase(), RoundPhase.WAITING, "Rejected start preserves phase");
            session.adaptersReady(initial.roundId()); pumpUntil(test, callbacks, () -> !session.mutationPending());
            session.onOnline(player, "Human", true); pumpUntil(test, callbacks, () -> !session.mutationPending());
            execute(dispatcher, admin, "hardrush admin start 直接提前开赛");
            pumpUntil(test, callbacks, () -> !session.mutationPending());
            test.assertValueEqual(session.snapshot().orElseThrow().phase(), RoundPhase.RUNNING, "One start command begins the round");
            execute(dispatcher, admin, "hardrush admin abort 直接中止");
            test.assertTrue(session.competitionLocked(), "Abort locks competition at command acceptance");
            pumpUntil(test, callbacks, () -> !session.mutationPending());
            test.assertValueEqual(session.snapshot().orElseThrow().phase(), RoundPhase.ROTATING, "One abort command starts safe rotation");
            var audits = query(test, callbacks, session.audits(initial.roundId(), 20, 0));
            test.assertValueEqual(audits.size(), 2, "Start and abort each commit one audit");
            test.assertFalse(output.hasTicketAfter(0), "Start and abort never request confirmation");
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void retryWorldExecutesDirectlyAfterItsAuditAndPreservesRoundIdentity(GameTestHelper test) throws Exception {
        var server = test.getLevel().getServer();
        Path database = Files.createTempDirectory("hardrush-retry-command-").toRealPath().resolve("rounds.sqlite");
        seedHistoricalFixture(database);
        var callbacks = new LinkedBlockingQueue<Runnable>(); var output = new RecordingSource();
        var retries = new java.util.concurrent.atomic.AtomicInteger();
        try (var session = new P1LobbySession(database, () -> 0, callbacks::add)) {
            pumpUntil(test, callbacks, () -> session.state() == P1LobbySession.State.OPEN && !session.mutationPending());
            var round = session.snapshot().orElseThrow();
            var commands = new P1AdminCommands(ignored -> session, ignored -> Optional.of("测试世界准备失败"),
                    (ignored, failure) -> retries.incrementAndGet());
            var dispatcher = new CommandDispatcher<CommandSourceStack>();
            dispatcher.register(commands.attach(Commands.literal("hardrush")));
            var source = server.createCommandSourceStack().withSource(output); var admin = permission(source, 3);
            test.assertValueEqual(commands.current(permission(source, 2), dev.hardrush.core.admin.AdminAction.RETRY, "越权"), 0,
                    "Legacy retry handler also checks permission");
            execute(dispatcher, admin, "hardrush admin retry-world 直接重试原世界");
            test.assertValueEqual(retries.get(), 0, "Adapter cannot retry before the durable audit callback");
            pumpUntil(test, callbacks, () -> !session.mutationPending());
            test.assertValueEqual(retries.get(), 1, "One retry command directly invokes the adapter once");
            test.assertValueEqual(session.snapshot().orElseThrow().roundId(), round.roundId(), "Retry preserves round identity");
            test.assertValueEqual(session.snapshot().orElseThrow().seed(), round.seed(), "Retry preserves seed");
            test.assertValueEqual(query(test, callbacks, session.audits(round.roundId(), 20, 0)).getFirst().action(), "RETRY_WORLD",
                    "Retry records its durable intent");
            test.assertFalse(output.hasTicketAfter(0), "Retry never requests confirmation");
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void revivalGrantAppliesDirectlyWithPermissionAndAtomicAudit(GameTestHelper test) throws Exception {
        var server = test.getLevel().getServer();
        Path database = Files.createTempDirectory("hardrush-admin-revival-").toRealPath().resolve("rounds.sqlite");
        UUID roundId = UUID.randomUUID(), target = UUID.randomUUID(), human = UUID.randomUUID();
        var rules = RulesConfig.defaults().withMode(GameMode.HARDCORE);
        var initial = new RoundSnapshot(roundId, 1, 123, 0, rules, RoundPhase.RUNNING, PauseReason.SERVER_STOPPED,
                0, 0, Map.of(target, new Participant(target, "Monster", ParticipantRole.MONSTER_LOBBY, false, false, 0, 0, 1, false),
                        human, new Participant(human, "Human", ParticipantRole.HUMAN_ACTIVE, false, false, 0, 0, 1, false)),
                true, false, null);
        try (var store = new SqliteRoundStore(database)) {
            store.savePendingRules(rules.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8), UUID.randomUUID(), "fixture");
            store.commit(new SnapshotWrite(roundId, -1, 0, RoundSnapshotCodec.encode(initial), UUID.randomUUID(), "ROUND_CREATED"));
        }
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var output = new RecordingSource();
        try (var session = new P1LobbySession(database, () -> 0, callbacks::add)) {
            pumpUntil(test, callbacks, () -> session.state() == P1LobbySession.State.OPEN && !session.mutationPending());
            session.adaptersReady(roundId);
            pumpUntil(test, callbacks, () -> !session.mutationPending());
            var commands = new P1AdminCommands(ignored -> session, ignored -> Optional.empty(), (ignored, failure) -> {});
            var dispatcher = new CommandDispatcher<CommandSourceStack>();
            dispatcher.register(commands.attach(Commands.literal("hardrush")));
            var source = server.createCommandSourceStack().withSource(output);
            var admin = permission(source, 3);
            var reader = permission(source, 2);
            parses(test, dispatcher, admin, "hardrush admin grant-revival Monster 0 测试", false);
            parses(test, dispatcher, admin, "hardrush admin grant-revival Monster -1 测试", false);
            execute(dispatcher, admin, "hardrush admin grant-revival Unknown 1 测试");
            test.assertTrue(output.last().contains("玩家不存在"), "Unknown target is rejected");
            parses(test, dispatcher, reader, "hardrush admin grant-revival Monster 3 越权", false);
            int firstMessage = output.messages.size();
            execute(dispatcher, admin, "hardrush admin grant-revival Monster 3 管理补偿");
            test.assertFalse(output.hasTicketAfter(firstMessage), "Grant command never issues a confirmation ticket");
            test.assertValueEqual(session.snapshot().orElseThrow().participants().get(target).revivalCharges(), 0,
                    "Credits publish only after the SQLite callback");
            execute(dispatcher, admin, "hardrush admin grant-revival Monster 3 提交中重复请求");
            test.assertTrue(output.last().contains("上一项状态仍在提交"), "Concurrent grant cannot duplicate a pending write");
            pumpUntil(test, callbacks, () -> !session.mutationPending());
            test.assertValueEqual(session.snapshot().orElseThrow().participants().get(target).revivalCharges(), 3,
                    "One command directly grants exact credits to an offline monster");
            test.assertTrue(output.last().contains("Monster") && output.last().contains("已发放 3 次复生"),
                    "Success identifies the target and committed amount");
            test.assertTrue(session.snapshot().orElseThrow().timingUncertain(), "Admin intervention marks the round uncertain");
            execute(dispatcher, admin, "hardrush admin grant-revival Monster 2147483647 溢出");
            test.assertTrue(output.last().contains("不能超过"), "Balance overflow is refused");
            execute(dispatcher, admin, "hardrush admin grant-revival " + target + " 2 追加补偿");
            pumpUntil(test, callbacks, () -> !session.mutationPending());
            test.assertValueEqual(session.snapshot().orElseThrow().participants().get(target).revivalCharges(), 5,
                    "Another direct UUID command adds to the latest balance");
            test.assertFalse(output.hasTicketAfter(firstMessage), "No grant operation requires confirm");
            var audits = query(test, callbacks, session.audits(roundId, 20, 0));
            test.assertValueEqual(audits.size(), 2, "Only the two successful grants are audited");
            test.assertTrue(audits.stream().allMatch(a -> a.action().equals("GRANT_REVIVAL") && a.afterValue().contains("player=" + target)),
                    "Audit records the grant target and resulting balance");
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
    }
}
