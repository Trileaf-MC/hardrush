package dev.hardrush.combat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.hardrush.combat.CombatRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(TntBlock.class)
public abstract class CombatTntBlockMixin {
    @WrapMethod(method="wasExploded")
    private void chain(ServerLevel level, BlockPos pos, Explosion explosion, Operation<Void> original) {
        if (CombatRuntime.rejectDamage(level)) return;
        try (var ignored = CombatRuntime.tntChain(level, explosion)) { original.call(level, pos, explosion); }
    }
    @WrapMethod(method="onProjectileHit")
    private void projectile(Level level, BlockState state, BlockHitResult hit, Projectile projectile, Operation<Void> original) {
        if (!(level instanceof ServerLevel serverLevel)) { original.call(level, state, hit, projectile); return; }
        if (CombatRuntime.rejectDamage(serverLevel)) return;
        try (var ignored = CombatRuntime.projectileAction(serverLevel, projectile)) { original.call(level, state, hit, projectile); }
    }
}
