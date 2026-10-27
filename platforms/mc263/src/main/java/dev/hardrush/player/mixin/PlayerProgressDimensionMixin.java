package dev.hardrush.player.mixin;

import dev.hardrush.record.ProgressRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class PlayerProgressDimensionMixin {
    @Inject(method = "triggerDimensionChangeTriggers", at = @At("TAIL"))
    private void hardrush$captureEndPresence(ServerLevel previous, CallbackInfo ci) {
        ProgressRuntime.dimensionChanged((ServerPlayer) (Object) this);
    }
}
