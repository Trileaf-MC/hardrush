package net.minecraft.world.level;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.material.FluidState;

public abstract class Level implements LevelAccessor, AutoCloseable {
   public void tickBlockEntities() {
      this.tickingBlockEntities = true;
      if (!this.pendingBlockEntityTickers.isEmpty()) {
         this.blockEntityTickers.addAll(this.pendingBlockEntityTickers);
         this.pendingBlockEntityTickers.clear();
      }

      Iterator<TickingBlockEntity> iterator = this.blockEntityTickers.iterator();
      boolean tickBlockEntities = this.tickRateManager().runsNormally();

      while (iterator.hasNext()) {
         TickingBlockEntity ticker = iterator.next();
         if (ticker.isRemoved()) {
            iterator.remove();
         } else if (tickBlockEntities && this.shouldTickBlocksAt(ticker.getPos())) {
            ticker.tick();
         }
      }

      this.tickingBlockEntities = false;
   }
}
