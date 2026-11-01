package dev.hardrush.world.mixin;

import com.mojang.brigadier.context.CommandContext;
import dev.hardrush.world.RoundWorldRouting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.GameRuleCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRuleCommand.class)
public abstract class RoundGameRuleCommandMixin {
    // A round is ready almost all the time, so the console also keeps the server copy that the next round starts from.
    @Inject(method = "setRule", at = @At("HEAD"))
    private static <T> void hardrush$consoleRulesForNextRounds(CommandContext<CommandSourceStack> context, GameRule<T> rule,
            CallbackInfoReturnable<Integer> cir) {
        var source = context.getSource();
        if (RoundWorldRouting.commandSource(source) == source) return;
        source.getServer().getGameRules().set(rule, context.getArgument("value", rule.valueClass()), null);
    }

    @Redirect(method = {"setRule", "queryRule"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/CommandSourceStack;getLevel()Lnet/minecraft/server/level/ServerLevel;"))
    private static ServerLevel hardrush$consoleRulesInRound(CommandSourceStack source) {
        return RoundWorldRouting.commandSource(source).getLevel();
    }
}
