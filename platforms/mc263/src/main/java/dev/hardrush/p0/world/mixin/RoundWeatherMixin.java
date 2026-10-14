package dev.hardrush.p0.world.mixin;

import dev.hardrush.p0.world.RoundRuntimeLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.WeatherData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerLevel.class)
public abstract class RoundWeatherMixin {
    // Fantasy already scopes weather broadcasts to each level; initial weather still uses the server data.
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;getWeatherData()Lnet/minecraft/world/level/saveddata/WeatherData;"))
    private WeatherData hardrush$initialWeather(MinecraftServer server) {
        return (Object) this instanceof RoundRuntimeLevel level ? level.getWeatherData() : server.getWeatherData();
    }
}
