package dev.hardrush.monster.mixin;

import dev.hardrush.monster.MonsterRoles;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A body whose species is fire immune is so natively (docs/plan/怪物环境变种.md §4): {@code Entity.fireImmune} is what
 * vanilla asks before it ignites (lavaIgnite), keeps burning (baseTick clears the fire, so the on-fire flag no client
 * sees is never set), and lets fire, lava or magma hurt (isInvulnerableToBase on #is_fire, lavaHurt).
 */
@Mixin(Entity.class)
public abstract class MonsterFireImmuneMixin {
    @Inject(method="fireImmune", at=@At("HEAD"), cancellable=true)
    private void nativeFireImmunity(CallbackInfoReturnable<Boolean> callback) {
        if ((Object)this instanceof ServerPlayer player && MonsterRoles.fireImmune(player)) callback.setReturnValue(true);
    }
}
