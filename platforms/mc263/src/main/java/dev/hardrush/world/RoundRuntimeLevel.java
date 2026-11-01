package dev.hardrush.world;

import java.util.function.BooleanSupplier;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.clock.PackedClockStates;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRuleMap;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.LevelData;
import xyz.nucleoid.fantasy.RuntimeClockManager;
import xyz.nucleoid.fantasy.RuntimeLevel;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.fantasy.RuntimeLevelData;

/** Round storage and clocks are completely separate from operator development tooling. */
public final class RoundRuntimeLevel extends RuntimeLevel implements dev.hardrush.world.IndependentWorldEnvironment {
    private static final SavedDataType<GameRuleMap> ROUND_RULES = new SavedDataType<>(Identifier.parse("hardrush:round_game_rules"),
            GameRuleMap::of, GameRuleMap.CODEC, DataFixTypes.SAVED_DATA_GAME_RULES);
    private RuntimeClockManager roundClocks;
    private GameRules roundRules;
    private boolean ticksRoundClocks;
    private RoundWorldBundle owner;
    private final boolean persistent;

    public RoundRuntimeLevel(MinecraftServer server, ResourceKey<Level> key, RuntimeLevelConfig config, Style style) {
        super(server, key, config, style);
        persistent = style == Style.PERSISTENT;
    }

    void bind(RoundWorldBundle owner, RoundRuntimeLevel overworld) {
        this.owner = owner;
        var previous = roundClocks;
        if (overworld.roundClocks == null) {
            BooleanSupplier advance = () -> overworld.getGameRules().get(GameRules.ADVANCE_TIME);
            var type = new SavedDataType<>(Identifier.parse("hardrush:round_clocks"),
                    () -> new RuntimeClockManager(PackedClockStates.EMPTY, advance),
                    PackedClockStates.CODEC.xmap(states -> new RuntimeClockManager(states, advance), RuntimeClockManager::packState), null);
            overworld.roundClocks = overworld.getDataStorage().computeIfAbsent(type);
            overworld.roundClocks.init(getServer());
        }
        if (overworld.roundRules == null) overworld.roundRules = openRoundRules(overworld);
        roundClocks = overworld.roundClocks;
        roundRules = overworld.roundRules;
        ticksRoundClocks = this == overworld;
        if (previous != roundClocks) followRound();
    }

    /** A new round starts from the server's rules; later changes belong to, and are saved with, this round. */
    private static GameRules openRoundRules(RoundRuntimeLevel overworld) {
        var server = overworld.getServer();
        var saved = overworld.getDataStorage().computeIfAbsent(ROUND_RULES);
        boolean fresh = saved.size() == 0;
        var rules = new GameRules(server.getWorldData().enabledFeatures(), saved);
        if (fresh) rules.setAll(server.getGameRules(), null);
        return rules;
    }

    /** ServerLevel's constructor binds day/moon timelines and spawn flags to Fantasy's placeholder clock and rules. */
    @SuppressWarnings("deprecation")
    private void followRound() {
        setEnvironmentAttributes(EnvironmentAttributeSystem.builder().addDefaultLayers(this).build());
        updateSkyBrightness();
        setSpawnSettings(isSpawningMonsters());
    }

    RoundWorldBundle owner() { return owner; }
    boolean persistent() { return persistent; }

    @Override public LevelData.RespawnData getRespawnData() {
        return owner == null ? super.getRespawnData() : owner.respawnData();
    }

    @Override public GameRules getGameRules() { return roundRules == null ? super.getGameRules() : roundRules; }

    @Override public ServerClockManager clockManager() { return roundClocks == null ? super.clockManager() : roundClocks; }

    @Override public WeatherData getWeatherData() { return getDataStorage().computeIfAbsent(WeatherData.TYPE); }

    @Override protected void tickTime() {
        if (roundClocks == null) { super.tickTime(); return; }
        ((RuntimeLevelData) getLevelData()).setGameTime(getServer().overworld().getGameTime());
        if (ticksRoundClocks) roundClocks.tickFromLevel(this);
    }
}
