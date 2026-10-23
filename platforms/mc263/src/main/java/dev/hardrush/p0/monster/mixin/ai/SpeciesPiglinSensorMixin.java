package dev.hardrush.p0.monster.mixin.ai;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.hardrush.p0.monster.MonsterRoles;
import java.util.ArrayList;
import java.util.function.Predicate;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.ai.sensing.PiglinSpecificSensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PiglinSpecificSensor.class)
public abstract class SpeciesPiglinSensorMixin {
    @WrapOperation(method="doTick", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/ai/memory/NearestVisibleLivingEntities;findAll(Ljava/util/function/Predicate;)Ljava/lang/Iterable;"))
    private Iterable<LivingEntity> nativeSpecies(NearestVisibleLivingEntities visible, Predicate<LivingEntity> predicate,
                                               Operation<Iterable<LivingEntity>> original) {
        // None of the three supported forms is a piglin nemesis, hoglin or zombified piglin/zoglin.
        var result = new ArrayList<LivingEntity>();
        for (var entity : original.call(visible, predicate)) if (!MonsterRoles.isMonster(entity)) result.add(entity);
        return result;
    }
}
