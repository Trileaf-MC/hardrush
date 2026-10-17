package dev.hardrush.p2.protection.mixin;

import dev.hardrush.p1.book.P1BookPort;
import dev.hardrush.p1.lobby.LobbyInteractions;
import dev.hardrush.p2.protection.P2ProtectionRuntime;
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
                .distanceToSqr(player.position()) > .000001) dev.hardrush.p2.lobby.P2LobbyRuntime.interruptReturn(player);
        if (P2ProtectionRuntime.rejectMove(player, player.level(), new Vec3(packet.getX(player.getX()), packet.getY(player.getY()), packet.getZ(player.getZ())))) {
            P2ProtectionRuntime.correct(player); ci.cancel();
        }
    }
    @Inject(method="handleMoveVehicle", at=@At("HEAD"), cancellable=true)
    private void vehicle(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
        if (!mainThread()) return;
        if (packet.movingTo().position().distanceToSqr(player.getRootVehicle().position()) > .000001)
            dev.hardrush.p2.lobby.P2LobbyRuntime.interruptReturn(player);
        var vehicle = player.getRootVehicle();
        if (P2ProtectionRuntime.rejectMove(vehicle, player.level(), packet.movingTo().position())) {
            P2ProtectionRuntime.correct(player); ci.cancel();
        }
    }
    @Inject(method="handlePlayerAction", at=@At("HEAD"), cancellable=true)
    private void action(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (!mainThread()) return;
        switch (packet.getAction()) {
            case START_DESTROY_BLOCK, STOP_DESTROY_BLOCK, ABORT_DESTROY_BLOCK -> {
                if (P2ProtectionRuntime.rejectInteraction(player, player.level(), packet.getPos())) {
                    player.connection.send(new ClientboundBlockUpdatePacket(player.level(), packet.getPos())); ci.cancel();
                }
            }
            case DROP_ITEM, DROP_ALL_ITEMS -> { if (P2ProtectionRuntime.protectedEntity(player)) ci.cancel(); }
            default -> {}
        }
    }
    @Inject(method="handleUseItemOn", at=@At("HEAD"), cancellable=true)
    private void block(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (!mainThread()) return;
        if (LobbyInteractions.consumed(LobbyInteractions.block(player, packet))) { ci.cancel(); return; }
        if (P2ProtectionRuntime.rejectInteraction(player, player.level(), packet.hitResult().getBlockPos())
                || P2ProtectionRuntime.protectedBlock(player.level(), packet.hitResult().getBlockPos().relative(packet.hitResult().getDirection()))) {
            player.connection.send(new ClientboundBlockUpdatePacket(player.level(), packet.hitResult().getBlockPos())); ci.cancel();
        }
    }
    @Inject(method="handleUseItem", at=@At("HEAD"), cancellable=true)
    private void item(ServerboundUseItemPacket packet, CallbackInfo ci) {
        if (mainThread() && P2ProtectionRuntime.protectedEntity(player)
                && !P1BookPort.isHistoryBook(player.getItemInHand(packet.hand()))) ci.cancel();
    }
    @Inject(method="handleInteract", at=@At("HEAD"), cancellable=true)
    private void entity(ServerboundInteractPacket packet, CallbackInfo ci) {
        if (mainThread() && LobbyInteractions.consumed(LobbyInteractions.entity(player, packet))) { ci.cancel(); return; }
        if (mainThread() && (P2ProtectionRuntime.protectedEntity(player)
                || P2ProtectionRuntime.protectedEntity(player.level().getEntity(packet.entityId())))) ci.cancel();
    }
    @Inject(method="handleContainerClick", at=@At("HEAD"), cancellable=true)
    private void inventory(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (dev.hardrush.p2.lobby.P2LobbyUi.isMenu(player)) return;
        if (!mainThread() || !P2ProtectionRuntime.protectedEntity(player)) return;
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
        if (mainThread() && P2ProtectionRuntime.protectedEntity(player)) ci.cancel();
    }
}
