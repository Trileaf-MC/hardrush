package dev.hardrush.p1.player.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Player.class)
public interface PlayerProfileResetAccessor {
    @Accessor("foodData") void hardrush$setFoodData(FoodData foodData);
}
