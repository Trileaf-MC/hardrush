package dev.hardrush.combat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.combat.CombatRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

/** Holds the whole native attack, including sweep, knockback and enchantment effects, in one batch. */
@Mixin(Player.class)
public abstract class CombatPlayerAttackMixin {
    @WrapMethod(method="attack")
    private void attack(Entity target, Operation<Void> original) {
        if (!((Object)this instanceof ServerPlayer player)) { original.call(target); return; }
        var level = player.level();
        if (CombatRuntime.rejectDamage(level) || dev.hardrush.pact.DuskPactRuntime.reject(player, target)) return;
        try (var ignored = CombatRuntime.playerAction(level, player, AttackKind.MELEE)) { original.call(target); }
    }
}
