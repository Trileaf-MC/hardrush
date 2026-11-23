package dev.hardrush.dialog;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Routes native dialog buttons back to the server. A button carries a {@code hardrush:} custom click action;
 * vanilla hands those to {@code MinecraftServer.handleCustomClickAction} without saying who clicked, so a mixin
 * on the player's connection calls {@link #handle} instead. A click is a request, never a capability: every
 * handler re-checks what it is asked to do, since any client can send any action id.
 */
public final class DialogClicks {
    public static final String NAMESPACE = "hardrush";
    private static final Map<String, BiConsumer<ServerPlayer, String>> HANDLERS = new ConcurrentHashMap<>();

    private DialogClicks() {}

    /** {@code handler} receives the clicking player and the rest of the path after {@code prefix + "/"}. */
    public static void register(String prefix, BiConsumer<ServerPlayer, String> handler) {
        if (HANDLERS.putIfAbsent(prefix, handler) != null) throw new IllegalStateException("Duplicate dialog handler: " + prefix);
    }

    public static ClickEvent click(String prefix, String rest) {
        return new ClickEvent.Custom(Identifier.fromNamespaceAndPath(NAMESPACE, prefix + "/" + rest), Optional.empty());
    }

    /** Whether the action was ours; vanilla's own handling is skipped for it. Runs on the server thread. */
    public static boolean handle(ServerPlayer player, Identifier id, Optional<Tag> payload) {
        if (!NAMESPACE.equals(id.getNamespace())) return false;
        String path = id.getPath();
        int slash = path.indexOf('/');
        var handler = slash < 0 ? null : HANDLERS.get(path.substring(0, slash));
        if (handler != null) handler.accept(player, path.substring(slash + 1));
        return true;
    }
}
