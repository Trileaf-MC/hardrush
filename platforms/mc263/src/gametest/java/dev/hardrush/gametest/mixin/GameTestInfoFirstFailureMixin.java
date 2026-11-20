package dev.hardrush.gametest.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.gametest.framework.GameTestException;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Test artifact only: a test reports its first failure.
 *
 * <p>Vanilla {@code fail} overwrites the recorded error, and a failed {@code thenExecute} step does not stop its
 * sequence, so the report used to name whatever failed last, usually a later step whose premise the real failure
 * had already broken. The first failure is kept, later ones ride along as suppressed exceptions, and the first is
 * logged with its stack trace: the report itself prints only the message, the trace names the failing step's line.
 *
 * @see GameTestSequenceStopMixin
 */
@Mixin(GameTestInfo.class)
public abstract class GameTestInfoFirstFailureMixin {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Shadow private @Nullable GameTestException error;
    @Shadow private boolean started;

    @Shadow public abstract Identifier id();

    @Inject(method = "fail(Lnet/minecraft/gametest/framework/GameTestException;)V", at = @At("HEAD"), cancellable = true)
    private void hardrush$keepFirstFailure(GameTestException failure, CallbackInfo ci) {
        if (error == null) {
            // Before the start there is no step to point at; the structure and setup failures say what they are.
            if (started) LOGGER.error("{} failed first here; later failures are attached to it, not reported instead", id(), failure);
            return;
        }
        if (failure != error) error.addSuppressed(failure);
        ci.cancel();
    }
}
