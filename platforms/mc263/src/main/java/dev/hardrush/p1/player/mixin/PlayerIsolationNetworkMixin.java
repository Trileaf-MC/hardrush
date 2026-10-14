package dev.hardrush.p1.player.mixin;

import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p1.combat.P1CombatRuntime;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PlayerIsolationNetworkMixin {
    @Shadow public ServerPlayer player;

    // Off-thread invocations proceed to vanilla's PacketUtils scheduling. The replay on the server thread is cancelled here.
    @Inject(method = {"handleMovePlayer", "handleMoveVehicle", "handlePlayerInput", "handlePlayerAction",
            "handleUseItemOn", "handleUseItem", "handleAttack", "handleInteract", "handlePunch",
            "handlePlayerCommand", "handlePaddleBoat", "handleSetCarriedItem", "handleContainerClose",
            "handleContainerClick", "handlePlaceRecipe", "handleContainerButtonClick", "handleSetCreativeModeSlot",
            "handlePlayerAbilities", "handleSpectatorAction", "handleTeleportToEntityPacket",
            "handleRenameItem", "handleSelectTrade", "handleSetBeaconPacket", "handleEditBook", "handleSignUpdate",
            "handlePickItemFromBlock", "handlePickItemFromEntity", "handleBundleItemSelectedPacket",
            "handleContainerSlotStateChanged", "handleSetCommandBlock", "handleSetCommandMinecart",
            "handleSetStructureBlock", "handleSetTestBlock", "handleTestInstanceBlockAction", "handleSetJigsawBlock",
            "handleJigsawGenerate", "handleSetGameRule", "handleChangeDifficulty", "handleChangeGameMode", "handleLockDifficulty"},
            at = @At("HEAD"), cancellable = true)
    private void hardrush$holdGameplay(CallbackInfo ci) {
        if (player.level().getServer().isSameThread() && P1PlayerProfiles.isLocked(player)) ci.cancel();
    }

    // Celebration allows movement and closing a menu, while sealing all new competitive actions.
    @Inject(method = {"handlePlayerAction", "handleUseItemOn", "handleUseItem", "handleAttack", "handleInteract", "handlePunch",
            "handleContainerClick", "handlePlaceRecipe", "handleContainerButtonClick", "handleSetCreativeModeSlot",
            "handleRenameItem", "handleSelectTrade", "handleSetBeaconPacket", "handleEditBook", "handleSignUpdate",
            "handlePickItemFromBlock", "handlePickItemFromEntity", "handleBundleItemSelectedPacket",
            "handleContainerSlotStateChanged", "handleSetCommandBlock", "handleSetCommandMinecart",
            "handleSetStructureBlock", "handleSetTestBlock", "handleTestInstanceBlockAction", "handleSetJigsawBlock",
            "handleJigsawGenerate", "handleSetGameRule", "handleChangeDifficulty", "handleChangeGameMode", "handleLockDifficulty"},
            at = @At("HEAD"), cancellable = true)
    private void hardrush$holdCompetition(CallbackInfo ci) {
        if (player.level().getServer().isSameThread() && P1CombatRuntime.competitionFrozen(player.level())) ci.cancel();
    }

    @Inject(method = "handleClientCommand", at = @At("HEAD"), cancellable = true)
    private void hardrush$allowOnlyCommittedRespawn(ServerboundClientCommandPacket packet, CallbackInfo ci) {
        if (!player.level().getServer().isSameThread() || !P1PlayerProfiles.isLocked(player)) return;
        if (packet.getAction() != ServerboundClientCommandPacket.Action.PERFORM_RESPAWN
                || !P1PlayerProfiles.isRespawnAllowed(player)) ci.cancel();
    }

    @Inject(method = "performUnsignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void hardrush$holdCommand(String command, CallbackInfo ci) {
        if (P1PlayerProfiles.isLocked(player) && !hardrush$allowedCommand(command)) ci.cancel();
    }

    @Inject(method = "performSignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void hardrush$holdSignedCommand(ServerboundChatCommandSignedPacket packet, LastSeenMessages messages, CallbackInfo ci) {
        if (P1PlayerProfiles.isLocked(player) && !hardrush$allowedCommand(packet.command())) ci.cancel();
    }

    private static boolean hardrush$allowedCommand(String command) {
        return command.equals("hardrush") || command.startsWith("hardrush ");
    }
}
