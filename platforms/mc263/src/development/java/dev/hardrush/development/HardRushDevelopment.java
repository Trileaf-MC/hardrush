package dev.hardrush.development;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.hardrush.disguise.MonsterDisguise;
import dev.hardrush.monster.MonsterRoles;
import dev.hardrush.development.world.DevelopmentWorlds;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Operator-only development tooling for world and role diagnostics. */
public final class HardRushDevelopment implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("hardrush-development");

    @Override
    public void onInitialize() {
        dev.hardrush.HardRush.initialize();
        dev.hardrush.world.RoundWorldRouting.registerAdditionalWorlds(new dev.hardrush.world.RoundWorldRouting.AdditionalWorlds() {
            public boolean owns(net.minecraft.server.level.ServerLevel level) { return DevelopmentWorlds.owns(level); }
            public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> semanticDimension(net.minecraft.server.level.ServerLevel level) { return DevelopmentWorlds.semanticDimension(level); }
            public net.minecraft.server.level.ServerLevel portalDestination(net.minecraft.server.level.ServerLevel level, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> target) { return DevelopmentWorlds.portalDestination(level,target); }
            public net.minecraft.world.level.portal.TeleportTransition boundRespawn(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.level.portal.TeleportTransition original) { return DevelopmentWorlds.boundRespawn(player,original); }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                DevelopmentWorlds.initialize(server);
                LOGGER.info("HardRush development worlds: {}", DevelopmentWorlds.status());
            } catch (Exception error) {
                LOGGER.error("Development world recovery failed; inspect the manifest before retrying", error);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(DevelopmentWorlds::close);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("hardrushdev")
                        .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                        .executes(context -> reply(context.getSource(),
                                "HardRush 开发工具：角色行为与世界诊断。命令：status、world create <seed>、world enter <player> <overworld|nether|end>、disguise <player> <zombie|skeleton|creeper>、skill <player>、clear <player>。disguise 进入真实角色；appearance 仅供外观诊断，不改变玩法身份。"))
                        .then(Commands.literal("status").executes(context ->
                                reply(context.getSource(), DevelopmentWorlds.status()))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> reply(context.getSource(),
                                                MonsterDisguise.status(EntityArgument.getPlayer(context, "player")) + "; "
                                                + MonsterRoles.status(EntityArgument.getPlayer(context, "player"))))))
                        .then(Commands.literal("world")
                                .then(Commands.literal("create")
                                        .then(Commands.argument("seed", LongArgumentType.longArg())
                                                .executes(context -> attempt(context.getSource(), () ->
                                                        DevelopmentWorlds.create(context.getSource().getServer(),
                                                                LongArgumentType.getLong(context, "seed"))))))
                                .then(Commands.literal("enter")
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .then(Commands.argument("dimension", StringArgumentType.word())
                                                        .suggests((context, builder) -> {
                                                            builder.suggest("overworld").suggest("nether").suggest("end");
                                                            return builder.buildFuture();
                                                        })
                                                        .executes(context -> {
                                                            var player = EntityArgument.getPlayer(context, "player");
                                                            return attempt(context.getSource(), () -> DevelopmentWorlds.teleport(
                                                                    player, StringArgumentType.getString(context, "dimension")));
                                                        })))))
                        .then(Commands.literal("disguise")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("kind", StringArgumentType.word())
                                                .suggests((context, builder) -> {
                                                    builder.suggest("zombie").suggest("skeleton").suggest("creeper");
                                                    return builder.buildFuture();
                                                })
                                                .executes(context -> {
                                                    var player = EntityArgument.getPlayer(context, "player");
                                            return attempt(context.getSource(), () -> MonsterRoles.select(
                                                            player, StringArgumentType.getString(context, "kind")));
                                                }))))
                        .then(Commands.literal("appearance")
                                .then(Commands.argument("player", EntityArgument.player())
                                    .then(Commands.argument("kind", StringArgumentType.word())
                                        .executes(context -> {
                                            var player=EntityArgument.getPlayer(context,"player");
                                            if (MonsterRoles.isMonster(player)) return reply(context.getSource(),"请先 clear 角色，再进行仅外观诊断。");
                                            return attempt(context.getSource(), () -> MonsterDisguise.apply(player,StringArgumentType.getString(context,"kind")));
                                        }))))
                        .then(Commands.literal("skill")
                                .then(Commands.argument("player", EntityArgument.player())
                                    .executes(context -> {
                                        var player = EntityArgument.getPlayer(context, "player");
                                        boolean used = MonsterRoles.summon(player) || MonsterRoles.burst(player) || MonsterRoles.ignite(player);
                                        return reply(context.getSource(), used ? "已使用角色技能。" : "技能尚未就绪或场地／角色不允许。");
                                    })))
                        .then(Commands.literal("clear")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> {
                                            var player = EntityArgument.getPlayer(context, "player");
                                            MonsterRoles.clear(player);
                                            MonsterDisguise.clear(player);
                                            return reply(context.getSource(), "已解除 " + player.getScoreboardName() + " 的原型伪装。");
                                        })))));
    }

    private static int reply(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int attempt(CommandSourceStack source, DevelopmentAction action) {
        try {
            return reply(source, action.run());
        } catch (Exception error) {
            LOGGER.warn("Development command failed", error);
            source.sendFailure(Component.literal("开发工具操作失败：" + error.getMessage()));
            return 0;
        }
    }

    @FunctionalInterface
    private interface DevelopmentAction {
        String run() throws Exception;
    }
}
