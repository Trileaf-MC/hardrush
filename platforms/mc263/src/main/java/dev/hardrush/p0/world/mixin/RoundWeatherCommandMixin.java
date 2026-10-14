package dev.hardrush.p0.world.mixin;

import dev.hardrush.p0.world.WorldProbe;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.WeatherCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WeatherCommand.class)
public abstract class RoundWeatherCommandMixin {
    @Redirect(method = {"setClear", "setRain", "setThunder"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;setWeatherParameters(IIZZ)V"))
    private static void hardrush$weatherInSourceLevel(MinecraftServer server, int clearTime, int weatherTime,
            boolean raining, boolean thundering, CommandSourceStack source, int duration) {
        if (!WorldProbe.owns(source.getLevel())) {
            server.setWeatherParameters(clearTime, weatherTime, raining, thundering);
            return;
        }
        var weather = source.getLevel().getWeatherData();
        weather.setClearWeatherTime(clearTime);
        weather.setRainTime(weatherTime);
        weather.setThunderTime(weatherTime);
        weather.setRaining(raining);
        weather.setThundering(thundering);
    }
}
