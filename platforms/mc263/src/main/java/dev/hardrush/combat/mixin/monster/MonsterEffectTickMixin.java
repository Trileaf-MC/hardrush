package dev.hardrush.combat.mixin.monster;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.hardrush.combat.EffectOrigins;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** A tick of an effect a monster's cloud applied hurts on that cloud's behalf. */
@Mixin(MobEffectInstance.class)
public abstract class MonsterEffectTickMixin {
    @Shadow @Final private Holder<MobEffect> effect;

    @WrapOperation(method = "tickServer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/effect/MobEffect;applyEffectTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;I)Z"))
    private boolean attributed(MobEffect instance, ServerLevel level, LivingEntity target, int amplifier, Operation<Boolean> original) {
        try (var ignored = EffectOrigins.ticking(level, target, effect)) { return original.call(instance, level, target, amplifier); }
    }
}
