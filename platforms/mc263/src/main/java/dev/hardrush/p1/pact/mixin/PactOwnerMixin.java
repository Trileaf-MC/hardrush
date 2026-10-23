package dev.hardrush.p1.pact.mixin;

import dev.hardrush.p1.pact.PactOwnerReference;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin({Projectile.class, AreaEffectCloud.class})
public interface PactOwnerMixin extends PactOwnerReference {
    @Override @Accessor("owner") EntityReference<?> hardrush$pactOwner();
}
