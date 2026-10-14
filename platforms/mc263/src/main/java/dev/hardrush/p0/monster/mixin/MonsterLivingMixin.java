package dev.hardrush.p0.monster.mixin;
import dev.hardrush.p0.monster.MonsterRoles;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(LivingEntity.class)
public abstract class MonsterLivingMixin {
    @Inject(method="canBreatheUnderwater", at=@At("HEAD"), cancellable=true)
    private void undeadBreathing(CallbackInfoReturnable<Boolean> callback) {
        if ((Object)this instanceof ServerPlayer player && MonsterRoles.isUndead(player)) callback.setReturnValue(true);
    }
    @Inject(method="releaseUsingItem", at=@At("HEAD"), cancellable=true)
    private void roleBow(CallbackInfo callback) {
        if ((Object)this instanceof ServerPlayer player && MonsterRoles.isMonster(player)) {
            MonsterRoles.releaseBow(player); callback.cancel();
        }
    }
    @Inject(method="isInvertedHealAndHarm", at=@At("HEAD"), cancellable=true)
    private void undeadHealing(CallbackInfoReturnable<Boolean> callback) {
        if ((Object)this instanceof ServerPlayer player && MonsterRoles.isUndead(player)) callback.setReturnValue(true);
    }
    @Inject(method="canBeAffected", at=@At("HEAD"), cancellable=true)
    private void nativeEffects(MobEffectInstance effect, CallbackInfoReturnable<Boolean> callback) {
        if ((Object)this instanceof ServerPlayer player && MonsterRoles.isMonster(player)) {
            var model=MonsterRoles.nativeModel(player);
            if (model != null) callback.setReturnValue(model.canBeAffected(effect));
        }
    }
    @Inject(method="dropAllDeathLoot", at=@At("HEAD"), cancellable=true)
    private void noRoleLoot(ServerLevel level, DamageSource source, CallbackInfo callback) {
        var entity=(LivingEntity)(Object)this;
        if (MonsterRoles.isMonster(entity)) {
            MonsterRoles.dropRoleEquipment((ServerPlayer)entity,true);
            callback.cancel();
        } else if (MonsterRoles.summonOwner(entity)!=null) callback.cancel();
    }
}
