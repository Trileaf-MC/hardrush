package dev.hardrush.p0.monster.mixin;
import dev.hardrush.p0.monster.MonsterRoles;
import net.minecraft.world.entity.monster.zombie.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Zombie.class)
public abstract class SummonedZombieMixin {
    @Inject(method="convertsInWater", at=@At("HEAD"), cancellable=true)
    private void keepSummonIdentity(CallbackInfoReturnable<Boolean> callback) {
        if (MonsterRoles.summonOwner((Zombie)(Object)this)!=null) callback.setReturnValue(false);
    }
}
