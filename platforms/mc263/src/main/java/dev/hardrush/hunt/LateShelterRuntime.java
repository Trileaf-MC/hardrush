package dev.hardrush.hunt;

import dev.hardrush.core.hunt.LateShelter;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.notice.Notices;
import dev.hardrush.notice.StatusBars;
import dev.hardrush.player.ClientLevelLoad;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.round.RoundSession;
import dev.hardrush.world.RoundWorldBundle;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;

/**
 * The late entrant's own side of 初入荒野 (docs/plan/复生庇护与初入荒野.md 2.4): a personal bar while monsters cannot
 * choose it, and a title when that ends. The rule itself lives in {@link LateShelter}; the courtyard enforces it.
 * Shown only once the client has loaded the world, and again after a reconnect or restart.
 */
public final class LateShelterRuntime {
    public static final String BAR = LateShelter.NAME + " · 怪物暂时无法追猎你";
    public static final String ENDED_TITLE = "行踪暴露";
    public static final String ENDED_SUBTITLE = "怪物现在可以追猎你了";
    private static final long FINAL_NANOS = 30_000_000_000L;
    private static final Map<MinecraftServer, LateShelterRuntime> SERVICES = new IdentityHashMap<>();
    private final Map<UUID, ServerBossEvent> bars = new HashMap<>();
    /** Lives that were shown a shelter this round and still owe their ending title. */
    private final Set<UUID> owed = new HashSet<>();
    private UUID round;

    private LateShelterRuntime() {}

    public static void tick(MinecraftServer server, RoundSession session) {
        SERVICES.computeIfAbsent(server, ignored -> new LateShelterRuntime()).run(server, session);
    }

    public static void close(MinecraftServer server) {
        var s = SERVICES.remove(server);
        if (s != null) s.bars.values().forEach(bar -> StatusBars.hideEverywhere(server, bar));
    }

    /** Test seam: the player currently sees the shelter bar. */
    public static boolean showing(ServerPlayer player) {
        var s = SERVICES.get(player.level().getServer());
        var bar = s == null ? null : s.bars.get(player.getUUID());
        return bar != null && bar.getPlayers().contains(player);
    }

    private void run(MinecraftServer server, RoundSession session) {
        // The live view: the committed snapshot lags a checkpoint behind the time the courtyard counts.
        var view = session.state() == RoundSession.State.OPEN ? session.view().orElse(null) : null;
        if (view == null || !view.roundId().equals(round)) { clear(server); owed.clear(); round = view == null ? null : view.roundId(); }
        if (view == null) return;
        boolean playing = (view.phase() == RoundPhase.RUNNING || view.phase() == RoundPhase.RESCUE) && !session.competitionLocked();
        for (var id : List.copyOf(bars.keySet())) if (server.getPlayerList().getPlayer(id) == null) hide(server, id);
        for (var player : server.getPlayerList().getPlayers()) {
            var p = view.participants().get(player.getUUID());
            long remaining = playing ? LateShelter.remainingNanos(view.rules(), p) : 0;
            boolean present = RoundWorldBundle.owns(player.level()) && !PlayerProfiles.isLocked(player)
                    && ClientLevelLoad.loaded(player);
            if (remaining > 0) {
                if (!present) { hide(server, player.getUUID()); continue; }
                owed.add(player.getUUID());
                var bar = bars.computeIfAbsent(player.getUUID(), ignored -> new ServerBossEvent(UUID.randomUUID(),
                        Notices.bar(BAR), BossEvent.BossBarColor.WHITE, BossEvent.BossBarOverlay.PROGRESS));
                bar.setProgress((float) Math.clamp(remaining / (double) LateShelter.durationNanos(view.rules()), 0, 1));
                bar.setColor(remaining <= FINAL_NANOS ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.WHITE);
                StatusBars.show(player, StatusBars.Slot.SHELTER, bar);
                continue;
            }
            hide(server, player.getUUID());
            if (!owed.contains(player.getUUID())) continue;
            // The life it was shown for is over (death, exit): nothing to announce.
            if (p == null || p.role() != ParticipantRole.HUMAN_ACTIVE || p.lifeSequence() != 1 || !playing) { owed.remove(player.getUUID()); continue; }
            if (!present || dev.hardrush.pact.DuskPactRuntime.titleBusy(server)) continue;
            owed.remove(player.getUUID());
            ended(player);
        }
        owed.removeIf(id -> { var p = view.participants().get(id); return p == null || p.role() != ParticipantRole.HUMAN_ACTIVE || p.lifeSequence() != 1; });
    }

    /** Same rhythm as the dusk pact's release: the moment monsters may come is worth a title and a bell. */
    private static void ended(ServerPlayer player) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 50, 10));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(ENDED_SUBTITLE)));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(ENDED_TITLE)));
        player.connection.send(new ClientboundSoundPacket(Holder.direct(SoundEvents.BELL_BLOCK), SoundSource.MASTER,
                player.getX(), player.getY(), player.getZ(), .5F, 1, player.getRandom().nextLong()));
    }

    private void hide(MinecraftServer server, UUID player) {
        var bar = bars.remove(player);
        if (bar != null) StatusBars.hideEverywhere(server, bar);
    }

    private void clear(MinecraftServer server) {
        for (var id : List.copyOf(bars.keySet())) hide(server, id);
    }
}
