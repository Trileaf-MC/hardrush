public abstract class Mob extends LivingEntity implements Targeting, EquipmentUser, Leashable {
   public <T extends Mob> @Nullable T convertTo(
      final EntityType<T> entityType, final ConversionParams params, final EntitySpawnReason spawnReason, final AfterConversion<T> afterConversion
   ) {
      if (this.isRemoved()) {
         return null;
      }

      T newMob = (T)entityType.create(this.level(), spawnReason);
      if (newMob == null) {
         return null;
      }

      params.type().convert(this, newMob, params);
      afterConversion.finalizeConversion(newMob);
      if (this.level() instanceof ServerLevel serverLevel) {
         serverLevel.addFreshEntity(newMob);
      }

      if (params.type().shouldDiscardAfterConversion()) {
         this.discard();
      }

      return newMob;
   }
}
