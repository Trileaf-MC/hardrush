package dev.hardrush.monster.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.hardrush.monster.MonsterRoles;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Monster players never collect experience, so orbs neither reach nor chase them. */
@Mixin(ExperienceOrb.class)
public abstract class MonsterExperienceMixin {
    @Shadow private @Nullable Player followingPlayer;

    @Inject(method="playerTouch", at=@At("HEAD"), cancellable=true)
    private void noExperience(Player player, CallbackInfo callback) {
        if (MonsterRoles.isMonster(player)) callback.cancel();
    }

    @Inject(method="followNearbyPlayer", at=@At("HEAD"))
    private void dropMonster(CallbackInfo callback) {
        if (MonsterRoles.isMonster(followingPlayer)) followingPlayer = null;
    }

    @WrapOperation(method="followNearbyPlayer", at=@At(value="INVOKE",
            target="Lnet/minecraft/world/level/Level;getNearestPlayer(Lnet/minecraft/world/entity/Entity;D)Lnet/minecraft/world/entity/player/Player;"))
    private @Nullable Player nearestHuman(Level level, Entity orb, double maxDist, Operation<Player> original) {
        if (!(level instanceof ServerLevel)) return original.call(level, orb, maxDist);
        return level.getNearestPlayer(orb.getX(), orb.getY(), orb.getZ(), maxDist,
                entity -> EntitySelector.NO_SPECTATORS.test(entity) && !MonsterRoles.isMonster(entity));
    }
}
