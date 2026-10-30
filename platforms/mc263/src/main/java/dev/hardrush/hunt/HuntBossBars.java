package dev.hardrush.hunt;

import dev.hardrush.HardRushProperties;
import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.hunt.HuntBars;
import dev.hardrush.core.hunt.PersonalHuntRequest;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.monster.MonsterRoles;
import dev.hardrush.round.RoundSession;
import dev.hardrush.world.LobbyWorld;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Native boss bars between a deployed monster player and the human it selected. The target is
 * kept in memory for the sortie only: disconnecting, returning or dying all end the sortie.
 */
public final class HuntBossBars {
    private static final Logger LOGGER = LoggerFactory.getLogger("hardrush-hunt");
    private static final int UPDATE_TICKS = 4;
    private record Sortie(UUID round, int hunterLife, int deployment, UUID target, int targetLife) {}
    /** Last direct hit by a monster player, kept for the damage linger even if that body dies in the same call. */
    private record Attacker(String name, ServerLevel level, Vec3 position, long until) {}
    /** What a human slot shows: a live body, or an attacker whose body already fell (empty bar). */
    private record Shown(String name, float progress, ServerLevel level, Vec3 position, double visibility, boolean live) {}
    private static final class HumanBars {
        final HuntBars.HumanView view = new HuntBars.HumanView();
        final List<ServerBossEvent> slots = new ArrayList<>();
    }
    private static final class TargetBar {
        final Sortie sortie;
        final HuntBars.TargetView view = new HuntBars.TargetView();
        final ServerBossEvent bar = bar();
        TargetBar(Sortie sortie) { this.sortie = sortie; }
    }
    private static final class Binding {
        final HuntBars.Settings settings = settings();
        final Map<UUID, Sortie> sorties = new HashMap<>();
        final Map<UUID, HumanBars> humans = new HashMap<>();
        final Map<UUID, TargetBar> targets = new HashMap<>();
        final Map<UUID, Attacker> attackers = new HashMap<>();
    }
    private static final Map<MinecraftServer, Binding> BINDINGS = new IdentityHashMap<>();
    private HuntBossBars() {}

