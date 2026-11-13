package dev.hardrush.lobby;

import dev.hardrush.core.lobby.*;
import dev.hardrush.core.record.RoundResultText;
import dev.hardrush.core.round.*;
import dev.hardrush.notice.Notices;
import dev.hardrush.notice.StatusBars;
import dev.hardrush.round.RoundSession;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.world.LobbyWorld;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Render after all intent producers, including early-return recovery/celebration ticks.
 * Item names are already shown by the vanilla selected-item HUD; do not repeat them in the action bar
 * ({@link HeldItemHints} adds only the usage line above the name).
 */
public final class PlayerFeedback {
    private static final java.util.Map<MinecraftServer, net.minecraft.server.level.ServerBossEvent> BARS = new java.util.IdentityHashMap<>();
    /** Profile checks normally finish within a tick or two; only a freeze that lasts is worth a hint. */
    private static final long FROZEN_HINT_DELAY_MILLIS = 2000;
    /** A facility tip is taught on the first approach of a round and stays this long; afterwards its sign speaks. */
    private static final long FACILITY_TIP_MILLIS = 4000;
    /** Height of the altar beam a totem holder sees, in blocks above the gold pad. */
    private static final int BEAM_HEIGHT = 14;
    private static final java.util.Map<ServerPlayer, Long> FROZEN_SINCE = new java.util.WeakHashMap<>();
    private PlayerFeedback() {}
    public static void close(MinecraftServer server) {
        var bar = BARS.remove(server);
        if (bar != null) StatusBars.hideEverywhere(server, bar);
    }

    /**
     * The stage bar tells the state; its fill is the remaining time. Only stages where players act on the exact
     * seconds (rescue) print them; the TAB list carries precise numbers for the rest.
     */
    private static void stage(MinecraftServer server, RoundSnapshot round) {
        var stageBar = BARS.computeIfAbsent(server, ignored -> new net.minecraft.server.level.ServerBossEvent(java.util.UUID.randomUUID(),
                net.minecraft.network.chat.Component.literal("HardRush"),
                net.minecraft.world.BossEvent.BossBarColor.YELLOW, net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS));
        boolean rescue = round.phase() == RoundPhase.RESCUE;
        boolean displaying = round.phase() == RoundPhase.SETTLING || round.phase() == RoundPhase.CELEBRATING;
        boolean completed = displaying && round.result().kind() == RoundResult.Kind.COMPLETED;
        boolean celebrating = round.phase() == RoundPhase.CELEBRATING && completed;
        boolean recovery = round.phase() == RoundPhase.RECOVERY_REQUIRED || dev.hardrush.round.RoundRuntime.sessionState(server)
                .filter(state -> state != RoundSession.State.OPEN).isPresent();
        boolean visible = recovery || rescue || displaying;
        String text;
        if (recovery) text = "比赛已暂停 · 等待管理员处理";
        else if (rescue) text = (round.awaitingReconnect() ? "人类全部离线 · 等待重连或救场" : "救场 · 祭坛复生或集结区加入")
                + " · " + Notices.key((round.remainingPhaseMillis() + 999) / 1000) + " 秒";
        else text = displaying ? RoundResultText.stageLabel(round.result(), celebrating ? "通关庆祝" : "挑战成功") : "";
        stageBar.setName(Notices.bar(text));
        long total = rescue ? round.rules().combatValues().get("round.rescueMillis").longValue() : completed
                ? RoundEngine.successCelebrationMillis(round.rules()) : round.rules().failureDisplayMillis();
        stageBar.setProgress(recovery ? 0 : Math.max(0, Math.min(1, (float) round.remainingPhaseMillis() / Math.max(1, total))));
        stageBar.setVisible(visible);
        for (var player : server.getPlayerList().getPlayers()) {
            if (!visible) { StatusBars.hide(player, StatusBars.Slot.PAUSE); StatusBars.hide(player, StatusBars.Slot.STAGE); continue; }
            var slot = recovery ? StatusBars.Slot.PAUSE : StatusBars.Slot.STAGE;
            StatusBars.hide(player, recovery ? StatusBars.Slot.STAGE : StatusBars.Slot.PAUSE);
            StatusBars.show(player, slot, stageBar);
        }
    }

