package dev.hardrush.p1;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.RoundSnapshot;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Safe, explicitly enabled P1 persistence foundation; no P0 world reuse. */
public final class P1LobbyRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("hardrush-p1");
    private static final Map<MinecraftServer, P1LobbySession> SESSIONS = new IdentityHashMap<>();
    private static boolean initialized;

    private P1LobbyRuntime() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!Boolean.getBoolean("hardrush.p1.enabled")) return;
            var reference = new P1LobbySession[1];
            var bootstrapCallbacks = new ArrayDeque<Runnable>();
            var session = new P1LobbySession(server.getWorldPath(LevelResource.ROOT).resolve("hardrush/p1.sqlite"),
                    MonotonicClock.system(), task -> server.execute(() -> {
                        // execute() may run inline on the server thread if
                        // bootstrap IO finished before constructor return.
                        if (reference[0] == null) bootstrapCallbacks.add(task);
                        else if (SESSIONS.get(server) == reference[0]) task.run();
                    }), (message, error) -> LOGGER.error(message, error));
            reference[0] = session;
            SESSIONS.put(server, session);
            while (!bootstrapCallbacks.isEmpty()) bootstrapCallbacks.remove().run();
            server.getPlayerList().getPlayers().forEach(player ->
                    session.onOnline(player.getUUID(), player.getScoreboardName(), true));
            LOGGER.info("P1 persistence foundation enabled; world/profile adapters pending; no participation unlocked");
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var session = SESSIONS.get(server);
            if (session != null) session.tick();
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var session = SESSIONS.get(server);
            if (session != null) session.onOnline(handler.player.getUUID(), handler.player.getScoreboardName(), true);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var session = SESSIONS.get(server);
            if (session != null) session.onOnline(handler.player.getUUID(), handler.player.getScoreboardName(), false);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            var session = SESSIONS.remove(server);
            if (session == null) return;
            try { session.close(); }
            catch (Exception error) { LOGGER.error("P1 stop flush failed; retain database and inspect diagnostics", error); }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("hardrush")
                        .executes(context -> status(context.getSource()))
                        .then(Commands.literal("status").executes(context -> status(context.getSource())))
                        .then(Commands.literal("join").executes(context -> {
                            var source = context.getSource();
                            var player = source.getPlayerOrException();
                            var session = SESSIONS.get(source.getServer());
                            return reply(source, session == null ? disabled() :
                                    session.requestJoin(player.getUUID(), player.getScoreboardName()));
                        }))
                        .then(Commands.literal("ready").executes(context -> ready(context.getSource(), true)))
                        .then(Commands.literal("unready").executes(context -> ready(context.getSource(), false)))
                        .then(Commands.literal("config")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.literal("next-mode")
                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                .suggests((context, builder) -> builder.suggest("normal").suggest("hardcore").buildFuture())
                                                .executes(context -> nextMode(context.getSource(),
                                                        StringArgumentType.getString(context, "mode"))))))));
    }

    private static int status(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(status(source.getServer())), false);
        return 1;
    }

    /** Main-thread, read-only health access; no participation or world-ready bypass. */
    public static String status(MinecraftServer server) {
        var session = SESSIONS.get(server);
        return session == null ? disabled().message() : session.status();
    }

    public static Optional<P1LobbySession.State> sessionState(MinecraftServer server) {
        var session = SESSIONS.get(server);
        return session == null ? Optional.empty() : Optional.of(session.state());
    }

    public static Optional<RoundSnapshot> snapshot(MinecraftServer server) {
        var session = SESSIONS.get(server);
        return session == null ? Optional.empty() : session.snapshot();
    }

    public static Optional<RulesConfig> nextRules(MinecraftServer server) {
        var session = SESSIONS.get(server);
        return session == null ? Optional.empty() : session.nextRules();
    }

    public static boolean mutationPending(MinecraftServer server) {
        var session = SESSIONS.get(server);
        return session != null && session.mutationPending();
    }

    private static int ready(CommandSourceStack source, boolean ready) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        var session = SESSIONS.get(source.getServer());
        return reply(source, session == null ? disabled() : session.requestReady(player.getUUID(), ready));
    }

    private static int nextMode(CommandSourceStack source, String argument) {
        var session = SESSIONS.get(source.getServer());
        if (session == null) return reply(source, disabled());
        GameMode mode = switch (argument.toLowerCase(Locale.ROOT)) {
            case "normal" -> GameMode.NORMAL;
            case "hardcore" -> GameMode.HARDCORE;
            default -> null;
        };
        if (mode == null) return reply(source, new P1LobbySession.Reply(false, "模式只接受 normal 或 hardcore。"));
        session.updateNextMode(mode, source.getTextName(), result -> reply(source, result));
        // Do not report synchronous command success while the durable write is pending.
        return 0;
    }

    private static int reply(CommandSourceStack source, P1LobbySession.Reply result) {
        if (result.success()) source.sendSuccess(() -> Component.literal(result.message()), false);
        else source.sendFailure(Component.literal(result.message()));
        return result.success() ? 1 : 0;
    }

    private static P1LobbySession.Reply disabled() {
        return new P1LobbySession.Reply(false,
                "P1 基础默认关闭；服务端启动参数 -Dhardrush.p1.enabled=true 可启用持久化基础。世界与玩家档案适配器待接通，暂不能加入或开局。");
    }
}
