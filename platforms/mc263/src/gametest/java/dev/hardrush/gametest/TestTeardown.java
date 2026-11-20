package dev.hardrush.gametest;

import com.mojang.logging.LogUtils;
import dev.hardrush.gametest.mixin.GameTestHelperAccessor;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import org.slf4j.Logger;

/**
 * Cleanup a test must do however it ends: passed, failed at any step, or timed out.
 *
 * <p>A sequence stops at its test's first failure, so a trailing cleanup step runs only when every step before it
 * passed. Work that leaves the shared server in a state other tests would notice (fixture connections, mock
 * players, forced chunks, temporary dimensions) is registered here instead and runs once the test has ended.
 */
public final class TestTeardown implements GameTestListener {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Deque<Runnable> actions = new ArrayDeque<>();

    private TestTeardown() {}

    /**
     * Runs {@code action} on the server thread once the test has ended and its result is decided; actions
     * registered later run first. One that throws is logged and does not stop the others or change the result.
     */
    public static void atEnd(GameTestHelper test, Runnable action) {
        var info = ((GameTestHelperAccessor) test).hardrush$testInfo();
        var teardown = info.getListeners().filter(TestTeardown.class::isInstance).map(TestTeardown.class::cast).findFirst()
                .orElseGet(() -> {
                    var created = new TestTeardown();
                    info.addListener(created);
                    return created;
                });
        teardown.actions.push(action);
        // Registered from a callback after the end: nothing would announce it any more.
        if (info.isDone()) teardown.run(info);
    }

    private void run(GameTestInfo info) {
        while (!actions.isEmpty()) {
            try {
                actions.pop().run();
            } catch (RuntimeException | AssertionError failure) {
                LOGGER.error("{} teardown failed", info.id(), failure);
            }
        }
    }

    @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { run(info); }

    @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { run(info); }

    @Override public void testStructureLoaded(GameTestInfo info) {}

    @Override public void testAddedForRerun(GameTestInfo original, GameTestInfo copy, GameTestRunner runner) {}
}
