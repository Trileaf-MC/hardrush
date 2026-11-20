package dev.hardrush.gametest;

import dev.hardrush.gametest.mixin.GameTestHelperAccessor;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.gametest.framework.RetryOptions;
import net.minecraft.gametest.framework.UnknownGameTestException;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Rotation;

/**
 * The harness reports a test's first failure: its sequences stop there, and every step failure fails the test.
 *
 * <p>Each case drives a detached test of its own by hand. The ticker never sees it, so its deliberate failures
 * stay out of the run's results.
 */
public final class GameTestHarnessGameTest {
    @GameTest
    public void aSequenceReportsItsFirstFailedStepAndRunsNoLaterStep(GameTestHelper test) {
        var info = detached(test);
        var later = new boolean[1];
        var sequence = info.createSequence()
                .thenExecute(() -> { throw failure("the first step fails"); })
                .thenExecute(() -> { later[0] = true; throw failure("a later step fails on the broken premise"); })
                .thenExecute(() -> later[0] = true);
        tick(info, sequence);
        tick(info, sequence);
        test.assertValueEqual(Util.describeError(info.getError()), failure("the first step fails").getMessage(),
                "The report names the first failed step");
        test.assertFalse(later[0], "No step after the failure runs, on that tick or the next");
        test.succeed();
    }

    @GameTest
    public void aLaterFailureRidesAlongInsteadOfReplacingTheFirst(GameTestHelper test) {
        var info = detached(test);
        var first = failure("first");
        var second = failure("second");
        info.fail(first);
        info.fail(second);
        info.fail(first);
        test.assertTrue(info.getError() == first, "The first failure stays the reported one");
        test.assertValueEqual(List.of(first.getSuppressed()), List.<Throwable>of(second), "A later failure is attached to it once");
        test.succeed();
    }

    @GameTest
    public void anExceptionFromAStepFailsTheTestInsteadOfCrashingTheServer(GameTestHelper test) {
        var executed = detached(test);
        var thrown = new IllegalStateException("a step throws");
        tick(executed, executed.createSequence().thenExecute(() -> { throw thrown; }));
        test.assertTrue(executed.getError() instanceof UnknownGameTestException && executed.getError().getCause() == thrown,
                "An exception from an executed step fails the test and keeps its trace");
        test.assertValueEqual(Util.describeError(executed.getError()), "a step throws", "The report names the exception");

        var awaited = detached(test);
        tick(awaited, awaited.createSequence().thenWaitUntil(() -> { throw new AssertionError("a waiting step asserts"); }));
        test.assertTrue(awaited.getError() instanceof UnknownGameTestException && awaited.getError().getCause() instanceof AssertionError,
                "An assertion error from a waiting step fails the test");
        test.succeed();
    }

    @GameTest
    public void aWaitingStepStillWaits(GameTestHelper test) {
        var info = detached(test);
        var ready = new boolean[1];
        var after = new boolean[1];
        var sequence = info.createSequence()
                .thenWaitUntil(() -> { if (!ready[0]) throw failure("not yet"); })
                .thenExecute(() -> after[0] = true);
        tick(info, sequence);
        test.assertTrue(info.getError() == null && !after[0], "An unsatisfied wait neither fails the test nor moves on");
        ready[0] = true;
        tick(info, sequence);
        test.assertTrue(info.getError() == null && after[0], "Once satisfied, the sequence continues");
        test.succeed();
    }

    @GameTest
    public void teardownRunsInReverseOnceTheTestEnds(GameTestHelper test) {
        var info = detached(test);
        var helper = new GameTestHelper(info);
        var order = new ArrayList<String>();
        TestTeardown.atEnd(helper, () -> order.add("registered first"));
        TestTeardown.atEnd(helper, () -> order.add("registered second"));
        test.assertTrue(order.isEmpty(), "Nothing runs before the end");
        test.assertValueEqual(info.getListeners().filter(TestTeardown.class::isInstance).count(), 1L, "One teardown per test");
        info.getListeners().forEach(listener -> listener.testFailed(info, null));
        test.assertValueEqual(order, List.of("registered second", "registered first"), "Later registrations run first");
        info.getListeners().forEach(listener -> listener.testPassed(info, null));
        test.assertValueEqual(order.size(), 2, "Each action runs once");
        test.succeed();
    }

    /** A test of the same kind that only these cases tick: it never starts, so its failures log nothing. */
    private static GameTestInfo detached(GameTestHelper test) {
        var running = ((GameTestHelperAccessor) test).hardrush$testInfo();
        return new GameTestInfo(running.getTestHolder(), Rotation.NONE, test.getLevel(), RetryOptions.noRetries());
    }

    private static void tick(GameTestInfo info, GameTestSequence sequence) {
        sequence.tickAndContinue(info.getTick());
    }

    private static GameTestAssertException failure(String message) {
        return new GameTestAssertException(Component.literal(message), 0);
    }
}
