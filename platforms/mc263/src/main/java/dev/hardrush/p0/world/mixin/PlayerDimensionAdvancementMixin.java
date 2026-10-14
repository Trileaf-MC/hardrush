package dev.hardrush.p0.world.mixin;

import dev.hardrush.p1.world.RoundWorldRouting;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Semantic keys apply only to advancement checks, never player position, packets or storage. */
@Mixin(ServerPlayer.class)
public abstract class PlayerDimensionAdvancementMixin {
    @Shadow private Vec3 enteredNetherPosition;

    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;setServerLevel(Lnet/minecraft/server/level/ServerLevel;)V"))
    private void hardrush$rememberNetherEntry(TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> cir) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (RoundWorldRouting.sameRound(player.level(), transition.newLevel())
                && RoundWorldRouting.semanticDimension(player.level()).equals(Level.OVERWORLD)
                && RoundWorldRouting.semanticDimension(transition.newLevel()).equals(Level.NETHER)) {
            enteredNetherPosition = player.position();
        }
    }

    @Redirect(method = "triggerDimensionChangeTriggers", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;dimension()Lnet/minecraft/resources/ResourceKey;"), require = 2)
    private ResourceKey<Level> hardrush$advancementDimension(ServerLevel level, ServerLevel source) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        return RoundWorldRouting.sameRound(source, player.level())
                ? RoundWorldRouting.semanticDimension(level) : level.dimension();
    }
}
