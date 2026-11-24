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
    private static final Map<String, PayloadHandler> PAYLOAD_HANDLERS = new ConcurrentHashMap<>();

    /** A handler that also reads the dialog's input values, which a {@code CustomAll} action sends as a compound. */
    @FunctionalInterface
    public interface PayloadHandler { void accept(ServerPlayer player, String rest, Optional<Tag> payload); }

    private DialogClicks() {}

    /** {@code handler} receives the clicking player and the rest of the path after {@code prefix + "/"}. */
    public static void register(String prefix, BiConsumer<ServerPlayer, String> handler) {
        if (HANDLERS.putIfAbsent(prefix, handler) != null) throw new IllegalStateException("Duplicate dialog handler: " + prefix);
    }

    /** Like {@link #register}, for dialogs with inputs. The payload is client data: validate every value. */
    public static void registerWithPayload(String prefix, PayloadHandler handler) {
        if (HANDLERS.containsKey(prefix) || PAYLOAD_HANDLERS.putIfAbsent(prefix, handler) != null) {
            throw new IllegalStateException("Duplicate dialog handler: " + prefix);
        }
    }

    public static Identifier id(String prefix, String rest) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, prefix + "/" + rest);
    }

    public static ClickEvent click(String prefix, String rest) {
        return new ClickEvent.Custom(Identifier.fromNamespaceAndPath(NAMESPACE, prefix + "/" + rest), Optional.empty());
    }

    /** Whether the action was ours; vanilla's own handling is skipped for it. Runs on the server thread. */
    public static boolean handle(ServerPlayer player, Identifier id, Optional<Tag> payload) {
        if (!NAMESPACE.equals(id.getNamespace())) return false;
        String path = id.getPath();
        int slash = path.indexOf('/');
        if (slash < 0) return true;
        var handler = HANDLERS.get(path.substring(0, slash));
        if (handler != null) handler.accept(player, path.substring(slash + 1));
        var withPayload = PAYLOAD_HANDLERS.get(path.substring(0, slash));
        if (withPayload != null) withPayload.accept(player, path.substring(slash + 1), payload);
        return true;
    }
}