    public static void tick(MinecraftServer server, RoundSnapshot r, RoundSession session) {
        var router = LobbyInteractions.get(server).orElse(null); if (router == null) return;
        if (server.getTickCount() % 5 == 0) stage(server, r);
        long now = System.nanoTime() / 1_000_000;
        boolean frozen = session.competitionLocked();
        long humans = r.participants().values().stream().filter(p -> p.role().isActiveHuman()).count();
        var lobby = LobbyWorld.open(server);
        for (var player : server.getPlayerList().getPlayers()) {
            var hints = router.hints(player); var p = r.participants().get(player.getUUID());
            for (var source : java.util.List.of("profile", "away", "celebration")) hints.clear(source);
            if (!LobbyWorld.isLobby(player.level())) { hints.clear("assembly"); hints.clear("facility"); }
            if (p == null || p.role() != ParticipantRole.MONSTER_LOBBY) { hints.clear("hunt"); hints.clear("hunt-target"); hints.clear("hunt-guide"); }
            if (!dev.hardrush.lobby.LobbyRuntime.channeling(player)) hints.clear("return");
            if (frozen) dev.hardrush.lobby.LobbyRuntime.interruptReturn(player, "比赛状态变化");
            // A suspended match says so on the stage bar; each facility explains its own refusal when used.
            boolean suspended = session.state() != RoundSession.State.OPEN || r.phase() == RoundPhase.RECOVERY_REQUIRED;
            if (!suspended && PlayerProfiles.simulationFrozen(player) && !dev.hardrush.celebration.CelebrationRuntime.arena(player)) {
                if (now - FROZEN_SINCE.computeIfAbsent(player, ignored -> now) >= FROZEN_HINT_DELAY_MILLIS)
                    hints.offer("profile", LobbyHints.Priority.RECOVERY, "正在整理行囊，稍等片刻……", now + 300);
            } else FROZEN_SINCE.remove(player);
            boolean holdsTotem = p != null && p.role() == ParticipantRole.MONSTER_LOBBY && p.revivalEntitled()
                    && dev.hardrush.core.round.MonsterCoins.enabled(r.rules());
            if (holdsTotem && !suspended && lobby.ready() && LobbyWorld.isLobby(player.level()) && server.getTickCount() % 10 == 0) altarBeam(player, lobby);
            dev.hardrush.world.FarLandings.arrived(player).ifPresent(text -> hints.offer("landing", LobbyHints.Priority.TARGET, text, now + 6000));
            // Rescue speaks through its stage bar, and totem holders see the altar beam. Only a restored round
            // still waits for its offline humans while running; see RoundEngine.live.
            if (r.phase() == RoundPhase.RUNNING && humans > 0 && r.onlineHumans() == 0)
                hints.offer("away", LobbyHints.Priority.FACILITY, "等待人类重新上线，计时暂停 · 暂无可追猎目标", now + 300);
            if (dev.hardrush.celebration.CelebrationRuntime.arena(player)) {
                hints.offer("celebration", LobbyHints.Priority.FACILITY, dev.hardrush.celebration.CelebrationRuntime.winner(player)
                        ? "跳进末地传送门即可结束庆祝"
                        : player.isSpectator() ? "你已阵亡，正在旁观庆祝"
                        : Notices.key(dev.hardrush.celebration.CelebrationRuntime.winnerName(server)) + " 正在庆祝 · 阵亡转为旁观，不计战绩", now + 300);
            }
            if (lobby.ready() && LobbyWorld.isLobby(player.level()) && PlayerProfiles.verifiedLobbyBody(player)) {
                for (var facility : LobbyLayout.Facility.values()) {
                    if (player.position().distanceToSqr(lobby.layoutPosition(LobbyLayout.facility(facility))) > 16) continue;
                    boolean altarReady = facility == LobbyLayout.Facility.ALTAR && holdsTotem;
                    String tip = facility + (altarReady ? ":totem" : "");
                    if (Notices.firstInRound(player, r.roundId(), "facility:" + tip))
                        hints.offer("facility", LobbyHints.Priority.FACILITY, facilityTip(facility, altarReady), now + FACILITY_TIP_MILLIS);
                    break;
                }
            }
            HeldItemHints.update(player, hints, now);
            // New lines go out at once; ongoing ones are refreshed every 5 ticks, within the client's 3-second display.
            hints.render(now, server.getTickCount() % 5 == 0).ifPresent(text -> player.connection.send(new ClientboundSetActionBarTextPacket(Notices.format(text))));
        }
    }

    /** What a facility does, taught once per round; signs (CampBlueprint) carry the short form afterwards. */
    static String facilityTip(LobbyLayout.Facility facility, boolean altarReady) {
        return switch (facility) {
            case RULES -> "右键阅读规则";
            case HISTORY -> "右键查看历届战绩、领取战绩书";
            case ALTAR -> altarReady ? "站上金块 " + Notices.key((RevivalAltar.DWELL_TICKS / 20)) + " 秒复生 · 消耗 " + Notices.key(1) + " 个复生图腾"
                    : "阵亡后有复生图腾，就能站上金块 " + Notices.key((RevivalAltar.DWELL_TICKS / 20)) + " 秒复生";
            case ZOMBIE, SKELETON, CREEPER -> "右键展位选择角色，再右键目标模型追猎";
            case PREVIOUS, NEXT -> "右键翻页 · 只对你自己生效";
            case MERCHANT -> "选好角色后右键女巫，用怪物币买出击增强";
            case VAULT -> "手持 " + Notices.key("试炼钥匙") + " 右键，开出一件一次性装备";
            case OMINOUS_VAULT -> "手持 " + Notices.key("不祥试炼钥匙") + " 右键，开出一件一次性装备";
        };
    }

    /**
     * A beacon-like column over the altar, sent to the totem holder alone so the whole camp shows where to go.
     * {@code overrideLimiter} lifts vanilla's 32-block particle range to 512 (ServerLevel.sendParticles).
     */
    private static void altarBeam(ServerPlayer player, LobbyWorld lobby) {
        var level = (net.minecraft.server.level.ServerLevel) player.level();
        var pad = lobby.layoutPosition(LobbyLayout.facility(LobbyLayout.Facility.ALTAR));
        for (int y = 1; y <= BEAM_HEIGHT; y++) {
            level.sendParticles(player, ParticleTypes.END_ROD, true, true, pad.x, pad.y + y, pad.z, 1, .06, .4, .06, 0);
        }
    }
}
