package dev.hardrush.celebration;

import dev.hardrush.core.round.*;
import dev.hardrush.round.RoundSession;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.world.LobbyWorld;
import dev.hardrush.world.RoundWorldBundle;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Main-thread, in-memory ceremony around the single winner. Everyone online is gathered at the dragon pool as a
 * plain survival player; nothing during it is recorded. A restart rotates instead of resuming it.
 */
public final class CelebrationRuntime {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("hardrush-celebration");
    private static final Map<MinecraftServer, CelebrationRuntime> SERVICES = new IdentityHashMap<>();
    /** Lets the native death animation play before the server replaces the body with a spectator. */
    private static final int SPECTATOR_RESPAWN_DELAY_TICKS = 20;
    private final MinecraftServer server;
    private final RoundSession session;
    private final PlayerProfiles profiles;
    private final Supplier<RoundWorldBundle> worlds;
    private final Map<UUID, ServerPlayer> gathered = new HashMap<>();
    private final Set<UUID> fallen = new HashSet<>();
    private final Map<UUID, Integer> deathTicks = new HashMap<>();
    private final Map<UUID, Map<MonsterSkill, Long>> skillReadyAt = new HashMap<>();
    private UUID roundId;
    private UUID winner;
    private String winnerName;
    private boolean awarded;
    private String endReason;
    private List<Slot> slots;
    private int nextSlot;
    private boolean stopping;

    private record Slot(Vec3 position, float yaw) {}

    private CelebrationRuntime(MinecraftServer server, RoundSession session, PlayerProfiles profiles,
            Supplier<RoundWorldBundle> worlds) {
        this.server = server; this.session = session; this.profiles = profiles; this.worlds = worlds;
    }

    public static void configure(MinecraftServer server, RoundSession session, PlayerProfiles profiles,
            Supplier<RoundWorldBundle> worlds) {
        SERVICES.put(server, new CelebrationRuntime(server, session, profiles, worlds));
    }
    public static void unconfigure(MinecraftServer server) { SERVICES.remove(server); }
    public static void nextRound(MinecraftServer server) {
        var service = SERVICES.get(server);
        if (service != null) service.reset(null);
    }
    public static boolean successful(RoundSnapshot round) {
        return !round.timingUncertain() && round.result() != null && round.result().kind() == RoundResult.Kind.COMPLETED
                && (round.phase() == RoundPhase.SETTLING || round.phase() == RoundPhase.CELEBRATING || round.phase() == RoundPhase.ROTATING);
    }

    public static void start(MinecraftServer server, RoundSnapshot round) {
        var service = SERVICES.get(server);
        if (service != null && successful(round)) service.track(round);
    }

    private void track(RoundSnapshot round) {
        if (round.roundId().equals(roundId)) return;
        reset(round);
    }

    private void reset(RoundSnapshot round) {
        gathered.clear(); fallen.clear(); deathTicks.clear(); skillReadyAt.clear();
        roundId = round == null ? null : round.roundId();
        winner = round == null ? null : round.result().winner();
        var participant = winner == null ? null : round.participants().get(winner);
        winnerName = participant == null ? "胜者" : dev.hardrush.core.book.SafeBookText.literal(participant.name(), 64);
        awarded = false; endReason = null; slots = null; nextSlot = 0;
    }

    /** Returns true once the winner has left the celebration (pool, death or disconnect); the caller then rotates. */
    public static boolean tick(MinecraftServer server, RoundSnapshot round) {
        var service = SERVICES.get(server);
        return service != null && successful(round) && service.tick(round);
    }

    private boolean tick(RoundSnapshot round) {
        track(round);
        if (stopping || round.phase() == RoundPhase.ROTATING) return false;
        if (endReason != null) return true;
        if (winner == null) { requestEnd("胜者待核验"); return true; }
        if (server.getPlayerList().getPlayer(winner) == null) { requestEnd("胜者离线"); return true; }
        var bundle = worlds.get();
        if (bundle == null || !bundle.ready() || !LobbyWorld.open(server).ready()) return false;
        var end = bundle.level(Level.END);
        for (var player : List.copyOf(server.getPlayerList().getPlayers())) {
            try { gather(round, end, player); }
            catch (RuntimeException failure) { session.adapterFailure("庆祝集结失败", failure); return false; }
            if (endReason != null) return true;
        }
        return false;
    }

