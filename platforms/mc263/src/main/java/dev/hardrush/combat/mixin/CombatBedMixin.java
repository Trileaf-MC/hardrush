package dev.hardrush.combat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.combat.CombatRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BedBlock.class)
public abstract class CombatBedMixin {
    @WrapMethod(method="destroyOnUse")
    private InteractionResult used(BlockState state, Level level, BlockPos pos, Player player, Operation<InteractionResult> original) {
        if (!(level instanceof ServerLevel serverLevel)) return original.call(state, level, pos, player);
        if (CombatRuntime.competitionFrozen(serverLevel)) return InteractionResult.FAIL;
        try (var ignored = CombatRuntime.playerAction(serverLevel, player, AttackKind.BED)) {
            return original.call(state, level, pos, player);
        }
    }
}
