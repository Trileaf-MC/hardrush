package dev.hardrush.monster.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.hardrush.monster.MonsterLegacy;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Silverfish crawling out of an infested monster player act for that monster (docs/plan/女巫药水与药水盲盒.md §5). */
@Mixin(targets = "net.minecraft.world.effect.InfestedMobEffect")
public abstract class MonsterInfestedMixin {
    @WrapOperation(method = "spawnSilverfish", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean adopted(ServerLevel level, Entity silverfish, Operation<Boolean> original, @Local(argsOnly = true) LivingEntity mob) {
        MonsterLegacy.infested(mob, silverfish);
        return original.call(level, silverfish);
    }
}
