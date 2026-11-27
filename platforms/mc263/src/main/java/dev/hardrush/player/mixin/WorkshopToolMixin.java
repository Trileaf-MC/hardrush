package dev.hardrush.player.mixin;

import dev.hardrush.lobby.workshop.Toolkit;
import dev.hardrush.lobby.workshop.WorkshopTable;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Builders' tool clicks (design 4.4): the toolbox opens its chest instead of being placed, the binding wand opens its
 * menus, and a marker armor stand is edited instead of dressed. Only builders inside their draft are affected, besides
 * the camp's workshop table, which opens the workshop chest instead of being placed; the
 * replay on the server thread is where the decision is made, ahead of every other guard.
 */
@Mixin(value = ServerGamePacketListenerImpl.class, priority = 900)
public abstract class WorkshopToolMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleUseItem", at = @At("HEAD"), cancellable = true)
    private void hardrush$toolboxInAir(ServerboundUseItemPacket packet, CallbackInfo ci) {
        if (player.level().getServer().isSameThread()
                && (Toolkit.useItem(player, packet.hand()) || WorkshopTable.useItem(player, packet.hand()))) {
            player.inventoryMenu.sendAllDataToRemote();
            ci.cancel();
        }
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
    private void hardrush$toolOnBlock(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (player.level().getServer().isSameThread()
                && (Toolkit.useItemOn(player, packet) || WorkshopTable.useItemOn(player, packet))) ci.cancel();
    }

    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void hardrush$toolOnEntity(ServerboundInteractPacket packet, CallbackInfo ci) {
        if (player.level().getServer().isSameThread() && Toolkit.interact(player, packet)) ci.cancel();
    }
}
