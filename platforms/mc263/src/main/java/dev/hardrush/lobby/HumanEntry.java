package dev.hardrush.lobby;

import dev.hardrush.core.lobby.*;
import dev.hardrush.core.record.RoundResultText;
import dev.hardrush.core.round.*;
import dev.hardrush.round.RoundSession;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.world.*;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Native spatial input and confirmation. No UI close event owns an accepted conversion. */
public final class HumanEntry {
    private static final Map<MinecraftServer, HumanEntry> SERVICES = new IdentityHashMap<>();
    /** Vanilla playersNetherPortalDefaultDelay default (NetherPortalBlock.getPortalTransitionTime). */
    static final int MIDROUND_DWELL_TICKS = 80;
    private final MinecraftServer server;
    private final RoundSession session;
    private final PlayerProfiles profiles;
    private final Supplier<RoundWorldBundle> worlds;
    private final Map<UUID, Intent> intents = new HashMap<>();
    private final Set<UUID> joining = new HashSet<>();
    private UUID registeredRound;
    private String lastLabel;
    private final net.minecraft.server.level.ServerBossEvent countdownBar = new net.minecraft.server.level.ServerBossEvent(
            UUID.randomUUID(), Component.literal("集结区"), net.minecraft.world.BossEvent.BossBarColor.GREEN, net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);
    private static final class Intent {
        final ServerPlayer player;
        boolean inside, pending, cancelled, restoring, joinSpent;
        int dwell;
        net.minecraft.server.level.ServerBossEvent joinBar;
        Intent(ServerPlayer player) { this.player = player; }
    }
    private HumanEntry(MinecraftServer server, RoundSession session, PlayerProfiles profiles, Supplier<RoundWorldBundle> worlds) {
        this.server = server; this.session = session; this.profiles = profiles; this.worlds = worlds;
        session.assemblyGuard(this::validAssembly);
    }
    public static void configure(MinecraftServer server, RoundSession session, PlayerProfiles profiles, Supplier<RoundWorldBundle> worlds) {
        SERVICES.put(server, new HumanEntry(server, session, profiles, worlds));
    }
    public static void close(MinecraftServer server) {
        var s = SERVICES.remove(server); if (s != null) { s.countdownBar.removeAllPlayers(); s.intents.values().forEach(HumanEntry::stopJoin); }
    }
    public static void disconnected(ServerPlayer player) {
        var s = SERVICES.get(player.level().getServer()); if (s != null) { var intent = s.intents.remove(player.getUUID()); if (intent != null) stopJoin(intent); s.joining.remove(player.getUUID()); }
    }
    private boolean verified(ServerPlayer player) {
        var round = session.snapshot().orElse(null);
        return round != null && session.state() == RoundSession.State.OPEN
                && server.getPlayerList().getPlayer(player.getUUID()) == player && player.connection != null && player.connection.isAcceptingMessages()
                && profiles.isReady(player.getUUID(), round) && PlayerProfiles.verifiedLobbyBody(player);
    }
    private boolean worldReady() {
        var bundle = worlds.get();
        return bundle != null && bundle.ready() && LobbyWorld.open(server).ready()
                && RoundWorldBundle.safeStanding(bundle.level(net.minecraft.world.level.Level.OVERWORLD), bundle.spawn());
    }
    public static void pause(MinecraftServer server) {
        var s = SERVICES.get(server); if (s != null) { s.countdownBar.setVisible(false); s.countdownBar.removeAllPlayers(); s.intents.values().forEach(HumanEntry::stopJoin); }
    }
    private boolean validAssembly(UUID id) {
        var i = intents.get(id);
        return i != null && !i.cancelled && verified(i.player) && worldReady() && LobbyWorld.open(server).inAssembly(i.player, i.inside);
    }
    private boolean eligible(ServerPlayer p, RoundSnapshot round) {
        var participant = round.participants().get(p.getUUID());
        return participant == null || participant.role() == ParticipantRole.LOBBY;
    }
    /** Read-only registration also runs during recovery, before competitive tick short-circuits. */
    public static void readOnlyTick(MinecraftServer server) {
        var s=SERVICES.get(server); var lobby=LobbyWorld.open(server);
        if(s==null||!lobby.ready()||s.session.snapshot().isEmpty())return;
        var router=LobbyInteractions.get(server).orElse(null); if(router==null)return;
        router.registerBlock(BlockPos.containing(lobby.layoutPosition(LobbyLayout.facility(LobbyLayout.Facility.RULES))), Blocks.LECTERN, LobbyContext.RULES, p->LobbyMenus.open(p,LobbyContext.RULES));
        router.registerBlock(BlockPos.containing(lobby.layoutPosition(LobbyLayout.facility(LobbyLayout.Facility.HISTORY))), Blocks.LECTERN, LobbyContext.HISTORY, dev.hardrush.record.HistoryLibrary::open);
    }
    public static void tick(MinecraftServer server) { var s = SERVICES.get(server); if (s != null) s.tick(); }
    private void tick() {
        var round = session.snapshot().orElse(null); if (round == null) return;
        var lobby = LobbyWorld.open(server);
        if (!Objects.equals(registeredRound, round.roundId())) { intents.values().forEach(HumanEntry::stopJoin); intents.clear(); joining.clear(); registeredRound = null; }
        if (lobby.ready() && registeredRound == null) {
            var router = LobbyInteractions.get(server).orElseThrow();
            for (var facility : LobbyLayout.Facility.values()) {
                // The altar is stood on, not clicked; see RevivalAltar.
                if (LobbyLayout.MONSTER_FACILITIES.contains(facility) || facility == LobbyLayout.Facility.MERCHANT
                        || facility == LobbyLayout.Facility.ALTAR) continue;
                var pos = BlockPos.containing(lobby.layoutPosition(LobbyLayout.facility(facility)));
                if (facility == LobbyLayout.Facility.VAULT || facility == LobbyLayout.Facility.OMINOUS_VAULT) {
                    var key = facility == LobbyLayout.Facility.VAULT ? dev.hardrush.core.round.ShopOffer.TRIAL_KEY
                            : dev.hardrush.core.round.ShopOffer.OMINOUS_KEY;
                    router.registerBlock(pos, Blocks.VAULT, LobbyContext.DISPLAY, player -> MonsterVaults.use(player, key));
                    continue;
                }
                router.registerBlock(pos, Blocks.LECTERN, LobbyContext.RULES, player -> {
                    if (facility == LobbyLayout.Facility.RULES) LobbyMenus.open(player, LobbyContext.RULES);
                    else if (facility == LobbyLayout.Facility.PREVIOUS || facility == LobbyLayout.Facility.NEXT)
                        dev.hardrush.lobby.LobbyRuntime.facility(player, facility);
                    else if (facility == LobbyLayout.Facility.HISTORY) dev.hardrush.record.HistoryLibrary.open(player);
                });
            }
            registeredRound = round.roundId();
        }
        String label = round.sequence() + " / " + round.phase() + " / " + session.recoveringCountdown();
        if (lobby.ready() && !label.equals(lastLabel)) {
            lobby.huntLabels(round.rules().mode() == dev.hardrush.core.config.GameMode.HARDCORE
                    && round.rules().huntRule() == dev.hardrush.core.config.RulesConfig.HuntRule.PERSONAL_TARGET
                    && (round.phase() == RoundPhase.RUNNING || round.phase() == RoundPhase.RESCUE));
            lobby.statusLabel(session.recoveringCountdown() ? "正在恢复上次集结" : switch(round.phase()) {
                case WAITING, COUNTDOWN -> "站入集结区参加"; case RUNNING, RESCUE -> "挑战中 · 站圈中途加入";
                case PREPARING -> "世界准备中 · 稍后开放";
                case SETTLING -> RoundResultText.stageLabel(round.result(), "本轮结算") + " · 集结暂停";
                case CELEBRATING -> RoundResultText.stageLabel(round.result(), "通关庆祝") + " · 集结暂停";
                case ROTATING -> "正在换轮 · 集结暂停";
                case RECOVERY_REQUIRED -> "恢复核验中 · 集结暂停";
            }, round.rules().mode() == dev.hardrush.core.config.GameMode.NORMAL ? "普通模式 · 怪物区关闭" : "极限模式 · 角色／追猎／祭坛"); lastLabel = label;
        }
        var view = session.view().orElse(round);
        countdownBar.setVisible(round.phase() == RoundPhase.COUNTDOWN);
        long readyCount = round.participants().values().stream().filter(p -> p.online() && p.ready() && p.role() == ParticipantRole.LOBBY).count();
        countdownBar.setName(Component.literal(session.recoveringCountdown() ? "正在恢复上次集结" : "已准备 " + readyCount + " 人 · " + (view.remainingPhaseMillis()+999)/1000 + " 秒后出发"));
        countdownBar.setProgress(Math.max(0, Math.min(1, (float)view.remainingPhaseMillis()/round.rules().startCountdownMillis())));
        for (var old : java.util.List.copyOf(countdownBar.getPlayers())) if (!verified(old)) countdownBar.removePlayer(old);
        for (var player : server.getPlayerList().getPlayers()) if (verified(player)) countdownBar.addPlayer(player);
        intents.entrySet().removeIf(e -> {
            boolean stale = server.getPlayerList().getPlayer(e.getKey()) != e.getValue().player;
            if (stale) stopJoin(e.getValue());
            return stale;
        });
        for (var player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (!verified(player)) { var held = intents.get(player.getUUID()); if (held != null) stopJoin(held); continue; }
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
                // Never relocate: a closed circle only explains itself. Whoever still stands inside
                // when it opens again prepares automatically through the cancelled-intention path.
                intent.inside = inside; intent.cancelled = true;
                if (midroundOpen(player, round)) midround(player, intent, inside);
                else {
                    stopJoin(intent);
                    if (inside) hint(player, closedReason(player, round));
                }
            } else {
                stopJoin(intent);
                boolean ready = participant != null && participant.ready();
                if (intent.inside && !inside) { intent.cancelled = true; if (ready || intent.pending) tell(player, "已取消准备。"); }
                intent.inside = inside;
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
        }
    }
    private void hint(ServerPlayer player, String message) {
        long now = System.nanoTime() / 1_000_000;
        LobbyInteractions.get(server).ifPresent(router -> {
            var hints = router.hints(player);
            if (message.startsWith("站入") && player.position().distanceToSqr(LobbyWorld.open(server).landing(LobbyLayout.Landing.RESTORE_READY)) > 144) {
                hints.clear("assembly"); return;
            }
            hints.offer("assembly", message.startsWith("站入") ? LobbyHints.Priority.FACILITY : LobbyHints.Priority.READY, message, now + 150);
        });
    }
    /** Why standing in the circle does nothing right now; only shown to someone inside it. */
    private String closedReason(ServerPlayer player, RoundSnapshot round) {
        var participant = round.participants().get(player.getUUID());
        if (participant != null && participant.role().isMonster()) return "你已化身怪物 · 去怪物庭院追猎，或到复生祭坛复生";
        if (participant != null && participant.role() == ParticipantRole.WITHDRAWN) return "你已退出本轮，下一轮可重新集结";
        if (participant != null && participant.role() == ParticipantRole.DISQUALIFIED) return "你已被取消本轮资格，下一轮可重新集结";
        if (session.state() != RoundSession.State.OPEN || round.phase() == RoundPhase.RECOVERY_REQUIRED) return "恢复核验中 · 集结区暂停";
        if (session.recoveringCountdown()) return "正在恢复上次集结 · 原准备者核验后续计";
        return switch (round.phase()) {
            case PREPARING, WAITING, COUNTDOWN -> "本轮世界准备中 · 开放后站在圈内自动准备";
            case SETTLING, CELEBRATING -> "本轮结束中 · 换轮后重新开放";
            case ROTATING -> "正在换轮 · 稍后重新开放";
            // Running with a temporary lock (checkpoint save, pending terminal result) or an in-flight identity change.
            default -> eligible(player, round) ? "本轮正在保存 · 稍候即可加入" : "身份转换中，请稍候";
        };
    }
    private boolean midroundOpen(ServerPlayer player, RoundSnapshot round) {
        return (round.phase() == RoundPhase.RUNNING || round.phase() == RoundPhase.RESCUE)
                && worldReady() && !session.competitionLocked() && eligible(player, round);
    }
    /** Mid-round admission: test seam for eligibility, independent of where the player stands. */
    public static boolean midroundAvailable(ServerPlayer player) {
        var s = SERVICES.get(player.level().getServer()); if (s == null) return false;
        var round = s.session.snapshot().orElse(null);
        return round != null && s.verified(player) && s.midroundOpen(player, round);
    }
    /** Like a Nether portal: stand the vanilla default delay inside, leave to cancel. Submitted requests are final. */
    private void midround(ServerPlayer player, Intent intent, boolean inside) {
        if (!inside) {
            stopJoin(intent); intent.joinSpent = false;
            hint(player, "站入集结区 4 秒中途加入"); return;
        }
        if (intent.joinSpent) { hint(player, joining.contains(player.getUUID()) ? "正在加入本轮" : "出圈后可重新站入加入"); return; }
        if (intent.joinBar == null) {
            intent.joinBar = new net.minecraft.server.level.ServerBossEvent(UUID.randomUUID(), Component.empty(),
                    net.minecraft.world.BossEvent.BossBarColor.PURPLE, net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);
            intent.joinBar.addPlayer(player);
            if (player.connection != null) player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                    net.minecraft.core.Holder.direct(net.minecraft.sounds.SoundEvents.PORTAL_TRIGGER), net.minecraft.sounds.SoundSource.AMBIENT,
                    player.getX(), player.getY(), player.getZ(), .25f, .8f + player.getRandom().nextFloat() * .4f, 0));
        }
        intent.dwell++;
        intent.joinBar.setName(Component.literal(worldAge() + " · 中途加入无出生保护 · 祝你好运"));
        intent.joinBar.setProgress(Math.max(0, 1 - (float)intent.dwell / MIDROUND_DWELL_TICKS));
        if (intent.dwell % 4 == 0) player.level().sendParticles(net.minecraft.core.particles.ParticleTypes.PORTAL,
                player.getX(), player.getY() + .8, player.getZ(), 6, .4, .6, .4, .3);
        if (intent.dwell < MIDROUND_DWELL_TICKS) { hint(player, "离开集结区取消引导"); return; }
        // Late lives land far from spawn; hold the portal open until a prepared landing is claimed.
        if (!dev.hardrush.world.FarLandings.reserve(server, player.getUUID())) {
            intent.joinBar.setName(Component.literal("正在寻找远方的落点…"));
            hint(player, "正在寻找远方的落点 · 离开集结区取消引导"); return;
        }
        stopJoin(intent); intent.joinSpent = true;
        if (!joining.add(player.getUUID())) return;
        session.requestJoin(player.getUUID(), player.getScoreboardName(), reply -> {
            joining.remove(player.getUUID());
            if (server.getPlayerList().getPlayer(player.getUUID()) == player) tell(player, reply.message());
        });
    }
    /** Test seam: the player is currently counting down a mid-round admission. */
    public static boolean guiding(ServerPlayer player) {
        var s = SERVICES.get(player.level().getServer()); if (s == null) return false;
        var intent = s.intents.get(player.getUUID());
        return intent != null && intent.player == player && intent.dwell > 0;
    }
    private static void stopJoin(Intent intent) {
        intent.dwell = 0;
        if (intent.joinBar != null) { intent.joinBar.removeAllPlayers(); intent.joinBar = null; }
    }
    /** Elapsed time of this round's overworld default clock, as F3 counts days (skipped nights included). */
    private String worldAge() {
        var level = worlds.get().level(net.minecraft.world.level.Level.OVERWORLD);
        long ticks = level.dimensionTypeRegistration().value().defaultClock()
                .map(clock -> level.clockManager().getInstance(clock).totalTicks()).orElse(level.getGameTime());
        return "世界已运转 " + ticks / 24000 + " 天 " + ticks % 24000 / 1000 + " 小时";
    }
    private static void tell(ServerPlayer player, String text) { if (player.connection != null) player.sendSystemMessage(Component.literal(text)); }
}
