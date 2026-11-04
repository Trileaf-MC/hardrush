package dev.hardrush.notice;

import dev.hardrush.core.lobby.LobbyHints;
import dev.hardrush.lobby.LobbyInteractions;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * The single outlet for player-facing messages, one method per channel of docs/plan/玩家消息分级.md:
 * {@link #hint} (T1 action bar), {@link #title} (T2), {@link #personal} (T4 chat) and {@link #broadcast} (T5 chat).
 * T0 is a sound or a visible change and T3 is the stage boss bar; neither goes through here.
 *
 * <p>Text is written in a small markup so the core rule layer can produce hints without Minecraft types:
 * {@code **key**} is white, {@code [[name]]} is a bold white player name, {@code ·} is dark gray and everything else
 * is gray. Wrap dynamic values with {@link #key} or {@link #name} so they cannot inject markup.
 */
public final class Notices {
    /** How long a one-shot action-bar notice outranks ongoing progress hints. */
    private static final long HINT_MILLIS = 3_000;
    private static final Style BODY = Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false);
    private static final Style KEY = Style.EMPTY.withColor(ChatFormatting.WHITE).withItalic(false);
    private static final Style NAME = KEY.withBold(true);
    private static final Style SEPARATOR = Style.EMPTY.withColor(ChatFormatting.DARK_GRAY).withItalic(false);

    private Notices() {}

    public static String key(Object value) { return "**" + plain(value) + "**"; }
    public static String name(Object value) { return "[[" + plain(value) + "]]"; }

    /** Dynamic text (player and mob names, item titles) never carries markup characters. */
    public static String plain(Object value) {
        return String.valueOf(value).replace("*", "").replace("[[", "").replace("]]", "").replace("·", "");
    }

    public static Component format(String markup) {
        MutableComponent out = Component.empty();
        var text = new StringBuilder();
        Style style = BODY;
        for (int i = 0; i < markup.length(); i++) {
            char c = markup.charAt(i);
            String pair = i + 1 < markup.length() ? markup.substring(i, i + 2) : "";
            if (pair.equals("**") && style != NAME) {
                flush(out, text, style); style = style == KEY ? BODY : KEY; i++;
            } else if (pair.equals("[[") && style == BODY) {
                flush(out, text, style); style = NAME; i++;
            } else if (pair.equals("]]") && style == NAME) {
                flush(out, text, style); style = BODY; i++;
            } else if (c == '·' && style == BODY) {
                flush(out, text, style); out.append(Component.literal("·").setStyle(SEPARATOR));
            } else text.append(c);
        }
        flush(out, text, style);
        return out;
    }

    private static void flush(MutableComponent out, StringBuilder text, Style style) {
        if (text.isEmpty()) return;
        out.append(Component.literal(text.toString()).setStyle(style));
        text.setLength(0);
    }

    /** T1: a short, personal, immediately useful line. Shares the action bar with the lobby hint arbiter. */
    public static void hint(ServerPlayer player, String markup) {
        if (!online(player)) return;
        var router = LobbyInteractions.get(player.level().getServer()).orElse(null);
        if (router != null) {
            router.hints(player).offer("notice", LobbyHints.Priority.NOTICE, markup, System.nanoTime() / 1_000_000 + HINT_MILLIS);
        } else player.connection.send(new ClientboundSetActionBarTextPacket(format(markup)));
    }

    /** T1 with the shared rejection sound: the request was understood but cannot be done now. */
    public static void refuse(ServerPlayer player, String markup) {
        hint(player, markup);
        if (online(player)) player.connection.send(new ClientboundSoundPacket(SoundEvents.NOTE_BLOCK_BASS,
                SoundSource.PLAYERS, player.getX(), player.getY(), player.getZ(), .5f, .8f, 0));
    }

    /** T2: rare turning points only (see the whitelist in the design document). */
    public static void title(ServerPlayer player, String title, String subtitleMarkup) {
        if (!online(player)) return;
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
        player.connection.send(new ClientboundSetSubtitleTextPacket(format(subtitleMarkup)));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(plain(title)).setStyle(KEY)));
    }

    /** T4: something the player may need to scroll back to, visible nowhere else. */
    public static void personal(ServerPlayer player, String markup) {
        if (online(player)) player.sendSystemMessage(format(markup));
    }

    /** T5: a whitelisted event of the match, told once to everybody. */
    public static void broadcast(MinecraftServer server, String markup) {
        server.getPlayerList().broadcastSystemMessage(format(markup), false);
    }

    /** Diagnostic detail for online gamemasters; players only ever get the generic wording. */
    public static void operators(MinecraftServer server, String text) {
        var message = Component.literal("[HardRush] " + text).withStyle(ChatFormatting.GRAY);
        for (var player : server.getPlayerList().getPlayers()) {
            if (online(player) && net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS)
                    .test(player.createCommandSourceStack())) player.sendSystemMessage(message);
        }
    }

    /** Elapsed time for players: {@code m:ss} under an hour, otherwise {@code h:mm:ss}. */
    public static String clock(long millis) {
        if (millis < 0) throw new IllegalArgumentException("Negative duration");
        long seconds = millis / 1000;
        long h = seconds / 3600, m = seconds / 60 % 60, s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    /** Whole seconds left, rounded up so a running countdown never shows 0 early. */
    public static long secondsCeil(long nanos) { return (Math.max(0, nanos) + 999_999_999L) / 1_000_000_000L; }

    private static boolean online(ServerPlayer player) {
        return player.connection != null && player.connection.isAcceptingMessages();
    }
}
