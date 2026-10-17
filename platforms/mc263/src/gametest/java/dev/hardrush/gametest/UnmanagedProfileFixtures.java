package dev.hardrush.gametest;

/** Explicit unmanaged mock-login scope, present only in the separately packaged test mod. */
public final class UnmanagedProfileFixtures {
    private static final ThreadLocal<Integer> DEPTH=ThreadLocal.withInitial(()->0);
    private UnmanagedProfileFixtures() {}
    public static boolean active(){return DEPTH.get()>0;}
    public static void run(Runnable task){int prior=DEPTH.get();DEPTH.set(prior+1);try{task.run();}finally{if(prior==0)DEPTH.remove();else DEPTH.set(prior);}}
}
