package dev.hardrush.death.mixin;

import dev.hardrush.death.DeathNarration;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The victim's kill credit while its death message is built is the round's ({@link DeathNarration#killCredit}). */
@Mixin(LivingEntity.class)
public abstract class DeathCreditMixin {
    @Inject(method = "getKillCredit", at = @At("HEAD"), cancellable = true)
    private void hardrush$roundCredit(CallbackInfoReturnable<LivingEntity> callback) {
        var credited = DeathNarration.killCredit((LivingEntity) (Object) this);
        if (credited != null) callback.setReturnValue(credited);
    }
}
