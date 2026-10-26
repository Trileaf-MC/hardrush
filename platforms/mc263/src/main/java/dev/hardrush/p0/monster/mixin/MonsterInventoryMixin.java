package dev.hardrush.p0.monster.mixin;
import dev.hardrush.p0.monster.MonsterRoles;
import dev.hardrush.p0.monster.MonsterItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MonsterInventoryMixin {
    @Shadow public ServerPlayer player;
    private void serverThread(Packet<ServerGamePacketListener> packet) {
        PacketUtils.ensureRunningOnSameThread(packet, (ServerGamePacketListenerImpl)(Object)this,
            player.level().getServer().packetProcessor());
    }
    private boolean lockInventory(CallbackInfo callback) {
        if (!MonsterRoles.isMonster(player)) return false;
        MonsterRoles.repairLoadout(player);
        player.containerMenu.sendAllDataToRemote();
        callback.cancel(); return true;
    }
    @Inject(method="handleContainerClick", at=@At("HEAD"), cancellable=true)
    private void lockClick(ServerboundContainerClickPacket packet, CallbackInfo callback) {
        serverThread(packet);
        // The menu itself validates item destinations, including internal moves.
        // Lobby menus retain their independent action routing.
    }
    @Inject(method="handleContainerButtonClick", at=@At("HEAD"), cancellable=true)
    private void lockButton(ServerboundContainerButtonClickPacket packet, CallbackInfo callback) { serverThread(packet); lockInventory(callback); }
    @Inject(method="handlePlaceRecipe", at=@At("HEAD"), cancellable=true)
    private void lockRecipe(ServerboundPlaceRecipePacket packet, CallbackInfo callback) { serverThread(packet); lockInventory(callback); }
    @Inject(method="handleSetCreativeModeSlot", at=@At("HEAD"), cancellable=true)
    private void lockCreative(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo callback) { serverThread(packet); lockInventory(callback); }
    @Inject(method="handlePlayerAction", at=@At("HEAD"), cancellable=true)
    private void lockActions(ServerboundPlayerActionPacket packet, CallbackInfo callback) {
        serverThread(packet);
        if (!MonsterRoles.isMonster(player)) return;
        switch (packet.getAction()) {
            case START_DESTROY_BLOCK, STOP_DESTROY_BLOCK, ABORT_DESTROY_BLOCK -> dev.hardrush.p2.lobby.P2LobbyRuntime.interruptReturn(player, "攻击方块");
            default -> {}
        }
        switch (packet.getAction()) {
            case DROP_ITEM, DROP_ALL_ITEMS -> {
                if (MonsterItems.bound(player.getMainHandItem())) lockInventory(callback);
            }
            case START_DESTROY_BLOCK, STOP_DESTROY_BLOCK, ABORT_DESTROY_BLOCK -> {
                player.connection.send(new ClientboundBlockUpdatePacket(player.level(), packet.getPos()));
                lockInventory(callback);
            }
            default -> {}
        }
    }
}
