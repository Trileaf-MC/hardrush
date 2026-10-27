package dev.hardrush.monster.mixin;
import dev.hardrush.monster.MonsterRoles;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
@Mixin(AbstractArrow.class)
public abstract class MonsterArrowMixin {
    @ModifyArg(method="onHitEntity", at=@At(value="INVOKE", target="Lnet/minecraft/world/damagesource/DamageSources;arrow(Lnet/minecraft/world/entity/projectile/arrow/AbstractArrow;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/damagesource/DamageSource;"), index=1)
    private Entity nativeSource(Entity owner) {
        return MonsterRoles.nativeArrowShooter((AbstractArrow)(Object)this, owner);
    }
}
