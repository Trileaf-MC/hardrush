package dev.hardrush.death.mixin;

import dev.hardrush.death.DeathNarration;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A deployed monster player, named by its form while a death message is built ({@link DeathNarration#displayName}). */
@Mixin(Player.class)
public abstract class DeathNamePlayerMixin {
    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
    private void hardrush$deathName(CallbackInfoReturnable<Component> callback) {
        var name = DeathNarration.displayName((Player) (Object) this);
        if (name != null) callback.setReturnValue(name);
    }
}
