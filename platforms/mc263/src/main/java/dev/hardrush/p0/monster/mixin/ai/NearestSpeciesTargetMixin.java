package dev.hardrush.p0.monster.mixin.ai;

import dev.hardrush.p0.monster.MonsterAiIdentity;
import dev.hardrush.p0.monster.MonsterRoles;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NearestAttackableTargetGoal.class)
public abstract class NearestSpeciesTargetMixin extends TargetGoal {
    @Shadow @Final protected Class<? extends LivingEntity> targetType;
    @Shadow @Final protected TargetingConditions targetConditions;
    @Shadow protected LivingEntity target;
    @Shadow protected abstract AABB getTargetSearchArea(double distance);
    protected NearestSpeciesTargetMixin(Mob mob, boolean see) { super(mob, see); }

    @Inject(method="findTarget", at=@At("HEAD"), cancellable=true)
    private void findSpecies(CallbackInfo ci) {
        if (MonsterRoles.summonOwner(mob) != null) return;
        var level = (ServerLevel)mob.level();
        var conditions = targetConditions.range(getFollowDistance());
        java.util.List<LivingEntity> candidates;
        if (targetType == Player.class || targetType == ServerPlayer.class) {
            candidates = level.players().stream().filter(p -> MonsterAiIdentity.matches(targetType, p))
                    .map(p -> (LivingEntity)p).toList();
        } else {
            candidates = MonsterAiIdentity.candidates(level, targetType, getTargetSearchArea(getFollowDistance()), e -> true);
        }
        target = level.getNearestEntity(candidates, conditions, mob, mob.getX(), mob.getEyeY(), mob.getZ());
        ci.cancel();
    }

    @Override
    public boolean canContinueToUse() {
        var current = mob.getTarget();
        if (current != null && MonsterRoles.summonOwner(mob) == null
                && !MonsterAiIdentity.matches(targetType, current)) return false;
        return super.canContinueToUse();
    }
}