    private void gather(RoundSnapshot round, ServerLevel end, ServerPlayer player) {
        var id = player.getUUID();
        if (profiles.failure(id).isPresent() || !profiles.isReady(id, round)) return;
        boolean isWinner = id.equals(winner);
        if (gathered.get(id) == player) {
            if (!player.isDeadOrDying()) return;
            if (isWinner) { requestEnd("胜者阵亡"); return; }
            fallen.add(id);
            int diedAt = deathTicks.computeIfAbsent(id, ignored -> server.getTickCount());
            if (server.getTickCount() - diedAt < SPECTATOR_RESPAWN_DELAY_TICKS) return;
            deathTicks.remove(id);
            // Vanilla void damage also reaches spectators, so a body lost below the island returns to the ring.
            var slot = player.level() == end && player.getY() >= end.getMinY()
                    ? new Slot(player.position(), player.getYRot()) : slot(end);
            gathered.put(id, profiles.respawnInCelebration(player, round, end, slot.position(), slot.yaw(), GameType.SPECTATOR));
            LOGGER.info("Fallen {} now spectates the celebration", id);
            return;
        }
        ServerPlayer body;
        if (isWinner) {
            if (player.isDeadOrDying()) body = respawn(round, end, player, GameType.SURVIVAL);
            else if (profiles.realRoundBody(player)) {
                if (player.level() != end) move(end, player);
                body = player;
            } else body = guest(round, end, player, GameType.SURVIVAL);
            if (!awarded) { CelebrationKit.award(body); awarded = true; }
            tell(body, "你是本轮胜者！已获得不可损坏的鞘翅和 16 色烟花，原物资全部保留。跳入龙池即结束庆祝。");
        } else if (fallen.contains(id)) {
            body = guest(round, end, player, GameType.SPECTATOR);
        } else if (profiles.realRoundBody(player)) {
            move(end, player);
            body = player;
            tell(body, "已集结到末地龙池旁，为胜者 " + winnerName + " 庆祝：原版生存，可 PvP 和建造，一切不计战绩。");
        } else {
            body = guest(round, end, player, GameType.SURVIVAL);
            tell(body, "以空手生存身份来到末地龙池旁，为胜者 " + winnerName + " 庆祝：可 PvP 和建造，一切不计战绩。");
        }
        gathered.put(id, body);
        LOGGER.info("Gathered {} for winner {}: {} in {}", id, winner, body.gameMode.getGameModeForPlayer(), body.level().dimension().identifier());
    }

    private void move(ServerLevel end, ServerPlayer player) {
        var slot = slot(end);
        profiles.moveIntoCelebration(player, end, slot.position(), slot.yaw());
    }

    private ServerPlayer guest(RoundSnapshot round, ServerLevel end, ServerPlayer player, GameType mode) {
        var slot = slot(end);
        return profiles.enterCelebrationAsGuest(player, round, end, slot.position(), slot.yaw(), mode);
    }

    private ServerPlayer respawn(RoundSnapshot round, ServerLevel end, ServerPlayer player, GameType mode) {
        var slot = slot(end);
        return profiles.respawnInCelebration(player, round, end, slot.position(), slot.yaw(), mode);
    }

    private Slot slot(ServerLevel end) {
        if (slots == null) slots = ring(end);
        return slots.get(nextSlot++ % slots.size());
    }

