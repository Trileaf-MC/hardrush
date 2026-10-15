package dev.hardrush.p2.combat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.hardrush.p2.combat.P2CombatRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.Zombie;
import org.spongepowered.asm.mixin.Mixin;

@Mixin({Mob.class, Zombie.class})
public abstract class MonsterCombatMobMixin {
    @WrapMethod(method="doHurtTarget")
    private boolean attack(ServerLevel level, Entity target, Operation<Boolean> original) {
        var mob = (Mob)(Object)this;
        if (P2CombatRuntime.reject(mob, target)) return false;
        try (var ignored = P2CombatRuntime.action(mob)) { return original.call(level, target); }
    }
}
