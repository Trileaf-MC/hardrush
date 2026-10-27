package dev.hardrush.gametest;

import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.record.ProgressWrite;
import dev.hardrush.combat.CombatRuntime;
import dev.hardrush.record.ProgressRuntime;
import dev.hardrush.combat.MonsterCombatRuntime;
import dev.hardrush.protection.ProtectionRuntime;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;

/** Test-mod-only temporary bindings; always restore the exact previous production binding. */
public final class RuntimeFixtures {
    public interface Scope extends AutoCloseable {@Override void close();}
    private RuntimeFixtures(){}
    @SuppressWarnings("unchecked") private static Runnable restore(Class<?> type,MinecraftServer server){
        try{var f=type.getDeclaredField("BINDINGS");f.setAccessible(true);var bindings=(Map<MinecraftServer,Object>)f.get(null);var prior=bindings.get(server);
            return ()->{if(prior==null)bindings.remove(server);else bindings.put(server,prior);};
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    public static CombatRuntime.Scope combat(MinecraftServer server,CombatRuntime.RoundAccess access,Consumer<CombatEvent> events){var restore=restore(CombatRuntime.class,server);CombatRuntime.configure(server,access,events);return restore::run;}
    public static MonsterCombatRuntime.Scope monsterCombat(MinecraftServer server,MonsterCombatRuntime.Access access){var restore=restore(MonsterCombatRuntime.class,server);MonsterCombatRuntime.configure(server,access);return restore::run;}
    public static Scope protection(MinecraftServer server,ProtectionRuntime.Access access){var restore=restore(ProtectionRuntime.class,server);ProtectionRuntime.configure(server,access);return restore::run;}
    public static Scope progress(MinecraftServer server,ProgressRuntime.Eligibility access,Consumer<ProgressWrite> writes){var restore=restore(ProgressRuntime.class,server);ProgressRuntime.configure(server,access,writes);return restore::run;}
    public static Scope dawnPact(MinecraftServer server,dev.hardrush.pact.DawnPactRuntime.Access access){
        var restore=restore(dev.hardrush.pact.DawnPactRuntime.class,server);
        dev.hardrush.pact.DawnPactRuntime.configure(server,access);
        return ()->{dev.hardrush.pact.DawnPactRuntime.close(server);restore.run();};
    }
}
