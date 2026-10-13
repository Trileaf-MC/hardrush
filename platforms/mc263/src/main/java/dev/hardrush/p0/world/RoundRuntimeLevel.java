package dev.hardrush.p0.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import xyz.nucleoid.fantasy.RuntimeLevel;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;

/** A persistent Fantasy level whose default respawn belongs to its own round. */
public final class RoundRuntimeLevel extends RuntimeLevel {
    public RoundRuntimeLevel(MinecraftServer server, ResourceKey<Level> key,
                             RuntimeLevelConfig config, Style style) {
        super(server, key, config, style);
    }

    @Override
    public LevelData.RespawnData getRespawnData() {
        LevelData.RespawnData own = WorldProbe.respawnData(this);
        return own == null ? super.getRespawnData() : own;
    }
}
