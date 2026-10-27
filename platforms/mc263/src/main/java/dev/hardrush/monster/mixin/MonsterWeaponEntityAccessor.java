package dev.hardrush.monster.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface MonsterWeaponEntityAccessor {
    @Accessor("lastKnownSpeed") void hardrush$setKnownWeaponSpeed(Vec3 speed);
}
