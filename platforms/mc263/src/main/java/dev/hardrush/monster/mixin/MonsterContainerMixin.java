package dev.hardrush.monster.mixin;

import dev.hardrush.monster.MonsterInventory;
import dev.hardrush.monster.MonsterRoles;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class MonsterContainerMixin {
    @Shadow protected abstract void resetQuickCraft();
    @Inject(method="clicked", at=@At("HEAD"), cancellable=true)
    private void guard(int index, int button, ContainerInput input, Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer owner && !MonsterInventory.mayClick(owner, (AbstractContainerMenu)(Object)this, index, button, input)) {
            resetQuickCraft(); owner.containerMenu.sendAllDataToRemote(); ci.cancel();
        }
    }
    @Inject(method="clicked", at=@At("RETURN"))
    private void sync(int index, int button, ContainerInput input, Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer owner && MonsterRoles.isMonster(owner)) MonsterRoles.repairLoadout(owner);
    }
}
