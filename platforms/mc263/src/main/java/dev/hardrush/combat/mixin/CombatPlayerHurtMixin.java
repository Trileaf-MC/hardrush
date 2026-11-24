package dev.hardrush.combat.mixin;

import dev.hardrush.combat.CombatRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What a hit brought before armour, enchantments and absorption (docs/plan/悬赏分档与追猎强度.md §6.1). Vanilla 26.3's
 * {@code LivingEntity.hurtServer} has already taken off a shield's block and the invulnerability window's overlap,
 * and {@code Player.hurtServer} has scaled by difficulty, so {@code Player.actuallyHurt} receives exactly that.
 */
@Mixin(Player.class)
public abstract class CombatPlayerHurtMixin {
    @Inject(method = "actuallyHurt", at = @At("HEAD"))
    private void incoming(ServerLevel level, DamageSource source, float damage, CallbackInfo info) {
        if (!((Player) (Object) this).isInvulnerableTo(level, source)) CombatRuntime.incomingDamage((Player) (Object) this, damage);
    }
}
