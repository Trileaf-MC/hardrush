package dev.hardrush.monster.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.hardrush.monster.MonsterBellAlarm;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A bell resonates for and reveals monster players as it does raiders (docs/plan/钟声显形.md). */
@Mixin(BellBlockEntity.class)
public abstract class MonsterBellMixin {
    /** Both the resonance check (32 blocks) and the glow filter (48 blocks) ask the same raider question. */
    @WrapOperation(method={"areRaidersNearby", "isRaiderWithinRange"}, require=2, at=@At(value="INVOKE",
            target="Lnet/minecraft/world/entity/LivingEntity;is(Lnet/minecraft/tags/TagKey;)Z"))
    private static boolean raider(LivingEntity entity, TagKey<EntityType<?>> tag, Operation<Boolean> original) {
        return original.call(entity, tag) || MonsterBellAlarm.counts(entity);
    }
}
