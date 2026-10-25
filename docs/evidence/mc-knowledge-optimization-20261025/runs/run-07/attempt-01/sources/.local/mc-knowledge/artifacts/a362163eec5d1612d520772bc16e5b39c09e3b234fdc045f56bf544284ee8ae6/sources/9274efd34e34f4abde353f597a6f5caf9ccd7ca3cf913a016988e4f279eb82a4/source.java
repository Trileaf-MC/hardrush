package net.minecraft.world.entity.ai.goal.target;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public class NearestAttackableTargetGoal<T extends LivingEntity> extends TargetGoal {
   protected void findTarget() {
      ServerLevel level = getServerLevel(this.mob);
      if (this.targetType != Player.class && this.targetType != ServerPlayer.class) {
         this.target = level.getNearestEntity(
            this.mob.level().getEntitiesOfClass(this.targetType, this.getTargetSearchArea(this.getFollowDistance()), entity -> true),
            this.getTargetConditions(),
            this.mob,
            this.mob.getX(),
            this.mob.getEyeY(),
            this.mob.getZ()
         );
      } else {
         this.target = level.getNearestPlayer(this.getTargetConditions(), this.mob, this.mob.getX(), this.mob.getEyeY(), this.mob.getZ());
      }
   }
}
