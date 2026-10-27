package dev.hardrush.monster.mixin;
import dev.hardrush.monster.MonsterCompanions;
import dev.hardrush.combat.MonsterCombatRuntime;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Mob.class)
public abstract class SummonTargetMixin {
    @Inject(method="setTarget", at=@At("HEAD"), cancellable=true)
    private void permittedTargets(LivingEntity target, CallbackInfo callback) {
        var mob=(Mob)(Object)this; var owner=MonsterCompanions.owner(mob);
        if (owner!=null && target!=null && mob.level() instanceof ServerLevel level
                && (!MonsterCompanions.allowedTarget(mob,target) || MonsterCombatRuntime.reject(mob,target))) callback.cancel();
    }
    @Inject(method="getTarget", at=@At("RETURN"), cancellable=true)
    private void forgetInvalid(CallbackInfoReturnable<LivingEntity> callback) {
        var mob=(Mob)(Object)this; var owner=MonsterCompanions.owner(mob); var target=callback.getReturnValue();
        if (owner!=null && target!=null && mob.level() instanceof ServerLevel level
                && (!MonsterCompanions.allowedTarget(mob,target) || MonsterCombatRuntime.reject(mob,target))) {
            mob.setTarget(null); callback.setReturnValue(null);
        }
    }
}
