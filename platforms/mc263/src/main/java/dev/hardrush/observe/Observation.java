package dev.hardrush.observe;

import dev.hardrush.core.lobby.LobbyHints;
import dev.hardrush.core.lobby.LobbyLayout;
import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.lobby.LobbyInteractions;
import dev.hardrush.lobby.LobbyRuntime;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.round.RoundSession;
import dev.hardrush.world.LobbyWorld;
import dev.hardrush.world.RoundWorldBundle;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket;
import net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Camp monsters and excluded players watch the match by riding a round player's camera, never by free flight.
 * The body stays a sealed lobby body ({@link PlayerProfiles#observing}); this runtime owns the target, the
 * vanilla keys that steer it (A/D, sneak, held jump) and the audience counts shown to the watched.
 */
public final class Observation {
    public enum Group { MONSTER, EXCLUDED }
    /** One menu/target row: humans show their current life, monsters their native form. */
    public record Candidate(ServerPlayer player, boolean human, MonsterRole role, long lifeNanos) {}

    private static final Logger LOGGER = LoggerFactory.getLogger("hardrush-observation");
    private static final int LOST_GRACE_TICKS = 60, HOLD_TICKS = 20, ALERT_COOLDOWN_TICKS = 200;
    /** The target must be tracked by the client before a camera packet can bind; repeat while it arrives. */
    private static final Set<Integer> CAMERA_RESEND = Set.of(1, 3, 10, 20, 40);

    private static final class Session {
        final ServerPlayer player;
        final ServerGamePacketListenerImpl connection;
        final Group group;
        final Vec3 home;
        final float homeYaw;
        final ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("正在观察"),
                BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
        UUID target;
        String targetName = "";
        long attachedAt, lostAt = -1, jumpSince = -1;
        boolean jumpFired;
        Input input;
        String shownTitle = "";
        Session(ServerPlayer player, Group group) {
            this.player = player; connection = player.connection; this.group = group;
            home = player.position(); homeYaw = player.getYRot(); input = player.getLastClientInput();
        }
    }
    private static final class Binding {
        final MinecraftServer server;
        final RoundSession session;
        final PlayerProfiles profiles;
        final Map<UUID, Session> sessions = new HashMap<>();
        Map<UUID, Integer> audience = Map.of();
        final Map<UUID, Long> alerted = new HashMap<>();
        Binding(MinecraftServer server, RoundSession session, PlayerProfiles profiles) {
            this.server = server; this.session = session; this.profiles = profiles;
        }
    }
    private static final Map<MinecraftServer, Binding> BINDINGS = new IdentityHashMap<>();

    private Observation() {}

    public static void configure(MinecraftServer server, RoundSession session, PlayerProfiles profiles) {
        BINDINGS.put(server, new Binding(server, session, profiles));
    }

    public static void close(MinecraftServer server) {
        var b = BINDINGS.remove(server);
        if (b != null) b.sessions.values().forEach(s -> dev.hardrush.notice.StatusBars.hideEverywhere(server, s.bar));
    }

    public static void disconnected(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        if (b == null) return;
        var s = b.sessions.get(player.getUUID());
        if (s != null && s.player == player) drop(b, s);
    }

    /** Which observers this body belongs to, from its committed identity alone. */
    public static Optional<Group> group(RoundSnapshot round, ServerPlayer player) {
        var p = round.participants().get(player.getUUID());
        if (p == null) return Optional.empty();
        if (p.role() == ParticipantRole.MONSTER_LOBBY) return Optional.of(Group.MONSTER);
        return p.role().isExcluded() ? Optional.of(Group.EXCLUDED) : Optional.empty();
    }

    public static boolean observing(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        var s = b == null ? null : b.sessions.get(player.getUUID());
        return s != null && s.player == player && PlayerProfiles.observing(player);
    }

    /** Between targets the body has no camera; it must not drift into free flight. */
    public static boolean holdMovement(ServerPlayer player) {
        return observing(player) && player.getCamera() == player;
    }

    /** How many players currently ride this player's camera, operators included. */
    public static int audience(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        return b == null ? 0 : b.audience.getOrDefault(player.getUUID(), 0);
    }

    /** Why this lobby body cannot start watching now; null when it can. */
    public static String refusal(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        var r = b == null ? null : b.session.snapshot().orElse(null);
        if (r == null || !open(b, r) || b.session.competitionLocked()) return "挑战进行中才能观战";
        var group = group(r, player).orElse(null);
        if (group == null) return "阵亡、退出或被取消资格后才能观战";
        if (!LobbyWorld.isLobby(player.level()) || !PlayerProfiles.verifiedLobbyBody(player)) return "回到营地才能观战";
        if (group == Group.MONSTER && LobbyRuntime.submitting(player)) return "正在出击，稍等片刻";
        return null;
    }

    /** Courtyard order, humans first; excluded players may also watch deployed monsters. */
    public static List<Candidate> candidates(ServerPlayer observer) {
        var b = BINDINGS.get(observer.level().getServer());
        var r = b == null ? null : b.session.snapshot().orElse(null);
        var group = r == null ? null : group(r, observer).orElse(null);
        return group == null ? List.of() : candidates(b, r, group);
    }

    private static List<Candidate> candidates(Binding b, RoundSnapshot r, Group group) {
        var server = b.server;
        var live = b.session.view().orElse(r);
        var humans = new ArrayList<Candidate>();
        var monsters = new ArrayList<Candidate>();
        for (var id : order(server, r)) {
            var target = server.getPlayerList().getPlayer(id);
            if (!valid(r, group, target)) continue;
            var p = r.participants().get(id);
            var lived = Optional.ofNullable(live.participants().get(id)).map(Participant::lifeHumanNanos).orElse(p.lifeHumanNanos());
            if (p.role() == ParticipantRole.HUMAN_ACTIVE) humans.add(new Candidate(target, true, p.monsterRole(), lived));
            else monsters.add(new Candidate(target, false, p.monsterRole(), 0));
        }
        humans.addAll(monsters);
        return humans;
    }

    /** The courtyard's append-only slots, then anyone else this round knows. */
    private static List<UUID> order(MinecraftServer server, RoundSnapshot r) {
        var order = new LinkedHashSet<>(LobbyRuntime.courtyardOrder(server));
        order.addAll(r.participants().keySet());
        return List.copyOf(order);
    }

    private static boolean open(Binding b, RoundSnapshot r) {
        return b.session.state() == RoundSession.State.OPEN && (r.phase() == RoundPhase.RUNNING || r.phase() == RoundPhase.RESCUE);
    }

    private static boolean valid(RoundSnapshot r, Group group, ServerPlayer target) {
        if (target == null || !target.isAlive() || target.isRemoved() || target.isSpectator() || PlayerProfiles.isLocked(target)
                || !RoundWorldBundle.owns(target.level())) return false;
        var p = r.participants().get(target.getUUID());
        return p != null && p.online() && (p.role() == ParticipantRole.HUMAN_ACTIVE
                || group == Group.EXCLUDED && p.role() == ParticipantRole.MONSTER_DEPLOYED);
    }

    /** Start riding {@code targetId}'s camera from the camp; refusals are spoken on the action bar. */
    public static boolean watch(ServerPlayer player, UUID targetId) {
        var server = player.level().getServer();
        var b = BINDINGS.get(server);
        String refusal = refusal(player);
        if (refusal != null) { hint(player, refusal); return false; }
        var r = b.session.snapshot().orElseThrow();
        var group = group(r, player).orElseThrow();
        var target = server.getPlayerList().getPlayer(targetId);
        if (!valid(r, group, target)) {
            hint(player, group == Group.MONSTER ? "只能观察挑战中的人类" : "只能观察挑战中的人类或出击中的怪物");
            return false;
        }
        var s = new Session(player, group);
        if (!PlayerProfiles.beginObservation(player)) { hint(player, "正在整理行囊，稍等片刻……"); return false; }
        b.sessions.put(player.getUUID(), s);
        dev.hardrush.notice.StatusBars.show(player, dev.hardrush.notice.StatusBars.Slot.OBSERVE, s.bar);
        attach(server, s, target);
        return true;
    }

    private static void attach(MinecraftServer server, Session s, ServerPlayer target) {
        s.target = target.getUUID();
        s.targetName = target.getScoreboardName();
        s.lostAt = -1;
        s.jumpSince = -1;
        s.attachedAt = server.getTickCount();
        // setCamera teleports the body beside the target, across worlds if needed; a stale same-target
        // camera after a portal would otherwise be a no-op.
        s.player.setCamera(s.player);
        s.player.setCamera(target);
    }

    /** Sneak leaves, and the body stands where it started watching. */
    private static void end(MinecraftServer server, Binding b, Session s, String message) {
        drop(b, s);
        try {
            b.profiles.endObservation(s.player, s.home, s.homeYaw, s.group == Group.MONSTER ? LobbyLayout.Landing.MONSTER : LobbyLayout.Landing.CENTRAL);
        } catch (RuntimeException failure) {
            LOGGER.error("Observer {} could not return to the camp", s.player.getUUID(), failure);
            b.profiles.returnToLobby(s.player);
        }
        if (message != null) hint(s.player, message);
    }

    private static void drop(Binding b, Session s) {
        b.sessions.remove(s.player.getUUID(), s);
        dev.hardrush.notice.StatusBars.hideEverywhere(b.server, s.bar);
        LobbyInteractions.get(b.server).ifPresent(router -> router.hints(s.player).clear("observe-help"));
    }

    public static void tick(MinecraftServer server) {
        var b = BINDINGS.get(server);
        if (b == null) return;
        var r = b.session.snapshot().orElse(null);
        long tick = server.getTickCount();
        for (var s : List.copyOf(b.sessions.values())) {
            var player = s.player;
            if (server.getPlayerList().getPlayer(player.getUUID()) != player || player.connection != s.connection
                    || !PlayerProfiles.observing(player)) { drop(b, s); continue; }
            if (r == null || !open(b, r) || group(r, player).orElse(null) != s.group) { end(server, b, s, null); continue; }
            var target = server.getPlayerList().getPlayer(s.target);
            if (valid(r, s.group, target)) {
                s.lostAt = -1;
                if (player.getCamera() != target || player.level() != target.level()) attach(server, s, target);
                else if (CAMERA_RESEND.contains((int) Math.min(Integer.MAX_VALUE, tick - s.attachedAt)) || (tick - s.attachedAt) % 20 == 0)
                    player.connection.send(new ClientboundSetCameraPacket(target));
            } else {
                if (s.lostAt < 0) s.lostAt = tick;
                if (tick - s.lostAt >= LOST_GRACE_TICKS) {
                    var next = step(b, r, s, 1);
                    if (next == null) { end(server, b, s, "没有可观察的玩家了，回到了营地"); continue; }
                    attach(server, s, next);
                    target = next;
                }
            }
            if (s.group == Group.MONSTER && s.jumpSince >= 0 && !s.jumpFired && s.lostAt < 0 && tick - s.jumpSince >= HOLD_TICKS) {
                s.jumpFired = true;
                LobbyRuntime.huntObserved(player, s.target);
            }
            if (b.sessions.get(player.getUUID()) == s) bar(s, server.getPlayerList().getPlayer(s.target), tick);
        }
        audience(server, b, tick);
    }

    private static void bar(Session s, ServerPlayer target, long tick) {
        boolean lost = s.lostAt >= 0;
        var hunting = s.group == Group.MONSTER ? LobbyRuntime.huntTarget(s.player) : Optional.<UUID>empty();
        boolean huntingThis = hunting.filter(s.target::equals).isPresent();
        boolean holding = s.group == Group.MONSTER && s.jumpSince >= 0 && !s.jumpFired && !lost;
        // The bar says whom and what; the keys live on the action bar below it, at the lowest priority.
        String title;
        if (lost) title = s.targetName + " 已无法观察，即将切换";
        else if (holding) title = (huntingThis ? "按住空格取消追猎 " : hunting.isPresent() ? "按住空格改追 " : "按住空格追猎 ") + s.targetName + "……";
        else title = (huntingThis ? "正在追猎 " : "正在观察 ") + s.targetName;
        if (!title.equals(s.shownTitle)) { s.bar.setName(dev.hardrush.notice.Notices.bar(title)); s.shownTitle = title; }
        String keys = s.group == Group.EXCLUDED ? "A/D 切换 · 潜行退出"
                : "A/D 切换 · " + (huntingThis ? "长按空格取消追猎" : hunting.isPresent() ? "长按空格改追此人" : "长按空格追猎") + " · 潜行退出";
        LobbyInteractions.get(s.player.level().getServer()).ifPresent(router -> router.hints(s.player).offer("observe-help",
                LobbyHints.Priority.GUIDANCE, keys, System.nanoTime() / 1_000_000 + 500));
        float progress = holding ? Math.min(1, (tick - s.jumpSince) / (float) HOLD_TICKS)
                : lost || target == null ? 0 : Math.max(0, Math.min(1, target.getHealth() / Math.max(1, target.getMaxHealth())));
        s.bar.setProgress(progress);
        var color = holding ? BossEvent.BossBarColor.YELLOW : lost ? BossEvent.BossBarColor.WHITE
                : target != null && isHuman(target) ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.PURPLE;
        s.bar.setColor(color);
    }

    private static boolean isHuman(ServerPlayer target) {
        var b = BINDINGS.get(target.level().getServer());
        return b != null && b.session.snapshot().map(r -> r.participants().get(target.getUUID()))
                .filter(p -> p.role() == ParticipantRole.HUMAN_ACTIVE).isPresent();
    }

    /** Next/previous watchable player; when the current one vanished, continue from its courtyard place. */
    private static ServerPlayer step(Binding b, RoundSnapshot r, Session s, int direction) {
        var server = s.player.level().getServer();
        var list = candidates(b, r, s.group).stream().map(Candidate::player).toList();
        if (list.isEmpty()) return null;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getUUID().equals(s.target)) return list.get(Math.floorMod(i + direction, list.size()));
        }
        var order = order(server, r);
        int lost = order.indexOf(s.target);
        int before = (int) list.stream().filter(p -> order.indexOf(p.getUUID()) < lost).count();
        return list.get(Math.floorMod(direction > 0 ? before : before - 1, list.size()));
    }

    private static void cycle(Binding b, Session s, int direction) {
        var r = b.session.snapshot().orElse(null);
        if (r == null) return;
        var next = step(b, r, s, direction);
        if (next == null) { hint(s.player, "没有其他可观察的玩家"); return; }
        if (next.getUUID().equals(s.target) && s.lostAt < 0) { hint(s.player, "只有 " + dev.hardrush.notice.Notices.key(s.targetName) + " 可以观察"); return; }
        attach(s.player.level().getServer(), s, next);
    }

    /** Native movement keys arrive even while riding another camera; consume them all. */
    public static boolean input(ServerPlayer player, Input input) {
        var b = BINDINGS.get(player.level().getServer());
        var s = b == null ? null : b.sessions.get(player.getUUID());
        if (s == null || s.player != player || !PlayerProfiles.observing(player)) return false;
        var before = s.input;
        s.input = input;
        player.setLastClientInput(input);
        if (input.shift() && !before.shift()) { end(player.level().getServer(), b, s, null); return true; }
        if (input.left() && !before.left()) cycle(b, s, -1);
        if (input.right() && !before.right()) cycle(b, s, 1);
        if (input.jump() && !before.jump()) { s.jumpSince = player.level().getServer().getTickCount(); s.jumpFired = false; }
        else if (!input.jump()) s.jumpSince = -1;
        return true;
    }

    /** Left-clicking another visible player while watching moves the camera there if it may be watched. */
    public static boolean spectate(ServerPlayer player, ServerboundSpectatorActionPacket packet) {
        var b = BINDINGS.get(player.level().getServer());
        var s = b == null ? null : b.sessions.get(player.getUUID());
        if (s == null || s.player != player) return false;
        var r = b.session.snapshot().orElse(null);
        packet.spectateEntityId().ifPresent(id -> {
            if (r != null && player.level().getEntity(id) instanceof ServerPlayer target && valid(r, s.group, target)
                    && !target.getUUID().equals(s.target)) attach(player.level().getServer(), s, target);
        });
        return true;
    }

    /** The native spectator menu's heads bind the camera directly instead of teleporting beside a player. */
    public static boolean teleport(ServerPlayer player, ServerboundTeleportToEntityPacket packet) {
        var b = BINDINGS.get(player.level().getServer());
        var s = b == null ? null : b.sessions.get(player.getUUID());
        if (s == null || s.player != player) return false;
        var r = b.session.snapshot().orElse(null);
        for (var level : player.level().getServer().getAllLevels()) {
            var entity = packet.getEntity(level);
            if (entity == null) continue;
            if (r != null && entity instanceof ServerPlayer target && valid(r, s.group, target)) attach(player.level().getServer(), s, target);
            else hint(player, s.group == Group.MONSTER ? "只能观察挑战中的人类" : "只能观察挑战中的人类或出击中的怪物");
            break;
        }
        return true;
    }

    /** Count cameras per target; a newly arrived watcher is announced softly, at most once per ten seconds. */
    private static void audience(MinecraftServer server, Binding b, long tick) {
        var counts = new HashMap<UUID, Integer>();
        for (var player : server.getPlayerList().getPlayers()) {
            if (player.getCamera() != player && player.getCamera() instanceof ServerPlayer target) counts.merge(target.getUUID(), 1, Integer::sum);
        }
        for (var entry : counts.entrySet()) {
            if (entry.getValue() <= b.audience.getOrDefault(entry.getKey(), 0)) continue;
            var target = server.getPlayerList().getPlayer(entry.getKey());
            Long last = b.alerted.get(entry.getKey());
            if (target == null || last != null && tick - last < ALERT_COOLDOWN_TICKS) continue;
            b.alerted.put(entry.getKey(), tick);
            // The spyglass sound is the alert; the words only explain it the first time in a round.
            var round = b.session.snapshot().orElse(null);
            if (round != null && dev.hardrush.notice.Notices.firstInRound(target, round.roundId(), "watched"))
                LobbyInteractions.get(server).ifPresent(router -> router.hints(target).offer("watched", LobbyHints.Priority.GUIDANCE,
                        "有人正在用望远镜观察你", System.nanoTime() / 1_000_000 + 2500));
            target.connection.send(new ClientboundSoundPacket(net.minecraft.core.Holder.direct(SoundEvents.SPYGLASS_USE), SoundSource.PLAYERS,
                    target.getX(), target.getY(), target.getZ(), .4f, 1f, 0));
        }
        b.alerted.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        b.audience = Map.copyOf(counts);
    }

    static void hint(ServerPlayer player, String text) {
        LobbyInteractions.get(player.level().getServer()).ifPresent(router -> router.hints(player).offer("observe",
                LobbyHints.Priority.TARGET, text, System.nanoTime() / 1_000_000 + 3000));
    }
}
