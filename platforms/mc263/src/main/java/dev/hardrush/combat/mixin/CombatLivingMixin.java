package dev.hardrush.combat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.hardrush.combat.CombatRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LivingEntity.class)
public abstract class CombatLivingMixin {
    @WrapMethod(method="hurtServer")
    private boolean damage(ServerLevel level, DamageSource source, float amount, Operation<Boolean> original) {
        if (CombatRuntime.rejectDamage(level)) return false;
        if (!((Object)this instanceof ServerPlayer player)) return original.call(level, source, amount);
        float before = player.getHealth();
        try (var ignored = CombatRuntime.damage(level, source)) {
            boolean accepted = original.call(level, source, amount);
            if (before > 0 && player.getHealth() <= 0) CombatRuntime.observed(level, player, source, Math.max(0, before - player.getHealth()), true);
            return accepted;
        }
    }
}
