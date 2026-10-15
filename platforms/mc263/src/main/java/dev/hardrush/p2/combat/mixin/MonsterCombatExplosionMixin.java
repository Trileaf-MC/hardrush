package dev.hardrush.p2.combat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.hardrush.p2.combat.P2CombatRuntime;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerExplosion.class)
public abstract class MonsterCombatExplosionMixin {
    @WrapMethod(method="explode")
    private int explosion(Operation<Integer> original) {
        var source = ((ServerExplosion)(Object)this).getDirectSourceEntity();
        if (source == null) return original.call();
        try (var ignored = P2CombatRuntime.action(source)) { return original.call(); }
    }
}
