package dev.hardrush.monster.mixin;
import dev.hardrush.monster.MonsterRoles;
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
    @Inject(method="checkTotemDeathProtection", at=@At("HEAD"), cancellable=true)
    private void noCreeperEquipmentProtection(DamageSource source, CallbackInfoReturnable<Boolean> callback) {
        if ((Object)this instanceof ServerPlayer player
                && MonsterRoles.currentForm(player) == dev.hardrush.monster.MonsterForm.CREEPER) callback.setReturnValue(false);
    }
    @Inject(method="addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z", at=@At("HEAD"), cancellable=true)
    private void ownSight(MobEffectInstance effect, net.minecraft.world.entity.Entity source, CallbackInfoReturnable<Boolean> callback) {
        if ((Object)this instanceof ServerPlayer player && MonsterRoles.refusesSight(player, effect)) callback.setReturnValue(false);
    }
    @Inject(method="canBreatheUnderwater", at=@At("HEAD"), cancellable=true)
    private void undeadBreathing(CallbackInfoReturnable<Boolean> callback) {
        if ((Object)this instanceof ServerPlayer player && MonsterRoles.isUndead(player)) callback.setReturnValue(true);
    }
    @Inject(method="releaseUsingItem", at=@At("HEAD"), cancellable=true)
    private void roleBow(CallbackInfo callback) {
        // A witch potion is let go of natively, like anyone's.
        if ((Object)this instanceof ServerPlayer player && MonsterRoles.isMonster(player)
                && !dev.hardrush.monster.MonsterPotions.drinking(player)) {
            MonsterRoles.releaseUsingItem(player); callback.cancel();
        }
    }
    @Inject(method="completeUsingItem", at=@At("HEAD"))
    private void witchPotion(CallbackInfo callback) {
        if ((Object)this instanceof ServerPlayer player && MonsterRoles.isMonster(player)) dev.hardrush.monster.MonsterPotions.finishing(player);
    }
    @Inject(method="isInvertedHealAndHarm", at=@At("HEAD"), cancellable=true)
    private void undeadHealing(CallbackInfoReturnable<Boolean> callback) {
        if ((Object)this instanceof ServerPlayer player && MonsterRoles.isUndead(player)) callback.setReturnValue(true);
    }
    @Inject(method="canFreeze", at=@At("HEAD"), cancellable=true)
    private void nativeFreezing(CallbackInfoReturnable<Boolean> callback) {
        if ((Object)this instanceof ServerPlayer player && MonsterRoles.isMonster(player)) {
            var model = MonsterRoles.nativeModel(player);
            if (model != null) callback.setReturnValue(model.canFreeze());
        }
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
            MonsterRoles.dropRoleEquipment((ServerPlayer)entity,source);
            dev.hardrush.monster.MonsterWorthRewards.slain((ServerPlayer)entity);
            callback.cancel();
        } else if (dev.hardrush.monster.MonsterCompanions.isDismissing(entity)) callback.cancel();
    }
}
