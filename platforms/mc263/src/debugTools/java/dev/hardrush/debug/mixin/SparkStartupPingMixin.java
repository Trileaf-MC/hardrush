package dev.hardrush.debug.mixin;

import java.util.Map;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** spark 1.10.187 starts ping polling before 26.3 creates the PlayerList. */
@Mixin(targets = "me.lucko.spark.minecraft.MinecraftPlayerPingProvider", remap = false)
public abstract class SparkStartupPingMixin {
    @Shadow @Final private MinecraftServer server;

    @Inject(method = "poll", at = @At("HEAD"), cancellable = true)
    private void hardrush$waitForPlayerList(CallbackInfoReturnable<Map<String, Integer>> callback) {
        if (server.getPlayerList() == null) callback.setReturnValue(Map.of());
    }
}
