package net.minecraft.core;

import javax.annotation.concurrent.Immutable;
import net.minecraft.util.Mth;

@Immutable
public class Vec3i implements Comparable<Vec3i> {
   public boolean closerThan(final Vec3i pos, final double distance) {
      return this.distSqr(pos) < Mth.square(distance);
   }
}
