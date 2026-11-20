package dev.hardrush.combat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.hardrush.combat.CombatRuntime;
import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.monster.MonsterRoles;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.PiercingWeapon;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A spear jab (the vanilla STAB action) never passes through {@code Player.attack}: like a sword swing, one jab and
 * every target it pierces is one melee action and one batch. A monster's jab is redirected to its native body by
 * {@code MonsterWeaponPiercingMixin}, which carries its own provenance.
 */
@Mixin(PiercingWeapon.class)
public abstract class CombatPiercingMixin {
    @WrapMethod(method = "attack")
    private void humanJab(LivingEntity attacker, EquipmentSlot hand, Operation<Void> original) {
        if (!(attacker instanceof ServerPlayer player) || MonsterRoles.isMonster(player)) { original.call(attacker, hand); return; }
        try (var ignored = CombatRuntime.playerAction(player.level(), player, AttackKind.MELEE)) { original.call(attacker, hand); }
    }
}
