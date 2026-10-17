package dev.hardrush.p1.lobby;

import dev.hardrush.core.lobby.*;
import dev.hardrush.core.round.*;
import dev.hardrush.p1.P1LobbySession;
import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p1.world.*;
import dev.hardrush.p2.lobby.*;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Native spatial input and confirmation. No UI close event owns an accepted conversion. */
public final class P1HumanEntry {
    private static final Map<MinecraftServer, P1HumanEntry> SERVICES = new IdentityHashMap<>();
    private final MinecraftServer server;
    private final P1LobbySession session;
    private final P1PlayerProfiles profiles;
    private final Supplier<P1RoundWorldBundle> worlds;
    private final Map<UUID, Intent> intents = new HashMap<>();
    private final Set<UUID> joining = new HashSet<>();
    private UUID registeredRound;
    private String lastLabel;
    private final net.minecraft.server.level.ServerBossEvent countdownBar = new net.minecraft.server.level.ServerBossEvent(
            UUID.randomUUID(), Component.literal("集结区"), net.minecraft.world.BossEvent.BossBarColor.GREEN, net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);
    private static final class Intent {
        final ServerPlayer player;
        boolean inside, pending, cancelled, restoring, gate;
        Intent(ServerPlayer player) { this.player = player; }
    }
    private P1HumanEntry(MinecraftServer server, P1LobbySession session, P1PlayerProfiles profiles, Supplier<P1RoundWorldBundle> worlds) {
        this.server = server; this.session = session; this.profiles = profiles; this.worlds = worlds;
        session.assemblyGuard(this::validAssembly);
    }
    public static void configure(MinecraftServer server, P1LobbySession session, P1PlayerProfiles profiles, Supplier<P1RoundWorldBundle> worlds) {
        SERVICES.put(server, new P1HumanEntry(server, session, profiles, worlds));
    }
    public static void close(MinecraftServer server) {
        var s = SERVICES.remove(server); if (s != null) s.countdownBar.removeAllPlayers();
    }
    public static void disconnected(ServerPlayer player) {
        var s = SERVICES.get(player.level().getServer()); if (s != null) { s.intents.remove(player.getUUID()); s.joining.remove(player.getUUID()); }
    }
    private boolean verified(ServerPlayer player) {
        var round = session.snapshot().orElse(null);
        return round != null && session.state() == P1LobbySession.State.OPEN
                && server.getPlayerList().getPlayer(player.getUUID()) == player && player.connection != null && player.connection.isAcceptingMessages()
                && profiles.isReady(player.getUUID(), round) && P1PlayerProfiles.verifiedLobbyBody(player);
    }
    private boolean worldReady() {
        var bundle = worlds.get();
        return bundle != null && bundle.ready() && P1LobbyWorld.open(server).ready()
                && P1RoundWorldBundle.safeStanding(bundle.level(net.minecraft.world.level.Level.OVERWORLD), bundle.spawn());
    }
    public static void pause(MinecraftServer server) {
        var s = SERVICES.get(server); if (s != null) { s.countdownBar.setVisible(false); s.countdownBar.removeAllPlayers(); }
    }
    private boolean validAssembly(UUID id) {
        var i = intents.get(id);
        return i != null && !i.cancelled && verified(i.player) && worldReady() && P1LobbyWorld.open(server).inAssembly(i.player, i.inside);
    }
    private boolean eligible(ServerPlayer p, RoundSnapshot round) {
        var participant = round.participants().get(p.getUUID());
        return participant == null || participant.role() == ParticipantRole.LOBBY;
    }
    public static void tick(MinecraftServer server) { var s = SERVICES.get(server); if (s != null) s.tick(); }
    private void tick() {
        var round = session.snapshot().orElse(null); if (round == null) return;
        var lobby = P1LobbyWorld.open(server);
        if (!Objects.equals(registeredRound, round.roundId())) { intents.clear(); joining.clear(); registeredRound = null; }
        if (lobby.ready() && registeredRound == null) {
            var router = LobbyInteractions.get(server).orElseThrow();
            for (var facility : LobbyLayout.Facility.values()) {
                var pos = BlockPos.containing(lobby.layoutPosition(LobbyLayout.facility(facility)));
                router.registerBlock(pos, Blocks.LECTERN, LobbyContext.RULES, player -> {
                    if (facility == LobbyLayout.Facility.RULES) P2LobbyUi.open(player, LobbyContext.RULES);
                    else if (facility == LobbyLayout.Facility.MIDROUND) openGate(player);
                    else tell(player, "此设施尚未开放。开局请前往集结区，挑战或救场期间可使用中途入场门。");
                });
            }
            registeredRound = round.roundId();
        }
        String label = round.sequence() + " / " + round.phase() + " / " + session.recoveringCountdown();
        if (lobby.ready() && !label.equals(lastLabel)) {
            lobby.statusLabel(session.recoveringCountdown() ? "正在恢复上次集结" : switch(round.phase()) {
                case WAITING, COUNTDOWN -> "站入集结区参加"; case RUNNING, RESCUE -> "集结关闭 · 中途门开放";
                case PREPARING -> "世界准备中 · 入口关闭"; case SETTLING -> "本轮结算 · 入口关闭";
                case CELEBRATING -> "通关庆祝 · 入口关闭"; case ROTATING -> "正在换轮 · 入口关闭";
                case RECOVERY_REQUIRED -> "恢复核验中 · 入口关闭";
            }, round.rules().mode() == dev.hardrush.core.config.GameMode.NORMAL ? "普通模式 · 怪物区关闭" : "极限模式 · 怪物设施待开放"); lastLabel = label;
        }
        var view = session.view().orElse(round);
        countdownBar.setVisible(round.phase() == RoundPhase.COUNTDOWN);
        long readyCount = round.participants().values().stream().filter(p -> p.online() && p.ready() && p.role() == ParticipantRole.LOBBY).count();
        countdownBar.setName(Component.literal(session.recoveringCountdown() ? "正在恢复上次集结" : "已准备 " + readyCount + " 人 · " + (view.remainingPhaseMillis()+999)/1000 + " 秒后出发"));
        countdownBar.setProgress(Math.max(0, Math.min(1, (float)view.remainingPhaseMillis()/round.rules().startCountdownMillis())));
        for (var old : java.util.List.copyOf(countdownBar.getPlayers())) if (!verified(old)) countdownBar.removePlayer(old);
        for (var player : server.getPlayerList().getPlayers()) if (verified(player)) countdownBar.addPlayer(player);
        intents.entrySet().removeIf(e -> server.getPlayerList().getPlayer(e.getKey()) != e.getValue().player);
        for (var player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (!verified(player)) continue;
            var intent = intents.computeIfAbsent(player.getUUID(), ignored -> new Intent(player));
            var participant = round.participants().get(player.getUUID());
            if (session.originalReady(round.roundId(), player.getUUID())) {
                if (intent.restoring && !lobby.inAssembly(player, true)) intent.cancelled = true;
                if (worldReady() && !intent.pending) {
                    if (!intent.restoring) {
                        intent.restoring = true;
                        boolean safe = lobby.safeLanding(LobbyLayout.Landing.RESTORE_READY);
                        profiles.placeInLobby(player, safe ? LobbyLayout.Landing.RESTORE_READY : LobbyLayout.Landing.WAIT_OUTSIDE);
                        intent.inside = safe; intent.cancelled = !safe;
                    }
                    intent.pending = true;
                    boolean safe = !intent.cancelled && lobby.inAssembly(player, true) && lobby.safeLanding(LobbyLayout.Landing.RESTORE_READY);
                    session.restoreReady(round.roundId(), player.getUUID(), player.getScoreboardName(), safe, reply -> {
                        intent.pending = false;
                        if (reply.success()) tell(player, reply.message());
                    });
                }
                hint(player, "正在恢复上次集结 · 原准备者核验后按保存余量续计");
                continue;
            }
            boolean open = worldReady() && !session.recoveringCountdown()
                    && (round.phase() == RoundPhase.WAITING || round.phase() == RoundPhase.COUNTDOWN) && eligible(player, round);
            boolean inside = lobby.inAssembly(player, intent.inside);
            if (!open) {
                if (inside) { profiles.placeInLobby(player, LobbyLayout.Landing.WAIT_OUTSIDE); tell(player, "集结区已关闭；挑战或救场期间请使用侧面的中途入场门。" ); }
                intent.inside = false; intent.cancelled = true;
            } else {
                if (intent.inside && !inside) { intent.cancelled = true; tell(player, "已取消准备。"); }
                intent.inside = inside;
                boolean ready = participant != null && participant.ready();
                // Once an in-flight intention is cancelled, re-entry cannot resurrect it. First
                // durably clear the old ready bit, then accept a new intention on a later tick.
                boolean wanted = inside && !intent.cancelled;
                if (!intent.pending && intent.cancelled && !ready) { intent.cancelled = false; wanted = inside; }
                if (!intent.pending && (wanted != ready)) {
                    intent.pending = true;
                    boolean preparing = wanted;
                    session.requestAssembly(round.roundId(), player.getUUID(), player.getScoreboardName(), wanted,
                            reply -> {
                                intent.pending = false;
                                if (reply.success() && preparing && validAssembly(player.getUUID())) {
                                    tell(player, "已准备，离开集结区可取消。");
                                    player.level().playSound(null, player.getX(), player.getY(), player.getZ(), net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP,
                                            net.minecraft.sounds.SoundSource.PLAYERS, .5f, 1.2f);
                                    player.level().sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, player.getX(), player.getY()+.5, player.getZ(), 5, .3, .3, .3, 0);
                                }
                            });
                }
                if (inside) hint(player, intent.pending || !ready ? "正在登记准备" : "已准备 · "
                        + (session.view().orElse(round).remainingPhaseMillis() + 999) / 1000 + " 秒后出发 · 离开集结区可取消");
                else hint(player, "站入集结区参加，离开取消准备");
            }
            boolean gate = gateDistance(player, 1.8);
            if (gate && !intent.gate && (round.phase() == RoundPhase.RUNNING || round.phase() == RoundPhase.RESCUE)) openGate(player);
            intent.gate = gate;
        }
    }
    private void hint(ServerPlayer player, String message) {
        long now = System.nanoTime() / 1_000_000;
        LobbyInteractions.get(server).ifPresent(router -> {
            var hints = router.hints(player);
            hints.offer("assembly", LobbyHints.Priority.READY, message, now + 150);
            hints.current(now).ifPresent(text -> player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(Component.literal(text))));
        });
    }
    private boolean gateDistance(ServerPlayer player, double range) {
        var lobby = P1LobbyWorld.open(server);
        return lobby.ready() && P1LobbyWorld.isLobby(player.level())
                && player.position().distanceToSqr(lobby.layoutPosition(LobbyLayout.facility(LobbyLayout.Facility.MIDROUND))) <= range * range;
    }
    public static boolean gateAvailable(ServerPlayer player) {
        var s = SERVICES.get(player.level().getServer()); if (s == null) return false;
        var round = s.session.snapshot().orElse(null);
        return s.verified(player) && s.worldReady() && round != null && !s.session.competitionLocked()
                && s.gateDistance(player, 5) && s.eligible(player, round);
    }
    public static boolean openGate(ServerPlayer player) {
        var s = SERVICES.get(player.level().getServer()); if (s == null) return false;
        if (!gateAvailable(player)) {
            tell(player, "中途门当前关闭，或你已参赛。极限死者请前往怪物庭院；有资格时使用祭坛。"); return false;
        }
        return P2LobbyUi.open(player, LobbyContext.ENTRY);
    }
    public static List<P2LobbyUi.Button> gateButtons(ServerPlayer player) {
        var s = SERVICES.get(player.level().getServer()); var round = s.session.view().orElseThrow();
        return List.of(new P2LobbyUi.Button(Items.PAPER, "第 " + round.sequence() + " 轮 · " + (round.rules().mode() == dev.hardrush.core.config.GameMode.NORMAL ? "普通模式" : "极限模式") + " · 挑战 " + round.challengeMillis() / 1000 + " 秒", "info", ""),
                new P2LobbyUi.Button(Items.GRASS_BLOCK, "空装进入主世界自然出生点，无出生保护", "info", ""),
                new P2LobbyUi.Button(Items.EMERALD, s.joining.contains(player.getUUID()) ? "正在加入" : "加入本轮", "enter", ""));
    }
    public static void confirm(ServerPlayer player, UUID expectedRound) {
        var s = SERVICES.get(player.level().getServer()); if (s == null || !gateAvailable(player) || !s.session.snapshot().orElseThrow().roundId().equals(expectedRound)
                || !s.joining.add(player.getUUID())) return;
        s.session.requestJoin(player.getUUID(), player.getScoreboardName(), reply -> {
            s.joining.remove(player.getUUID());
            if (s.server.getPlayerList().getPlayer(player.getUUID()) == player) tell(player, reply.message());
        });
    }
    private static void tell(ServerPlayer player, String text) { if (player.connection != null) player.sendSystemMessage(Component.literal(text)); }
}
