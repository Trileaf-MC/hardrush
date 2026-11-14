package dev.hardrush.protection.mixin;

import dev.hardrush.protection.ProtectionRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Right-clicking an interactive block (see LobbyInteractive) is let through to vanilla, which falls back to the
 * held item when the block does not consume the click. Held items must never act on a sealed level.
 */
@Mixin(ItemStack.class)
public abstract class ProtectionItemMixin {
    @Inject(method="useOn", at=@At("HEAD"), cancellable=true)
    private void useOn(UseOnContext context, CallbackInfoReturnable<InteractionResult> ci) {
        if (context.getLevel() instanceof ServerLevel level && ProtectionRuntime.protectedLevel(level)) ci.setReturnValue(InteractionResult.PASS);
    }
}
