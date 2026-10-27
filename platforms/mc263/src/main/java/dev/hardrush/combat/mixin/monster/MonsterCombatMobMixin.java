package dev.hardrush.combat.mixin.monster;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.hardrush.combat.MonsterCombatRuntime;
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
        if (MonsterCombatRuntime.reject(mob, target)) return false;
        try (var ignored = MonsterCombatRuntime.action(mob)) { return original.call(level, target); }
    }
}
