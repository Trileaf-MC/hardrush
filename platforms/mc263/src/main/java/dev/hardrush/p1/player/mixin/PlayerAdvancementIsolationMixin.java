package dev.hardrush.p1.player.mixin;

import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p1.record.P1ProgressRuntime;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementIsolationMixin {
    @Shadow private ServerPlayer player;
    @Inject(method = "award", at = @At("HEAD"), cancellable = true)
    private void hardrush$holdProgress(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> ci) {
        if (P1PlayerProfiles.isLocked(player)) ci.setReturnValue(false);
    }

    @Inject(method = "award", at = @At("RETURN"))
    private void hardrush$completedProgress(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> ci) {
        P1ProgressRuntime.advancementAwarded(player, holder, ci.getReturnValue());
    }
}
