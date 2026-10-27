package dev.hardrush.player.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla executes stopped-server callbacks inline; only the owner may update its reentrant counter. */
@Mixin(ReentrantBlockableEventLoop.class)
public abstract class StoppedServerTaskMixin<R extends Runnable> extends BlockableEventLoop<R> {
    protected StoppedServerTaskMixin(String name, boolean debug) { super(name, debug); }

    @Inject(method="doRunTask", at=@At("HEAD"), cancellable=true)
    private void hardrush$keepOwnerCounter(R task, CallbackInfo ci) {
        if ((Object)this instanceof MinecraftServer server && !server.isSameThread()) {
            // Preserve vanilla task/error handling and inline shutdown work. Do not race the
            // owner's shared reentrantCount, which otherwise leaves haveTime() permanently true.
            super.doRunTask(task);
            ci.cancel();
        }
    }
}
