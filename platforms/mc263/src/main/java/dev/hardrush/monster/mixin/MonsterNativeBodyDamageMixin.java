package dev.hardrush.monster.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.hardrush.monster.MonsterNativeWeapons;
import dev.hardrush.monster.MonsterRoles;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

/** Native enchantments must hurt the live body, never an unregistered or obsolete template. */
@Mixin({LivingEntity.class, ServerPlayer.class})
public abstract class MonsterNativeBodyDamageMixin {
    @WrapMethod(method = "hurtServer")
    private boolean authoritativeBody(ServerLevel level, DamageSource source, float damage, Operation<Boolean> original) {
        var model = (LivingEntity) (Object) this;
        source = MonsterNativeWeapons.nativeRetaliationSource(source);
        return MonsterRoles.isNativeBody(model)
                ? MonsterNativeWeapons.hurtNativeBody(model, level, source, damage)
                : original.call(level, source, damage);
    }
}
