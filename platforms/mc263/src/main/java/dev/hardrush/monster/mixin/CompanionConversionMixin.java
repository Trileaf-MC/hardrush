package dev.hardrush.monster.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.hardrush.monster.MonsterCompanions;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.ConversionType;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Transfer ownership before the new mob enters the world and before entity-load listeners see it. */
@Mixin(Mob.class)
public abstract class CompanionConversionMixin {
    @WrapOperation(method="convertTo(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/ConversionParams;Lnet/minecraft/world/entity/EntitySpawnReason;Lnet/minecraft/world/entity/ConversionParams$AfterConversion;)Lnet/minecraft/world/entity/Mob;",
            at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/ConversionType;convert(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/world/entity/Mob;Lnet/minecraft/world/entity/ConversionParams;)V"))
    private void inheritConversion(ConversionType type, Mob source, Mob child, ConversionParams params, Operation<Void> original) {
        original.call(type, source, child, params);
        MonsterCompanions.inherit(MonsterCompanions.conversionSource(source), child);
    }
}
