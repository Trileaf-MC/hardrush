package dev.hardrush.p1;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;

/** Never enter vanilla doRunTask from an IO caller, even while the server is stopping. */
public final class P1MainThreadExecutor implements Executor {
    private final Thread owner;
    private final BooleanSupplier accepting;
    private final Consumer<Runnable> enqueue;

    P1MainThreadExecutor(Thread owner, BooleanSupplier accepting, Consumer<Runnable> enqueue) {
        this.owner=Objects.requireNonNull(owner);
        this.accepting=Objects.requireNonNull(accepting);
        this.enqueue=Objects.requireNonNull(enqueue);
    }

    public static P1MainThreadExecutor forServer(MinecraftServer server) {
        return new P1MainThreadExecutor(server.getRunningThread(), ()->!server.isStopped(),
                task->server.schedule(new TickTask(server.getTickCount(),task)));
    }

    @Override public void execute(Runnable task) { execute(task,()->{}); }

    /** Rejection may complete a future on the submitting thread, but never touches native state. */
    public void execute(Runnable task, Runnable rejected) {
        Objects.requireNonNull(task);Objects.requireNonNull(rejected);
        if(Thread.currentThread()==owner) task.run();
        else if(!accepting.getAsBoolean()) rejected.run();
        else enqueue.accept(()->{if(accepting.getAsBoolean())task.run();else rejected.run();});
    }
}
