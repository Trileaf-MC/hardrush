package dev.hardrush.admin;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

/** Main-thread work deferred to the end of the next server tick, for steps that wait on an earlier commit. */
final class NextTick {
    private static final Map<MinecraftServer, List<Runnable>> TASKS = new IdentityHashMap<>();

    static { ServerTickEvents.END_SERVER_TICK.register(NextTick::run); }

    private NextTick() {}

    static void schedule(MinecraftServer server, Runnable task) {
        TASKS.computeIfAbsent(server, ignored -> new ArrayList<>()).add(task);
    }

    private static void run(MinecraftServer server) {
        var due = TASKS.remove(server);
        if (due != null) due.forEach(Runnable::run);
    }
}
