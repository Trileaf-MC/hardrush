package dev.hardrush.player.mixin;

import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemCooldowns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemCooldowns.class)
public interface PlayerCooldownAccessor {
    @Accessor("cooldowns") Map<Identifier, ?> hardrush$cooldowns();
}
