package dev.hardrush.p0.monster.mixin;

import dev.hardrush.p0.monster.MonsterCreeper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class MonsterCreeperLightningMixin {
    @Inject(method = "thunderHit", at = @At("RETURN"))
    private void creeperLightning(ServerLevel level, LightningBolt bolt, CallbackInfo callback) {
        if ((Object)this instanceof ServerPlayer player) MonsterCreeper.onLightning(player);
    }
}
