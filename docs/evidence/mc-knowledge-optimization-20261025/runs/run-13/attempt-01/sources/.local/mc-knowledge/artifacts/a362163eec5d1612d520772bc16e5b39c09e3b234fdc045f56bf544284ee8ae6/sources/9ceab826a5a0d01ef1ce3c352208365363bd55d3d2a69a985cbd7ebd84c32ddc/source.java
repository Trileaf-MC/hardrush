package net.minecraft.world.level.levelgen;

import net.minecraft.util.RandomSource;

public class XoroshiroRandomSource implements RandomSource {
   @Override
   public int nextInt(final int bound) {
      if (bound <= 0) {
         throw new IllegalArgumentException("Bound must be positive");
      }

      long randomBits = Integer.toUnsignedLong(this.nextInt());
      long multipliedRandomBits = randomBits * bound;
      long fractionalPart = multipliedRandomBits & 4294967295L;
      if (fractionalPart < bound) {
         for (int unbiasedBucketsStartIndex = Integer.remainderUnsigned(~bound + 1, bound);
            fractionalPart < unbiasedBucketsStartIndex;
            fractionalPart = multipliedRandomBits & 4294967295L
         ) {
            randomBits = Integer.toUnsignedLong(this.nextInt());
            multipliedRandomBits = randomBits * bound;
         }
      }

      long integerPart = multipliedRandomBits >> 32;
      return (int)integerPart;
   }
}
