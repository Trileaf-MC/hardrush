package dev.hardrush.monster;

import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.round.RoundSession;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.world.RoundWorldBundle;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.LoggerFactory;

/** Formal body admission and durable skill reservations. All callbacks run on the server thread. */
public final class MonsterRoundRuntime {
    private record Binding(RoundSession session, PlayerProfiles profiles,
                           Supplier<RoundWorldBundle> worlds, Set<UUID> casting) {}
    private static final Map<MinecraftServer, Binding> BINDINGS = new IdentityHashMap<>();
    private MonsterRoundRuntime() {}

    public static void configure(MinecraftServer server, RoundSession session, PlayerProfiles profiles,
                                 Supplier<RoundWorldBundle> worlds) {
        BINDINGS.put(server, new Binding(session, profiles, worlds, new HashSet<>()));
    }
    public static void unconfigure(MinecraftServer server) { BINDINGS.remove(server); }
    public static Optional<RulesConfig> rules(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        return b == null ? Optional.empty() : b.session.snapshot()
                .filter(r -> r.participants().containsKey(player.getUUID())).map(RoundSnapshot::rules);
    }
    public static String identity(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        var r = b == null ? null : b.session.snapshot().orElse(null);
        var p = r == null ? null : r.participants().get(player.getUUID());
        return p == null ? "prototype:" + player.getUUID() : r.roundId() + ":" + p.lifeSequence() + ":" + p.monsterDeploymentSequence();
    }
    public static boolean canAct(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        var r = b == null ? null : b.session.snapshot().orElse(null);
        if (r == null || !r.participants().containsKey(player.getUUID())) return true;
        var p = r.participants().get(player.getUUID());
        var worlds = b.worlds.get();
        // The winner's celebration keeps a gathered monster's form and skills; nothing it does is recorded.
        if (dev.hardrush.celebration.CelebrationRuntime.arena(player)) {
            return p.role() == ParticipantRole.MONSTER_DEPLOYED && player.isAlive() && !player.isSpectator()
                    && !PlayerProfiles.isLocked(player) && b.profiles.isReady(p.id(), r)
                    && player.level().getServer().getPlayerList().getPlayer(p.id()) == player;
        }
        return b.session.state() == RoundSession.State.OPEN && !b.session.competitionLocked()
                && r.phase() == RoundPhase.RUNNING && p.role() == ParticipantRole.MONSTER_DEPLOYED && p.online()
                && player.isAlive() && !player.isSpectator() && !PlayerProfiles.isLocked(player) && b.profiles.isReady(p.id(), r)
                && player.level().getServer().getPlayerList().getPlayer(p.id()) == player
                && worlds != null && worlds.ready()
                && (player.level() == worlds.level(Level.OVERWORLD) || player.level() == worlds.level(Level.NETHER)
                    || player.level() == worlds.level(Level.END));
    }
    public static boolean canSelect(ServerPlayer player, String kind) {
        var b = BINDINGS.get(player.level().getServer());
        var r = b == null ? null : b.session.snapshot().orElse(null);
        var p = r == null ? null : r.participants().get(player.getUUID());
        return p == null || canAct(player) && p.monsterRole().name().equalsIgnoreCase(kind);
    }
    public static long remaining(ServerPlayer player, MonsterSkill skill) {
        var b = BINDINGS.get(player.level().getServer());
        var r = b == null ? null : b.session.snapshot().orElse(null);
        var p = r == null ? null : r.participants().get(player.getUUID());
        if (p != null && dev.hardrush.celebration.CelebrationRuntime.arena(player)) {
            return dev.hardrush.celebration.CelebrationRuntime.skillRemaining(player, skill);
        }
        return p == null ? -1 : p.monsterSkillRemainingMillis(skill, System.currentTimeMillis());
    }
    public static boolean casting(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        return b != null && b.casting.contains(player.getUUID());
    }
    /** Empty means standalone development tooling. A present result means the formal request was handled. */
    public static Optional<Boolean> cast(ServerPlayer player, MonsterSkill skill, BooleanSupplier effect) {
        var server = player.level().getServer();
        var b = BINDINGS.get(server);
        var r = b == null ? null : b.session.snapshot().orElse(null);
        var p = r == null ? null : r.participants().get(player.getUUID());
        if (p == null) return Optional.empty();
        if (dev.hardrush.celebration.CelebrationRuntime.arena(player)) return Optional.of(castInCelebration(player, skill, effect));
        if (p.monsterSkillRemainingMillis(skill, System.currentTimeMillis()) > 0) return Optional.of(false);
        if (!canAct(player) || !b.casting.add(p.id())) return Optional.of(false);
        long now = System.currentTimeMillis();
        long previous = p.monsterSkillReadyAtMillis().getOrDefault(skill, 0L);
        var level = player.level();
        b.session.requestMonsterSkill(r.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(), skill, now, reply -> {
            if (BINDINGS.get(server) != b) return;
            if (!reply.success()) {
                b.casting.remove(p.id());
                tell(player, reply.message()); return;
            }
            var latest = b.session.snapshot().orElseThrow();
            var identity = latest.participants().get(p.id());
            // Death, return, replacement, dimension change or a new round interrupts an admitted cast.
            if (!canAct(player) || !latest.roundId().equals(r.roundId()) || identity == null
                    || identity.lifeSequence() != p.lifeSequence()
                    || identity.monsterDeploymentSequence() != p.monsterDeploymentSequence()
                    || identity.monsterRole() != p.monsterRole()) {
                b.casting.remove(p.id()); return;
            }
            try {
                if (player.level() == level && effect.getAsBoolean()) { b.casting.remove(p.id()); return; }
            } catch (RuntimeException failure) {
                // An unknown partially-applied effect cannot safely be replayed or refunded.
                b.casting.remove(p.id());
                LoggerFactory.getLogger(MonsterRoundRuntime.class).error("Monster skill effect failed for " + p.id() + "/" + skill.id(), failure);
                tell(player, "技能执行异常，已记录日志并保留冷却，请联系管理员。"); return;
            }
            // A prepared effect whose safe positions changed must not spend a skill cooldown.
            long consumed = identity.monsterSkillReadyAtMillis().getOrDefault(skill, 0L);
            b.session.requestMonsterSkillCancelled(r.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(),
                    skill, consumed, previous, cancelled -> {
                        b.casting.remove(p.id());
                        tell(player, cancelled.success() ? "安全条件已改变，技能未施放且保留冷却机会。" : cancelled.message());
                    });
        });
        return Optional.of(true);
    }
    /** The round is already settled: run the effect directly with an in-memory cooldown, never a durable reservation. */
    private static boolean castInCelebration(ServerPlayer player, MonsterSkill skill, BooleanSupplier effect) {
        if (dev.hardrush.celebration.CelebrationRuntime.skillRemaining(player, skill) > 0 || !canAct(player)) return false;
        try {
            if (!effect.getAsBoolean()) return false;
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(MonsterRoundRuntime.class).error("Celebration monster skill failed for " + player.getUUID() + "/" + skill.id(), failure);
            tell(player, "技能执行异常，已记录日志。"); return false;
        }
        dev.hardrush.celebration.CelebrationRuntime.consumeSkill(player, skill);
        return true;
    }
    private static void tell(ServerPlayer player, String text) { player.sendSystemMessage(Component.literal(text)); }
}
