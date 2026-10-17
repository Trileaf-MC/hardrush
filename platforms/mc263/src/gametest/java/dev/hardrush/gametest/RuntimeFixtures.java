package dev.hardrush.gametest;

import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.record.ProgressWrite;
import dev.hardrush.p1.combat.P1CombatRuntime;
import dev.hardrush.p1.record.P1ProgressRuntime;
import dev.hardrush.p2.combat.P2CombatRuntime;
import dev.hardrush.p2.protection.P2ProtectionRuntime;
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
    public static P1CombatRuntime.Scope combat(MinecraftServer server,P1CombatRuntime.RoundAccess access,Consumer<CombatEvent> events){var restore=restore(P1CombatRuntime.class,server);P1CombatRuntime.configure(server,access,events);return restore::run;}
    public static P2CombatRuntime.Scope monsterCombat(MinecraftServer server,P2CombatRuntime.Access access){var restore=restore(P2CombatRuntime.class,server);P2CombatRuntime.configure(server,access);return restore::run;}
    public static Scope protection(MinecraftServer server,P2ProtectionRuntime.Access access){var restore=restore(P2ProtectionRuntime.class,server);P2ProtectionRuntime.configure(server,access);return restore::run;}
    public static Scope progress(MinecraftServer server,P1ProgressRuntime.Eligibility access,Consumer<ProgressWrite> writes){var restore=restore(P1ProgressRuntime.class,server);P1ProgressRuntime.configure(server,access,writes);return restore::run;}
}
