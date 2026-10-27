package dev.hardrush.monster.mixin.ai;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.hardrush.monster.MonsterRoles;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.sensing.PlayerSensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerSensor.class)
public abstract class SpeciesPlayerSensorMixin {
    @WrapOperation(method="doTick", at=@At(value="INVOKE", target="Lnet/minecraft/server/level/ServerLevel;players()Ljava/util/List;"))
    private List<ServerPlayer> humanPlayers(ServerLevel level, Operation<List<ServerPlayer>> original) {
        return original.call(level).stream().filter(p -> !MonsterRoles.isMonster(p)).toList();
    }
}
