package dev.hardrush.monster.mixin.ai;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.hardrush.monster.MonsterRoles;
import java.util.List;
import net.minecraft.server.level.ServerEntityGetter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerEntityGetter.class)
public interface SpeciesPlayerQueryMixin {
    @WrapOperation(method="getNearestPlayer", at=@At(value="INVOKE", target="Lnet/minecraft/server/level/ServerEntityGetter;getNearestEntity(Ljava/util/List;Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;Lnet/minecraft/world/entity/LivingEntity;DDD)Lnet/minecraft/world/entity/LivingEntity;"))
    private LivingEntity humanCandidates(ServerEntityGetter getter, List<? extends LivingEntity> candidates,
                                         TargetingConditions conditions, LivingEntity source, double x, double y, double z,
                                         Operation<LivingEntity> original) {
        if (source instanceof Mob && MonsterRoles.summonOwner(source) == null)
            candidates = candidates.stream().filter(p -> !MonsterRoles.isMonster(p)).toList();
        return original.call(getter, candidates, conditions, source, x, y, z);
    }

    @WrapOperation(method="getNearbyPlayers", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;test(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/LivingEntity;)Z"))
    private boolean humanTarget(TargetingConditions conditions, ServerLevel level, LivingEntity source, LivingEntity target,
                                Operation<Boolean> original) {
        return !(source instanceof Mob && MonsterRoles.summonOwner(source) == null && MonsterRoles.isMonster(target))
                && original.call(conditions, level, source, target);
    }
}
