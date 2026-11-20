package dev.hardrush.revival.mixin;

import dev.hardrush.revival.TotemSave;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A held revival totem saves an active human in place ({@link TotemSave}). Shares the head of
 * {@code checkTotemDeathProtection} with {@code MonsterLivingMixin.noCreeperEquipmentProtection}, which only answers for
 * a deployed creeper body: such a body is never an active human, so whichever runs first, the other has nothing to do.
 */
@Mixin(LivingEntity.class)
public abstract class TotemSaveMixin {
    @Inject(method = "checkTotemDeathProtection", at = @At("HEAD"), cancellable = true)
    private void revivalTotem(DamageSource source, CallbackInfoReturnable<Boolean> callback) {
        if (TotemSave.protect((LivingEntity) (Object) this, source)) callback.setReturnValue(true);
    }
}
