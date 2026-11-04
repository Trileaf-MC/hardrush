package dev.hardrush.lobby;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.lobby.LobbyHints;
import dev.hardrush.core.lobby.LobbyLayout;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.round.RoundSession;
import dev.hardrush.world.LobbyWorld;
import java.util.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;

/**
 * The camp's revival altar. A camp monster holding a revival totem climbs onto the gold pad and stands
 * there for the vanilla Nether-portal delay, like the assembly circle's mid-round join; the revival is
 * then submitted exactly as the old confirmation menu did, and a submitted request is final. Anyone else
 * on the pad only reads why in the action bar. Rites sound like a beacon; the arrival is a native totem pop.
 */
public final class RevivalAltar {
    static final int DWELL_TICKS = HumanEntry.MIDROUND_DWELL_TICKS;
    private static final int STAGES = 4;
    /** How long a revived body may take to load into the formal world before its totem pop is dropped. */
    private static final int ARRIVAL_TICKS = 600;
    private static final Map<MinecraftServer, RevivalAltar> SERVICES = new IdentityHashMap<>();
    private static final class Rite {
        final ServerPlayer player;
        int dwell, stage;
        boolean spent;
        ServerBossEvent bar;
        Rite(ServerPlayer player) { this.player = player; }
    }
    private final MinecraftServer server;
    private final Map<UUID, Rite> rites = new HashMap<>();
    /** Revived players still loading into the formal world, by server-tick deadline. */
    private final Map<UUID, Integer> arrivals = new HashMap<>();

    private RevivalAltar(MinecraftServer server) { this.server = server; }

    /** Runs every tick, recovery included, so the beam also goes dark while the altar cannot answer. */
    public static void tick(MinecraftServer server, RoundSnapshot round, RoundSession session) {
        SERVICES.computeIfAbsent(server, RevivalAltar::new).run(round, session);
    }
    public static void close(MinecraftServer server) {
        var s = SERVICES.remove(server); if (s != null) s.rites.values().forEach(RevivalAltar::stop);
    }
    public static void disconnected(ServerPlayer player) {
        var s = SERVICES.get(player.level().getServer());
        if (s != null) { var rite = s.rites.remove(player.getUUID()); if (rite != null) stop(rite); }
    }
    /** Test seam: the player is counting down a rite that has not been submitted yet. */
    public static boolean channeling(ServerPlayer player) {
        var s = SERVICES.get(player.level().getServer()); if (s == null) return false;
        var rite = s.rites.get(player.getUUID());
        return rite != null && rite.player == player && !rite.spent && rite.dwell > 0;
    }

