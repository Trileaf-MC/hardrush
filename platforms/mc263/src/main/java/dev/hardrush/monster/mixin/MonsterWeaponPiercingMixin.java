package dev.hardrush.monster.mixin;

import dev.hardrush.monster.MonsterNativeWeapons;
import dev.hardrush.monster.MonsterRoles;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.PiercingWeapon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PiercingWeapon.class)
public abstract class MonsterWeaponPiercingMixin {
    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void nativeStab(LivingEntity attacker, EquipmentSlot hand, CallbackInfo callback) {
        if (attacker instanceof ServerPlayer player && MonsterRoles.isMonster(player)) {
            MonsterNativeWeapons.stabbing(player, hand);
            callback.cancel();
        }
    }
}
