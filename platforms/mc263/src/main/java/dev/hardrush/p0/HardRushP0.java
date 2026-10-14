package dev.hardrush.p0;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.hardrush.p0.disguise.DisguiseProbe;
import dev.hardrush.p0.monster.MonsterRoles;
import dev.hardrush.p0.world.WorldProbe;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Operator-only technical probes. This is not the competition implementation. */
public final class HardRushP0 implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("hardrush-p0");

    @Override
    public void onInitialize() {
        MonsterRoles.initialize();
        LOGGER.info("HardRush P0: operator world/appearance/monster-role probes; formal race/lobby/rewards are not implemented");
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                WorldProbe.initialize(server);
                LOGGER.info("HardRush P0 worlds: {}", WorldProbe.status());
            } catch (Exception error) {
                LOGGER.error("P0 world recovery failed; inspect the manifest before retrying", error);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> { MonsterRoles.tick(server); DisguiseProbe.tick(server); });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            MonsterRoles.onDisconnect(handler.player); DisguiseProbe.onDisconnect(handler.player);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            MonsterRoles.onJoin(handler.player); DisguiseProbe.onJoin(handler.player);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            MonsterRoles.stop(server);
            DisguiseProbe.stop(server);
            WorldProbe.close(server);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("hardrushp0")
                        .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                        .executes(context -> reply(context.getSource(),
                                "HardRush P0：角色行为原型。命令：status、world create <seed>、world enter <player> <overworld|nether|end>、disguise <player> <zombie|skeleton|creeper>、skill <player>、clear <player>。disguise 进入真实角色；appearance 仅供外观诊断，不改变玩法身份。"))
                        .then(Commands.literal("status").executes(context ->
                                reply(context.getSource(), WorldProbe.status()))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> reply(context.getSource(),
                                                DisguiseProbe.status(EntityArgument.getPlayer(context, "player")) + "; "
                                                + MonsterRoles.status(EntityArgument.getPlayer(context, "player"))))))
                        .then(Commands.literal("world")
                                .then(Commands.literal("create")
                                        .then(Commands.argument("seed", LongArgumentType.longArg())
                                                .executes(context -> attempt(context.getSource(), () ->
                                                        WorldProbe.create(context.getSource().getServer(),
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
                                                            return attempt(context.getSource(), () -> WorldProbe.teleport(
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
                                            return attempt(context.getSource(), () -> DisguiseProbe.apply(player,StringArgumentType.getString(context,"kind")));
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
                                            DisguiseProbe.clear(player);
                                            return reply(context.getSource(), "已解除 " + player.getScoreboardName() + " 的原型伪装。");
                                        })))));
    }

    private static int reply(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int attempt(CommandSourceStack source, ProbeAction action) {
        try {
            return reply(source, action.run());
        } catch (Exception error) {
            LOGGER.warn("P0 command failed", error);
            source.sendFailure(Component.literal("P0 操作失败：" + error.getMessage()));
            return 0;
        }
    }

    @FunctionalInterface
    private interface ProbeAction {
        String run() throws Exception;
    }
}
