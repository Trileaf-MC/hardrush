package dev.hardrush.debug;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Debug-only camera experiment. Never registered or packaged in the release mod. */
public final class SelfViewCameraProbe implements ModInitializer {
    private static final Map<UUID, Preview> PREVIEWS = new HashMap<>();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
            dispatcher.register(Commands.literal("hardrushdebug")
                .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .then(Commands.literal("camera")
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("mode", StringArgumentType.word())
                            .suggests((context, builder) -> builder.suggest("self").suggest("reset").buildFuture())
                            .executes(context -> {
                                var player = EntityArgument.getPlayer(context, "player");
                                String mode = StringArgumentType.getString(context, "mode");
                                if (mode.equals("reset")) reset(player);
                                else if (mode.equals("self")) {
                                    try {
                                        Entity model = visibleSelfModel(player);
                                        player.connection.send(new ClientboundSetCameraPacket(model));
                                        PREVIEWS.put(player.getUUID(), new Preview(player, model,
                                            context.getSource().getServer().getTickCount() + 100));
                                    } catch (ReflectiveOperationException | IllegalStateException failure) {
                                        context.getSource().sendFailure(Component.literal(failure.getMessage()));
                                        return 0;
                                    }
                                } else {
                                    context.getSource().sendFailure(Component.literal("Mode must be self or reset"));
                                    return 0;
                                }
                                context.getSource().sendSuccess(() -> Component.literal(
                                    "Debug camera=" + mode + "; self resets within 100 server ticks"), false);
                                return 1;
                            }))))));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (Preview preview : java.util.List.copyOf(PREVIEWS.values())) {
                boolean valid;
                try { valid = visibleSelfModel(preview.player()) == preview.model(); }
                catch (ReflectiveOperationException | IllegalStateException failure) { valid = false; }
                if (!valid || server.getTickCount() >= preview.until()) reset(preview.player());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PREVIEWS.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Preview preview : java.util.List.copyOf(PREVIEWS.values())) reset(preview.player());
        });
    }

    private static void reset(ServerPlayer player) {
        PREVIEWS.remove(player.getUUID());
        player.connection.send(new ClientboundSetCameraPacket(player));
    }

    /** Reflect only inside the isolated debug tool, keeping experiment APIs out of release code. */
    private static Entity visibleSelfModel(ServerPlayer player) throws ReflectiveOperationException {
        var active = Class.forName("dev.hardrush.p0.disguise.DisguiseProbe").getDeclaredField("ACTIVE");
        active.setAccessible(true);
        Object state = ((Map<?, ?>) active.get(null)).get(player.getUUID());
        if (state == null) throw new IllegalStateException("Enable a visible P0 disguise first");
        var visible = state.getClass().getDeclaredField("selfVisible");
        visible.setAccessible(true);
        if (!visible.getBoolean(state)) throw new IllegalStateException("Enable a visible P0 disguise first");
        var model = state.getClass().getDeclaredField("model");
        model.setAccessible(true);
        return (Entity) model.get(state);
    }

    private record Preview(ServerPlayer player, Entity model, int until) {}
}
