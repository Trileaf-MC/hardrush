package dev.hardrush.p0.monster.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.hardrush.p0.monster.MonsterNativeWeapons;
import dev.hardrush.p0.monster.MonsterRoles;
import dev.hardrush.p1.combat.P1CombatRuntime;
import dev.hardrush.p2.combat.P2CombatRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** ThrownTrident overrides AbstractArrow.onHitEntity and therefore needs its own admission scope. */
@Mixin(ThrownTrident.class)
public abstract class MonsterWeaponTridentMixin {
    @WrapMethod(method = "onHitEntity")
    private void admittedImpact(EntityHitResult hit, Operation<Void> original) {
        var trident = (ThrownTrident) (Object) this;
        if (!(trident.level() instanceof ServerLevel level)) { original.call(hit); return; }
        var owner = MonsterNativeWeapons.tridentOwner(trident);
        if (P2CombatRuntime.reject(trident, hit.getEntity())
                || owner != null && !MonsterRoles.legalTarget(owner, level, hit.getEntity())) {
            trident.discard(); return;
        }
        if (P1CombatRuntime.rejectDamage(level)) return;
        try (var ignored = P1CombatRuntime.projectileAction(level, trident);
             var monster = P2CombatRuntime.action(trident)) { original.call(hit); }
    }

    @ModifyArg(method = "onHitEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/damagesource/DamageSources;trident(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/damagesource/DamageSource;"), index = 1)
    private Entity nativeSource(Entity owner) {
        return MonsterNativeWeapons.tridentShooter((ThrownTrident) (Object) this, owner);
    }

}
