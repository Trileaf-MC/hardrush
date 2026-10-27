package dev.hardrush.round;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MainThreadExecutorTest {
    /** Same stopped-dispatch rule as MinecraftServer, using the actual vanilla reentrant loop. */
    static final class Loop extends ReentrantBlockableEventLoop<Runnable> {
        final Thread owner=Thread.currentThread();
        final AtomicInteger workerEntries=new AtomicInteger();
        volatile boolean stopped;
        Loop(){super("callback-regression",false);}
        @Override public Runnable wrapRunnable(Runnable task){return task;}
        @Override protected boolean shouldRun(Runnable task){return true;}
        @Override protected Thread getRunningThread(){return owner;}
        @Override protected boolean scheduleExecutables(){return super.scheduleExecutables()&&!stopped;}
        @Override protected void doRunTask(Runnable task){if(Thread.currentThread()!=owner)workerEntries.incrementAndGet();super.doRunTask(task);}
        void drain(){while(pollTask()){};}
        boolean active(){return runningTask();}
        MainThreadExecutor callbacks(){return new MainThreadExecutor(owner,()->!stopped,this::schedule);}
    }

    @Test void ownerBootstrapAndCloseDrainStillRunInline(){
        var loop=new Loop();var applied=new AtomicInteger();
        loop.callbacks().execute(applied::incrementAndGet);
        loop.stopped=true;loop.callbacks().execute(applied::incrementAndGet);
        assertEquals(2,applied.get());assertFalse(loop.active());
    }

    @Test void queuedCallbackRejectsWhenShutdownWinsTheRace()throws Exception{
        var loop=new Loop();var applied=new AtomicInteger();var rejected=new AtomicInteger();
        try(var worker=Executors.newSingleThreadExecutor()){
            worker.submit(()->loop.callbacks().execute(applied::incrementAndGet,rejected::incrementAndGet)).get(5,TimeUnit.SECONDS);
        }
        assertEquals(0,applied.get());loop.stopped=true;loop.drain();
        assertEquals(0,applied.get());assertEquals(1,rejected.get());assertEquals(0,loop.workerEntries.get());
    }

    @Test void stoppedIoSubmissionsNeverEnterVanillaReentrantCounter()throws Exception{
        var unsafe=new Loop();unsafe.stopped=true;
        try(var worker=Executors.newSingleThreadExecutor()){
            // Negative control: the former inner guard runs AFTER vanilla already entered doRunTask.
            worker.submit(()->unsafe.execute(()->assertNotEquals(unsafe.owner,Thread.currentThread()))).get(5,TimeUnit.SECONDS);
        }
        assertEquals(1,unsafe.workerEntries.get());
        var safe=new Loop();safe.stopped=true;var applied=new AtomicInteger();var rejected=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(8)){
            var start=new CountDownLatch(1);var jobs=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<8;i++)jobs.add(pool.submit(()->{start.await();for(int n=0;n<2000;n++)safe.callbacks().execute(applied::incrementAndGet,rejected::incrementAndGet);return null;}));
            start.countDown();
            for(int n=0;n<2000;n++)safe.callbacks().execute(()->assertSame(safe.owner,Thread.currentThread()));
            for(var job:jobs)job.get(10,TimeUnit.SECONDS);
        }
        assertEquals(0,safe.workerEntries.get());assertEquals(0,applied.get());assertEquals(16000,rejected.get());assertFalse(safe.active());
    }
}
