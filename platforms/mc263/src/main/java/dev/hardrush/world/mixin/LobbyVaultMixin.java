package dev.hardrush.world.mixin;

import dev.hardrush.world.LobbyWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.vault.VaultConfig;
import net.minecraft.world.level.block.entity.vault.VaultServerData;
import net.minecraft.world.level.block.entity.vault.VaultSharedData;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Camp vaults never cycle native loot or unlock; MonsterVaults drives each observer by packets. */
@Mixin(targets = "net.minecraft.world.level.block.entity.vault.VaultBlockEntity$Server")
public abstract class LobbyVaultMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private static void hardrush$campVault(ServerLevel level, BlockPos pos, BlockState state, VaultConfig config,
            VaultServerData serverData, VaultSharedData sharedData, CallbackInfo ci) {
        if (LobbyWorld.isLobby(level)) ci.cancel();
    }
}
