package dev.hardrush.world.mixin;

import dev.hardrush.world.RoundWorldRouting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.TimeCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(TimeCommand.class)
public abstract class RoundTimeCommandMixin {
    // Fantasy already resolves each clock from the source level; the console only needs to stand in the round.
    @ModifyVariable(method = {"suggestTimeMarkers", "queryGameTime", "queryTime", "queryTimelineTicks", "queryTimelineRepetitions",
            "setTotalTicks", "addTime", "setTimeToTimeMarker", "setPaused", "setRate"}, at = @At("HEAD"), argsOnly = true)
    private static CommandSourceStack hardrush$consoleTimeInRound(CommandSourceStack source) {
        return RoundWorldRouting.commandSource(source);
    }
}
