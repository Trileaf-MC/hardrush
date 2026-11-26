package dev.hardrush.revival.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 26.3 {@code LivingEntity.checkTotemDeathProtection}: what a lethal hit asks of the held totems, with the revival totem
 * answering first ({@code TotemSaveMixin}). A departure mid-fight asks it directly ({@code CombatDeparture}).
 */
@Mixin(LivingEntity.class)
public interface TotemDeathProtectionInvoker {
    @Invoker("checkTotemDeathProtection") boolean hardrush$checkTotemDeathProtection(DamageSource source);
}
