package dev.hardrush.lobby.pack;

import dev.hardrush.core.lobby.plan.LobbyPlan;
import dev.hardrush.core.lobby.plan.PackManifest;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;

/**
 * A pack read, upgraded and sanitized, its tiles in {@link TileKey#ORDER} and ready for {@link PackPlacer}. Installs
 * ({@link SanitizeMode#INSTALL}) and draft imports ({@link SanitizeMode#DRAFT}) share this step.
 */
public record PreparedPack(PackManifest manifest, LobbyPlan plan, Map<TileKey, CompoundTag> tiles, SanitizeReport report) {
    public PreparedPack { tiles = Collections.unmodifiableMap(new LinkedHashMap<>(tiles)); }

    /** Read, upgrade and sanitize; IO thread. */
    public static PreparedPack read(LobbyInstall.Source source, PackLimits limits, SanitizeMode mode) throws IOException, PackException {
        PackContents contents;
        try (var in = source.open()) { contents = PackFile.read(in, limits); }
        var tiles = new LinkedHashMap<TileKey, CompoundTag>();
        var report = SanitizeReport.EMPTY;
        for (var entry : contents.tiles().entrySet()) {
            var tile = PackTiles.upgrade(entry.getValue(), NbtUtils.getDataVersion(entry.getValue(), contents.manifest().dataVersion()));
            report = report.plus(TileSanitizer.sanitize(tile, mode));
            tiles.put(entry.getKey(), tile);
        }
        TileSanitizer.checkTotals(tiles.values());
        return new PreparedPack(contents.manifest(), contents.plan(), tiles, report);
    }
}
