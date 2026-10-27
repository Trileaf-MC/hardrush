package dev.hardrush.world;

import net.minecraft.world.level.saveddata.WeatherData;

/** Constructor-time seed/weather access for owned runtime worlds, including optional development worlds. */
public interface IndependentWorldEnvironment {
    long getSeed();
    WeatherData getWeatherData();
}
