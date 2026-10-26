package dev.hardrush.p0.monster.mixin;

import dev.hardrush.p0.monster.MonsterInventory;
import dev.hardrush.p0.monster.MonsterItems;
import dev.hardrush.p0.monster.MonsterRoles;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Also covers vanilla cursor cleanup on closing a full storage menu. */
@Mixin(ServerPlayer.class)
public abstract class MonsterBoundDropMixin {
    @Inject(method="drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;", at=@At("HEAD"), cancellable=true)
    private void retainBound(ItemStack stack, boolean thrown, Prediction prediction, CallbackInfoReturnable<ItemEntity> ci) {
        var player = (ServerPlayer)(Object)this;
        if (!MonsterRoles.isMonster(player) || !MonsterItems.bound(stack)) return;
        MonsterInventory.recoverBoundDrop(player, stack);
        ci.setReturnValue(null);
    }
}
