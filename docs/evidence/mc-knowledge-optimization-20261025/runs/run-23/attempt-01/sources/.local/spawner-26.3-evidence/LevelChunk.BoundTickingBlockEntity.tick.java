package net.minecraft.world.level.chunk;

import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Supplier;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.util.debug.DebugStructureInfo;
import net.minecraft.util.debug.DebugValueSource;
import net.minecraft.util.debug.DebugStructureInfo.Piece;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;

public class LevelChunk extends ChunkAccess implements DebugValueSource {
   private class BoundTickingBlockEntity<T extends BlockEntity> implements TickingBlockEntity {
      @Override
      public void tick() {
         if (!this.blockEntity.isRemoved() && this.blockEntity.hasLevel()) {
            BlockPos pos = this.blockEntity.getBlockPos();
            if (LevelChunk.this.isTicking(pos)) {
               try {
                  ProfilerFiller profiler = Profiler.get();
                  profiler.push(this::getType);
                  BlockState blockState = LevelChunk.this.getBlockState(pos);
                  if (this.blockEntity.getType().isValid(blockState)) {
                     this.ticker.tick(LevelChunk.this.level, this.blockEntity.getBlockPos(), blockState, this.blockEntity);
                     this.loggedInvalidBlockState = false;
                  } else if (!this.loggedInvalidBlockState) {
                     this.loggedInvalidBlockState = true;
                     LevelChunk.LOGGER
                        .warn(
                           "Block entity {} @ {} state {} invalid for ticking:",
                           new Object[]{LogUtils.defer(this::getType), LogUtils.defer(this::getPos), blockState}
                        );
                  }

                  profiler.pop();
               } catch (Throwable t) {
                  CrashReport report = CrashReport.forThrowable(t, "Ticking block entity");
                  CrashReportCategory category = report.addCategory("Block entity being ticked");
                  this.blockEntity.fillCrashReportCategory(category);
                  throw new ReportedException(report);
               }
            }
         }
      }
   }
}
