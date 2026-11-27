package dev.hardrush.lobby;

import dev.hardrush.core.hunt.LateShelter;
import dev.hardrush.core.hunt.PersonalHuntRequest;
import dev.hardrush.core.lobby.plan.Landing;
import dev.hardrush.core.lobby.plan.LobbyFunction;
import com.mojang.authlib.GameProfile;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.lobby.LobbyContext;
import dev.hardrush.core.round.*;
import dev.hardrush.monster.MonsterCompanions;
import dev.hardrush.monster.MonsterRoles;
import dev.hardrush.round.RoundSession;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.world.RoundWorldBundle;
import dev.hardrush.hunt.HuntRetracks;
import dev.hardrush.hunt.SafeDeployment;
import dev.hardrush.protection.ProtectionRuntime;
import dev.hardrush.monster.MonsterRoundRuntime;
import dev.hardrush.combat.MonsterCombatRuntime;
import dev.hardrush.notice.Notices;
import dev.hardrush.notice.Replies;
import java.util.*;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Main-thread lobby requests. Native admission follows the durable sortie transition. */
public final class LobbyRuntime {
    /** A role click that would discard a paused sortie, waiting for a separate second click; times are server ticks. */
    private record AbandonAsk(MonsterRole role, long asked, long lastClick) {}
    /**
     * A five-second channel: back to camp, standing still, or (retrack) onto the quarry's trail again, which the
     * monster may keep walking through. Taking damage or acting breaks either.
     */
    private record Channel(ServerPlayer player, ServerLevel level, long started, int life, int sortie, boolean retrack) {}
    /** The search for a landing after a completed retrack channel; the monster may keep walking meanwhile. */
    private static final class Retrack {
        final ServerPlayer player, targetPlayer;
        final ServerLevel origLevel;
        final int life, sortie, targetLife;
        final long started;
        SafeDeployment.Search search;
        Retrack(ServerPlayer player, ServerPlayer target, int life, int sortie, int targetLife, long started) {
            this.player = player; targetPlayer = target; origLevel = (ServerLevel) player.level();
            this.life = life; this.sortie = sortie; this.targetLife = targetLife; this.started = started;
        }
    }
    private static final class Deployment {
        final ServerPlayer player, targetPlayer;
        final net.minecraft.server.network.ServerGamePacketListenerImpl connection, targetConnection;
        final PersonalHuntRequest identity;
        final long started;
        int sortie;
        ServerLevel level;
        SafeDeployment.Search search;
        boolean cancelled, seaTipShown;
        /** 0 while it holds one of the quarry's hunt slots; otherwise its place in that quarry's queue. */
        int queued;
        /** When the 60-second search window counts from: the click, or later when a long queue left too little of it. */
        long searchFrom;
        Deployment(ServerPlayer player, ServerPlayer target, PersonalHuntRequest identity, long started) {
            this.player = player; targetPlayer = target; connection = player.connection; targetConnection = target == null ? null : target.connection;
            this.identity = identity; this.started = started; searchFrom = started; sortie = identity.initialSortie();
        }
    }
    private record Refund(Deployment deployment, boolean resume) {}
    private record Display(ServerPlayer player, net.minecraft.server.network.ServerGamePacketListenerImpl connection,
                           List<LobbyProjections.Target> targets) {}
    /** A death told once the body lands in camp; {@code wary} names the humans a monster's defeat locked. */
    private record Arrival(boolean human, List<String> wary, boolean paidCreeper) {}
    private static final class Binding {
        final RoundSession session;
        final PlayerProfiles profiles;
        final Supplier<RoundWorldBundle> worlds;
        java.util.function.LongSupplier searchClock = System::nanoTime;
        final dev.hardrush.core.hunt.HuntOmens omens = new dev.hardrush.core.hunt.HuntOmens();
        Random searchRandom;
        UUID round;
        final Map<UUID, Deployment> searches = new HashMap<>();
        final Map<UUID, Deployment> resumptions = new HashMap<>();
        final Map<UUID, Deployment> pending = new HashMap<>();
        final Map<UUID, Refund> refunds = new HashMap<>();
        /** Courtyard seats in display-slot order; see {@link #seat}. */
        final ArrayList<GameProfile> slots = new ArrayList<>();
        final Map<UUID, Integer> deadAt = new HashMap<>();
        final Map<UUID, Display> displays = new HashMap<>();
        /** Display copies of each human's worn gear, read every {@link #GEAR_TICKS} ticks. */
        final Map<UUID, Map<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack>> gear = new HashMap<>();
        final Map<UUID, Boolean> cooling = new HashMap<>();
        /** The humans each camp monster's defeats locked when last shown, to tell it when one is free again. */
        final Map<UUID, java.util.Set<UUID>> locked = new HashMap<>();
        final Set<UUID> reviving = new HashSet<>();
        final Map<UUID, Channel> channels = new HashMap<>();
        /** Monsters whose return channel finished and whose return is still committing: the shard answers nothing meanwhile. */
        final Set<UUID> returning = new HashSet<>();
        final Map<UUID, Retrack> retracks = new HashMap<>();
        final Map<UUID, Long> retryAt = new HashMap<>();
        final Set<UUID> committing = new HashSet<>();
        final Map<UUID, AbandonAsk> abandoning = new HashMap<>();
        /** Deaths waiting for the respawned body to stand in camp before they are told. */
        final Map<UUID, Arrival> arrivals = new HashMap<>();
        Binding(RoundSession session, PlayerProfiles profiles, Supplier<RoundWorldBundle> worlds) {
            this.session = session; this.profiles = profiles; this.worlds = worlds;
        }
    }
    private static final Map<MinecraftServer, Binding> BINDINGS = new IdentityHashMap<>();
    private static boolean initialized;
    private LobbyRuntime() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (entity instanceof ServerPlayer player && taken > 0) interruptChannel(player, "受到伤害");
        });
    }

    public static void configure(MinecraftServer server, RoundSession session, PlayerProfiles profiles,
                                 Supplier<RoundWorldBundle> worlds) {
        var binding = new Binding(session, profiles, worlds);
        BINDINGS.put(server, binding);
        LobbyInteractions.configure(server, new LobbyInteractions.Access() {
            public UUID round() { return session.snapshot().map(RoundSnapshot::roundId).orElse(null); }
            public boolean allowed(ServerPlayer player, LobbyContext context) {
                return contextAvailable(player, binding, context);
            }
        });
        MonsterRoundRuntime.configure(server, session, profiles, worlds);
        var camp = new CampAccess() {
            @Override public Optional<RoundSnapshot> committed() { return session.snapshot(); }
            @Override public boolean eligible(ServerPlayer player) {
                var r = session.snapshot().orElse(null);
                var p = r == null ? null : r.participants().get(player.getUUID());
                return p != null && p.online() && p.role() == ParticipantRole.MONSTER_LOBBY
                        && r.rules().mode() == dev.hardrush.core.config.GameMode.HARDCORE
                        && (r.phase() == RoundPhase.RUNNING || r.phase() == RoundPhase.RESCUE)
                        && !session.competitionLocked() && current(player, binding)
                        && PlayerProfiles.verifiedLobbyBody(player) && player.isAlive();
            }
            /** A hunt still waiting for a landing is not busy: the camp stays usable while it waits. */
            @Override public boolean busy(UUID player) {
                return binding.committing.contains(player) || binding.resumptions.containsKey(player) || binding.pending.containsKey(player);
            }
            @Override public RoundSession session() { return session; }
        };
        dev.hardrush.revival.RevivalTotems.configure(server, new dev.hardrush.revival.RevivalTotems.Access() {
            @Override public Optional<RoundSnapshot> committed() { return session.snapshot(); }
            @Override public boolean competitionLocked() { return session.competitionLocked(); }
            @Override public boolean bodyReady(ServerPlayer player, dev.hardrush.core.round.Participant participant) {
                if (!current(player, binding) || !player.isAlive()) return false;
                return switch (participant.role()) {
                    case MONSTER_LOBBY -> PlayerProfiles.verifiedLobbyBody(player);
                    // The same body AFTER_DEATH would capture a death from: admitted, verified, unlocked, this life.
                    case HUMAN_ACTIVE -> PlayerProfiles.verifiedRoundBody(player)
                            && ((dev.hardrush.player.ProfileTagged) player).hardrush$lifeSequence() == participant.lifeSequence();
                    default -> false;
                };
            }
        });
        // A save in flight already left the hand: the projection shows the balance it will commit to.
        dev.hardrush.revival.RevivalTotems.pendingSaves(server, session::pendingTotemSaves);
        dev.hardrush.revival.TotemSave.configure(server, (player, round, life, killCredit, drop) -> {
            // The body whose death AFTER_DEATH would capture, inside its lethal hit; the session decides the rest.
            if (!current(player, binding) || !PlayerProfiles.verifiedDyingRoundBody(player)
                    || ((dev.hardrush.player.ProfileTagged) player).hardrush$lifeSequence() != life
                    || !session.captureTotemSave(round, player.getUUID(), life, killCredit, drop)) return OptionalInt.empty();
            return OptionalInt.of(session.totemChargesLeft(player.getUUID()));
        });
        dev.hardrush.revival.TotemTab.configure(server, new dev.hardrush.revival.TotemTab.Access() {
            @Override public Optional<RoundSnapshot> committed() { return session.snapshot(); }
            @Override public int chargesLeft(UUID player) { return session.totemChargesLeft(player); }
        });
        dev.hardrush.revival.TotemDrops.configure(server, new dev.hardrush.revival.TotemDrops.Access() {
            @Override public Optional<RoundSnapshot> view() { return session.view(); }
            @Override public boolean open() { return session.state() == RoundSession.State.OPEN; }
            @Override public boolean collects(ServerPlayer player) { return dev.hardrush.revival.RevivalTotems.projectsHuman(player); }
            @Override public boolean hunts(ServerPlayer player) {
                return MonsterRoles.isMonster(player) && dev.hardrush.monster.MonsterRoundRuntime.canAct(player);
            }
            @Override public void refresh(ServerPlayer player) { dev.hardrush.revival.RevivalTotems.refresh(player); }
            @Override public void requestPickup(UUID round, UUID token, UUID player, int life, int sortie,
                    java.util.function.Consumer<RoundSession.Reply> reply) {
                session.requestLooseTotemPickup(round, token, player, life, sortie, reply);
            }
            @Override public boolean saveInFlight(UUID token) { return session.totemSaveDropInFlight(token); }
        });
        CampTransfers.configure(server, camp);
        CampKit.configure(server, camp);
        MonsterShop.configure(server, camp);
        MonsterVaults.configure(server, camp);
        MonsterCombatRuntime.configure(server, new MonsterCombatRuntime.Access() {
            @Override public Optional<RoundSnapshot> snapshot() { return session.snapshot(); }
            @Override public boolean competitionLocked() { return session.competitionLocked(); }
            @Override public boolean formalLevel(ServerLevel level) { return !dimension(binding, level).equals("lobby"); }
        });
        LobbyMenus.configure(server, new LobbyMenus.Access() {
            // The old competition menu is gone: every action has an in-world entrance (models, altar, items).
            @Override public boolean available(ServerPlayer player) { return false; }
            @Override public boolean available(ServerPlayer player, LobbyContext context) {
                return context == LobbyContext.COMPETITION ? available(player) : contextAvailable(player, binding, context);
            }
            @Override public List<ButtonMenu.Button> buttons(ServerPlayer player, LobbyContext context) {
                if (context == LobbyContext.WITHDRAWAL) return dev.hardrush.participation.ParticipationControl.withdrawalButtons(player);
                if (context == LobbyContext.HISTORY) return dev.hardrush.record.HistoryLibrary.buttons(player);
                // The rule lectern opens a book instead (RuleBook); no other context has a menu.
                return List.of();
            }
            @Override public void act(ServerPlayer player, UUID round, LobbyContext context, String action, String value) {
                if (context == LobbyContext.WITHDRAWAL) {
                    if (action.equals("withdraw-confirm")) dev.hardrush.participation.ParticipationControl.confirmWithdrawal(player, round, value);
                    else if (action.equals("withdraw-cancel")) player.closeContainer();
                }
                else if (context == LobbyContext.HISTORY) dev.hardrush.record.HistoryLibrary.act(player, action, value);
            }
            @Override public UUID round(ServerPlayer player) { return session.snapshot().orElseThrow().roundId(); }
            @Override public List<ButtonMenu.Button> buttons(ServerPlayer player) { return List.of(); }
            @Override public void act(ServerPlayer player, UUID round, String action, String value) { }
        });
    }
    public static void unconfigure(MinecraftServer server) {
        BINDINGS.remove(server); LobbyInteractions.close(server); LobbyMenus.unconfigure(server); dev.hardrush.revival.RevivalTotems.unconfigure(server);
        dev.hardrush.revival.TotemDrops.unconfigure(server); dev.hardrush.revival.TotemSave.unconfigure(server);
        dev.hardrush.revival.TotemTab.unconfigure(server); CampTransfers.unconfigure(server);
        CampKit.unconfigure(server); MonsterShop.unconfigure(server); MonsterVaults.unconfigure(server);
        MonsterRoundRuntime.unconfigure(server); MonsterCombatRuntime.unconfigure(server);
    }
    public static void prepareStop(MinecraftServer server) {
        var b = BINDINGS.get(server); if (b == null) return;
        b.pending.values().forEach(d -> d.cancelled = true);
        b.channels.clear(); b.returning.clear(); b.retracks.clear(); b.searches.clear(); b.resumptions.clear(); b.displays.clear(); b.cooling.clear(); b.abandoning.clear();
        LobbyInteractions.get(server).ifPresent(r -> r.projections().clear());
        for (var player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (MonsterRoles.isMonster(player)) MonsterRoles.clear(player);
        }
    }
    private static boolean contextAvailable(ServerPlayer player, Binding b, LobbyContext context) {
        boolean verified = current(player, b) && PlayerProfiles.verifiedLobbyBody(player);
        return switch (context) {
            case WITHDRAWAL -> dev.hardrush.participation.ParticipationControl.canWithdraw(player);
            case RULES -> verified;
            case HISTORY -> verified;
            case DISPLAY -> verified && dev.hardrush.round.RoundRuntime.lobbyActivityAllowed(player);
            case COMPETITION -> verified && dev.hardrush.round.RoundRuntime.lobbyActivityAllowed(player)
                    && b.worlds.get() != null && b.worlds.get().ready()
                    && b.session.snapshot().filter(r -> switch (r.phase()) {
                        case WAITING, COUNTDOWN, RUNNING, RESCUE -> true;
                        default -> false;
                    }).isPresent();
        };
    }

    private static boolean current(ServerPlayer player, Binding b) {
        return b != null && player.level().getServer().getPlayerList().getPlayer(player.getUUID()) == player
                && b.session.state() == RoundSession.State.OPEN
                && b.session.snapshot().filter(r -> b.profiles.isReady(player.getUUID(), r)).isPresent();
    }
    private static boolean lobbyAvailable(ServerPlayer player, Binding b) {
        return current(player, b) && dev.hardrush.world.LobbyWorld.isLobby(player.level())
                && player.isAlive();
    }
    private static final String PAUSED = "比赛已暂停，暂时不能出击";
    /** Only called with a server-owned projection selection after BOTH packet guards. */
    private static void beginDeployment(ServerPlayer player, Binding b, LobbyProjections.Selection selected) {
        var round = b.session.view().orElse(null);
        if (round == null || !selected.round().equals(round.roundId()) || !personalOpen(b, round)
                || !contextAvailable(player, b, LobbyContext.DISPLAY)) return;
        hunt(player, b, round, selected.target(), selected.life(), false);
    }
    /** Holding jump while riding a human's camera is the same request as right-clicking its model. */
    public static void huntObserved(ServerPlayer player, UUID target) {
        var b = BINDINGS.get(player.level().getServer());
        var round = b == null ? null : b.session.view().orElse(null);
        if (round == null || !personalOpen(b, round) || !current(player, b) || !dev.hardrush.observe.Observation.observing(player)) return;
        var quarry = round.participants().get(target);
        if (quarry == null || quarry.role() != ParticipantRole.HUMAN_ACTIVE) { Notices.refuse(player, Notices.key(quarry == null ? "此人" : quarry.name()) + " 目前不适合再被追猎了，换个人吧"); return; }
        hunt(player, b, round, target, quarry.lifeSequence(), true);
    }
    private static void hunt(ServerPlayer player, Binding b, RoundSnapshot round, UUID targetId, int targetLife, boolean observed) {
        var p = round.participants().get(player.getUUID());
        if (p == null || p.role() != ParticipantRole.MONSTER_LOBBY || !p.online()) {
            Notices.refuse(player, "只有庭院里待命的怪物可以追猎"); return;
        }
        var waiting = b.searches.get(p.id());
        if (waiting != null && waiting.identity.target().equals(targetId)) {
            // Holding right-click repeats the interaction every few ticks; only a deliberate second click cancels.
            if (b.searchClock.getAsLong() - waiting.started < 1_000_000_000L) return;
            cancelSearch(b, waiting, "对 " + Notices.key(waiting.targetPlayer.getScoreboardName()) + " 的追猎已取消"); sound(player, false); return;
        }
        if (b.committing.contains(p.id()) || b.resumptions.containsKey(p.id())) {
            Notices.hint(player, "正在出击……"); return;
        }
        if (revivalFirst(player, round, p)) return;
        if (round.adminPaused()) { Notices.refuse(player, PAUSED); return; }
        var quarry = round.participants().get(targetId);
        var creeperRefusal = creeperRefusal(round, p, targetId);
        if (creeperRefusal.isPresent()) { Notices.refuse(player, creeperRefusal.get()); return; }
        if (quarry != null && quarry.lifeSequence() == targetLife && LateShelter.sheltered(round.rules(), quarry)) {
            // Not a failed search: no retry penalty, and the target is neither away nor dead.
            Notices.refuse(player, Notices.key(quarry.name()) + " " + LateShelter.NAME + "，稍后才能追猎"); return;
        }
        long now = b.searchClock.getAsLong();
        if (p.monsterCooldownNanos() > 0) { Notices.refuse(player, "出击冷却中 · " + Notices.key(seconds(p.monsterCooldownNanos())) + " 秒"); return; }
        long locked = b.session.view().map(live -> live.participants().get(p.id())).map(live -> live.huntLockNanos(targetId)).orElse(0L);
        if (locked > 0) {
            Notices.refuse(player, Notices.key(targetName(round, targetId)) + " 正提防着你 · " + Notices.key(seconds(locked)) + " 秒后才能再追猎");
            return;
        }
        Long retry = b.retryAt.get(p.id());
        if (retry != null && now - retry < 0) { Notices.refuse(player, Notices.key(seconds(retry - now)) + " 秒后才能再次追猎"); return; }
        var identity = new PersonalHuntRequest(round.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(),
                p.monsterRole(), targetId, targetLife);
        var target = player.level().getServer().getPlayerList().getPlayer(identity.target());
        var d = new Deployment(player, target, identity, now);
        if (target == null || !requestTarget(b, d)) { searchFailed(b, d, Notices.key(targetName(round, targetId)) + " 已离线或阵亡，追猎取消"); return; }
        if (waiting != null) cancelSearch(b, waiting, null);
        restartSearch(b, d);
        // The progress hint below names the target and how to cancel; no separate announcement is needed.
    }
    /** Whether the courtyard's role models, merchant and paging work right now; their name tags say so. */
    public static boolean courtyardOpen(MinecraftServer server) {
        var b = BINDINGS.get(server);
        var r = b == null ? null : b.session.snapshot().orElse(null);
        return r != null && personalOpen(b, r);
    }
    static LobbyRoleModels.CreeperBooth creeperBooth(ServerPlayer viewer) {
        var b = BINDINGS.get(viewer.level().getServer());
        var round = b == null ? null : b.session.snapshot().orElse(null);
        if (round == null || !MonsterCoins.creeperPaid(round.rules())) return LobbyRoleModels.CreeperBooth.NATIVE;
        var p = round.participants().get(viewer.getUUID());
        if (p == null || p.role().isExcluded()) return LobbyRoleModels.CreeperBooth.NATIVE;
        return switch (p.wallet().creeperTicket()) {
            case NONE -> new LobbyRoleModels.CreeperBooth(LobbyRoleModels.CreeperState.LOCKED, MonsterCoins.creeperUnlockPrice(round.rules()));
            case PLAIN -> new LobbyRoleModels.CreeperBooth(LobbyRoleModels.CreeperState.UNLOCKED,
                    MonsterCoins.creeperChargeable(round.rules()) ? MonsterCoins.creeperChargePrice(round.rules()) : 0);
            case CHARGED -> new LobbyRoleModels.CreeperBooth(LobbyRoleModels.CreeperState.CHARGED, 0);
        };
    }
    /**
     * Real lightning struck a deployed creeper player: a paid ticket becomes a charged one, as if bought at the booth
     * (docs/plan/苦力怕付费解锁.md §7.1). The session drops the event when there is no plain ticket to raise.
     */
    public static void creeperStruck(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        var round = b == null ? null : b.session.snapshot().orElse(null);
        var p = round == null ? null : round.participants().get(player.getUUID());
        if (p != null) b.session.creeperStruck(round.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence());
    }
    /**
     * Why this camp creeper may not set out after {@code target} (docs/plan/苦力怕付费解锁.md): its ticket is spent, or the
     * human is below three stars. Empty when the paid-creeper rules allow the hunt; core checks the same at commit.
     */
    private static Optional<String> creeperRefusal(RoundSnapshot round, Participant p, UUID target) {
        if (!paidCreeper(round.rules(), p)) return Optional.empty();
        if (!p.wallet().creeperTicket().unlocked())
            return Optional.of("苦力怕的出击资格已用掉 · 回苦力怕展位付 " + MonsterCoins.creeperUnlockPrice(round.rules()) + " 怪物币解锁");
        var quarry = round.participants().get(target);
        if (quarry != null && quarry.role() == ParticipantRole.HUMAN_ACTIVE
                && Bounty.stars(round.rules(), Bounty.threat(round.rules(), quarry)) < HuntPressure.CREEPER_MIN_STARS)
            return Optional.of(Replies.CREEPER_LOW_STARS);
        return Optional.empty();
    }
    /** {@link #creeperRefusal(RoundSnapshot, Participant, UUID)} for a camp monster watching {@code target}. */
    public static Optional<String> creeperRefusal(ServerPlayer hunter, UUID target) {
        var b = BINDINGS.get(hunter.level().getServer());
        var round = b == null ? null : b.session.snapshot().orElse(null);
        var p = round == null ? null : round.participants().get(hunter.getUUID());
        return p == null || p.role() != ParticipantRole.MONSTER_LOBBY ? Optional.empty() : creeperRefusal(round, p, target);
    }
    private static boolean paidCreeper(RulesConfig rules, Participant p) {
        return p.monsterRole() == MonsterRole.CREEPER && MonsterCoins.creeperPaid(rules);
    }
    private static boolean personalOpen(Binding b, RoundSnapshot r) {
        return r.rules().mode() == dev.hardrush.core.config.GameMode.HARDCORE
                && r.rules().huntRule() == RulesConfig.HuntRule.PERSONAL_TARGET
                && (r.phase() == RoundPhase.RUNNING || r.phase() == RoundPhase.RESCUE)
                && !b.session.competitionLocked();
    }
    private static boolean requestTarget(Binding b, Deployment d) {
        // The live view, so a shelter ends here exactly when the model and the engine say it does.
        var r = b.session.view().orElse(null);
        var target = d.player.level().getServer().getPlayerList().getPlayer(d.identity.target());
        return !d.cancelled && r != null && personalOpen(b, r) && d.identity.targetMatches(r)
                && target != null && target == d.targetPlayer && target.connection == d.targetConnection
                && b.profiles.isReady(target.getUUID(), r) && !dimension(b, target.level()).equals("lobby")
                && validTarget(b, target, target.level());
    }
    private static boolean requestHunter(Binding b, Deployment d, ParticipantRole role, int sortie) {
        var r = b.session.snapshot().orElse(null);
        return !d.cancelled && r != null && personalOpen(b, r) && current(d.player, b)
                && d.player.connection == d.connection && d.identity.hunterMatches(r, role, sortie);
    }
    public static void tick(MinecraftServer server) {
        var b = BINDINGS.get(server); if (b == null) return;
        synchronize(server, b);
        CampTransfers.tick(server);
        dev.hardrush.revival.TotemDrops.tick(server);
        dev.hardrush.revival.RevivalTotems.tick(server);
        CampKit.tick(server);
        MonsterVaults.tick(server);
        var round = b.session.view().orElse(null);
        updateDisplays(server, b);
        tellArrivals(server, b);
        boolean active = round != null && (round.phase() == RoundPhase.RUNNING || round.phase() == RoundPhase.RESCUE)
                && !b.session.competitionLocked();
        HuntRetracks.tick(server, active ? round : null, LobbyRuntime::retrackReady);
        if (!active) {
            for (var d : List.copyOf(b.searches.values())) searchFailed(b, d, "本轮即将结束，追猎取消");
            for (var d : b.pending.values()) d.cancelled = true;
            for (var channel : List.copyOf(b.channels.values())) interruptChannel(channel.player, "比赛状态变化");
            for (var retrack : List.copyOf(b.retracks.values())) interruptChannel(retrack.player, "比赛状态变化");
            b.resumptions.clear(); return;
        }
        // An administrator's pause stops sorties at once (docs/plan/管理员命令与面板.md 2.2); searching on would only
        // be refused at commit, up to a minute later. Not the hunter's fault: no retry wait.
        if (round.adminPaused()) for (var d : List.copyOf(b.searches.values())) cancelSearch(b, d, PAUSED);
        for (var refund : List.copyOf(b.refunds.values())) compensate(b, refund);
        for (var d : List.copyOf(b.resumptions.values())) {
            if (!current(d.player, b)) {
                if (server.getPlayerList().getPlayer(d.player.getUUID()) != d.player || b.profiles.failure(d.player.getUUID()).isPresent()) {
                    b.resumptions.remove(d.player.getUUID()); searchFailed(b, d, "情况有变，追猎取消");
                }
                continue;
            }
            b.resumptions.remove(d.player.getUUID());
            if (!requestHunter(b, d, ParticipantRole.MONSTER_LOBBY, d.sortie) || !requestTarget(b, d)) {
                searchFailed(b, d, Notices.key(d.targetPlayer.getScoreboardName()) + " 已离线或阵亡，追猎取消"); continue;
            }
            restartSearch(b, d);
        }
        for (var entry : List.copyOf(b.channels.entrySet())) {
            var ch = entry.getValue(); var p = round.participants().get(entry.getKey());
            // Both channels allow walking (docs/plan/击败锁定与怪物平衡.md §3); only acting or being hurt breaks them.
            if (current(ch.player, b) && ch.player.level() != ch.level) { interruptChannel(ch.player, "切换维度"); continue; }
            if (current(ch.player, b) && MonsterRoles.isIgniting(ch.player)) { interruptChannel(ch.player, ignitionReason(ch.player)); continue; }
            if (!current(ch.player, b) || p == null || p.role() != ParticipantRole.MONSTER_DEPLOYED
                    || p.lifeSequence() != ch.life || p.monsterDeploymentSequence() != ch.sortie) {
                interruptChannel(ch.player, "比赛状态变化"); continue;
            }
            long duration = channelNanos(round, ch.retrack);
            long elapsed = System.nanoTime() - ch.started;
            ChannelEffects.tick(ch.player, ch.retrack, elapsed, duration);
            hint(ch.player, "return", dev.hardrush.core.lobby.LobbyHints.Priority.PROGRESS,
                    (ch.retrack ? "正在循迹" : "正在返回营地") + " · 还需 " + Math.max(0, (duration - elapsed + 999_999_999L) / 1_000_000_000L) + " 秒");
            if (ch.retrack && elapsed >= duration) {
                b.channels.remove(entry.getKey());
                ChannelEffects.complete(ch.player, true);
                beginRetrackSearch(b, ch, p);
            } else if (elapsed >= duration && !b.session.mutationPending()) {
                b.channels.remove(entry.getKey());
                ChannelEffects.complete(ch.player, false);
                // Only the sortie's own bound items are parked; world items settle here when the return commits.
                var from = ch.player.position(); var level = (ServerLevel) ch.player.level();
                String parked = dev.hardrush.monster.MonsterParking.capture(ch.player);
                b.returning.add(p.id());
                b.session.requestMonsterReturn(round.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(),
                        parked, reply -> {
                            b.returning.remove(p.id());
                            if (reply.success()) {
                                ChannelEffects.departed(ch.player, level, from, false);
                                Notices.hint(ch.player, returned(round, !parked.isEmpty()));
                            } else { Replies.refuse(ch.player, reply, "没能返回营地"); }
                        });
            }
        }
        for (var r : List.copyOf(b.retracks.values())) tickRetrack(b, round, r);
        for (var d : List.copyOf(b.searches.values())) {
            if (!requestHunter(b, d, ParticipantRole.MONSTER_LOBBY, d.sortie) || !requestTarget(b, d)) {
                searchFailed(b, d, Notices.key(d.targetPlayer.getScoreboardName()) + " 已离线或阵亡，追猎取消"); continue;
            }
            // Watching the quarry from its camera keeps the hunt; the body returns to the courtyard it left.
            if (!inCamp(d.player) && !dev.hardrush.observe.Observation.observing(d.player)) { cancelSearch(b, d, "你离开了庭院，追猎取消"); continue; }
            // A full quarry keeps the rest waiting in click order; the wait counts toward the same 60 seconds.
            boolean wasQueued = d.queued > 0;
            d.queued = queuePlace(b, round, d);
            if (d.queued > 0) {
                if (b.searchClock.getAsLong() - d.started >= SafeDeployment.DEPLOY_WAIT_NANOS)
                    searchFailed(b, d, Notices.key(d.targetPlayer.getScoreboardName()) + " 一直满员，追猎取消 · 可以换个人");
                continue;
            }
            if (wasQueued) {
                // Its turn came: the queue counted toward the 60 seconds, but at least a short search remains.
                long now = b.searchClock.getAsLong();
                d.searchFrom = Math.max(d.started, now - SafeDeployment.DEPLOY_WAIT_NANOS + MIN_SEARCH_AFTER_QUEUE_NANOS);
                restartSearch(b, d);
                continue;
            }
            var target = d.targetPlayer;
            if (target.level() != d.level) { restartSearch(b, d); continue; }
            var result = d.search.tick(SafeDeployment.Quarry.of(target), humans(b, d.level));
            if (result.status() == SafeDeployment.Status.PENDING) continue;
            if (result.status() == SafeDeployment.Status.FAILED) {
                searchFailed(b, d, NO_DEPLOYMENT); continue;
            }
            if (!d.search.revalidate(target.position(), humans(b, d.level))) { restartSearch(b, d); continue; }
            if (b.session.mutationPending()) continue;
            b.searches.remove(d.player.getUUID()); b.committing.add(d.player.getUUID()); b.pending.put(d.player.getUUID(), d);
            b.session.requestPersonalHunt(d.identity, d.sortie, reply -> completeDeployment(b, d, reply));
        }
    }
    private static void completeDeployment(Binding b, Deployment d, RoundSession.Reply reply) {
        UUID id = d.identity.hunter();
        if (!reply.success() && reply.refusal() == Refusal.HUNT_FULL) {
            // Another monster took the last slot first: back into the queue, not a failed search.
            b.pending.remove(id, d); b.committing.remove(id); restartSearch(b, d); return;
        }
        if (!reply.success()) {
            b.pending.remove(id, d); b.committing.remove(id); searchFailed(b, d, Replies.text(reply, Notices.key(d.targetPlayer.getScoreboardName()) + " 情况有变，临时去掉了出击 · 可再次尝试")); return;
        }
        boolean hunter = requestHunter(b, d, ParticipantRole.MONSTER_DEPLOYED, d.sortie + 1);
        boolean target = requestTarget(b, d);
        boolean safe = hunter && target && d.targetPlayer.level() == d.level
                && d.search.revalidate(d.targetPlayer.position(), humans(b, d.level));
        if (safe) {
            try {
                var committed = b.session.snapshot().orElseThrow();
                var landing = d.search.result().position();
                boolean burrowed = d.search.result().burrowed();
                // Hollowed first so the hunter never lands inside stone for a frame. A departure that fails after this
                // leaves a sealed, empty pocket behind; like every dug block, it is deliberately never refilled.
                if (burrowed) dev.hardrush.hunt.HuntBurrows.carve(d.level, landing, d.player);
                if (b.profiles.deployMonster(d.player, committed, d.level, landing, d.search.yaw(d.targetPlayer.position()))) {
                    // Until this commits, a disconnect cancels the sortie instead of spending it.
                    b.session.monsterLanded(d.identity.round(), id, d.identity.hunterLife(), d.sortie + 1);
                    MonsterRoles.select(d.player, d.identity.kind().name().toLowerCase(Locale.ROOT));
                    if (!MonsterRoles.isMonster(d.player) || MonsterRoles.nativeModel(d.player) == null)
                        throw new IllegalStateException("已提交出场未能应用原生怪物角色");
                    boolean submerged = d.search.result().submerged();
                    boolean risen = submerged && d.identity.kind() == MonsterRole.ZOMBIE;
                    // The landing site decides the species; a parked variant resuming keeps what it was (MonsterHabitat).
                    boolean turned = MonsterRoles.settle(d.player, submerged);
                    b.pending.remove(id, d); b.committing.remove(id);
                    dev.hardrush.hunt.HuntBossBars.deployed(d.player, committed, d.identity);
                    HuntRetracks.arrived(d.player, d.targetPlayer, burrowed);
                    if (b.omens.landed(d.targetPlayer.getUUID(), d.identity.kind(), b.searchClock.getAsLong() / 1_000_000))
                        dev.hardrush.hunt.HuntOmen.play(d.targetPlayer);
                    sound(d.player, true);
                    String quarry = Notices.key(d.targetPlayer.getScoreboardName());
                    if (burrowed) { burrowed(d.player); return; }
                    String form = MonsterRoles.currentForm(d.player).label;
                    Notices.hint(d.player, turned ? (risen ? "已化身" + form + "，从水下出击" : "已化身" + form + "出击")
                            : risen ? "已从水下出击"
                            : d.search.result().fallback()
                            ? "已出击：去 " + quarry + " 的路被切断，这是能到的最近处" : "已出击，" + quarry + " 就在附近"); return;
                }
                if (b.profiles.failure(id).isPresent()) {
                    b.session.adapterFailure("追猎档案应用失败", new IllegalStateException(b.profiles.failure(id).orElseThrow()));
                    b.pending.remove(id, d); b.committing.remove(id); return;
                }
            } catch (RuntimeException fault) {
                b.pending.remove(id, d); b.committing.remove(id); b.session.adapterFailure("追猎世界或档案应用失败", fault); return;
            }
        }
        // Refund only the exact committed sortie. A replacement life/round is never touched.
        var r = b.session.snapshot().orElse(null);
        if (r == null || !d.identity.round().equals(r.roundId())) { b.pending.remove(id, d); b.committing.remove(id); return; }
        var p = r.participants().get(id);
        if (p == null || p.lifeSequence() != d.identity.hunterLife() || p.role() != ParticipantRole.MONSTER_DEPLOYED
                || p.monsterDeploymentSequence() != d.sortie + 1) { b.pending.remove(id, d); b.committing.remove(id); return; }
        b.refunds.put(id, new Refund(d, hunter && target));
        compensate(b, b.refunds.get(id));
    }
    private static void compensate(Binding b, Refund refund) {
        var d = refund.deployment; var id = d.identity.hunter();
        if (b.session.mutationPending()) return;
        var r = b.session.snapshot().orElse(null);
        var p = r == null ? null : r.participants().get(id);
        if (r == null || !d.identity.round().equals(r.roundId()) || p == null || p.lifeSequence() != d.identity.hunterLife()
                || p.role() != ParticipantRole.MONSTER_DEPLOYED || p.monsterDeploymentSequence() != d.sortie + 1) {
            b.refunds.remove(id, refund); b.pending.remove(id, d); b.committing.remove(id);
            searchFailed(b, d, "情况有变，追猎取消"); return;
        }
        b.session.requestMonsterDeployCancelled(d.identity.round(), id, d.identity.hunterLife(), d.sortie + 1, refunded -> {
            if (!refunded.success()) {
                if (b.session.state() == RoundSession.State.FAILED || b.session.competitionLocked()) {
                    Notices.refuse(d.player, "出击出错，请联系管理员");
                    Notices.operators(d.player.level().getServer(), d.player.getScoreboardName() + " 出击退回失败：" + refunded.message());
                }
                return; // Transient queued death/connection write: retain the exact refund and retry after it drains.
            }
            b.refunds.remove(id, refund); b.pending.remove(id, d); b.committing.remove(id);
            d.sortie++;
            if (refund.resume && requestTarget(b, d)) {
                b.resumptions.put(id, d);
                Notices.hint(d.player, "出击位置失效，正在重新寻找");
            } else searchFailed(b, d, Notices.key(d.targetPlayer.getScoreboardName()) + " 情况有变，临时去掉了出击 · 可再次尝试");
        });
    }
    /**
     * 0 while this search holds one of its quarry's hunt slots (docs/plan/悬赏分档与追猎强度.md §3); otherwise its
     * 1-based place in the quarry's queue. Monsters out on the quarry and deployments being committed take slots
     * first, then searches in the order they were clicked.
     */
    private static int queuePlace(Binding b, RoundSnapshot view, Deployment d) {
        UUID quarry = d.identity.target();
        int cap = HuntPressure.concurrentHunters(Bounty.standing(view.rules(), view.participants(), quarry).stars());
        int taken = RoundEngine.huntersOn(view, quarry) + (int) b.pending.values().stream()
                .filter(other -> other != d && other.identity.target().equals(quarry)
                        && view.participants().get(other.identity.hunter()) != null
                        && view.participants().get(other.identity.hunter()).role() != ParticipantRole.MONSTER_DEPLOYED).count();
        var line = b.searches.values().stream().filter(other -> other.identity.target().equals(quarry))
                .sorted(Comparator.comparingLong((Deployment other) -> other.started).thenComparing(other -> other.identity.hunter())).toList();
        int free = Math.max(0, cap - taken), index = line.indexOf(d);
        return index < 0 || index < free ? 0 : index - free + 1;
    }
    /** A deliberate cancel or target switch: no failure sound and no retry wait. */
    private static void cancelSearch(Binding b, Deployment d, String message) {
        b.searches.remove(d.identity.hunter(), d); b.resumptions.remove(d.identity.hunter(), d);
        if (message != null && d.player.level().getServer().getPlayerList().getPlayer(d.player.getUUID()) == d.player) Notices.hint(d.player, message);
    }
    private static boolean inCamp(ServerPlayer player) {
        var camp = dev.hardrush.world.LobbyWorld.open(player.level().getServer());
        if (!camp.ready() || player.level() != camp.holdingLevel()) return false;
        return camp.plan().inZone(LobbyFunction.COURTYARD, player.getX(), player.getY(), player.getZ(), false);
    }
    private static void searchFailed(Binding b, Deployment d, String message) {
        b.searches.remove(d.identity.hunter(), d); b.resumptions.remove(d.identity.hunter(), d);
        b.retryAt.put(d.identity.hunter(), b.searchClock.getAsLong() + 3_000_000_000L);
        if (d.player.level().getServer().getPlayerList().getPlayer(d.player.getUUID()) == d.player) {
            Notices.hint(d.player, message); sound(d.player, false);
        }
    }
    /**
     * Same target life only; dimension changes and compensation share one 60-second wait, counted from the click, or
     * from a little later when a queue left too little of it ({@code searchFrom}).
     */
    private static void restartSearch(Binding b, Deployment d) {
        b.searches.remove(d.identity.hunter(), d);
        d.search = null;
        if (!requestHunter(b, d, ParticipantRole.MONSTER_LOBBY, d.sortie) || !requestTarget(b, d)) {
            searchFailed(b, d, Notices.key(d.targetPlayer.getScoreboardName()) + " 已离线或阵亡，追猎取消"); return;
        }
        if (b.searchClock.getAsLong() - d.searchFrom >= SafeDeployment.DEPLOY_WAIT_NANOS) {
            searchFailed(b, d, NO_DEPLOYMENT); return;
        }
        d.level = d.targetPlayer.level();
        d.search = new SafeDeployment.Search(d.level, d.player, d.identity.kind(), SafeDeployment.Quarry.of(d.targetPlayer),
                humans(b, d.level), b.searchRandom, b.searchClock, d.searchFrom, SafeDeployment.DEPLOY_WAIT_NANOS);
        b.searches.put(d.identity.hunter(), d);
    }
    private static final String CELEBRATION_RETURN = "庆祝期间不能返回营地";
    /** A queued hunter whose turn comes late still gets this long to find a landing. */
    private static final long MIN_SEARCH_AFTER_QUEUE_NANOS = 15_000_000_000L;
    private static final String NO_DEPLOYMENT = "暂时没找到合适的出击位置 · " + Notices.key(3) + " 秒后可重试";

    public static boolean useReturn(ServerPlayer player) {
        if (dev.hardrush.celebration.CelebrationRuntime.arena(player)) { Notices.hint(player, CELEBRATION_RETURN); return false; }
        var b = BINDINGS.get(player.level().getServer());
        var r = b == null ? null : b.session.view().orElse(null);
        var p = r == null ? null : r.participants().get(player.getUUID());
        if (p == null || !current(player, b) || !player.isAlive() || PlayerProfiles.isLocked(player)
                || b.session.competitionLocked() || r.phase() != RoundPhase.RUNNING && r.phase() != RoundPhase.RESCUE) return false;
        beginReturn(player, b, p);
        return channeling(player);
    }
    private static void beginReturn(ServerPlayer player, Binding b, Participant p) {
        if (p.role() != ParticipantRole.MONSTER_DEPLOYED || MonsterRoles.isIgniting(player) || MonsterRoles.performingSkill(player)) {
            Notices.refuse(player, "现在不能返回营地"); return;
        }
        // Holding the item, or right-clicking again, neither restarts nor repeats a running or finished channel.
        var running = b.channels.get(p.id());
        if (running != null && !running.retrack() || b.returning.contains(p.id())) return;
        // A retrack's channel or its landing search gives way to the shard: the compass is not rested.
        supersede(player, b);
        var round = b.session.view().orElseThrow();
        b.channels.put(p.id(), new Channel(player, (ServerLevel) player.level(), System.nanoTime(),
                p.lifeSequence(), p.monsterDeploymentSequence(), false));
        // The progress hint shows the countdown; the echo shard's held hint and description already state the rule.
        ChannelEffects.start(player, false, channelNanos(round, false));
    }
    private static String returned(RoundSnapshot round, boolean parked) {
        long wait = seconds(RoundEngine.returnCooldownNanos(round.rules()));
        return (parked ? "已返回营地，本次出击已暂存" : "已返回营地") + (wait == 0 ? "" : " · " + Notices.key(wait) + " 秒后可再出击");
    }
    private static String targetName(RoundSnapshot round, UUID target) {
        var p = round.participants().get(target);
        return p == null ? "目标" : p.name();
    }
    /** The bound quarry's name, highlighted; a neutral word when the compass has lost it entirely. */
    private static String quarryName(ServerPlayer hunter) {
        return HuntRetracks.quarry(hunter).map(target -> Notices.key(target.getScoreboardName())).orElse("目标");
    }
    private static long channelNanos(RoundSnapshot round, boolean retrack) {
        return (retrack ? RoundEngine.retrackChannelMillis(round.rules()) : RoundEngine.returnChannelMillis(round.rules())) * 1_000_000L;
    }
    /** A creeper's own press on its igniter is a skill; a human's igniter forced the fuse. */
    private static String ignitionReason(ServerPlayer player) {
        return dev.hardrush.monster.MonsterCreeper.forced(player) ? "被点燃" : "施放技能";
    }
    private static final String CELEBRATION_RETRACK = "庆祝期间不能循迹";

    /** Right-click on the bound tracking compass: a channel, walking allowed, then the quarry's trail again. */
    public static boolean useRetrack(ServerPlayer player, net.minecraft.world.item.ItemStack compass) {
        if (dev.hardrush.celebration.CelebrationRuntime.arena(player)) { Notices.hint(player, CELEBRATION_RETRACK); return false; }
        var b = BINDINGS.get(player.level().getServer());
        var r = b == null ? null : b.session.view().orElse(null);
        var p = r == null ? null : r.participants().get(player.getUUID());
        if (p == null || !current(player, b) || !player.isAlive() || PlayerProfiles.isLocked(player)
                || b.session.competitionLocked() || r.phase() != RoundPhase.RUNNING && r.phase() != RoundPhase.RESCUE) return false;
        if (p.role() != ParticipantRole.MONSTER_DEPLOYED || MonsterRoles.isIgniting(player) || MonsterRoles.performingSkill(player)) {
            Notices.refuse(player, "现在不能循迹"); return false;
        }
        if (retracking(b, p.id())) return true;
        if (b.returning.contains(p.id())) return false;
        // The native cooldown overlay on the compass already says it is not ready.
        if (player.getCooldowns().isOnCooldown(compass)) return false;
        if (retrackTarget(b, player).isEmpty()) {
            Notices.refuse(player, "罗盘失去了指向：" + quarryName(player) + " 已离线、阵亡或不在挑战世界"); return false;
        }
        // Only a compass that would really set out takes over a running return; a dark one leaves it be.
        supersede(player, b);
        b.channels.put(p.id(), new Channel(player, (ServerLevel) player.level(), System.nanoTime(),
                p.lifeSequence(), p.monsterDeploymentSequence(), true));
        ChannelEffects.start(player, true, channelNanos(r, true));
        return true;
    }
    /** The quarry a retrack may set out for: alive, online, unprotected and in the challenge world, any dimension. */
    private static Optional<ServerPlayer> retrackTarget(Binding b, ServerPlayer hunter) {
        return HuntRetracks.quarry(hunter).filter(target -> validTarget(b, target, (ServerLevel) target.level())
                && !dimension(b, (ServerLevel) target.level()).equals("lobby"));
    }
    /** Whether a press on the compass would start a retrack now, apart from its rest: what its glint shows. */
    public static boolean retrackReady(ServerPlayer hunter) {
        var b = BINDINGS.get(hunter.level().getServer());
        return b != null && !retracking(b, hunter.getUUID()) && retrackTarget(b, hunter).isPresent();
    }
    /** The compass's own channel, or the landing search after it, is under way. */
    private static boolean retracking(Binding b, UUID player) {
        var channel = b.channels.get(player);
        return b.retracks.containsKey(player) || channel != null && channel.retrack();
    }
    /**
     * The other control takes over: the running channel, or a retrack's search, fizzles out as it would when broken,
     * but this is the monster's own choice, so no failure note sounds and the notice names the switch.
     */
    private static void supersede(ServerPlayer player, Binding b) {
        var channel = b.channels.remove(player.getUUID());
        var retrack = b.retracks.remove(player.getUUID());
        if (channel == null && retrack == null) return;
        boolean tracking = channel == null || channel.retrack();
        ChannelEffects.interrupted(player, tracking);
        Notices.hint(player, tracking ? "已取消循迹，改为返回营地" : "已取消返回营地，改为循迹");
    }
    private static void beginRetrackSearch(Binding b, Channel ch, dev.hardrush.core.round.Participant p) {
        var target = HuntRetracks.quarry(ch.player).orElse(null);
        var life = HuntRetracks.quarryLife(ch.player);
        if (target == null || life.isEmpty() || !validTarget(b, target, (ServerLevel) target.level())) {
            Notices.hint(ch.player, quarryName(ch.player) + " 已离线或阵亡，循迹取消，罗盘不进入冷却"); sound(ch.player, false); return;
        }
        var r = new Retrack(ch.player, target, p.lifeSequence(), p.monsterDeploymentSequence(), life.getAsInt(), b.searchClock.getAsLong());
        retrackSearch(b, r);
        b.retracks.put(p.id(), r);
        hint(ch.player, "return", dev.hardrush.core.lobby.LobbyHints.Priority.PROGRESS, "正在寻找 " + Notices.key(target.getScoreboardName()) + " 附近的落点");
    }
    private static void retrackSearch(Binding b, Retrack r) {
        var level = (ServerLevel) r.targetPlayer.level();
        var kind = b.session.snapshot().map(s -> s.participants().get(r.player.getUUID()))
                .map(dev.hardrush.core.round.Participant::monsterRole).orElse(MonsterRole.ZOMBIE);
        r.search = new SafeDeployment.Search(level, r.player, kind, SafeDeployment.Quarry.of(r.targetPlayer), humans(b, level),
                b.searchRandom, b.searchClock, r.started, SafeDeployment.RETRACK_WAIT_NANOS);
    }
    private static void tickRetrack(Binding b, RoundSnapshot round, Retrack r) {
        var p = round.participants().get(r.player.getUUID());
        var target = r.targetPlayer;
        var targetIdentity = round.participants().get(target.getUUID());
        if (current(r.player, b) && r.player.level() != r.origLevel) { interruptChannel(r.player, "切换维度"); return; }
        if (current(r.player, b) && MonsterRoles.isIgniting(r.player)) { interruptChannel(r.player, ignitionReason(r.player)); return; }
        if (!current(r.player, b) || p == null || p.role() != ParticipantRole.MONSTER_DEPLOYED || !r.player.isAlive()
                || p.lifeSequence() != r.life || p.monsterDeploymentSequence() != r.sortie) { interruptChannel(r.player, "比赛状态变化"); return; }
        if (target.level().getServer().getPlayerList().getPlayer(target.getUUID()) != target || targetIdentity == null
                || targetIdentity.lifeSequence() != r.targetLife || !validTarget(b, target, (ServerLevel) target.level())
                || dimension(b, (ServerLevel) target.level()).equals("lobby")) {
            b.retracks.remove(r.player.getUUID());
            Notices.hint(r.player, Notices.key(target.getScoreboardName()) + " 已离线或阵亡，循迹取消，罗盘不进入冷却"); sound(r.player, false); return;
        }
        if (r.search == null || target.level() != r.search.level()) retrackSearch(b, r);
        var level = r.search.level();
        var result = r.search.tick(SafeDeployment.Quarry.of(target), humans(b, level));
        if (result.status() == SafeDeployment.Status.PENDING) return;
        b.retracks.remove(r.player.getUUID());
        LobbyInteractions.get(r.player.level().getServer()).ifPresent(router -> router.hints(r.player).clear("return"));
        if (result.status() == SafeDeployment.Status.FAILED || !r.search.revalidate(target.position(), humans(b, level))) {
            Notices.hint(r.player, SafeDeployment.atSea(target) ? Notices.key(target.getScoreboardName()) + " 在海上，没有落脚点，罗盘不进入冷却"
                    : "暂没找到能循迹过去的位置"); sound(r.player, false); return;
        }
        var landing = result.position();
        var from = r.player.position(); var fromLevel = (ServerLevel) r.player.level();
        if (result.burrowed()) dev.hardrush.hunt.HuntBurrows.carve(level, landing, r.player);
        if (!r.player.teleportTo(level, landing.x, landing.y, landing.z, java.util.Set.of(), r.search.yaw(target.position()), 0, true)) {
            Notices.hint(r.player, "落点失效，循迹取消"); sound(r.player, false); return;
        }
        boolean turned = MonsterRoles.settle(r.player, result.submerged());
        HuntRetracks.retracked(r.player, target, result.burrowed());
        // Followers stay where they are while it hides: there is no room around a burrow but the quarry's tunnel.
        if (!result.burrowed()) MonsterCompanions.regroup(r.player);
        ChannelEffects.departed(r.player, fromLevel, from, true);
        sound(r.player, true);
        String cut = "去 " + Notices.key(target.getScoreboardName()) + " 的路被切断，这是能到的最近处";
        if (result.burrowed()) burrowed(r.player);
        else if (turned) Notices.hint(r.player, "已化身" + MonsterRoles.currentForm(r.player).label + (result.fallback() ? " · " + cut : ""));
        else if (result.fallback()) Notices.hint(r.player, cut);
    }
    /** Landed hidden in a burrow: how to get out, which only the hands can do. */
    private static void burrowed(ServerPlayer hunter) {
        dev.hardrush.hunt.HuntBurrows.entered(hunter, hunter.position());
        Notices.hint(hunter, MonsterRoles.currentForm(hunter) == dev.hardrush.monster.MonsterForm.CREEPER
                ? "你藏进了石头里 · 苦力怕挖不动，等人挖到你再引爆"
                : "你藏进了石头里 · 挖开前面的墙，破石而出");
    }
    /**
     * Breaks a return or retrack channel, or the retrack's search that follows it.
     * @param reason one of the plain words players see: 切换维度、受到伤害、攻击、互动、使用物品、施放技能、被点燃、敲门、挖掘、比赛状态变化
     */
    public static void interruptChannel(ServerPlayer player, String reason) {
        var b = BINDINGS.get(player.level().getServer());
        if (b == null) return;
        var channel = b.channels.remove(player.getUUID());
        var retrack = b.retracks.remove(player.getUUID());
        if (channel == null && retrack == null) return;
        boolean tracking = channel == null || channel.retrack();
        String what = tracking ? "循迹" : "返回营地";
        ChannelEffects.interrupted(player, tracking);
        LobbyInteractions.get(player.level().getServer()).ifPresent(router -> router.hints(player).clear("return"));
        Notices.hint(player, what + "被打断（" + Notices.key(reason) + "）");
        sound(player, false);
    }
    public static boolean channeling(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        return b != null && (b.channels.containsKey(player.getUUID()) || b.retracks.containsKey(player.getUUID()));
    }
    public static boolean targetAllowed(UUID owner, ServerLevel level, net.minecraft.world.entity.Entity target) {
        var b = BINDINGS.get(level.getServer());
        if (b == null) return true;
        var round = b.session.snapshot().orElse(null);
        var origin = round == null ? null : round.participants().get(owner);
        if (origin == null) return true; // Operator-only development fixtures retain their independent scope.
        if (dev.hardrush.celebration.CelebrationRuntime.arena(level)) {
            // Celebration is native survival: anyone standing in the End may be fought, never recorded.
            return !owner.equals(target.getUUID()) && target instanceof ServerPlayer player && player.level() == level
                    && player.isAlive() && !player.isSpectator() && !PlayerProfiles.isLocked(player);
        }
        return round.phase() == RoundPhase.RUNNING && !b.session.competitionLocked()
                && origin.lifeSequence() > 0 && !owner.equals(target.getUUID())
                && target instanceof ServerPlayer player && !dimension(b, level).equals("lobby")
                && player.level() == level && player.isAlive() && !PlayerProfiles.isLocked(player)
                && !ProtectionRuntime.protectedEntity(player)
                && Optional.ofNullable(round.participants().get(player.getUUID()))
                    .filter(p -> p.online() && (p.role() == ParticipantRole.HUMAN_ACTIVE || p.role() == ParticipantRole.MONSTER_DEPLOYED)).isPresent();
    }
    public static void onCommitted(MinecraftServer server, RoundSnapshot before, RoundSnapshot after) {
        var b = BINDINGS.get(server); if (b == null) return;
        if (!before.roundId().equals(after.roundId())) {
            for (var player : server.getPlayerList().getPlayers()) if (MonsterRoles.isMonster(player)) MonsterRoles.clear(player);
            b.pending.values().forEach(d -> d.cancelled = true);
            b.searches.clear(); b.resumptions.clear(); b.pending.clear(); b.refunds.clear(); b.channels.clear(); b.returning.clear(); b.retracks.clear(); b.committing.clear(); b.retryAt.clear(); b.displays.clear(); return;
        }
        if (dev.hardrush.celebration.CelebrationRuntime.successful(after)) {
            b.searches.clear(); b.resumptions.clear(); b.channels.clear(); b.retracks.clear(); return;
        }
        if (before.phase() != after.phase() && after.phase() != RoundPhase.RUNNING && after.phase() != RoundPhase.RESCUE)
            for (var ch : List.copyOf(b.channels.values())) interruptChannel(ch.player, "比赛状态变化");
        if (before.phase() != after.phase() && after.phase() != RoundPhase.RUNNING && after.phase() != RoundPhase.RESCUE)
            for (var r : List.copyOf(b.retracks.values())) interruptChannel(r.player, "比赛状态变化");
        for (var p : after.participants().values()) {
            var old = before.participants().get(p.id());
            if (old != null && old.role().isMonster() && p.role() == ParticipantRole.HUMAN_ACTIVE) {
                b.reviving.add(p.id());
                RevivalAltar.revived(server, p.id(), after.roundId(), p.lifeSequence());
                var revived = server.getPlayerList().getPlayer(p.id());
                if (revived != null && MonsterRoles.isMonster(revived)) MonsterRoles.clear(revived);
                b.channels.remove(p.id()); b.retracks.remove(p.id()); b.searches.remove(p.id()); b.resumptions.remove(p.id());
            }
            if (old != null && p.wallet().coins() > old.wallet().coins()) {
                var owner = server.getPlayerList().getPlayer(p.id());
                if (owner != null) coinFeedback(owner, b.session.committedEventType(), old, p);
            }
            // A human's pickup tells itself (TotemDrops); a human's other gains are used in the world, a monster's at the
            // altar, a monster's pickup included: it is paid like a kill.
            boolean humanPickup = p.role() == ParticipantRole.HUMAN_ACTIVE && "TOTEM_PICKUP".equals(b.session.committedEventType());
            if (old != null && p.revivalCharges() > old.revivalCharges() && !humanPickup) {
                var owner = server.getPlayerList().getPlayer(p.id());
                if (owner != null) {
                    boolean recalled = old.role() == ParticipantRole.MONSTER_DEPLOYED && p.role() == ParticipantRole.MONSTER_LOBBY
                            && dev.hardrush.core.round.MonsterCoins.enabled(after.rules());
                    String gained = "获得 " + Notices.key(p.revivalCharges() - old.revivalCharges()) + " 个复生图腾 · ";
                    Notices.hint(owner, p.role() == ParticipantRole.HUMAN_ACTIVE ? gained + "拿在手上阵亡时原地复生"
                            : recalled ? "获得复生图腾，可在营地祭坛复生" : gained + "可在营地祭坛复生");
                    sound(owner, true);
                }
            }
            // Both deaths are told after the respawn lands in camp; a death screen would hide them.
            if (old != null && old.role() == ParticipantRole.HUMAN_ACTIVE && p.role() == ParticipantRole.MONSTER_LOBBY)
                b.arrivals.put(p.id(), new Arrival(true, List.of(), false));
            // Deaths commit as a batch; the single monster death event only remains for older callers.
            var event = b.session.committedEventType();
            if (old != null && old.role() == ParticipantRole.MONSTER_DEPLOYED && p.role() == ParticipantRole.MONSTER_LOBBY
                    && ("DEATH_BATCH".equals(event) || "MONSTER_DEATH".equals(event)))
                b.arrivals.put(p.id(), new Arrival(false, defeated(server, after, old, p), paidCreeper(after.rules(), old)));
            if (p.role() != ParticipantRole.MONSTER_LOBBY || old == null || old.role() != ParticipantRole.MONSTER_DEPLOYED) continue;
            var player = server.getPlayerList().getPlayer(p.id());
            if (player == null || !b.profiles.manages(player)) continue;
            b.channels.remove(p.id()); b.retracks.remove(p.id());
            MonsterRoles.clear(player);
            b.profiles.returnMonsterToLobby(player, after);
            // A kill that earned a revival in a coin round is summoned to the altar's steps, facing the beam.
            // A dead body was replaced by its native respawn, so look the player up again.
            var summoned = server.getPlayerList().getPlayer(p.id());
            if (dev.hardrush.core.round.MonsterCoins.enabled(after.rules()) && p.revivalCharges() > old.revivalCharges()
                    && summoned != null && dev.hardrush.world.LobbyWorld.isLobby(summoned.level())
                    && dev.hardrush.world.LobbyWorld.open(server).safeLanding(Landing.ALTAR))
                b.profiles.placeInLobby(summoned, Landing.ALTAR);
        }
    }
    /** A camp monster holding a revival is pointed to the altar instead of hunting, choosing or shopping. */
    public static boolean revivalFirst(ServerPlayer player, RoundSnapshot round, dev.hardrush.core.round.Participant p) {
        if (!dev.hardrush.core.round.MonsterCoins.enabled(round.rules()) || !p.revivalEntitled()) return false;
        Notices.refuse(player, Replies.REVIVAL_FIRST);
        sound(player, false);
        return true;
    }
    /** Only the earner hears the native experience chime; the reason follows the committed event. */
    private static void coinFeedback(ServerPlayer player, String event, dev.hardrush.core.round.Participant before,
                                     dev.hardrush.core.round.Participant after) {
        int gained = after.wallet().coins() - before.wallet().coins();
        // A hit pays at once, like a hit marker: its reason is obvious. Only the other payouts name theirs, and
        // a dead human's payout is not called a monster's ("阵亡不等于变成怪物").
        String reason = switch (event) {
            case "MONSTER_DAMAGE_COINS" -> null;
            case "DEATH_BATCH" -> before.role().isActiveHuman() ? "阵亡补给" : "阵亡奖励";
            case "MONSTER_DEPLOY", "MONSTER_SELECTION" -> "出击退回";
            case "ADMIN_COINS" -> "管理员发放";
            default -> "奖励";
        };
        Notices.hint(player, Notices.key("+" + gained) + " " + Notices.COINS + (reason == null ? "" : "（" + reason + "）")
                + " · 共 " + Notices.key(after.wallet().coins()));
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP),
                net.minecraft.sounds.SoundSource.PLAYERS, player.getX(), player.getY(), player.getZ(), 0.3f, 1.2f,
                player.getRandom().nextLong()));
    }
    /** The courtyard seats in display order; observers cycle through players in the same order. */
    public static List<UUID> courtyardOrder(MinecraftServer server) {
        var b = BINDINGS.get(server);
        return b == null ? List.of() : b.slots.stream().map(GameProfile::id).toList();
    }
    /** A sortie being committed or compensated; a camp body must not leave for a camera meanwhile. */
    public static boolean submitting(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        UUID id = player.getUUID();
        return b != null && (b.committing.contains(id) || b.pending.containsKey(id) || b.resumptions.containsKey(id));
    }
    /** Whom this camp monster is currently waiting to hunt, if anyone. */
    public static Optional<UUID> huntTarget(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        if (b == null) return Optional.empty();
        var d = b.searches.get(player.getUUID());
        if (d == null) d = b.resumptions.get(player.getUUID());
        if (d == null) d = b.pending.get(player.getUUID());
        return d == null ? Optional.empty() : Optional.of(d.identity.target());
    }
    public static void disconnected(ServerPlayer player) {
        LobbyInteractions.disconnected(player);
        MonsterVaults.disconnected(player);
        CreeperUnlockDialog.disconnected(player);
        var b = BINDINGS.get(player.level().getServer()); if (b == null) return;
        b.channels.remove(player.getUUID()); b.retracks.remove(player.getUUID()); b.displays.remove(player.getUUID());
        var attempts = new HashSet<Deployment>(b.searches.values()); attempts.addAll(b.resumptions.values()); attempts.addAll(b.pending.values());
        for (var d : attempts) if (d.identity.hunter().equals(player.getUUID()) || d.identity.target().equals(player.getUUID())) {
            d.cancelled = true;
            // A leaving hunter reads nothing; a hunter whose target left hears the usual cancellation.
            if (!b.pending.containsValue(d)) searchFailed(b, d, Notices.key(d.targetPlayer.getScoreboardName()) + " 已离线或阵亡，追猎取消");
        }
        // Do not rebind a prototype body during the next native JOIN before the durable lobby gate.
        if (MonsterRoles.isMonster(player)) MonsterRoles.clear(player);
    }
    private static void synchronize(MinecraftServer server, Binding b) {
        var round = b.session.snapshot().orElse(null); if (round == null) return;
        if (!round.roundId().equals(b.round)) {
            b.round = round.roundId(); b.searchRandom = new Random(round.seed() ^ 0x4841524452555348L);
            b.slots.clear(); b.deadAt.clear(); b.displays.clear(); b.cooling.clear(); b.locked.clear(); b.abandoning.clear(); b.reviving.clear();
        }
    }
    /**
     * The humans a monster's defeat just locked (docs/plan/击败锁定与怪物平衡.md §1), each told at once that it will
     * leave them alone for a while; their names are returned for the monster's own arrival line.
     */
    private static List<String> defeated(MinecraftServer server, RoundSnapshot after, Participant before, Participant monster) {
        var names = new ArrayList<String>();
        monster.huntLocks().forEach((human, remaining) -> {
            if (remaining <= before.huntLockNanos(human)) return;
            var quarry = after.participants().get(human);
            if (quarry == null) return;
            names.add(quarry.name());
            var body = server.getPlayerList().getPlayer(human);
            if (body != null) Notices.hint(body, Notices.key(monster.name()) + " 倒下了 · 一段时间内它不会再来找你");
        });
        return names;
    }
    /** A death is told once the respawned body stands in camp, so the death screen cannot swallow it. */
    private static void tellArrivals(MinecraftServer server, Binding b) {
        b.arrivals.entrySet().removeIf(entry -> {
            var player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) return true;
            var arrival = entry.getValue();
            // TODO: [Refactor] A verified camp body is never admitted to the round, so isLocked alone holds competition arrivals
            // forever. Only the paid creeper's line, which says the ticket is spent, uses the camp-body check for now: widening it
            // to every arrival made the lobby runtime GameTest's parked-sword step flaky, which needs its own investigation.
            boolean ready = arrival.paidCreeper() ? PlayerProfiles.verifiedLobbyBody(player) : !PlayerProfiles.isLocked(player);
            if (!player.isAlive() || !dev.hardrush.world.LobbyWorld.isLobby(player.level()) || !ready
                    || player.connection == null || !player.connection.hasClientLoaded()) return false;
            if (arrival.human()) Notices.title(player, "你阵亡了", "已回到营地 · 可以在展位化身怪物出击");
            else if (arrival.wary().isEmpty()) Notices.hint(player, "你在出击中倒下，回到了庭院"
                    + (arrival.paidCreeper() ? " · 苦力怕需重新解锁" : " · 可以马上再出击"));
            else Notices.hint(player, "你在出击中倒下 · " + String.join("、", arrival.wary().stream().map(Notices::key).toList())
                    + " 暂时会提防你" + (arrival.paidCreeper() ? " · 苦力怕需重新解锁" : "，可以先去追猎别人"));
            return true;
        });
    }
    /** Which router context gates a clicked lobby function. */
    public static LobbyContext context(LobbyFunction function) {
        return switch (function) {
            case RULES -> LobbyContext.RULES;
            case HISTORY -> LobbyContext.HISTORY;
            case VAULT, OMINOUS_VAULT -> LobbyContext.DISPLAY;
            // The courtyard facilities explain their own refusal when used, so their click always gets through.
            case PAGE_PREV, PAGE_NEXT, ROLE_ZOMBIE, ROLE_SKELETON, ROLE_CREEPER, MERCHANT -> LobbyContext.RULES;
            default -> throw new IllegalArgumentException("Not a clickable lobby function: " + function.id());
        };
    }
    /**
     * The single dispatch from a lobby function to its behaviour, whatever bound it: a block, a role model or
     * a tagged entity. The router has already validated the click; {@code clicked} is the block for
     * block triggers and null otherwise.
     */
    public static void activate(ServerPlayer player, LobbyFunction function, net.minecraft.core.BlockPos clicked) {
        activate(player, function, clicked, false);
    }
    /** {@code priced}: the clicked facility already shows its own price, as the courtyard's creeper statue does. */
    public static void activate(ServerPlayer player, LobbyFunction function, net.minecraft.core.BlockPos clicked, boolean priced) {
        switch (function) {
            case RULES -> {
                var b = BINDINGS.get(player.level().getServer());
                if (b != null) b.session.snapshot().ifPresent(r -> RuleBook.open(player, r.rules()));
            }
            case HISTORY -> dev.hardrush.record.HistoryLibrary.open(player);
            case VAULT, OMINOUS_VAULT -> MonsterVaults.use(player, function == LobbyFunction.OMINOUS_VAULT
                    ? ShopOffer.OMINOUS_KEY : ShopOffer.TRIAL_KEY, java.util.Objects.requireNonNull(clicked, "vault position"));
            case PAGE_PREV, PAGE_NEXT, ROLE_ZOMBIE, ROLE_SKELETON, ROLE_CREEPER, MERCHANT -> courtyard(player, function, priced ? Entry.PRICED : Entry.UNPRICED, null);
            default -> throw new IllegalArgumentException("Not a clickable lobby function: " + function.id());
        }
    }
    /** Role models, the merchant and paging share the courtyard's opening hours. */
    /** How a courtyard click reached the role booth: whether its price was on show, or a dialog already confirmed it. */
    private enum Entry { UNPRICED, PRICED, CONFIRMED }
    /**
     * The creeper dialog's pay button, for the offer it was opened with: the same courtyard click, confirmed, as long as the
     * round, price and booth are still those the player was shown. Every other check runs again.
     */
    static void confirmCreeperUnlock(ServerPlayer player, CreeperUnlockDialog.Offer offer) {
        courtyard(player, LobbyFunction.ROLE_CREEPER, Entry.CONFIRMED, offer);
    }
    private static void courtyard(ServerPlayer player, LobbyFunction function, Entry entry, CreeperUnlockDialog.Offer offer) {
        var b = BINDINGS.get(player.level().getServer());
        var r = b == null ? null : b.session.snapshot().orElse(null);
        if (r == null || !contextAvailable(player, b, LobbyContext.DISPLAY) || !personalOpen(b, r)) {
            Notices.refuse(player, "庭院只在极限挑战或救场期间开放"); return;
        }
        if (function == LobbyFunction.MERCHANT) { MonsterShop.open(player); return; }
        if (function == LobbyFunction.PAGE_PREV || function == LobbyFunction.PAGE_NEXT) {
            var projections = LobbyInteractions.get(player.level().getServer()).orElseThrow().projections();
            projections.turn(player, function == LobbyFunction.PAGE_NEXT ? 1 : -1);
            return;
        }
        var p = r.participants().get(player.getUUID());
        MonsterRole role = switch (function) {
            case ROLE_ZOMBIE -> MonsterRole.ZOMBIE; case ROLE_SKELETON -> MonsterRole.SKELETON; case ROLE_CREEPER -> MonsterRole.CREEPER;
            default -> null;
        };
        if (p == null || p.role() != ParticipantRole.MONSTER_LOBBY) { Notices.refuse(player, "阵亡后才能在这里选择怪物角色"); return; }
        if (b.committing.contains(p.id()) || b.resumptions.containsKey(p.id())) {
            Notices.refuse(player, "正在出击，稍等片刻"); return;
        }
        if (role != null && revivalFirst(player, r, p)) return;
        if (role == null) return;
        boolean unlocking = role == MonsterRole.CREEPER && MonsterCoins.creeperPaid(r.rules()) && !p.wallet().creeperTicket().unlocked();
        // A second payment at the booth calls lightning down on a creeper already let out (§7); any other role's
        // click on it only picks the creeper again.
        boolean charging = role == MonsterRole.CREEPER && !unlocking && p.monsterRole() == MonsterRole.CREEPER
                && MonsterCoins.creeperChargeable(r.rules()) && p.wallet().creeperTicket() == dev.hardrush.core.round.CreeperTicket.PLAIN;
        int price = unlocking ? MonsterCoins.creeperUnlockPrice(r.rules()) : charging ? MonsterCoins.creeperChargePrice(r.rules()) : 0;
        if (charging) {
            if (p.wallet().coins() < price) {
                Notices.refuse(player, chargePrice(price, p));
                LobbyInteractions.get(player.level().getServer()).orElseThrow().roleModels().hiss(player);
                return;
            }
            if (entry == Entry.UNPRICED) {
                CreeperUnlockDialog.show(player, new CreeperUnlockDialog.Offer(UUID.randomUUID(), r.roundId(), price,
                        player.position(), player.level().getServer().getTickCount(), true), p.wallet().coins(), Optional.empty());
                return;
            }
            if (entry == Entry.CONFIRMED && (offer == null || !offer.charge() || !offer.round().equals(r.roundId()) || offer.price() != price
                    || player.position().distanceToSqr(offer.at()) > CreeperUnlockDialog.REACH * CreeperUnlockDialog.REACH)) {
                Notices.refuse(player, "价格或位置已变化，请重新右键苦力怕展位"); return;
            }
            b.committing.add(p.id());
            b.session.requestCreeperCharge(r.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(), price, reply -> {
                b.committing.remove(p.id());
                if (!current(player, b)) return;
                var after = b.session.snapshot().filter(round -> round.roundId().equals(r.roundId()))
                        .map(round -> round.participants().get(p.id())).orElse(p);
                if (reply.success()) Notices.hint(player, "苦力怕已带电 · 爆炸威力翻倍");
                else Replies.refuse(player, reply, "现在不能引雷", chargePrice(price, after));
            });
            return;
        }
        if (unlocking) {
            if (p.wallet().coins() < price) {
                Notices.refuse(player, creeperPrice(price, p));
                LobbyInteractions.get(player.level().getServer()).orElseThrow().roleModels().hiss(player);
                return;
            }
            boolean hasQuarry = r.participants().values().stream().anyMatch(q -> q.online() && q.role() == ParticipantRole.HUMAN_ACTIVE
                    && Bounty.stars(r.rules(), Bounty.threat(r.rules(), q)) >= HuntPressure.CREEPER_MIN_STARS);
            if (!hasQuarry) { Notices.refuse(player, Replies.CREEPER_NO_QUARRY); return; }
            // A block or tagged-entity booth shows no price of its own: a native dialog names it, and what a different
            // role would discard, and its button pays.
            if (entry == Entry.UNPRICED) {
                var discarded = p.wallet().hasParkedBody() && role != p.monsterRole()
                        ? Optional.of(dev.hardrush.monster.MonsterParking.describe(p.wallet().parked()).orElse(kindName(p.monsterRole())))
                        : Optional.<String>empty();
                CreeperUnlockDialog.show(player, new CreeperUnlockDialog.Offer(UUID.randomUUID(), r.roundId(), price,
                        player.position(), player.level().getServer().getTickCount(), false), p.wallet().coins(), discarded);
                return;
            }
            if (entry == Entry.CONFIRMED && (offer == null || offer.charge() || !offer.round().equals(r.roundId()) || offer.price() != price
                    || player.position().distanceToSqr(offer.at()) > CreeperUnlockDialog.REACH * CreeperUnlockDialog.REACH)) {
                Notices.refuse(player, "价格或位置已变化，请重新右键苦力怕展位"); return;
            }
        }
        // Picking another role ends a waiting hunt; the new body would not match the pending identity.
        var waiting = b.searches.get(p.id());
        if (waiting != null) cancelSearch(b, waiting, "已换角色，对 " + Notices.key(waiting.targetPlayer.getScoreboardName()) + " 的追猎已取消");
        // The pay dialog already named the paused sortie it discards.
        if (p.wallet().hasParkedBody() && role != p.monsterRole() && !(unlocking && entry == Entry.CONFIRMED)
                && !confirmAbandon(b, player, p, role)) return;
        b.committing.add(p.id());
        b.session.requestMonsterRole(r.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(), role, reply -> {
            b.committing.remove(p.id());
            if (!current(player, b)) return;
            var after = b.session.snapshot().filter(round -> round.roundId().equals(r.roundId()))
                    .map(round -> round.participants().get(p.id())).orElse(p);
            if (reply.success()) {
                if (unlocking && after.wallet().creeperTicket().unlocked()) Notices.hint(player, "苦力怕已解锁 · 右键三星以上的人类出击");
                else sound(player, true);
            } else if (unlocking) Replies.refuse(player, reply, "现在不能选择角色", creeperPrice(price, after));
            else Replies.refuse(player, reply, "现在不能选择角色");
        });
    }
    private static String chargePrice(int price, Participant p) {
        return "引雷需要 " + price + " 怪物币，现有 " + p.wallet().coins();
    }
    private static String creeperPrice(int price, Participant p) {
        return "解锁苦力怕需要 " + price + " 怪物币，现有 " + p.wallet().coins();
    }
    /**
     * Another role discards the paused sortie, so it takes a second click within a few seconds.
     * A native held right-click repeats every 4 ticks; such repeats count as the same click.
     */
    private static boolean confirmAbandon(Binding b, ServerPlayer player, Participant p, MonsterRole role) {
        long now = player.level().getServer().getTickCount();
        var ask = b.abandoning.get(p.id());
        if (ask != null && ask.role() == role && now - ask.lastClick() <= 5) {
            b.abandoning.put(p.id(), new AbandonAsk(role, ask.asked(), now)); return false;
        }
        if (ask != null && ask.role() == role && now - ask.asked() <= 120) { b.abandoning.remove(p.id()); return true; }
        b.abandoning.put(p.id(), new AbandonAsk(role, now, now));
        String parked = dev.hardrush.monster.MonsterParking.describe(p.wallet().parked()).orElse(kindName(p.monsterRole()));
        Notices.hint(player, "再点一次改选 " + Notices.key(kindName(role)) + "：暂存的 " + Notices.key(parked) + " 连同装备和身价作废");
        sound(player, false);
        return false;
    }
    /** Seats humans on the courtyard's display slots ({@link dev.hardrush.core.lobby.CourtyardSeats}). */
    private static void seat(MinecraftServer server, Binding b, RoundSnapshot r) {
        var profiles = new HashMap<UUID, GameProfile>();
        for (var slot : b.slots) profiles.put(slot.id(), slot);
        var humans = r.participants().values().stream()
                .filter(p -> p.lifeSequence() > 0 && p.role() == ParticipantRole.HUMAN_ACTIVE).toList();
        for (var p : humans) profiles.putIfAbsent(p.id(), new GameProfile(p.id(), p.name()));
        var before = b.slots.stream().map(GameProfile::id).toList();
        var after = dev.hardrush.core.lobby.CourtyardSeats.seat(before, humans.stream().map(Participant::id).toList(),
                holder -> vacant(server, b, r, holder));
        before.stream().filter(id -> !after.contains(id)).forEach(b.deadAt::remove);
        b.slots.clear();
        for (var id : after) {
            var player = server.getPlayerList().getPlayer(id);
            b.slots.add(player != null ? player.getGameProfile() : profiles.get(id));
        }
    }
    private static boolean vacant(MinecraftServer server, Binding b, RoundSnapshot r, UUID holder) {
        var p = r.participants().get(holder);
        if (p == null) return true;
        if (p.role() == ParticipantRole.HUMAN_ACTIVE) return false;
        var died = b.deadAt.get(holder);
        return died != null && server.getTickCount() - died >= DEAD_MODEL_TICKS;
    }
    private static void updateDisplays(MinecraftServer server, Binding b) {
        var r = b.session.snapshot().orElse(null); if (r == null) return;
        var projections = LobbyInteractions.get(server).orElseThrow().projections();
        if (!personalOpen(b, r) || !dev.hardrush.world.LobbyWorld.open(server).ready()) {
            if (!b.displays.isEmpty()) projections.clear();
            b.displays.clear(); return;
        }
        seat(server, b, r);
        var targets = new ArrayList<LobbyProjections.Target>();
        for (var slot : b.slots) {
            var p = r.participants().get(slot.id());
            // Defensive: rounds never drop participants (a withdrawn or disqualified human falls through the non-human
            // branch below); should a holder ever vanish from the snapshot, it keeps its hole so nobody after it shifts.
            if (p == null) { targets.add(new LobbyProjections.Target(slot, 1, LobbyProjections.State.EMPTY)); continue; }
            var player = server.getPlayerList().getPlayer(p.id());
            LobbyProjections.State state;
            if (p.role() != ParticipantRole.HUMAN_ACTIVE) {
                int died = b.deadAt.computeIfAbsent(p.id(), ignored -> server.getTickCount());
                state = server.getTickCount() - died < DEAD_MODEL_TICKS ? LobbyProjections.State.DEAD : LobbyProjections.State.EMPTY;
            } else {
                b.deadAt.remove(p.id());
                state = player == null ? LobbyProjections.State.AWAY
                        : b.reviving.contains(p.id()) && (!p.online() || !b.profiles.isReady(p.id(), r) || PlayerProfiles.isLocked(player)
                                || RevivalAltar.arriving(server, p.id()))
                        ? LobbyProjections.State.RESTORING
                        : !b.profiles.isReady(p.id(), r) || PlayerProfiles.isLocked(player) ? LobbyProjections.State.CLOSED
                        : !p.online() ? LobbyProjections.State.AWAY
                        : validTarget(b, player, player.level()) && !dimension(b, player.level()).equals("lobby")
                        ? LobbyProjections.State.AVAILABLE : LobbyProjections.State.CLOSED;
                // The revival title and broadcast come from RevivalAltar when the body lands.
                if (state == LobbyProjections.State.AVAILABLE) b.reviving.remove(p.id());
                if (state == LobbyProjections.State.AVAILABLE && LateShelter.sheltered(r.rules(), live(b, p)))
                    state = LobbyProjections.State.SHELTERED;
            }
            targets.add(new LobbyProjections.Target(slot, p.lifeSequence(), state));
        }
        // Defeat locks count down in live server time; the committed round only moves at commits.
        var live = b.session.view().orElse(r);
        var standings = Bounty.standings(live.rules(), live.participants());
        var looks = looks(server, b, r, targets, standings);
        var hunters = hunters(server, b, r);
        b.locked.keySet().removeIf(id -> live.participants().get(id) == null || !live.participants().get(id).role().isMonster());
        for (var observer : server.getPlayerList().getPlayers()) {
            if (!contextAvailable(observer, b, LobbyContext.DISPLAY)) { b.displays.remove(observer.getUUID()); continue; }
            var old = b.displays.get(observer.getUUID()); var view = projections.view(observer);
            var identity = r.participants().get(observer.getUUID());
            var lived = live.participants().get(observer.getUUID());
            if (identity != null && identity.role() == ParticipantRole.MONSTER_LOBBY) {
                boolean cooling = identity.monsterCooldownNanos() > 0;
                if (Boolean.TRUE.equals(b.cooling.put(identity.id(), cooling)) && !cooling) {
                    if (paidCreeper(r.rules(), identity) && !identity.wallet().creeperTicket().unlocked())
                        Notices.hint(observer, "出击冷却结束 · 苦力怕要先回展位解锁");
                    else { Notices.hint(observer, "可以再次出击了"); sound(observer, true); }
                }
            }
            if (lived != null && lived.role() == ParticipantRole.MONSTER_LOBBY) freedFromLocks(observer, b, live, lived);
            var seen = seenBy(lived, looks, hunters, live.rules(), standings);
            // Only who stands where rebuilds the page; names, stars, glow and gear change on the standing models.
            if (old == null || old.player != observer || old.connection != observer.connection || !old.targets.equals(targets) || view.isEmpty()) {
                // Dropped trailing seats can take the observer's page away; it then lands on the new last page.
                int page = Math.min(view.map(LobbyProjections.View::page).orElse(0), LobbyProjections.pages(targets.size(),
                        dev.hardrush.world.LobbyWorld.open(server).plan().displayCapacity()) - 1);
                projections.show(observer, r.roundId(), targets, seen, page, (actor, selected) -> beginDeployment(actor, b, selected),
                        (actor, selected) -> dev.hardrush.observe.Observation.watch(actor, selected.target()));
                b.displays.put(observer.getUUID(), new Display(observer, observer.connection, List.copyOf(targets)));
            } else projections.decorate(observer, seen);
            if (server.getTickCount() % 5 == 0) {
                var p = r.participants().get(observer.getUUID());
                if (p != null && p.role().isExcluded()) projections.aimed(observer).ifPresent(selected -> LobbyInteractions.get(server).orElseThrow()
                        .hints(observer).offer("watch-target", dev.hardrush.core.lobby.LobbyHints.Priority.TARGET,
                                Notices.key(r.participants().get(selected.target()).name()) + " · 左键观察", System.nanoTime() / 1_000_000 + 300));
                if (p != null && p.role() == ParticipantRole.MONSTER_LOBBY) {
                    var hints = LobbyInteractions.get(server).orElseThrow().hints(observer);
                    long now = System.nanoTime() / 1_000_000;
                    var attempt = b.searches.get(p.id());
                    if (attempt != null && attempt.search != null) {
                        var quarry = attempt.targetPlayer;
                        String name = Notices.key(quarry.getScoreboardName());
                        long waited = Math.max(0, b.searchClock.getAsLong() - attempt.started) / 1_000_000_000L;
                        String cancel = dev.hardrush.observe.Observation.observing(observer) ? " · 长按空格取消" : " · 再次右键取消";
                        String state;
                        if (attempt.queued > 0) state = "追猎 " + name + " 的怪物已满 · 排队第 " + Notices.key(attempt.queued) + " 位" + cancel;
                        else if (SafeDeployment.atSea(quarry)) {
                            if (attempt.identity.kind() != MonsterRole.ZOMBIE) {
                                state = name + " 在海上，等靠岸后出击" + cancel;
                                if (!attempt.seaTipShown) {
                                    attempt.seaTipShown = true;
                                    Notices.hint(observer, "换成僵尸可以化身溺尸，从水下出击");
                                }
                            } else state = name + " 在海上，正在寻找水下出击位置";
                        } else state = quarry.onGround()
                                ? (waited >= SafeDeployment.FALLBACK_AFTER_NANOS / 1_000_000_000L && dev.hardrush.hunt.HuntBurrows.allowed(attempt.level)
                                        ? "正在寻找 " + name + " 附近能破石而出的位置 · "
                                        : "正在寻找 " + name + " 附近的最佳出击位置 · ") + Notices.key(waited) + " 秒" + cancel
                                : name + " 不在地面，等落地后出击" + cancel;
                        hints.offer("hunt", dev.hardrush.core.lobby.LobbyHints.Priority.PROGRESS, state, now + 300);
                    } else if (attempt != null || b.committing.contains(p.id()) || b.resumptions.containsKey(p.id()))
                        hints.offer("hunt", dev.hardrush.core.lobby.LobbyHints.Priority.PROGRESS, "正在出击……", now + 300);
                    else if (!dev.hardrush.core.round.MonsterCoins.enabled(r.rules()) || !p.revivalEntitled()) {
                        boolean creeper = paidCreeper(r.rules(), p);
                        var available = targets.stream().filter(target -> target.state() == LobbyProjections.State.AVAILABLE)
                                .map(target -> target.originalProfile().id())
                                .filter(id -> !creeper || !belowCreeperStars(standings, id)).toList();
                        // Every human it may see has just beaten it: the soonest one free is the wait.
                        var freest = lived == null || available.isEmpty() ? Optional.<UUID>empty()
                                : available.stream().allMatch(id -> lived.huntLockNanos(id) > 0)
                                ? available.stream().min(java.util.Comparator.comparingLong(lived::huntLockNanos)) : Optional.<UUID>empty();
                        if (inCamp(observer)) {
                            String guidance = creeper && !p.wallet().creeperTicket().unlocked()
                                    ? "苦力怕未解锁 · 右键笼中的苦力怕，花 " + MonsterCoins.creeperUnlockPrice(r.rules()) + " 怪物币解锁"
                                    : available.isEmpty() ? (creeper ? "苦力怕只追三星以上 · 暂无可追的人类" : "暂无可追猎的人类")
                                    : Notices.key(kindName(p.monsterRole())) + (p.monsterCooldownNanos() > 0
                                    ? " · 出击冷却 " + Notices.key(seconds(p.monsterCooldownNanos())) + " 秒"
                                    : freest.map(id -> " · 人类都在提防你 · " + Notices.key(seconds(lived.huntLockNanos(id))) + " 秒后可追猎 "
                                            + Notices.key(live.participants().get(id).name())).orElse(" · 右键目标模型追猎"));
                            hints.offer("hunt-guide", dev.hardrush.core.lobby.LobbyHints.Priority.GUIDANCE, guidance, now + 300);
                        }
                        // The role is already on the idle line; aiming at a model only adds whom and what each click does.
                        projections.aimed(observer).ifPresent(selected -> {
                            long locked = lived == null ? 0 : lived.huntLockNanos(selected.target());
                            hints.offer("hunt-target", dev.hardrush.core.lobby.LobbyHints.Priority.TARGET,
                                    Notices.key(r.participants().get(selected.target()).name())
                                            + (creeper && !p.wallet().creeperTicket().unlocked() ? " · 先回苦力怕展位解锁"
                                            : creeper && belowCreeperStars(standings, selected.target()) ? " · 苦力怕只追三星以上"
                                            : projections.sheltered(observer, selected) ? " · " + LateShelter.NAME
                                            : p.monsterCooldownNanos() > 0 ? " · 冷却 " + Notices.key(seconds(p.monsterCooldownNanos())) + " 秒"
                                            : locked > 0 ? " · 提防你 " + Notices.key(seconds(locked)) + " 秒" : " · 右键追猎") + " · 左键观察", now + 300);
                        });
                        dev.hardrush.monster.MonsterParking.describe(p.wallet().parked()).ifPresent(parked -> hints.offer("parked",
                                dev.hardrush.core.lobby.LobbyHints.Priority.FACILITY, "暂存的出击：" + Notices.key(parked)
                                        + (dev.hardrush.core.round.MonsterWorth.enabled(r.rules()) ? " · 身价 "
                                        + Notices.key(dev.hardrush.core.round.MonsterWorth.level(dev.hardrush.core.round.MonsterWorth.milliLevels(r.rules(), p.wallet()))) + " 级" : ""),
                                now + 300));
                    }
                }
            }
        }
        b.displays.entrySet().removeIf(e -> server.getPlayerList().getPlayer(e.getKey()) != e.getValue().player);
    }
    private static ServerLevel level(Binding b, MonsterDimension dimension) {
        var worlds = b.worlds.get();
        return worlds == null || !worlds.ready() ? null : worlds.level(switch (dimension) {
            case OVERWORLD -> Level.OVERWORLD; case NETHER -> Level.NETHER; case END -> Level.END;
        });
    }
    /** The participant with its live human time; the committed snapshot lags by up to a checkpoint. */
    private static dev.hardrush.core.round.Participant live(Binding b, dev.hardrush.core.round.Participant p) {
        return b.session.view().map(view -> view.participants().get(p.id())).orElse(p);
    }
    /** "4:32" while a late entrant's first life cannot be hunted yet; empty otherwise. */
    public static Optional<String> shelterClock(ServerPlayer target) {
        var b = BINDINGS.get(target.level().getServer());
        var round = b == null ? null : b.session.view().orElse(null);
        if (round == null) return Optional.empty();
        long remaining = LateShelter.remainingNanos(round.rules(), round.participants().get(target.getUUID()));
        return remaining > 0 ? Optional.of(LateShelter.clock(remaining)) : Optional.empty();
    }
    private static final int GEAR_TICKS = 10;
    /** How long a fallen human's model stays in its seat before the seat is free for another human. */
    private static final int DEAD_MODEL_TICKS = 40;

    /**
     * What each huntable model shows: name, bounty stars and whereabouts, the human's worn gear, and for the top
     * bounty a glow and the ominous banner of a raid captain in place of its helmet.
     */
    private static Map<UUID, LobbyProjections.Look> looks(MinecraftServer server, Binding b, RoundSnapshot r,
                                                         List<LobbyProjections.Target> targets, Map<UUID, Bounty.Standing> standings) {
        boolean refreshGear = server.getTickCount() % GEAR_TICKS == 0;
        var looks = new HashMap<UUID, LobbyProjections.Look>();
        for (var target : targets) {
            if (target.state() == LobbyProjections.State.SHELTERED) {
                // 初入荒野: whose and until when, nothing that would help choose; the clock ticks on the standing model.
                var p = r.participants().get(target.originalProfile().id());
                looks.put(target.originalProfile().id(), new LobbyProjections.Look(Component.literal(target.originalProfile().name()
                        + " · " + LateShelter.NAME + " " + LateShelter.clock(LateShelter.remainingNanos(r.rules(), live(b, p)))), false, Map.of()));
                continue;
            }
            if (target.state() != LobbyProjections.State.AVAILABLE) continue;
            var id = target.originalProfile().id();
            var player = server.getPlayerList().getPlayer(id);
            if (player == null) continue;
            if (refreshGear || !b.gear.containsKey(id)) b.gear.put(id, displayGear(player));
            var gear = new java.util.EnumMap<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack>(
                    net.minecraft.world.entity.EquipmentSlot.class);
            gear.putAll(b.gear.get(id));
            var standing = standings.get(id);
            var name = Component.empty();
            if (standing != null && standing.leader()) {
                name.append(Component.literal("头号悬赏 ").withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.BOLD));
                name.append(Component.literal(target.originalProfile().name()).withStyle(net.minecraft.ChatFormatting.GOLD));
                gear.put(net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.raid.Raid.getOminousBannerInstance(
                        player.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BANNER_PATTERN)));
            } else name.append(target.originalProfile().name());
            if (standing != null) name.append(Component.literal(" · ")).append(Component.literal(
                    dev.hardrush.core.round.Bounty.starText(standing.stars())).withStyle(starColor(standing.stars())));
            name.append(" · ").append(targetLocation(b, player));
            looks.put(id, new LobbyProjections.Look(name, standing != null && standing.leader(), gear));
        }
        b.gear.keySet().retainAll(targets.stream().filter(t -> t.state() == LobbyProjections.State.AVAILABLE)
                .map(t -> t.originalProfile().id()).toList());
        return looks;
    }

    /** How many monsters are after each human: out on a sortie for them, or waiting in the camp to set out. */
    private static Map<UUID, Integer> hunters(MinecraftServer server, Binding b, RoundSnapshot r) {
        var counts = new HashMap<>(dev.hardrush.hunt.HuntBossBars.hunters(server, r));
        for (var search : b.searches.values()) counts.merge(search.identity.target(), 1, Integer::sum);
        return counts;
    }

    /**
     * What one camp monster sees on the target models (docs/plan/击败锁定与怪物平衡.md §1, §6): how many monsters are
     * after each human, and who is still wary of it after beating it. Anyone else sees the shared looks.
     */
    private static Map<UUID, LobbyProjections.Look> seenBy(Participant monster, Map<UUID, LobbyProjections.Look> looks,
                                                           Map<UUID, Integer> hunters, RulesConfig rules, Map<UUID, Bounty.Standing> standings) {
        if (monster == null || monster.role() != ParticipantRole.MONSTER_LOBBY) return looks;
        boolean creeper = paidCreeper(rules, monster);
        var seen = new HashMap<UUID, LobbyProjections.Look>();
        looks.forEach((id, look) -> {
            if (creeper && belowCreeperStars(standings, id)) {
                var name = look.name().copy().append(Component.literal(" · 苦力怕只追三星以上").withStyle(net.minecraft.ChatFormatting.GRAY));
                seen.put(id, new LobbyProjections.Look(name, look.glowing(), look.gear()));
                return;
            }
            int hunting = hunters.getOrDefault(id, 0);
            long locked = monster.huntLockNanos(id);
            if (hunting == 0 && locked == 0) { seen.put(id, look); return; }
            var name = look.name().copy();
            int cap = HuntPressure.concurrentHunters(standings.getOrDefault(id, Bounty.Standing.NONE).stars());
            if (hunting >= cap) name.append(Component.literal(" · 满员 " + cap + "/" + cap
                    + (hunting > cap ? " · " + (hunting - cap) + " 只在排队" : "")).withStyle(net.minecraft.ChatFormatting.GRAY));
            else if (hunting > 0) name.append(Component.literal(" · " + hunting + "/" + cap + " 只怪物在追").withStyle(net.minecraft.ChatFormatting.GRAY));
            if (locked > 0) name.append(Component.literal(" · 提防你 " + seconds(locked) + " 秒").withStyle(net.minecraft.ChatFormatting.RED));
            seen.put(id, new LobbyProjections.Look(name, look.glowing() && locked == 0, look.gear()));
        });
        return seen;
    }

    private static boolean belowCreeperStars(Map<UUID, Bounty.Standing> standings, UUID target) {
        return standings.getOrDefault(target, Bounty.Standing.NONE).stars() < HuntPressure.CREEPER_MIN_STARS;
    }

    /** Tells a camp monster once a human who beat it may be hunted again. */
    private static void freedFromLocks(ServerPlayer observer, Binding b, RoundSnapshot live, Participant monster) {
        var now = java.util.Set.copyOf(monster.huntLocks().keySet());
        var before = b.locked.put(monster.id(), now);
        if (before == null) return;
        for (var human : before) {
            var quarry = live.participants().get(human);
            if (now.contains(human) || quarry == null || quarry.role() != ParticipantRole.HUMAN_ACTIVE) continue;
            // A paid creeper still could not set out after this human; the courtyard says why when it tries.
            if (creeperRefusal(live, monster, human).isPresent()) continue;
            Notices.hint(observer, "可以再次追猎 " + Notices.key(quarry.name()) + " 了"); sound(observer, true);
        }
    }

    private static net.minecraft.ChatFormatting starColor(int stars) {
        return switch (stars) {
            case 5 -> net.minecraft.ChatFormatting.RED; case 4 -> net.minecraft.ChatFormatting.GOLD;
            case 3 -> net.minecraft.ChatFormatting.YELLOW; default -> net.minecraft.ChatFormatting.WHITE;
        };
    }

    /**
     * What the camp may see of a human's worn gear: a fresh stack of the same item carrying only how it looks.
     * Everything else (a lodestone compass's target, a book's pages, a bundle's contents, durability) stays with the
     * human, and wear alone never changes what the model shows.
     */
    private static Map<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack> displayGear(ServerPlayer player) {
        var gear = new java.util.EnumMap<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack>(
                net.minecraft.world.entity.EquipmentSlot.class);
        for (var slot : LobbyProjections.Look.SLOTS) {
            var worn = player.getItemBySlot(slot);
            if (!worn.isEmpty()) gear.put(slot, displayCopy(worn));
        }
        return gear;
    }

    static net.minecraft.world.item.ItemStack displayCopy(net.minecraft.world.item.ItemStack worn) {
        var shown = new net.minecraft.world.item.ItemStack(worn.getItem());
        for (var type : java.util.List.<net.minecraft.core.component.DataComponentType<?>>of(
                net.minecraft.core.component.DataComponents.ITEM_MODEL, net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA,
                net.minecraft.core.component.DataComponents.EQUIPPABLE, net.minecraft.core.component.DataComponents.DYED_COLOR,
                net.minecraft.core.component.DataComponents.TRIM, net.minecraft.core.component.DataComponents.PROFILE,
                net.minecraft.core.component.DataComponents.BANNER_PATTERNS, net.minecraft.core.component.DataComponents.BASE_COLOR))
            copyComponent(worn, shown, type);
        // The glint says "enchanted" without telling which enchantments.
        if (worn.hasFoil()) shown.set(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return shown;
    }

    private static <T> void copyComponent(net.minecraft.world.item.ItemStack from, net.minecraft.world.item.ItemStack to,
                                          net.minecraft.core.component.DataComponentType<T> type) {
        T value = from.get(type);
        if (value != null) to.set(type, value);
    }

    private static Component targetLocation(Binding b, ServerPlayer player) {
        String dimensionName = switch (dimension(b, player.level())) {
            case "overworld" -> "主世界"; case "nether" -> "下界"; case "end" -> "末地";
            default -> "未知维度";
        };
        var biome = player.level().getBiome(player.blockPosition()).unwrapKey()
                .map(key -> Component.translatableWithFallback(key.identifier().toLanguageKey("biome"), key.identifier().toString()))
                .orElseGet(() -> Component.literal("未知群系"));
        return Component.literal(dimensionName + " · ").append(biome);
    }
    private static String dimension(Binding b, ServerLevel level) {
        for (var kind : MonsterDimension.values()) if (level == level(b, kind)) return kind.name().toLowerCase(Locale.ROOT);
        return "lobby";
    }
    private static boolean validTarget(Binding b, ServerPlayer player, ServerLevel level) {
        if (player == null || player.level() != level || !player.isAlive() || PlayerProfiles.isLocked(player)
                || ProtectionRuntime.protectedEntity(player) || RevivalAltar.arriving(level.getServer(), player.getUUID())) return false;
        return b.session.snapshot().map(r -> r.participants().get(player.getUUID()))
                .filter(p -> p.online() && p.role() == ParticipantRole.HUMAN_ACTIVE).isPresent();
    }
    private static List<Vec3> humans(Binding b, ServerLevel level) {
        var round = b.session.snapshot().orElseThrow();
        return level.getServer().getPlayerList().getPlayers().stream().filter(p -> p.level() == level && p.isAlive())
                .filter(p -> Optional.ofNullable(round.participants().get(p.getUUID()))
                        .filter(identity -> identity.online() && identity.role() == ParticipantRole.HUMAN_ACTIVE).isPresent())
                .map(ServerPlayer::position).toList();
    }
    /** Why the altar cannot begin a rite for this camp body now; null when it can. */
    static String altarRefusal(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        var r = b == null ? null : b.session.snapshot().orElse(null);
        if (r == null || b.session.state() != RoundSession.State.OPEN || r.phase() == RoundPhase.RECOVERY_REQUIRED) return "比赛已暂停，祭坛关闭";
        if (r.rules().mode() == dev.hardrush.core.config.GameMode.NORMAL) return "普通模式不开放祭坛，阵亡后原版重生";
        var p = r.participants().get(player.getUUID());
        if (p != null && p.role() == ParticipantRole.WITHDRAWN) return "你已退出本轮，下一轮再来";
        if (p != null && p.role() == ParticipantRole.DISQUALIFIED) return "你已被取消本轮资格，下一轮再来";
        if (r.phase() != RoundPhase.RUNNING && r.phase() != RoundPhase.RESCUE) return "祭坛只在挑战进行中开放";
        if (p == null || !p.role().isMonster()) return "阵亡后才能在祭坛复生 · 参赛去集结区";
        if (p.role() != ParticipantRole.MONSTER_LOBBY || !p.online()) return HumanEntry.STEP_PENDING;
        if (b.session.competitionLocked() || !lobbyAvailable(player, b)) return "服务器正忙，稍后再试";
        if (!p.revivalEntitled()) return "你没有复生图腾 · 击杀人类可获得";
        var worlds = b.worlds.get();
        if (worlds == null || !worlds.ready() || !RoundWorldBundle.safeStanding(worlds.level(Level.OVERWORLD), worlds.spawn())) {
            reportUnsafeSpawn(player, b);
            return "现在无法复生，复生图腾还在，请联系管理员";
        }
        UUID id = player.getUUID();
        if (b.committing.contains(id) || b.resumptions.containsKey(id) || b.pending.containsKey(id)) return "正在出击，稍等片刻";
        return null;
    }
    /** Operators get the detail behind the generic altar refusal, at most once per player and round. */
    private static void reportUnsafeSpawn(ServerPlayer player, Binding b) {
        var round = b.session.snapshot().orElse(null);
        if (round != null && Notices.firstInRound(player, round.roundId(), "operators:unsafe-spawn"))
            Notices.operators(player.level().getServer(), player.getScoreboardName() + " 无法在祭坛复生：本轮挑战世界的出生点未就绪或不安全");
    }

    /** A completed altar rite: the same durable revival request; its reply is told to the player. */
    static void beginRevival(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        var round = b == null ? null : b.session.view().orElse(null);
        if (round == null || !current(player, b)) return;
        var participant = round.participants().get(player.getUUID());
        if (participant == null || participant.role() != ParticipantRole.MONSTER_LOBBY || !participant.online()
                || round.phase() != RoundPhase.RUNNING && round.phase() != RoundPhase.RESCUE
                || b.session.competitionLocked() || !lobbyAvailable(player, b)) {
            Notices.refuse(player, "现在无法复生"); return;
        }
        UUID id = player.getUUID();
        var waiting = b.searches.get(id);
        if (waiting != null) cancelSearch(b, waiting, "前往复生，追猎取消");
        if (b.committing.contains(id) || b.searches.containsKey(id) || b.resumptions.containsKey(id)) {
            Notices.hint(player, "正在出击，稍等片刻"); return;
        }
        var worlds = b.worlds.get();
        if (worlds == null || !worlds.ready() || !RoundWorldBundle.safeStanding(worlds.level(Level.OVERWORLD), worlds.spawn())) {
            reportUnsafeSpawn(player, b);
            Notices.refuse(player, "现在无法复生，复生图腾还在，请联系管理员"); return;
        }
        try {
            b.committing.add(id);
            b.session.requestRevival(round.roundId(), id, participant.lifeSequence(), participant.monsterDeploymentSequence(), reply -> {
                b.committing.remove(id);
                if (current(player, b) && !reply.success()) Replies.refuse(player, reply, "现在无法复生");
            });
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            b.committing.remove(id);
            org.slf4j.LoggerFactory.getLogger(LobbyRuntime.class).debug("Revival request of {} rejected", id, invalid);
            Notices.refuse(player, "现在无法复生");
        }
    }
    static boolean reviveCommitting(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        return b != null && b.committing.contains(player.getUUID());
    }
    private static void hint(ServerPlayer player, String source, dev.hardrush.core.lobby.LobbyHints.Priority priority, String text) {
        LobbyInteractions.get(player.level().getServer()).ifPresent(router -> router.hints(player).offer(source, priority, text, System.nanoTime() / 1_000_000 + 300));
    }
    private static String kindName(MonsterRole role) {
        return switch (role) { case ZOMBIE -> "僵尸"; case SKELETON -> "骷髅"; case CREEPER -> "苦力怕"; };
    }
    private static long seconds(long nanos) { return nanos / 1_000_000_000L + (nanos % 1_000_000_000L == 0 ? 0 : 1); }
    private static void sound(ServerPlayer player, boolean success) {
        if (player.connection != null && player.connection.isAcceptingMessages()) player.connection.send(
                new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                        success ? net.minecraft.core.Holder.direct(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP)
                                : net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BASS,
                        net.minecraft.sounds.SoundSource.PLAYERS, player.getX(), player.getY(), player.getZ(),
                        .5f, success ? 1.2f : .8f, 0));
    }
}