    private static HuntBars.Settings settings() {
        try {
            return HuntBars.Settings.parse(key -> System.getProperty(HardRushProperties.HUNT_BOSS_BAR_PREFIX + key));
        } catch (IllegalArgumentException invalid) {
            LOGGER.warn("Ignoring invalid {}* settings; using defaults: {}", HardRushProperties.HUNT_BOSS_BAR_PREFIX, invalid.getMessage());
            return HuntBars.Settings.DEFAULTS;
        }
    }
    private static Binding binding(MinecraftServer server) { return BINDINGS.computeIfAbsent(server, ignored -> new Binding()); }
    private static ServerBossEvent bar() {
        return new ServerBossEvent(UUID.randomUUID(), Component.empty(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
    }
    private static long now() { return System.nanoTime() / 1_000_000; }

    /** Called once the personal hunt sortie is durably committed and the monster body is applied. */
    public static void deployed(ServerPlayer hunter, RoundSnapshot committed, PersonalHuntRequest identity) {
        var p = committed.participants().get(hunter.getUUID());
        if (p == null || p.role() != ParticipantRole.MONSTER_DEPLOYED || !committed.roundId().equals(identity.round())) return;
        var b = binding(hunter.level().getServer());
        var sortie = new Sortie(committed.roundId(), p.lifeSequence(), p.monsterDeploymentSequence(), identity.target(), identity.targetLife());
        b.sorties.put(hunter.getUUID(), sortie);
        var old = b.targets.remove(hunter.getUUID());
        if (old != null) old.bar.removeAllPlayers();
    }

    /** Direct monster-player damage only; companions keep players and summons indistinguishable. */
    public static void damaged(ServerPlayer victim, CombatOrigin origin, RoundSnapshot round) {
        if (origin.kind() == AttackKind.SUMMON || !origin.roundId().equals(round.roundId())) return;
        var monster = round.participants().get(origin.actor().playerId());
        if (monster == null || monster.role() != ParticipantRole.MONSTER_DEPLOYED
                || monster.lifeSequence() != origin.actor().lifeSequence()
                || monster.monsterDeploymentSequence() != origin.actor().deploymentSequence()) return;
        var b = binding(victim.level().getServer());
        long now = now();
        b.humans.computeIfAbsent(victim.getUUID(), ignored -> new HumanBars()).view.damaged(monster.id(), now);
        // A creeper's blast kills its own body in the same call, before the next bar update runs.
        var body = victim.level().getServer().getPlayerList().getPlayer(monster.id());
        var level = body != null && body.level() instanceof ServerLevel own ? own : (ServerLevel) victim.level();
        var attacker = new Attacker(body != null ? body.getScoreboardName() : monster.name(), level,
                body != null ? body.position() : victim.position(), now + HuntBars.LINGER_MILLIS);
        b.attackers.merge(monster.id(), attacker, (old, next) -> next.until() >= old.until() ? next : old);
    }

    public static void tick(MinecraftServer server, RoundSnapshot round, RoundSession session) {
        if (server.getTickCount() % UPDATE_TICKS != 0) return;
        var b = BINDINGS.get(server);
        if (b == null) return;
        // Not competitionLocked(): periodic full checkpoints briefly lock entry and must not blink the bars.
        boolean active = session.state() == RoundSession.State.OPEN && HuntBars.active(round);
        b.sorties.entrySet().removeIf(entry -> {
            var p = round.participants().get(entry.getKey());
            var s = entry.getValue();
            return !s.round().equals(round.roundId()) || p == null || p.role() != ParticipantRole.MONSTER_DEPLOYED
                    || p.lifeSequence() != s.hunterLife() || p.monsterDeploymentSequence() != s.deployment();
        });
        var bodies = new LinkedHashMap<UUID, ServerPlayer>();
        for (var id : b.sorties.keySet()) {
            var player = server.getPlayerList().getPlayer(id);
            if (player != null && player.isAlive() && !player.isSpectator() && MonsterRoles.isMonster(player)
                    && player.level() instanceof ServerLevel level && !LobbyWorld.isLobby(level)) bodies.put(id, player);
        }
        long now = now();
        b.attackers.values().removeIf(attacker -> attacker.until() <= now);
        humans(server, round, b, bodies, active, now);
        targets(server, round, b, bodies, active, now);
    }

    private static void humans(MinecraftServer server, RoundSnapshot round, Binding b, Map<UUID, ServerPlayer> bodies,
                               boolean active, long now) {
        var viewers = new HashSet<UUID>();
        var shown = new LinkedHashMap<UUID, Shown>();
        bodies.forEach((id, monster) -> shown.put(id, new Shown(monster.getScoreboardName(),
                progress(monster.getHealth(), monster.getMaxHealth()), (ServerLevel) monster.level(), monster.position(),
                // The real body's vanilla detection modifiers: sneaking and invisibility with armor cover.
                monster.getVisibilityPercent((ServerLevel) monster.level(), null), true)));
        b.attackers.forEach((id, attacker) -> shown.putIfAbsent(id,
                new Shown(attacker.name(), 0, attacker.level(), attacker.position(), 1.0, false)));
        if (active) for (var human : round.participants().values()) {
            if (human.role() != ParticipantRole.HUMAN_ACTIVE || !human.online()) continue;
            var player = server.getPlayerList().getPlayer(human.id());
            if (player == null || !player.isAlive() || player.isSpectator() || !(player.level() instanceof ServerLevel level)
                    || LobbyWorld.isLobby(level)) continue;
            viewers.add(human.id());
            var bars = b.humans.computeIfAbsent(human.id(), ignored -> new HumanBars());
            var candidates = new ArrayList<HuntBars.HumanView.Candidate>();
            shown.forEach((id, monster) -> {
                var sortie = b.sorties.get(id);
                double distance = monster.level() == level ? monster.position().distanceTo(player.position()) : Double.NaN;
                // A fallen attacker never counts as hunting: only an unexpired damage linger can keep it shown.
                candidates.add(new HuntBars.HumanView.Candidate(id, distance, monster.visibility(), monster.live() && sortie != null
                        && sortie.target().equals(human.id()) && sortie.targetLife() == human.lifeSequence()));
            });
            var frame = bars.view.update(candidates, b.settings, now);
            render(player, bars, frame, shown);
        }
        for (var it = b.humans.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            if (viewers.contains(entry.getKey())) continue;
            entry.getValue().slots.forEach(ServerBossEvent::removeAllPlayers);
            it.remove();
        }
    }

    private static void render(ServerPlayer viewer, HumanBars bars, HuntBars.HumanView.Frame frame, Map<UUID, Shown> shown) {
        while (bars.slots.size() < frame.monsters().size()) bars.slots.add(bar());
        // Fixed slots keep the client's insertion order equal to the distance order.
        for (int i = 0; i < bars.slots.size(); i++) {
            var slot = bars.slots.get(i);
            if (i >= frame.monsters().size()) { slot.setVisible(false); continue; }
            var monster = shown.get(frame.monsters().get(i));
            slot.setName(Component.literal(HuntBars.humanTitle(monster.name())));
            slot.setProgress(monster.progress());
            slot.setDarkenScreen(i == 0 && frame.darken());
            attach(slot, viewer);
            slot.setVisible(true);
        }
    }

    private static void targets(MinecraftServer server, RoundSnapshot round, Binding b, Map<UUID, ServerPlayer> bodies,
                                boolean active, long now) {
        for (var it = b.targets.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            if (bodies.containsKey(entry.getKey()) && b.sorties.get(entry.getKey()) == entry.getValue().sortie) continue;
            entry.getValue().bar.removeAllPlayers();
            it.remove();
        }
        bodies.forEach((id, monster) -> {
            var sortie = b.sorties.get(id);
            var view = b.targets.computeIfAbsent(id, ignored -> new TargetBar(sortie));
            if (!active) { view.bar.setVisible(false); return; }
            var target = round.participants().get(sortie.target());
            var targetPlayer = server.getPlayerList().getPlayer(sortie.target());
            var state = HuntBars.targetState(target, sortie.targetLife(), targetPlayer != null && targetPlayer.level() == monster.level());
            if (!view.view.visible(state, now)) { view.bar.setVisible(false); return; }
            view.bar.setName(Component.literal(HuntBars.targetTitle(state, target.name())));
            view.bar.setColor(state == HuntBars.TargetState.OFFLINE ? BossEvent.BossBarColor.WHITE : BossEvent.BossBarColor.RED);
            if (state == HuntBars.TargetState.KILLED) view.bar.setProgress(0);
            // Offline keeps the last known health frozen.
            else if (state != HuntBars.TargetState.OFFLINE && targetPlayer != null)
                view.bar.setProgress(progress(targetPlayer.getHealth(), targetPlayer.getMaxHealth()));
            attach(view.bar, monster);
            view.bar.setVisible(true);
        });
    }

    private static void attach(ServerBossEvent bar, ServerPlayer viewer) {
        // A reconnect is a new ServerPlayer under the same UUID; never keep the stale connection.
        for (var old : List.copyOf(bar.getPlayers())) if (old != viewer) bar.removePlayer(old);
        if (!bar.getPlayers().contains(viewer)) bar.addPlayer(viewer);
    }
    private static float progress(float health, float max) { return max <= 0 ? 0 : Math.max(0, Math.min(1, health / max)); }

    public static void disconnected(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        if (b == null) return;
        var human = b.humans.remove(player.getUUID());
        if (human != null) human.slots.forEach(ServerBossEvent::removeAllPlayers);
        var target = b.targets.remove(player.getUUID());
        if (target != null) target.bar.removeAllPlayers();
    }

    public static void close(MinecraftServer server) {
        var b = BINDINGS.remove(server);
        if (b == null) return;
        b.humans.values().forEach(bars -> bars.slots.forEach(ServerBossEvent::removeAllPlayers));
        b.targets.values().forEach(bar -> bar.bar.removeAllPlayers());
        b.attackers.clear();
    }
}
