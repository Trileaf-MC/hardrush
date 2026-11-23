package dev.hardrush.bounty;

import dev.hardrush.core.round.Bounty;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.notice.Notices;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.player.mixin.PlayerAdvancementProfileAccessor;
import dev.hardrush.round.RoundSession;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * The bounty board's live side: native advancements feed each human life's threat, the TAB footer shows a
 * human its own stars, and the turning points of the board are told on the action bar. Notice state is kept in
 * memory only; after a restart the current board is the baseline and nothing is told late.
 */
public final class BountyRuntime {
    private static final int RECONCILE_TICKS = 100;
    private static final long TIER_NOTICE_MILLIS = 30_000;
    private static final long LEADER_NOTICE_MILLIS = 60_000;

    /** What one human life was last told. */
    private static final class Seen {
        final int life;
        int stars;
        boolean leader;
        int kills;
        int milestones;
        final Set<Integer> lowTiersTold = new HashSet<>();
        final Map<Integer, Long> toldAt = new HashMap<>();
        Seen(int life) { this.life = life; }
    }

    private static final class Binding {
        final RoundSession session;
        UUID round;
        final Map<UUID, Seen> seen = new HashMap<>();
        Set<UUID> leaders;
        final Map<UUID, Long> leaderToldAt = new HashMap<>();
        Binding(RoundSession session) { this.session = session; }
    }

    private static final Map<MinecraftServer, Binding> BINDINGS = new IdentityHashMap<>();

    private BountyRuntime() {}

    public static void configure(MinecraftServer server, RoundSession session) { BINDINGS.put(server, new Binding(session)); }
    public static void close(MinecraftServer server) { BINDINGS.remove(server); }

    /** Called at native award RETURN; only the completing criterion of a scored milestone reaches the session. */
    public static void advancementAwarded(ServerPlayer player, AdvancementHolder holder, boolean changed) {
        if (!changed || !Bounty.MILESTONES.containsKey(holder.id().toString())
                || !player.getAdvancements().getOrStartProgress(holder).isDone()) return;
        report(player, holder.id().toString());
    }

    private static void report(ServerPlayer player, String advancement) {
        var binding = BINDINGS.get(player.level().getServer());
        if (binding == null || PlayerProfiles.isLocked(player) || !player.isAlive()) return;
        var round = binding.session.view().orElse(null);
        var participant = round == null ? null : round.participants().get(player.getUUID());
        if (participant == null || !participant.online() || participant.role() != ParticipantRole.HUMAN_ACTIVE) return;
        binding.session.threatMilestone(round.roundId(), player.getUUID(), participant.lifeSequence(), advancement);
    }

    public static void tick(MinecraftServer server, RoundSnapshot round) {
        var binding = BINDINGS.get(server);
        if (binding == null) return;
        if (!round.roundId().equals(binding.round)) {
            binding.round = round.roundId(); binding.seen.clear(); binding.leaders = null; binding.leaderToldAt.clear();
        }
        if (!Bounty.enabled(round.rules()) || round.phase() != RoundPhase.RUNNING && round.phase() != RoundPhase.RESCUE) return;
        // A milestone event lost to a crash or a refused write is found again on the player's own progress.
        if (server.getTickCount() % RECONCILE_TICKS == 0) reconcile(server);
        var standings = Bounty.standings(round.rules(), round.participants());
        long now = System.nanoTime() / 1_000_000;
        tellHumans(server, round, binding, standings, now);
        tellCamp(server, round, binding, standings, now);
    }

    private static void reconcile(MinecraftServer server) {
        for (var player : server.getPlayerList().getPlayers()) {
            var progress = ((PlayerAdvancementProfileAccessor) player.getAdvancements()).hardrush$progress();
            progress.forEach((holder, done) -> {
                if (done.isDone() && Bounty.MILESTONES.containsKey(holder.id().toString())) report(player, holder.id().toString());
            });
        }
    }

