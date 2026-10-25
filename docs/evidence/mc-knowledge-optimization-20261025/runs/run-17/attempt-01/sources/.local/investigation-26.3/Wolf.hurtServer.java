public class Wolf extends TamableAnimal implements NeutralMob {
   @Override
   public boolean hurtServer(final ServerLevel level, final DamageSource source, final float damage) {
      if (this.isInvulnerableTo(level, source)) {
         return false;
      }

      this.setOrderedToSit(false);
      return super.hurtServer(level, source, damage);
   }
}
