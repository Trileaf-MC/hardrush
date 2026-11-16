package dev.hardrush.lobby;

import dev.hardrush.core.lobby.LobbyContext;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The lobby's facility menus ({@link LobbyContext}): one binding per server decides availability, buttons and actions
 * for each context; the chest itself is a {@link ButtonMenu}. A menu also closes when its round changes.
 */
public final class LobbyMenus {
    private static final Map<MinecraftServer, Binding> BINDINGS = new IdentityHashMap<>();

    public interface Access {
        boolean available(ServerPlayer player);
        UUID round(ServerPlayer player);
        List<ButtonMenu.Button> buttons(ServerPlayer player);
        void act(ServerPlayer player, UUID expectedRound, String action, String value);
        default boolean available(ServerPlayer player, LobbyContext context) { return context == LobbyContext.COMPETITION && available(player); }
        default List<ButtonMenu.Button> buttons(ServerPlayer player, LobbyContext context) { return buttons(player); }
        default void act(ServerPlayer player, UUID expectedRound, LobbyContext context, String action, String value) {
            if (context == LobbyContext.COMPETITION) act(player, expectedRound, action, value);
        }
    }

    private LobbyMenus() {}

    public static void configure(MinecraftServer server, Access access) {
        requireServerThread(server);
        BINDINGS.put(server, new Binding(Objects.requireNonNull(access)));
    }

    public static void unconfigure(MinecraftServer server) {
        requireServerThread(server);
        BINDINGS.remove(server);
    }

    public static boolean open(ServerPlayer player) { return open(player, LobbyContext.COMPETITION); }

    public static boolean open(ServerPlayer player, LobbyContext context) {
        var server = player.level().getServer();
        requireServerThread(server);
        Binding binding = BINDINGS.get(server);
        if (binding == null || !binding.access.available(player, context)) return false;
        UUID round = binding.access.round(player);
        if (round == null) return false;
        return ButtonMenu.open(player, Component.literal(context == LobbyContext.WITHDRAWAL ? "退出本轮并观战 · 确认"
                : context == LobbyContext.HISTORY ? "战绩书屋" : "HardRush"), binding.throttle, new ButtonMenu.Spec() {
            @Override public boolean valid(ServerPlayer owner) {
                return BINDINGS.get(server) == binding && binding.access.available(owner, context)
                        && round.equals(binding.access.round(owner));
            }
            @Override public List<ButtonMenu.Button> buttons(ServerPlayer owner) { return binding.access.buttons(owner, context); }
            @Override public void act(ServerPlayer owner, ButtonMenu.Button button) {
                binding.access.act(owner, round, context, button.action(), button.value());
            }
        });
    }

    /** Also identifies stale menus so the network adapter keeps creative-slot packets sealed. */
    public static boolean isMenu(ServerPlayer player) { return ButtonMenu.isMenu(player); }

    /** Preserve the short reconnect throttle, then release expired UUID/action entries. */
    public static void tick(MinecraftServer server) {
        requireServerThread(server);
        var binding = BINDINGS.get(server);
        if (binding != null) binding.throttle.expire();
    }

    private static void requireServerThread(MinecraftServer server) {
        Objects.requireNonNull(server);
        if (!server.isSameThread()) throw new IllegalStateException("Lobby GUI requires the server thread");
    }

    private record Binding(Access access, ButtonMenu.Throttle throttle) {
        Binding(Access access) { this(access, new ButtonMenu.Throttle()); }
    }
}
