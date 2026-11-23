package dev.hardrush.revival.mixin;

import dev.hardrush.revival.RevivalTotems;
import dev.hardrush.revival.TotemDrops;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3 {@code ItemEntity.playerTouch}: no floor revival totem (a drop, or a projected copy that slipped out) is ever
 * picked up natively; a drop is taken through {@link TotemDrops#touch} instead. Other mixins cancel at the same HEAD
 * (a frozen competition, a protected player, a monster that may not act); whichever runs first, the result is the
 * same, since {@link TotemDrops#touch} only lets a human whose projection is in PROJECT state, or a monster out hunting
 * that may act, take a drop, which already excludes a locked competition, a lobby body and a monster that may not act.
 */
@Mixin(ItemEntity.class)
public abstract class TotemPickupMixin {
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void hardrush$takeThroughRegistry(Player player, CallbackInfo ci) {
        var item = (ItemEntity) (Object) this;
        if (!RevivalTotems.is(item.getItem())) return;
        ci.cancel();
        if (player instanceof ServerPlayer taker) TotemDrops.touch(taker, item);
    }
}
