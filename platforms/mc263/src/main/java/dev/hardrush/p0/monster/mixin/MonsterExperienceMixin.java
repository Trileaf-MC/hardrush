package dev.hardrush.p0.monster.mixin;
import dev.hardrush.p0.monster.MonsterRoles;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ExperienceOrb.class)
public abstract class MonsterExperienceMixin {
    @Inject(method="playerTouch", at=@At("HEAD"), cancellable=true)
    private void noExperience(Player player, CallbackInfo callback) {
        if (MonsterRoles.isMonster(player)) callback.cancel();
    }
}
