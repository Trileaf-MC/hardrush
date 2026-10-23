package dev.hardrush.p0.monster.mixin.ai;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.hardrush.p0.monster.MonsterAiIdentity;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AvoidEntityGoal.class)
public abstract class SpeciesAvoidanceMixin {
    @Shadow protected LivingEntity toAvoid;
    @Shadow @Final protected Class<? extends LivingEntity> avoidClass;
    @Shadow @Final protected PathNavigation pathNav;

    @WrapOperation(method="canUse", at=@At(value="INVOKE", target="Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private List<LivingEntity> speciesCandidates(Level level, Class<? extends LivingEntity> type, AABB area,
                                                Predicate<LivingEntity> predicate, Operation<List<LivingEntity>> original) {
        return MonsterAiIdentity.candidates((ServerLevel)level, type, area, predicate);
    }

    @Inject(method="canContinueToUse", at=@At("HEAD"), cancellable=true)
    private void currentSpecies(CallbackInfoReturnable<Boolean> ci) {
        if (toAvoid != null && !MonsterAiIdentity.matches(avoidClass, toAvoid)) {
            pathNav.stop();
            ci.setReturnValue(false);
        }
    }
}
