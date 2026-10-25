package net.minecraft.world.level.chunk;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.debug.DebugStructureInfo;
import net.minecraft.util.debug.DebugValueSource;
import net.minecraft.util.debug.DebugStructureInfo.Piece;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;

public class LevelChunk extends ChunkAccess implements DebugValueSource {
   private boolean isTicking(final BlockPos pos) {
      if (!this.level.getWorldBorder().isWithinBounds(pos)) {
         return false;
      } else {
         return !(this.level instanceof ServerLevel serverLevel)
            ? true
            : this.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING) && serverLevel.areEntitiesLoaded(ChunkPos.pack(pos));
      }
   }
}
