package dev.hardrush.monster.mixin;

import dev.hardrush.monster.MonsterLegacy;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A dead monster's gust learns whom vanilla actually threw (docs/plan/女巫药水与药水盲盒.md §5.1). */
@Mixin(ServerExplosion.class)
public abstract class MonsterGustMixin {
    @Inject(method = "explode", at = @At("RETURN"))
    private void thrown(CallbackInfoReturnable<Integer> callback) { MonsterLegacy.exploded((ServerExplosion)(Object)this); }
}
