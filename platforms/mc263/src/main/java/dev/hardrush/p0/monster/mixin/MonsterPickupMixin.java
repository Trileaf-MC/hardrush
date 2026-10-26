package dev.hardrush.p0.monster.mixin;
import dev.hardrush.p0.monster.MonsterRoles;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ItemEntity.class)
public abstract class MonsterPickupMixin {
    @Inject(method="playerTouch", at=@At("HEAD"), cancellable=true)
    private void noPickup(Player player, CallbackInfo callback) {
        if (player instanceof net.minecraft.server.level.ServerPlayer owner && MonsterRoles.isMonster(owner)
                && (!owner.isAlive() || !dev.hardrush.p2.monster.P2MonsterRuntime.canAct(owner)
                || dev.hardrush.p0.monster.MonsterItems.bound(((ItemEntity)(Object)this).getItem()))) callback.cancel();
    }
    @Inject(method="playerTouch", at=@At("RETURN"))
    private void refreshEquipment(Player player, CallbackInfo callback) {
        if (player instanceof net.minecraft.server.level.ServerPlayer owner && MonsterRoles.isMonster(owner)) MonsterRoles.repairLoadout(owner);
    }
}
