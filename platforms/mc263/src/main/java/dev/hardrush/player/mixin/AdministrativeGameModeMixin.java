package dev.hardrush.player.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.hardrush.player.PlayerProfiles;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.GameModeCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameModeCommand.class)
public abstract class AdministrativeGameModeMixin {
    @WrapOperation(method = "setGameMode(Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/GameType;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;setGameMode(Lnet/minecraft/world/level/GameType;)Z"))
    private static boolean hardrush$administrativeSpectator(ServerPlayer player, GameType mode, Operation<Boolean> original,
            CommandSourceStack source, ServerPlayer target, GameType requested) {
        return PlayerProfiles.administrativeGameMode(source, player, mode)
                .orElseGet(() -> original.call(player, mode));
    }
}
