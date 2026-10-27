package dev.hardrush.monster.mixin.ai;

import dev.hardrush.monster.MonsterRoles;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Enderman.class)
public abstract class SpeciesEndermanMixin {
    @Inject(method="isBeingStaredBy", at=@At("HEAD"), cancellable=true)
    private void nativeGaze(Player player, CallbackInfoReturnable<Boolean> ci) {
        if (MonsterRoles.isMonster(player)) ci.setReturnValue(false);
    }
}
