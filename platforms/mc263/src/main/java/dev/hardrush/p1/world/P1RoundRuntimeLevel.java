package dev.hardrush.p1.world;

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

/** P1 storage and clocks are completely separate from the operator P0 probe. */
public final class P1RoundRuntimeLevel extends RuntimeLevel {
    private RuntimeClockManager roundClocks;
    private boolean ticksRoundClocks;
    private P1RoundWorldBundle owner;
    private final boolean persistent;

    public P1RoundRuntimeLevel(MinecraftServer server, ResourceKey<Level> key, RuntimeLevelConfig config, Style style) {
        super(server, key, config, style);
        persistent = style == Style.PERSISTENT;
    }

    void bind(P1RoundWorldBundle owner, P1RoundRuntimeLevel overworld) {
        this.owner = owner;
        if (overworld.roundClocks == null) {
            BooleanSupplier advance = () -> overworld.getGameRules().get(GameRules.ADVANCE_TIME);
            var type = new SavedDataType<>(Identifier.parse("hardrush:p1_clocks"),
                    () -> new RuntimeClockManager(PackedClockStates.EMPTY, advance),
                    PackedClockStates.CODEC.xmap(states -> new RuntimeClockManager(states, advance), RuntimeClockManager::packState), null);
            overworld.roundClocks = overworld.getDataStorage().computeIfAbsent(type);
            overworld.roundClocks.init(getServer());
        }
        roundClocks = overworld.roundClocks;
        ticksRoundClocks = this == overworld;
    }

    P1RoundWorldBundle owner() { return owner; }
    boolean persistent() { return persistent; }

    @Override public LevelData.RespawnData getRespawnData() {
        return owner == null ? super.getRespawnData() : owner.respawnData();
    }

    @Override public ServerClockManager clockManager() { return roundClocks == null ? super.clockManager() : roundClocks; }

    @Override public WeatherData getWeatherData() { return getDataStorage().computeIfAbsent(WeatherData.TYPE); }

    @Override protected void tickTime() {
        if (roundClocks == null) { super.tickTime(); return; }
        ((RuntimeLevelData) getLevelData()).setGameTime(getServer().overworld().getGameTime());
        if (ticksRoundClocks) roundClocks.tickFromLevel(this);
    }
}
