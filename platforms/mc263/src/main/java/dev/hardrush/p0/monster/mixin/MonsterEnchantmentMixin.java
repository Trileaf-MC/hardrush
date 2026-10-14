package dev.hardrush.p0.monster.mixin;

import dev.hardrush.p0.monster.MonsterRoles;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Species predicates such as Smite use the role, without changing Player#getType. */
@Mixin(EnchantmentHelper.class)
public abstract class MonsterEnchantmentMixin {
    @ModifyVariable(method="modifyDamage", at=@At("HEAD"), argsOnly=true, ordinal=0)
    private static Entity nativeTarget(Entity target) {
        return MonsterRoles.enchantmentTarget(target);
    }
}
