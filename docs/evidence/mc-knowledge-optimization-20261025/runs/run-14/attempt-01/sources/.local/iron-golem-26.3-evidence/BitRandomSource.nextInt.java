package net.minecraft.world.level.levelgen;

import net.minecraft.util.RandomSource;

public interface BitRandomSource extends RandomSource {
   @Override
   default int nextInt(final int bound) {
      if (bound <= 0) {
         throw new IllegalArgumentException("Bound must be positive");
      }

      if ((bound & bound - 1) == 0) {
         return (int)((long)bound * this.next(31) >> 31);
      }

      int sample;
      int modulo;
      do {
         sample = this.next(31);
         modulo = sample % bound;
      } while (sample - modulo + (bound - 1) < 0);

      return modulo;
   }
}
