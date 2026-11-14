package dev.hardrush.lobby.pack;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class TileKeyTest {
    @Test void indexIsFloorDivisionByTileSize() {
        assertEquals(new TileKey(0, 1, 0), TileKey.of(0, 63, 47));
        assertEquals(new TileKey(-1, -1, 1), TileKey.of(-1, -48, 48));
        assertEquals(new TileKey(-2, 0, -1), TileKey.of(-49, 0, -48));
        var key = new TileKey(-2, 1, 3);
        assertEquals(-96, key.originX()); assertEquals(48, key.originY()); assertEquals(144, key.originZ());
        assertEquals(key, TileKey.of(key.originX() + 47, key.originY(), key.originZ() + 47));
    }

    @Test void entryNamesRoundTripAndOnlyCanonicalSpellingsParse() {
        var key = new TileKey(-2, 1, 30);
        assertEquals("tiles/-2_1_30.nbt", key.entryName());
        assertEquals(Optional.of(key), TileKey.parseEntryName(key.entryName()));
        assertEquals(Optional.of(new TileKey(Integer.MIN_VALUE, 0, Integer.MAX_VALUE)),
                TileKey.parseEntryName("tiles/-2147483648_0_2147483647.nbt"));
        for (var bad : new String[] {"tiles/a_b_c.nbt", "tiles/1_2_3.nbt.bak", "../x", "tiles/1_2.nbt", "tiles/+1_2_3.nbt",
                "tiles/01_2_3.nbt", "tiles/-0_2_3.nbt", "tiles/2147483648_0_0.nbt", "tiles/1_2_3.NBT", "/tiles/1_2_3.nbt",
                "tiles/../1_2_3.nbt", "tiles/1_2_3.nbt/", "tiles/1 _2_3.nbt", "pack.json"}) {
            assertEquals(Optional.empty(), TileKey.parseEntryName(bad), bad);
        }
    }
}
