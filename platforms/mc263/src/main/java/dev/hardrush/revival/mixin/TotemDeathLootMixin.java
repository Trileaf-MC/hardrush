package dev.hardrush.revival.mixin;

import dev.hardrush.revival.TotemKeeping;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Death never spills the dying player's own totems (design 2.6), nor the 遗落之地 compass of the life that ends. Runs beside {@code MonsterLivingMixin.noRoleLoot} at
 * the same head: that one cancels the whole loot of a monster body, this one only empties the totems, so their
 * order does not matter.
 */
@Mixin(LivingEntity.class)
public abstract class TotemDeathLootMixin {
    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"))
    private void hardrush$keepTotems(ServerLevel level, DamageSource source, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        TotemKeeping.stripForDeath(player);
        dev.hardrush.revival.FallenCompass.stripForDeath(player);
    }
}
