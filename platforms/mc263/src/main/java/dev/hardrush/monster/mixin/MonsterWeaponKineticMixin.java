package dev.hardrush.monster.mixin;

import dev.hardrush.monster.MonsterRoles;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.KineticWeapon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KineticWeapon.class)
public abstract class MonsterWeaponKineticMixin {
    @Inject(method = "damageEntities", at = @At("HEAD"), cancellable = true)
    private void nativeMotion(ItemStack stack, int remaining, LivingEntity attacker, EquipmentSlot slot, CallbackInfo callback) {
        if (attacker instanceof ServerPlayer player && MonsterRoles.isMonster(player)) callback.cancel();
    }
}
