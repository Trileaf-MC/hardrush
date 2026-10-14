package dev.hardrush.p1.combat.mixin;

import dev.hardrush.p1.combat.P1CombatRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native collision pickups have no gameplay packet, so they need their own celebration gate. */
@Mixin({ItemEntity.class, ExperienceOrb.class})
public abstract class CombatPickupMixin {
    @Inject(method="playerTouch", at=@At("HEAD"), cancellable=true)
    private void pickup(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer && P1CombatRuntime.competitionFrozen(serverPlayer.level())) ci.cancel();
    }
}
