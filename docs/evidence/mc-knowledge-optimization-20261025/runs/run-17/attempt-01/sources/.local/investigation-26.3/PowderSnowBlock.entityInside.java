public class PowderSnowBlock extends Block implements BucketPickup {
   @Override
   protected void entityInside(
      final BlockState state, final Level level, final BlockPos pos, final Entity entity, final InsideBlockEffectApplier effectApplier, final boolean isPrecise
   ) {
      if (!(entity instanceof LivingEntity) || entity.getInBlockState().is(this)) {
         entity.makeStuckInBlock(state, new Vec3(0.9F, 1.5, 0.9F));
         if (level instanceof ServerLevel serverLevel) {
            RandomSource random = level.getRandom();
            Vec3 knownMovement = entity.getKnownMovement();
            boolean isMoving = knownMovement.x() != 0.0 || knownMovement.z() != 0.0;
            if (isMoving && random.nextBoolean()) {
               serverLevel.sendParticles(
                  ParticleTypes.SNOWFLAKE,
                  entity.getX(),
                  pos.getY() + 1,
                  entity.getZ(),
                  1,
                  0.0,
                  0.0,
                  0.0,
                  Mth.randomBetween(random, -1.0F, 1.0F) * 0.083333336F,
                  0.05F,
                  Mth.randomBetween(random, -1.0F, 1.0F) * 0.083333336F,
                  RandomizationType.ALTERNATIVE
               );
            }
         }
      }

      BlockPos position = pos.immutable();
      effectApplier.runBefore(
         InsideBlockEffectType.EXTINGUISH,
         e -> {
            if (level instanceof ServerLevel serverLevel
               && e.isOnFire()
               && (serverLevel.getGameRules().get(GameRules.MOB_GRIEFING) || e instanceof Player)
               && e.mayInteract(serverLevel, position)) {
               level.destroyBlock(position, false);
            }
         }
      );
      effectApplier.apply(InsideBlockEffectType.FREEZE);
      effectApplier.apply(InsideBlockEffectType.EXTINGUISH);
   }
}