    /** A committed revival: the altar flares in camp now, the totem pops once the body has loaded. */
    static void revived(MinecraftServer server, UUID player) {
        var s = SERVICES.get(server); if (s == null) return;
        s.arrivals.put(player, server.getTickCount() + ARRIVAL_TICKS);
        var lobby = LobbyWorld.open(server); if (!lobby.ready()) return;
        var at = lobby.layoutPosition(LobbyLayout.facility(LobbyLayout.Facility.ALTAR)).add(0, 1.5, 0);
        var level = lobby.holdingLevel();
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, at.x, at.y, at.z, 60, .8, .8, .8, .5);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.TOTEM_USE, SoundSource.BLOCKS, 1f, 1f);
    }

    private void run(RoundSnapshot round, RoundSession session) {
        var lobby = LobbyWorld.open(server);
        lobby.altarLit(round.rules().mode() == GameMode.HARDCORE && session.state() == RoundSession.State.OPEN
                && (round.phase() == RoundPhase.RUNNING || round.phase() == RoundPhase.RESCUE));
        rites.entrySet().removeIf(e -> {
            boolean stale = server.getPlayerList().getPlayer(e.getKey()) != e.getValue().player;
            if (stale) stop(e.getValue());
            return stale;
        });
        for (var player : List.copyOf(server.getPlayerList().getPlayers())) {
            var rite = rites.get(player.getUUID());
            if (!PlayerProfiles.verifiedLobbyBody(player) || !lobby.onAltar(player)) {
                if (rite != null) { if (!rite.spent && rite.dwell > 0) fizzle(player); stop(rite); rites.remove(player.getUUID()); }
                continue;
            }
            if (rite == null) rites.put(player.getUUID(), rite = new Rite(player));
            if (rite.spent) {
                if (LobbyRuntime.reviveCommitting(player)) hint(player, LobbyHints.Priority.PROGRESS, "正在复生…");
                else { stop(rite); hint(player, LobbyHints.Priority.READY, "走下祭坛后可重新开始复生仪式"); }
                continue;
            }
            String refusal = LobbyRuntime.altarRefusal(player);
            if (refusal != null) {
                if (rite.dwell > 0) fizzle(player);
                stop(rite); hint(player, LobbyHints.Priority.READY, refusal); continue;
            }
            advance(rite);
        }
        arrivals.entrySet().removeIf(e -> {
            var player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null || server.getTickCount() > e.getValue()) return true;
            if (LobbyWorld.isLobby(player.level()) || PlayerProfiles.isLocked(player)
                    || player.connection == null || !player.connection.hasClientLoaded()) return false;
            arrive(player);
            return true;
        });
    }

    private void advance(Rite rite) {
        var player = rite.player;
        if (rite.dwell == 0) {
            rite.bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("复生仪式 · 消耗 1 个复生图腾 · 空装降落在远离出生点的荒野 · 无出生保护"),
                    BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
            rite.bar.addPlayer(player);
            play(player, SoundEvents.BEACON_POWER_SELECT, .8f, 1f);
        }
        rite.dwell++;
        rite.bar.setProgress(Math.max(0, 1 - (float) rite.dwell / DWELL_TICKS));
        // Three rising bells on the whole seconds; the fourth second ends in the submission itself.
        int stage = rite.dwell * STAGES / DWELL_TICKS;
        if (stage > rite.stage && stage < STAGES) play(player, SoundEvents.NOTE_BLOCK_BELL.value(), 1f, .7f + .25f * stage);
        rite.stage = stage;
        if (rite.dwell % 2 == 0) player.level().sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
                player.getX(), player.getY() + .2, player.getZ(), 1 + rite.stage * 2, .35, .1, .35, .25);
        if (rite.dwell < DWELL_TICKS) { hint(player, LobbyHints.Priority.PROGRESS, "复生仪式进行中 · 走下祭坛取消"); return; }
        // A revived life lands far from spawn; the rite holds until a prepared landing is claimed.
        if (!dev.hardrush.world.FarLandings.reserve(server, player.getUUID())) {
            rite.bar.setName(Component.literal("正在寻找远方的落点…"));
            hint(player, LobbyHints.Priority.PROGRESS, "正在寻找远方的落点 · 走下祭坛取消"); return;
        }
        rite.spent = true;
        rite.bar.setName(Component.literal("正在复生…"));
        LobbyRuntime.beginRevival(player);
    }

    /** The revived body has loaded into the formal world: the client plays its own totem animation. */
    private static void arrive(ServerPlayer player) {
        player.level().broadcastEntityEvent(player, (byte) 35);
        var server = player.level().getServer();
        String age = HumanEntry.challengeWorldAge(server).map(days -> "世界已过去 " + dev.hardrush.notice.Notices.key(days) + "，")
                .orElse("");
        dev.hardrush.notice.Notices.title(player, "重获新生", age + "祝你好运");
        dev.hardrush.notice.Notices.broadcast(server, dev.hardrush.notice.Notices.name(player.getScoreboardName())
                + " 在祭坛重获新生，以人类身份重返挑战");
    }

    private static void fizzle(ServerPlayer player) {
        play(player, SoundEvents.BEACON_DEACTIVATE, .7f, 1.4f);
        player.level().sendParticles(ParticleTypes.SMOKE, player.getX(), player.getY() + .9, player.getZ(), 8, .3, .5, .3, .02);
    }

    private static void stop(Rite rite) {
        rite.dwell = 0; rite.stage = 0;
        if (rite.bar != null) { rite.bar.removeAllPlayers(); rite.bar = null; }
    }

    private static void play(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.BLOCKS, volume, pitch);
    }

    private void hint(ServerPlayer player, LobbyHints.Priority priority, String text) {
        LobbyInteractions.get(server).ifPresent(router -> router.hints(player).offer("altar", priority, text, System.nanoTime() / 1_000_000 + 150));
    }
}
