package dev.hardrush.gametest;

import dev.hardrush.HardRushProperties;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.round.RoundRuntime;
import dev.hardrush.round.RoundSession;
import java.util.ArrayList;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** Real server lifecycle and Brigadier wiring, with no game client or world entry. */
public final class RoundLifecycleGameTest {
    @GameTest(maxTicks = 400)
    public void lobbyLifecycleAndDurableCommands(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var command = server.getCommands().getDispatcher().getRoot().getChild("hardrush");
        test.assertTrue(command != null, "Competition command registered by mod initialization");
        for (String entry : new String[]{"status", "join", "ready", "unready"}) {
            test.assertTrue(command.getChild(entry) != null, "Competition command entry exists: " + entry);
        }
        test.assertValueEqual(dispatch(server.createCommandSourceStack(), "hardrush status"), 1,
                "Read-only status dispatches on a real server");
        if (!HardRushProperties.competitionEnabled()) {
            test.assertTrue(RoundRuntime.sessionState(server).isEmpty(), "Default runtime is disabled");
            test.assertTrue(RoundRuntime.snapshot(server).isEmpty(), "Disabled runtime has no competition round");
            test.assertTrue(RoundRuntime.status(server).contains("默认关闭"), "Disabled status is explicit");
            test.succeed();
            return;
        }
        var identity = new UUID[1];
        var fingerprint = new String[1];
        var output = new RecordingSource();
        var adminSource = server.createCommandSourceStack().withSource(output);
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(
                        RoundRuntime.sessionState(server).orElseThrow() == RoundSession.State.OPEN,
                        "Enabled lifecycle durably opens the initial round: " + RoundRuntime.status(server)))
                .thenWaitUntil(() -> test.assertTrue(!RoundRuntime.mutationPending(server), "Initial durable writes drained"))
                .thenWaitUntil(() -> test.assertTrue(!RoundRuntime.snapshot(server).orElseThrow().cleanShutdown(),
                        "The first runtime tick durably marks the running process dirty"))
                .thenExecute(() -> {
                    var round = RoundRuntime.snapshot(server).orElseThrow();
                    identity[0] = round.roundId();
                    fingerprint[0] = round.rules().fingerprint();
                    test.assertTrue(round.phase() == RoundPhase.PREPARING || round.phase() == RoundPhase.WAITING
                            || round.phase() == RoundPhase.RECOVERY_REQUIRED, "Initial round prepares, waits, or retains an earlier crash marker");
                    test.assertTrue(!round.cleanShutdown(), "Running process was marked dirty before runtime opened");
                    test.assertValueEqual(round.challengeNanos(), 0L, "Worldless foundation has no race time");
                    test.assertTrue(round.participants().isEmpty(), "Worldless foundation has no participants");
                    var player = test.makeMockServerPlayerInLevel();
                    try {
                        for (String request : new String[]{"hardrush join", "hardrush ready", "hardrush unready"}) {
                            test.assertValueEqual(dispatch(player.createCommandSourceStack(), request), 0,
                                    "Unprepared world rejects player request: " + request);
                        }
                    } finally {
                        server.getPlayerList().remove(player);
                    }
                    test.assertTrue(RoundRuntime.snapshot(server).orElseThrow().participants().isEmpty(),
                            "Rejected commands do not fabricate participants");
                    test.assertTrue(server.getCommands().getDispatcher().parse("hardrush config next-mode normal 大厅生命周期测试", adminSource)
                            .getReader().canRead(), "Every round is hardcore: no next-mode command is left to parse");
                    test.assertValueEqual(RoundRuntime.snapshot(server).orElseThrow().rules().fingerprint(),
                            fingerprint[0], "Commands preserve the current round's frozen rules");
                })
                .thenExecute(() -> {
                    var round = RoundRuntime.snapshot(server).orElseThrow();
                    test.assertValueEqual(round.roundId(), identity[0], "Commands preserve round identity");
                    test.assertTrue(round.phase() == RoundPhase.PREPARING || round.phase() == RoundPhase.WAITING
                            || round.phase() == RoundPhase.RECOVERY_REQUIRED, "Commands cannot start a race or clear a crash marker");
                    test.assertValueEqual(round.challengeNanos(), 0L, "Commands cannot fabricate race time");
                    test.assertTrue(round.participants().isEmpty(), "No fake participating players");
                    test.assertValueEqual(dispatch(server.createCommandSourceStack(), "hardrush status"), 1,
                            "Status remains available after asynchronous commands");
                })
                .thenSucceed();
    }

    private static int dispatch(CommandSourceStack source, String command) {
        try { return source.getServer().getCommands().getDispatcher().execute(command, source); }
        catch (Exception error) { throw new IllegalStateException("GameTest command failed: " + command, error); }
    }

    private static final class RecordingSource implements CommandSource {
        private final ArrayList<String> messages = new ArrayList<>();
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }
}
