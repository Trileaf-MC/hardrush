package dev.hardrush.p1.combat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.p1.combat.P1CombatRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(RespawnAnchorBlock.class)
public abstract class CombatAnchorMixin {
    @WrapMethod(method="useWithoutItem")
    private InteractionResult used(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit, Operation<InteractionResult> original) {
        if (!(level instanceof ServerLevel serverLevel)) return original.call(state, level, pos, player, hit);
        try (var ignored = P1CombatRuntime.playerAction(serverLevel, player, AttackKind.RESPAWN_ANCHOR)) {
            return original.call(state, level, pos, player, hit);
        }
    }
}