    /** Safe island surface around the exit portal, every slot facing the dragon pool. */
    private static List<Slot> ring(ServerLevel end) {
        var result = new ArrayList<Slot>();
        for (int radius : new int[] {8, 11}) for (int i = 0; i < 16; i++) {
            double angle = Math.PI * 2 * i / 16;
            int x = (int) Math.round(Math.cos(angle) * radius), z = (int) Math.round(Math.sin(angle) * radius);
            end.getChunk(x >> 4, z >> 4);
            var feet = new BlockPos(x, end.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (!RoundWorldBundle.safeStanding(end, feet)) continue;
            var position = Vec3.atBottomCenterOf(feet);
            result.add(new Slot(position, (float) Math.toDegrees(Math.atan2(position.x - 0.5, 0.5 - position.z))));
        }
        // The round bundle keeps the native obsidian platform safe even if the island surface was dug away.
        if (result.isEmpty()) result.add(new Slot(Vec3.atBottomCenterOf(ServerLevel.END_SPAWN_POINT), 0));
        return List.copyOf(result);
    }

    private void requestEnd(String reason) {
        if (endReason != null) return;
        endReason = reason;
        LOGGER.info("Celebration of round {} ends: {}", roundId, reason);
        server.getPlayerList().broadcastSystemMessage(Component.literal("庆祝结束（" + reason + "），全员返回独立大厅并换轮。"), false);
    }

    private static void tell(ServerPlayer player, String text) { player.sendSystemMessage(Component.literal(text)); }

    /** The End during a successful celebration: native survival rules, with combat attribution still sealed. */
    public static boolean arena(ServerLevel level) {
        var service = SERVICES.get(level.getServer());
        if (service == null || service.stopping || service.session.state() != RoundSession.State.OPEN) return false;
        var round = service.session.snapshot().orElse(null);
        var bundle = service.worlds.get();
        return round != null && successful(round) && round.phase() != RoundPhase.ROTATING
                && round.roundId().equals(service.roundId) && bundle != null && bundle.ready() && level == bundle.level(Level.END);
    }

    public static boolean arena(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        return service != null && service.gathered.get(player.getUUID()) == player && arena(player.level());
    }

    /** Gathering and celebrating must not award native advancements; the round's progress record is already closed. */
    public static boolean sealsProgress(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        var round = service == null ? null : service.session.snapshot().orElse(null);
        return round != null && successful(round);
    }

    public static boolean winner(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        return service != null && player.getUUID().equals(service.winner) && arena(player);
    }

    public static String winnerName(MinecraftServer server) {
        var service = SERVICES.get(server);
        return service == null || service.winnerName == null ? "胜者" : service.winnerName;
    }

    public static void died(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        if (service == null || service.gathered.get(player.getUUID()) != player) return;
        if (player.getUUID().equals(service.winner)) service.requestEnd("胜者阵亡");
        else {
            service.fallen.add(player.getUUID());
            service.deathTicks.put(player.getUUID(), service.server.getTickCount());
        }
    }

    public static void disconnected(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        if (service == null || service.gathered.get(player.getUUID()) != player) return;
        service.gathered.remove(player.getUUID());
        var round = service.session.snapshot().orElse(null);
        if (player.getUUID().equals(service.winner) && round != null && successful(round) && round.phase() != RoundPhase.ROTATING) {
            service.requestEnd("胜者离线");
        }
    }

    /** Called by native end portal routing. The pool never moves anyone; only the winner's entry ends the ceremony. */
    public static boolean enterExitPortal(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        var round = service == null ? null : service.session.snapshot().orElse(null);
        var bundle = service == null ? null : service.worlds.get();
        if (round == null || !successful(round) || bundle == null || !bundle.ready() || player.level() != bundle.level(Level.END)) return false;
        if (player.getUUID().equals(service.winner) && service.gathered.get(player.getUUID()) == player
                && round.phase() != RoundPhase.ROTATING) service.requestEnd("胜者跳入龙池");
        return true;
    }

    /** A celebration death is replaced by the server; the client's own respawn request must not race it. */
    public static boolean ownsRespawn(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        var round = service == null ? null : service.session.snapshot().orElse(null);
        return round != null && successful(round)
                && (service.gathered.get(player.getUUID()) == player || RoundWorldBundle.owns(player.level()));
    }

    /** Returns true only after every connected body is back in the lobby and its profile work has completed. */
    public static boolean evacuate(MinecraftServer server, RoundSnapshot round) {
        var service = SERVICES.get(server);
        return service == null || !successful(round) || service.evacuate(round);
    }

    private boolean evacuate(RoundSnapshot round) {
        if (stopping || !LobbyWorld.open(server).ready()) return false;
        boolean returned = true;
        for (var player : List.copyOf(server.getPlayerList().getPlayers())) {
            var id = player.getUUID();
            if (profiles.failure(id).isPresent()) continue;
            if (!profiles.isReady(id, round)) { returned = false; continue; }
            if (gathered.get(id) == player || RoundWorldBundle.owns(player.level()) || player.isDeadOrDying()) {
                try { player = profiles.finishCelebration(player, round); }
                catch (RuntimeException failure) { session.adapterFailure("庆祝撤离失败", failure); return false; }
                gathered.remove(id);
                tell(player, "庆祝结束，已返回独立大厅；旧世界安全释放后换轮，下一轮需重新主动进入集结区。");
            }
            if (!profiles.isReady(id, round) || !LobbyWorld.isLobby(player.level())) returned = false;
        }
        return returned;
    }

    /** Celebration casts never touch the durable round: cooldowns start from the committed deadline and stay in memory. */
    public static long skillRemaining(ServerPlayer player, MonsterSkill skill) {
        var service = SERVICES.get(player.level().getServer());
        if (service == null) return 0;
        return Math.max(0, service.skillDeadline(player, skill) - System.currentTimeMillis());
    }

    public static void consumeSkill(ServerPlayer player, MonsterSkill skill) {
        var service = SERVICES.get(player.level().getServer());
        var round = service == null ? null : service.session.snapshot().orElse(null);
        if (round == null) return;
        service.skillReadyAt.computeIfAbsent(player.getUUID(), ignored -> new EnumMap<>(MonsterSkill.class))
                .put(skill, System.currentTimeMillis() + skill.cooldownMillis(round.rules()));
    }

    private long skillDeadline(ServerPlayer player, MonsterSkill skill) {
        var local = skillReadyAt.get(player.getUUID());
        if (local != null && local.containsKey(skill)) return local.get(skill);
        var round = session.snapshot().orElse(null);
        var participant = round == null ? null : round.participants().get(player.getUUID());
        return participant == null ? 0 : participant.monsterSkillReadyAtMillis().getOrDefault(skill, 0L);
    }

    public static void prepareStop(MinecraftServer server) {
        var service = SERVICES.get(server);
        // The ordinary stop path returns every body to the lobby; a restart rotates rather than resuming.
        if (service != null) service.stopping = true;
    }
}
