package dev.hardrush.p2.locator.mixin;

import dev.hardrush.p2.locator.PlayerLocator;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WaypointTransmitter.class)
public interface WaypointTransmitterMixin {
    // Used both when establishing a connection and when invalidating an existing one.
    @Inject(method = "doesSourceIgnoreReceiver", at = @At("HEAD"), cancellable = true)
    private static void hideMonsterFromHuman(LivingEntity source, ServerPlayer receiver,
            CallbackInfoReturnable<Boolean> callback) {
        if (PlayerLocator.hideFrom(source, receiver)) callback.setReturnValue(true);
    }
}
