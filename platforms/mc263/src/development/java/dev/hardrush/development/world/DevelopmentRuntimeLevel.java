package dev.hardrush.development.world;

import java.util.function.BooleanSupplier;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.clock.PackedClockStates;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.LevelData;
import xyz.nucleoid.fantasy.RuntimeClockManager;
import xyz.nucleoid.fantasy.RuntimeLevel;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.fantasy.RuntimeLevelData;

/** A persistent Fantasy level whose default respawn belongs to its own round. */
public final class DevelopmentRuntimeLevel extends RuntimeLevel implements dev.hardrush.world.IndependentWorldEnvironment {
    private RuntimeClockManager roundClocks;
    private boolean ticksRoundClocks;

    public DevelopmentRuntimeLevel(MinecraftServer server, ResourceKey<Level> key,
                             RuntimeLevelConfig config, Style style) {
        super(server, key, config, style);
    }

    @Override
    public LevelData.RespawnData getRespawnData() {
        LevelData.RespawnData own = DevelopmentWorlds.respawnData(this);
        return own == null ? super.getRespawnData() : own;
    }

    /** Fantasy's default runtime clocks are in memory; save one clock set in the round overworld. */
    public void initializeRoundClocks(DevelopmentRuntimeLevel overworld) {
        if (overworld.roundClocks == null) {
            BooleanSupplier advanceTime = () -> overworld.getGameRules().get(GameRules.ADVANCE_TIME);
            var type = new SavedDataType<>(Identifier.parse("hardrush:p0_clocks"),
                    () -> new RuntimeClockManager(PackedClockStates.EMPTY, advanceTime),
                    PackedClockStates.CODEC.xmap(states -> new RuntimeClockManager(states, advanceTime), RuntimeClockManager::packState),
                    null);
            overworld.roundClocks = overworld.getDataStorage().computeIfAbsent(type);
            overworld.roundClocks.init(getServer());
        }
        roundClocks = overworld.roundClocks;
        ticksRoundClocks = this == overworld;
    }

    @Override
    public ServerClockManager clockManager() {
        // ServerLevel's constructor calls this before the round bundle can bind its clocks.
        return roundClocks == null ? super.clockManager() : roundClocks;
    }

    @Override
    public WeatherData getWeatherData() {
        return getDataStorage().computeIfAbsent(WeatherData.TYPE);
    }

    @Override
    protected void tickTime() {
        if (roundClocks == null) {
            super.tickTime();
            return;
        }
        // Retain Fantasy's global simulation tick, while advancing the round's day cycle just once.
        ((RuntimeLevelData) getLevelData()).setGameTime(getServer().overworld().getGameTime());
        if (ticksRoundClocks) roundClocks.tickFromLevel(this);
    }
}
