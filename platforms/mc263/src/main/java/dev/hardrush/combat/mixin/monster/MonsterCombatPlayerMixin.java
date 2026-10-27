package dev.hardrush.combat.mixin.monster;

import dev.hardrush.combat.MonsterCombatRuntime;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class MonsterCombatPlayerMixin {
    @Inject(method="attack", at=@At("HEAD"), cancellable=true)
    private void attack(Entity target, CallbackInfo ci) {
        if (MonsterCombatRuntime.formalMonsterPlayer((Entity)(Object)this)) {
            // Fabric's AttackEntityCallback is another HEAD injection. Whichever guard runs
            // first owns the same native-role path and cancels vanilla; never depend on order.
            dev.hardrush.monster.MonsterRoles.attack((net.minecraft.server.level.ServerPlayer)(Object)this, target);
            ci.cancel();
        }
    }
}
