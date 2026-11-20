package dev.hardrush.combat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.hardrush.combat.CombatRuntime;
import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.monster.MonsterRoles;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.KineticWeapon;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A spear charge damages from the item's use tick, outside {@code Player.attack}: each tick's sweep along the charge is
 * one melee action and one batch. A monster's charge is cancelled by {@code MonsterWeaponKineticMixin} and replayed on
 * its native body.
 */
@Mixin(KineticWeapon.class)
public abstract class CombatKineticMixin {
    @WrapMethod(method = "damageEntities")
    private void humanCharge(ItemStack stack, int remaining, LivingEntity attacker, EquipmentSlot slot, Operation<Void> original) {
        if (!(attacker instanceof ServerPlayer player) || MonsterRoles.isMonster(player)) {
            original.call(stack, remaining, attacker, slot);
            return;
        }
        try (var ignored = CombatRuntime.playerAction(player.level(), player, AttackKind.MELEE)) {
            original.call(stack, remaining, attacker, slot);
        }
    }
}
