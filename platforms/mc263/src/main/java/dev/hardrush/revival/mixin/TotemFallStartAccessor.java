package dev.hardrush.revival.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 26.3 {@code ServerPlayer.startingToFallPosition}: set by {@code trackStartFallingPosition} on the first server tick
 * with a fall distance, cleared by {@code resetFallDistance}; {@code die} leaves it alone, so {@code AFTER_DEATH} still
 * reads where a fall into the void began (docs/plan/复生图腾守恒.md 2.3).
 */
@Mixin(ServerPlayer.class)
public interface TotemFallStartAccessor {
    @Accessor("startingToFallPosition") Vec3 hardrush$fallStart();
}
