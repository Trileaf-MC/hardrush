package dev.hardrush.monster.mixin;

import dev.hardrush.combat.MonsterCombatRuntime;
import dev.hardrush.monster.MonsterCompanions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A warden picks targets through its anger, not through Mob.setTarget or targeting conditions, so a companion warden
 * (a monster box's) is kept off its owner and allies here; otherwise it would swing at an owner it can never hurt.
 */
@Mixin(Warden.class)
public abstract class CompanionWardenMixin {
    @Inject(method="canTargetEntity", at=@At("RETURN"), cancellable=true)
    private void companionTargets(Entity entity, CallbackInfoReturnable<Boolean> callback) {
        var warden = (Warden) (Object) this;
        if (callback.getReturnValueZ() && MonsterCompanions.owner(warden) != null
                && (!MonsterCompanions.allowedTarget(warden, entity) || MonsterCombatRuntime.reject(warden, entity))) {
            callback.setReturnValue(false);
        }
    }
}
