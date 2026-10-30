package dev.hardrush.protection.mixin;

import dev.hardrush.book.WrittenBookPort;
import dev.hardrush.lobby.LobbyInteractions;
import dev.hardrush.lobby.RevivalTotems;
import dev.hardrush.protection.ProtectionRuntime;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ProtectionNetworkMixin {
    @Shadow public ServerPlayer player;
    private boolean mainThread() { return player.level().getServer().isSameThread(); }
    @Inject(method="handleMovePlayer", at=@At("HEAD"), cancellable=true)
    private void move(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!mainThread()) return;
        if (new Vec3(packet.getX(player.getX()), packet.getY(player.getY()), packet.getZ(player.getZ()))
                .distanceToSqr(player.position()) > .000001) dev.hardrush.lobby.LobbyRuntime.interruptReturn(player, "移动");
        if (ProtectionRuntime.rejectMove(player, player.level(), new Vec3(packet.getX(player.getX()), packet.getY(player.getY()), packet.getZ(player.getZ())))) {
            ProtectionRuntime.correct(player); ci.cancel();
        }
    }
    @Inject(method="handleMoveVehicle", at=@At("HEAD"), cancellable=true)
    private void vehicle(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
        if (!mainThread()) return;
        if (packet.movingTo().position().distanceToSqr(player.getRootVehicle().position()) > .000001)
            dev.hardrush.lobby.LobbyRuntime.interruptReturn(player, "移动");
        var vehicle = player.getRootVehicle();
        if (ProtectionRuntime.rejectMove(vehicle, player.level(), packet.movingTo().position())) {
            ProtectionRuntime.correct(player); ci.cancel();
        }
    }
    @Inject(method="handlePlayerAction", at=@At("HEAD"), cancellable=true)
    private void action(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (!mainThread()) return;
        switch (packet.getAction()) {
            case START_DESTROY_BLOCK, STOP_DESTROY_BLOCK, ABORT_DESTROY_BLOCK -> {
                dev.hardrush.lobby.LobbyRuntime.interruptReturn(player, "攻击方块");
                if (ProtectionRuntime.rejectInteraction(player, player.level(), packet.getPos())) {
                    player.connection.send(new ClientboundBlockUpdatePacket(player.level(), packet.getPos())); ci.cancel();
                }
            }
            case DROP_ITEM, DROP_ALL_ITEMS -> {
                // Revival totems are the one lobby item that may be thrown; RevivalTotems commits or restores it.
                if (ProtectionRuntime.protectedEntity(player)
                        && !RevivalTotems.is(player.getInventory().getSelectedItem())) ci.cancel();
            }
            default -> {}
        }
    }
    @Inject(method="handleUseItemOn", at=@At("HEAD"), cancellable=true)
    private void block(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (!mainThread()) return;
        if (LobbyInteractions.consumed(LobbyInteractions.block(player, packet))) { ci.cancel(); return; }
        if (ProtectionRuntime.rejectInteraction(player, player.level(), packet.hitResult().getBlockPos())
                || ProtectionRuntime.protectedBlock(player.level(), packet.hitResult().getBlockPos().relative(packet.hitResult().getDirection()))) {
            player.connection.send(new ClientboundBlockUpdatePacket(player.level(), packet.hitResult().getBlockPos())); ci.cancel();
        }
    }
    @Inject(method="handleUseItem", at=@At("HEAD"), cancellable=true)
    private void item(ServerboundUseItemPacket packet, CallbackInfo ci) {
        if (mainThread() && ProtectionRuntime.protectedEntity(player)
                && !WrittenBookPort.isHistoryBook(player.getItemInHand(packet.hand()))) ci.cancel();
    }
    @Inject(method="handleInteract", at=@At("HEAD"), cancellable=true)
    private void entity(ServerboundInteractPacket packet, CallbackInfo ci) {
        if (mainThread() && LobbyInteractions.consumed(LobbyInteractions.entity(player, packet))) { ci.cancel(); return; }
        if (mainThread() && (ProtectionRuntime.protectedEntity(player)
                || ProtectionRuntime.protectedEntity(player.level().getEntity(packet.entityId())))) ci.cancel();
    }
    @Inject(method="handleContainerClick", at=@At("HEAD"), cancellable=true)
    private void inventory(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (dev.hardrush.lobby.LobbyMenus.isMenu(player)) return;
        // The witch's menu accepts only its own guarded result click; see MonsterShop.ShopMenu.
        if (mainThread() && dev.hardrush.lobby.MonsterShop.isShop(player)) return;
        if (!mainThread() || !ProtectionRuntime.protectedEntity(player)) return;
        if (RevivalTotems.throwsFromInventory(player, packet)) return;
        // Personal inventory sorting is allowed. QUICK_CRAFT uses negative-slot control packets
        // to begin/end a normal drag; only PICKUP/outside and THROW actually discard items.
        if (player.containerMenu != player.inventoryMenu || packet.containerInput() == ContainerInput.THROW
                || packet.slotNum() < 0 && packet.containerInput() != ContainerInput.QUICK_CRAFT) {
            player.containerMenu.sendAllDataToRemote(); ci.cancel();
        }
    }
    @Inject(method={"handleAttack", "handlePunch", "handleContainerButtonClick", "handlePlaceRecipe"},
            at=@At("HEAD"), cancellable=true)
    private void protectedInput(CallbackInfo ci) {
        if (mainThread() && ProtectionRuntime.protectedEntity(player)) ci.cancel();
    }
}
