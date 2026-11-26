package dev.hardrush.player;

import java.util.Optional;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;

/**
 * The command gate for a {@linkplain PlayerProfiles#sealed sealed} body (lobby, observing, held death, pending
 * participation, checkpoint save): which commands reach vanilla and, for the rest, why not. The mixin only asks here.
 *
 * <p>Conversation, public or private, stays open because it never touches the body, inventory or world. That includes
 * an observer riding a competitor's camera: the same camp monster could whisper the moment it stops watching, so
 * refusing it there would only get in the way of talking. Aliases are listed separately because the gate sees the
 * typed root word, not the redirect target; vanilla still checks each command's permission.
 */
public final class SealedCommands {
    private static final Set<String> CONVERSATION = Set.of("me", "say", "list", "help", "tell", "msg", "w", "teammsg", "tm");

    private SealedCommands() {}

    /** Empty when {@code command} may run; otherwise the refusal markup for the action bar. */
    public static Optional<String> refusal(ServerPlayer player, String command) {
        var root = command.strip().split("\\s+", 2)[0];
        if (root.equals("hardrush") || CONVERSATION.contains(root)) return Optional.empty();
        if (PlayerProfiles.administrativeCommandAllowed(player, command)) return Optional.empty();
        if (dev.hardrush.storage.FullCheckpointRuntime.saving(player.level().getServer()))
            return Optional.of("正在存档 · 稍后再试");
        return Optional.of("现在只能用 **聊天** 类命令");
    }
}
