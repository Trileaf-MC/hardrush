package dev.hardrush.gametest.mixin;

import java.util.Iterator;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestException;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.gametest.framework.UnknownGameTestException;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Test artifact only: a sequence stops at its test's first failure, and every step failure fails the test.
 *
 * <p>Vanilla runs the steps after a failed {@code thenExecute} on the same tick and once more on the next. Those
 * steps run on premises the failure already broke: their assertions bury the real failure, and a step that throws
 * anything other than a {@link GameTestException} escapes the game test ticker and crashes the whole server, so no
 * test reports at all. Here the steps after a failure never run, and a step that throws such an exception fails its
 * test the way vanilla already treats an exception from the test method itself or a scheduled callback.
 *
 * <p>Steps after a failure are skipped, so cleanup a test must do however it ends belongs in
 * {@link dev.hardrush.gametest.TestTeardown}, not in a trailing step.
 *
 * @see GameTestInfoFirstFailureMixin
 */
@Mixin(GameTestSequence.class)
public abstract class GameTestSequenceStopMixin {
    @Shadow @Final private GameTestInfo parent;

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Ljava/util/Iterator;hasNext()Z"))
    private boolean hardrush$stopAfterFailure(Iterator<?> events) {
        return !parent.hasFailed() && events.hasNext();
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"))
    private void hardrush$failInsteadOfCrash(Runnable step) {
        try {
            step.run();
        } catch (GameTestAssertException notYet) {
            // A waiting step is not satisfied yet; vanilla retries it next tick, or fails the test at the timeout.
            throw notYet;
        } catch (GameTestException failure) {
            parent.fail(failure);
        } catch (RuntimeException | AssertionError failure) {
            var wrapped = new UnknownGameTestException(failure);
            // The report describes the cause, and the logged trace then points at the step that threw.
            wrapped.initCause(failure);
            parent.fail(wrapped);
        }
    }
}
