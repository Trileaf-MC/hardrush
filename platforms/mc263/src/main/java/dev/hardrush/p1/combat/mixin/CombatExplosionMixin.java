package dev.hardrush.p1.combat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.hardrush.p1.combat.P1CombatRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;

@Mixin(ServerExplosion.class)
public abstract class CombatExplosionMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Entity source;
    @WrapMethod(method="explode")
    private int batch(Operation<Integer> original) {
        if (P1CombatRuntime.rejectDamage(level)) return 0;
        try (var ignored = P1CombatRuntime.explosion(level, source)) { return original.call(); }
    }
}
