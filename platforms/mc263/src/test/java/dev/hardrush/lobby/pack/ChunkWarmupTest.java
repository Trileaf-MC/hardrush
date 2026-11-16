package dev.hardrush.lobby.pack;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.HardRushProperties;
import dev.hardrush.core.lobby.plan.Box;
import dev.hardrush.core.lobby.plan.Cell;
import dev.hardrush.core.lobby.plan.PackManifest;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class ChunkWarmupTest {
    @BeforeAll static void bootstrap() {
        // ChunkWarmup's ticket type and DimensionType's constants reach the registries.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void refusesBoxesWiderThanTheLargestLobby() {
        // The check runs before the level is touched, so no server is needed.
        var huge = new Box(new Cell(Integer.MIN_VALUE, 0, 0), new Cell(Integer.MAX_VALUE, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkWarmup(null, huge));
        int widest = HardRushProperties.MAX_LOBBY_MAX_SIZE;
        var tooWide = new Box(new Cell(1, 0, 0), new Cell(1 + widest + 16, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkWarmup(null, tooWide));
    }

    @Test void acceptsTheLargestLobbyStraddlingChunkEdges() {
        int widest = HardRushProperties.MAX_LOBBY_MAX_SIZE;
        // Starting one block before a chunk edge touches the most chunks a box this wide can.
        var box = new Box(new Cell(-1, 0, -1), new Cell(widest - 2, 0, widest - 2));
        var warmup = new ChunkWarmup(null, box);
        assertEquals(ChunkWarmup.MAX_CHUNKS_PER_AXIS * ChunkWarmup.MAX_CHUNKS_PER_AXIS, warmup.chunks());
    }

    @Test void worldLimitsMatchVanilla() {
        assertEquals(Level.MAX_LEVEL_SIZE, PackManifest.MAX_HORIZONTAL_COORDINATE);
        assertEquals(DimensionType.MIN_Y, PackManifest.MIN_Y);
        assertEquals(DimensionType.MAX_Y, PackManifest.MAX_Y);
    }
}
