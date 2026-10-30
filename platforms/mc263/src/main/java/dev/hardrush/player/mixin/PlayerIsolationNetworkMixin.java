package dev.hardrush.player.mixin;

import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.lobby.LobbyInteractions;
import dev.hardrush.lobby.RevivalTotems;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import dev.hardrush.combat.CombatRuntime;
import dev.hardrush.round.RoundRuntime;
import dev.hardrush.book.WrittenBookPort;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import dev.hardrush.celebration.CelebrationRuntime;
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
    @Inject(method = {"handleMoveVehicle", "handleAttack", "handlePunch", "handlePaddleBoat",
            "handlePlaceRecipe", "handleContainerButtonClick", "handleSetCreativeModeSlot",
            "handlePlayerAbilities",
            "handleRenameItem", "handleSetBeaconPacket", "handleEditBook", "handleSignUpdate",
            "handlePickItemFromBlock", "handlePickItemFromEntity", "handleBundleItemSelectedPacket",
            "handleContainerSlotStateChanged", "handleSetCommandBlock", "handleSetCommandMinecart",
            "handleSetStructureBlock", "handleSetTestBlock", "handleTestInstanceBlockAction", "handleSetJigsawBlock",
            "handleJigsawGenerate", "handleSetGameRule", "handleChangeDifficulty", "handleChangeGameMode", "handleLockDifficulty"},
            at = @At("HEAD"), cancellable = true)
    private void hardrush$holdGameplay(CallbackInfo ci) {
        if (player.level().getServer().isSameThread() && PlayerProfiles.isLocked(player)) ci.cancel();
    }

    // A camp monster's isolated body may still pick an offer in the witch's guarded menu.
    @Inject(method = "handleSelectTrade", at = @At("HEAD"), cancellable = true)
    private void hardrush$holdTrade(CallbackInfo ci) {
        if (player.level().getServer().isSameThread() && PlayerProfiles.isLocked(player)
                && !dev.hardrush.lobby.MonsterShop.isShop(player)) ci.cancel();
    }

    @Inject(method = {"handleSpectatorAction", "handleTeleportToEntityPacket"}, at = @At("HEAD"), cancellable = true)
    private void hardrush$authorizedObservation(CallbackInfo ci) {
        if (player.level().getServer().isSameThread() && PlayerProfiles.isLocked(player)
                && (!PlayerProfiles.isAdministrativeSpectator(player) || PlayerProfiles.simulationFrozen(player))) ci.cancel();
        // Destination admission remains owned by the player teleport/protection hooks.
    }

    @Inject(method = "handleTeleportToEntityPacket", at = @At("HEAD"), cancellable = true)
    private void hardrush$celebrationObservation(net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket packet, CallbackInfo ci) {
        // A fallen celebration spectator watches the dragon pool, never another world.
        if (player.level().getServer().isSameThread() && CelebrationRuntime.arena(player)
                && packet.getEntity(player.level()) == null) ci.cancel();
    }

    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void hardrush$facilityEntity(ServerboundInteractPacket packet, CallbackInfo ci) {
        if (!player.level().getServer().isSameThread()) return;
        if (LobbyInteractions.consumed(LobbyInteractions.entity(player, packet))
                || PlayerProfiles.isLocked(player) || CombatRuntime.competitionFrozen(player.level())) ci.cancel();
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void hardrush$holdMovement(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (player.level().getServer().isSameThread() && PlayerProfiles.simulationFrozen(player)) {
            // Reject the prediction too; otherwise a frozen client appears able to walk locally.
            player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
            ci.cancel();
        }
    }

    @Inject(method = "handlePlayerInput", at = @At("HEAD"), cancellable = true)
    private void hardrush$holdInput(CallbackInfo ci) {
        if (player.level().getServer().isSameThread() && PlayerProfiles.simulationFrozen(player)) ci.cancel();
    }

    @Inject(method = "handlePlayerCommand", at = @At("HEAD"), cancellable = true)
    private void hardrush$lobbySprint(ServerboundPlayerCommandPacket packet, CallbackInfo ci) {
        if (!player.level().getServer().isSameThread() || !PlayerProfiles.isLocked(player)) return;
        boolean sprint = packet.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING
                || packet.getAction() == ServerboundPlayerCommandPacket.Action.STOP_SPRINTING;
        if (!sprint || !PlayerProfiles.canMoveInLobby(player)) ci.cancel();
    }

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void hardrush$holdBlockAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (player.level().getServer().isSameThread() && PlayerProfiles.isLocked(player)) {
            switch (packet.getAction()) {
                case START_DESTROY_BLOCK, STOP_DESTROY_BLOCK, ABORT_DESTROY_BLOCK -> {
                    player.connection.ackBlockChangesUpTo(packet.getSequence());
                    player.connection.send(new ClientboundBlockUpdatePacket(player.level(), packet.getPos()));
                }
                // A camp monster may throw its revival totem; RevivalTotems commits the transfer.
                case DROP_ITEM, DROP_ALL_ITEMS -> {
                    if (RevivalTotems.is(player.getInventory().getSelectedItem())) return;
                }
                default -> { }
            }
            ci.cancel();
        }
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
    private void hardrush$holdBlockUse(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (player.level().getServer().isSameThread() && LobbyInteractions.consumed(LobbyInteractions.block(player, packet))) { ci.cancel(); return; }
        if (player.level().getServer().isSameThread() && PlayerProfiles.isLocked(player)) {
            player.connection.ackBlockChangesUpTo(packet.sequence());
            var hit = packet.hitResult();
            player.connection.send(new ClientboundBlockUpdatePacket(player.level(), hit.getBlockPos()));
            player.connection.send(new ClientboundBlockUpdatePacket(player.level(), hit.getBlockPos().relative(hit.getDirection())));
            ci.cancel();
        }
    }

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void hardrush$lobbyMenuClick(net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (player.level().getServer().isSameThread() && RevivalTotems.throwsFromInventory(player, packet)) return;
        if (player.level().getServer().isSameThread() && !dev.hardrush.lobby.LobbyMenus.isMenu(player)
                && !dev.hardrush.lobby.MonsterShop.isShop(player)
                && (PlayerProfiles.isLocked(player) || CombatRuntime.competitionFrozen(player.level()))) ci.cancel();
    }

    @Inject(method = "handleContainerClose", at = @At("HEAD"), cancellable = true)
    private void hardrush$lobbyMenuClose(CallbackInfo ci) {
        if (player.level().getServer().isSameThread() && PlayerProfiles.isLocked(player)
                && !dev.hardrush.lobby.LobbyMenus.isMenu(player) && !dev.hardrush.lobby.MonsterShop.isShop(player)) ci.cancel();
    }

    @Inject(method = "handleSetCreativeModeSlot", at = @At("HEAD"), cancellable = true)
    private void hardrush$immutableMenu(CallbackInfo ci) {
        if (player.level().getServer().isSameThread() && dev.hardrush.lobby.LobbyMenus.isMenu(player)) ci.cancel();
    }

    @Inject(method = {"handleAttack", "handlePunch", "handleInteract"}, at = @At("HEAD"))
    private void hardrush$cancelReturn(CallbackInfo ci) {
        if (player.level().getServer().isSameThread()) dev.hardrush.lobby.LobbyRuntime.interruptReturn(player, "攻击／实体交互");
    }

    @Inject(method = "handleUseItem", at = @At("HEAD"))
    private void hardrush$returnItem(ServerboundUseItemPacket packet, CallbackInfo ci) {
        if (player.level().getServer().isSameThread()
                && !dev.hardrush.monster.MonsterRoles.isReturnControl(player, packet.hand()))
            dev.hardrush.lobby.LobbyRuntime.interruptReturn(player, "使用物品／技能");
    }
    @Inject(method = "handleUseItemOn", at = @At("HEAD"))
    private void hardrush$returnBlockItem(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (player.level().getServer().isSameThread()
                && !dev.hardrush.monster.MonsterRoles.isReturnControl(player, packet.hand()))
            dev.hardrush.lobby.LobbyRuntime.interruptReturn(player, "使用物品／技能");
    }

    // Celebration allows movement and closing a menu, while sealing all new competitive actions.
    @Inject(method = {"handlePlayerAction", "handleAttack", "handlePunch",
            "handlePlaceRecipe", "handleContainerButtonClick", "handleSetCreativeModeSlot",
            "handleRenameItem", "handleSelectTrade", "handleSetBeaconPacket", "handleEditBook", "handleSignUpdate",
            "handlePickItemFromBlock", "handlePickItemFromEntity", "handleBundleItemSelectedPacket",
            "handleContainerSlotStateChanged", "handleSetCommandBlock", "handleSetCommandMinecart",
            "handleSetStructureBlock", "handleSetTestBlock", "handleTestInstanceBlockAction", "handleSetJigsawBlock",
            "handleJigsawGenerate", "handleSetGameRule", "handleChangeDifficulty", "handleChangeGameMode", "handleLockDifficulty"},
            at = @At("HEAD"), cancellable = true)
    private void hardrush$holdCompetition(CallbackInfo ci) {
        if (player.level().getServer().isSameThread() && CombatRuntime.competitionFrozen(player.level())) ci.cancel();
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
    private void hardrush$holdFrozenBlockUse(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (player.level().getServer().isSameThread() && LobbyInteractions.consumed(LobbyInteractions.block(player, packet))) { ci.cancel(); return; }
        if (player.level().getServer().isSameThread() && CombatRuntime.competitionFrozen(player.level())) ci.cancel();
    }

    @Inject(method = "handleSetCarriedItem", at = @At("HEAD"), cancellable = true)
    private void hardrush$bookSlotSelection(CallbackInfo ci) {
        if (player.level().getServer().isSameThread() && PlayerProfiles.isLocked(player)
                && !RoundRuntime.lobbyBookReady(player)) ci.cancel();
    }

    @Inject(method = "handleUseItem", at = @At("HEAD"), cancellable = true)
    private void hardrush$readHistoryBook(ServerboundUseItemPacket packet, CallbackInfo ci) {
        if (!player.level().getServer().isSameThread()) return;
        boolean locked = PlayerProfiles.isLocked(player);
        if (!locked && !CombatRuntime.competitionFrozen(player.level())) return;
        // Only a generated written book can be used while other item/gameplay input stays sealed.
        if (!WrittenBookPort.isHistoryBook(player.getItemInHand(packet.hand()))
                || locked && !RoundRuntime.lobbyBookReady(player)) ci.cancel();
    }

    @Inject(method = "handleClientCommand", at = @At("HEAD"), cancellable = true)
    private void hardrush$allowOnlyCommittedRespawn(ServerboundClientCommandPacket packet, CallbackInfo ci) {
        if (!player.level().getServer().isSameThread()) return;
        // The celebration coordinator replaces dead bodies itself, as spectators beside the dragon pool.
        if (packet.getAction() == ServerboundClientCommandPacket.Action.PERFORM_RESPAWN && CelebrationRuntime.ownsRespawn(player)) {
            ci.cancel(); return;
        }
        if (!PlayerProfiles.isLocked(player)) return;
        if (packet.getAction() != ServerboundClientCommandPacket.Action.PERFORM_RESPAWN
                || !PlayerProfiles.isRespawnAllowed(player)) ci.cancel();
    }

    @Inject(method = "performUnsignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void hardrush$holdCommand(String command, CallbackInfo ci) {
        if (PlayerProfiles.isLocked(player) && !hardrush$allowedCommand(command)) ci.cancel();
    }

    @Inject(method = "performSignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void hardrush$holdSignedCommand(ServerboundChatCommandSignedPacket packet, LastSeenMessages messages, CallbackInfo ci) {
        if (PlayerProfiles.isLocked(player) && !hardrush$allowedCommand(packet.command())) ci.cancel();
    }

    private boolean hardrush$allowedCommand(String command) {
        return command.equals("hardrush") || command.startsWith("hardrush ")
                || PlayerProfiles.administrativeCommandAllowed(player, command);
    }
}
