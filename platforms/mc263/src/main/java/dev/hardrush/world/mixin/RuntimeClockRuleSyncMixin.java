package dev.hardrush.world.mixin;

import java.util.Collections;
import java.util.IdentityHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.nucleoid.fantasy.RuntimeClockManager;

@Mixin(MinecraftServer.class)
public abstract class RuntimeClockRuleSyncMixin {
    // Vanilla resyncs only the server clock; runtime clocks read their own level's advance_time and need it too.
    @Inject(method = "onGameRuleChanged", at = @At("TAIL"))
    private void hardrush$resyncRuntimeClocks(GameRule<?> rule, Object value, CallbackInfo ci) {
        if (rule != GameRules.ADVANCE_TIME) return;
        var synced = Collections.newSetFromMap(new IdentityHashMap<RuntimeClockManager, Boolean>());
        for (ServerLevel level : ((MinecraftServer) (Object) this).getAllLevels()) {
            if (!(level.clockManager() instanceof RuntimeClockManager clocks) || !synced.add(clocks)) continue;
            var packet = clocks.createFullSyncPacket();
            for (ServerLevel viewer : ((MinecraftServer) (Object) this).getAllLevels()) {
                if (viewer.clockManager() == clocks) viewer.players().forEach(player -> player.connection.send(packet));
            }
        }
    }
}
