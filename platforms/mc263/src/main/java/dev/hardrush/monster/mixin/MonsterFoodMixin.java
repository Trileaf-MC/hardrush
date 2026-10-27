package dev.hardrush.monster.mixin;
import dev.hardrush.monster.MonsterRoles;
import net.minecraft.world.food.FoodData;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(FoodData.class)
public abstract class MonsterFoodMixin {
    @Shadow private float exhaustionLevel;
    @Inject(method="tick", at=@At("HEAD"), cancellable=true)
    private void noHungerOrRegeneration(ServerPlayer player, CallbackInfo callback) {
        if (MonsterRoles.isMonster(player)) { exhaustionLevel=0; callback.cancel(); }
    }
}
