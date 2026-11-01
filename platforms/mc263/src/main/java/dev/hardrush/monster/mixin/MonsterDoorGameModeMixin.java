package dev.hardrush.monster.mixin;

import dev.hardrush.monster.MonsterDoorBreaking;
import dev.hardrush.monster.MonsterRoles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerPlayerGameMode.class, priority = 1100)
public abstract class MonsterDoorGameModeMixin {
    @Shadow @Final protected ServerPlayer player;

    @Inject(method = "handleBlockBreakAction", at = @At("HEAD"), cancellable = true)
    private void monsterMining(BlockPos pos, Action action, Direction direction, int maxY, int sequence, CallbackInfo callback) {
        if (!MonsterRoles.isMonster(player)) return;
        MonsterDoorBreaking.action(player, pos, action);
        callback.cancel();
    }
}