    private static void tellHumans(MinecraftServer server, RoundSnapshot round, Binding binding,
                                   Map<UUID, Bounty.Standing> standings, long now) {
        binding.seen.keySet().removeIf(id -> !standings.containsKey(id));
        standings.forEach((id, standing) -> {
            var participant = round.participants().get(id);
            var threat = participant.threat().forLife(participant.lifeSequence());
            var seen = binding.seen.get(id);
            if (seen == null || seen.life != participant.lifeSequence()) {
                // A new life, or a restart: the board as it stands is the baseline.
                seen = new Seen(participant.lifeSequence());
                seen.stars = standing.stars(); seen.leader = standing.leader();
                seen.kills = threat.monsterKills(); seen.milestones = threat.milestones().size();
                binding.seen.put(id, seen);
                return;
            }
            var player = server.getPlayerList().getPlayer(id);
            boolean listening = player != null && participant.online() && !PlayerProfiles.isLocked(player);
            String reason = threat.monsterKills() > seen.kills ? "击杀了怪物 · " : threat.milestones().size() > seen.milestones ? "取得新进度 · " : "";
            if (listening) {
                if (standing.leader() && !seen.leader) {
                    Notices.hint(player, "你成为了" + Notices.key("头号悬赏") + "，怪物会优先盯上你");
                    player.connection.send(new ClientboundSoundPacket(net.minecraft.core.Holder.direct(SoundEvents.APPLY_EFFECT_BAD_OMEN), SoundSource.PLAYERS,
                            player.getX(), player.getY(), player.getZ(), 1, 1, player.getRandom().nextLong()));
                } else if (!standing.leader() && seen.leader) {
                    Notices.hint(player, "你不再是头号悬赏");
                } else if (standing.stars() > seen.stars && tierWorthTelling(seen, standing.stars(), now)) {
                    Notices.hint(player, reason + "悬赏升至 " + Notices.key(Bounty.starText(standing.stars())));
                }
            }
            seen.stars = standing.stars(); seen.leader = standing.leader();
            seen.kills = threat.monsterKills(); seen.milestones = threat.milestones().size();
        });
    }

    /** Four and five stars are told on every entry; two and three once per life. Any tier waits 30 seconds to repeat. */
    private static boolean tierWorthTelling(Seen seen, int stars, long now) {
        if (stars < 2) return false;
        if (stars <= 3 && !seen.lowTiersTold.add(stars)) return false;
        Long last = seen.toldAt.get(stars);
        if (last != null && now - last < TIER_NOTICE_MILLIS) return false;
        seen.toldAt.put(stars, now);
        return true;
    }

    /** A new top bounty is announced to the monsters waiting in the camp, with the raid horn. */
    private static void tellCamp(MinecraftServer server, RoundSnapshot round, Binding binding,
                                 Map<UUID, Bounty.Standing> standings, long now) {
        var leaders = new HashSet<UUID>();
        standings.forEach((id, standing) -> { if (standing.leader()) leaders.add(id); });
        var previous = binding.leaders;
        binding.leaders = leaders;
        if (previous == null) return;
        for (UUID leader : leaders) {
            if (previous.contains(leader)) continue;
            Long last = binding.leaderToldAt.get(leader);
            if (last != null && now - last < LEADER_NOTICE_MILLIS) continue;
            binding.leaderToldAt.put(leader, now);
            String name = round.participants().get(leader).name();
            for (var monster : server.getPlayerList().getPlayers()) {
                var identity = round.participants().get(monster.getUUID());
                if (identity == null || identity.role() != ParticipantRole.MONSTER_LOBBY || !identity.online()) continue;
                Notices.hint(monster, Notices.key(name) + " 成为头号悬赏");
                monster.connection.send(new ClientboundSoundPacket(SoundEvents.RAID_HORN, SoundSource.NEUTRAL,
                        monster.getX(), monster.getY(), monster.getZ(), .6f, 1, monster.getRandom().nextLong()));
            }
        }
    }

    /** The TAB footer line of a human: its own stars only. Empty for anyone else or a round without bounties. */
    public static Optional<String> tabLine(ServerPlayer player, RoundSnapshot round) {
        if (!Bounty.enabled(round.rules())) return Optional.empty();
        var participant = round.participants().get(player.getUUID());
        if (participant == null || participant.role() != ParticipantRole.HUMAN_ACTIVE) return Optional.empty();
        var standing = Bounty.standing(round.rules(), round.participants(), player.getUUID());
        return Optional.of("你的悬赏 " + Bounty.starText(standing.stars()) + (standing.leader() ? " · 头号悬赏" : ""));
    }
}
