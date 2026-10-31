package dev.hardrush.pact;

import dev.hardrush.core.combat.DuskPact;
import dev.hardrush.core.lobby.LobbyHints;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.monster.MonsterRoles;
import dev.hardrush.combat.CombatRuntime;
import dev.hardrush.lobby.LobbyInteractions;
import dev.hardrush.combat.MonsterCombatRuntime;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.gamerules.GameRules;

/** Server-only grace policy and native UI. Protection is global to the round, independent of each viewer. */
public final class DuskPactRuntime {
    public interface Access {
        Optional<RoundSnapshot> snapshot();
        ServerLevel overworld();
        DuskPactData data();
        boolean formalLevel(ServerLevel level);
        default boolean locked() { return false; }
    }
    private static final class Binding {
        final Access access;
        final ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), Component.empty(), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
        final Map<ServerPlayer, Long> viewers = new IdentityHashMap<>();
        final Map<UUID, Long> blockedAt = new HashMap<>();
        final Set<UUID> introduced = new HashSet<>();
        UUID roundId;
        String tab;
        boolean wasProtected;
        Binding(Access access) { this.access = access; }
    }
    private static final Map<MinecraftServer, Binding> BINDINGS = new IdentityHashMap<>();
    private DuskPactRuntime() {}

    public static void configure(MinecraftServer server, Access access) { BINDINGS.put(server, new Binding(access)); }
    public static void close(MinecraftServer server) {
        var binding = BINDINGS.remove(server);
        if (binding != null) binding.bar.removeAllPlayers();
    }
    private static boolean challenge(RoundSnapshot round) {
        return round != null && (round.phase() == RoundPhase.RUNNING || round.phase() == RoundPhase.RESCUE);
    }
    private static boolean protectedNow(Binding binding) {
        var access = binding.access;
        var round = access.snapshot().orElse(null);
        var level = access.overworld();
        if (!challenge(round) || level == null) return false;
        var clock = level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
        var data = access.data();
        if (!data.started()) {
            // World preparation/waiting must not spend the first day before anyone can play.
            level.clockManager().setTotalTicks(clock, 0);
            data.start();
        }
        return data.protectedAt(level.clockManager().getInstance(clock).totalTicks());
    }
    private static DuskPact.Display display(Binding binding, MinecraftServer server) {
        var level = binding.access.overworld();
        var clock = level.clockManager().getInstance(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD));
        return DuskPact.display(clock.totalTicks(), clock.partialTick(), clock.rate(), server.tickRateManager().tickrate(),
                clock.isPaused() || !level.getGameRules().get(GameRules.ADVANCE_TIME) || server.tickRateManager().isFrozen());
    }
    /** Countdown line for the native TAB footer while the contract is shown; the bossbar stays hidden until the warning. */
    public static Optional<String> tabLine(MinecraftServer server) {
        var binding = BINDINGS.get(server);
        return binding == null ? Optional.empty() : Optional.ofNullable(binding.tab);
    }
    private static UUID actor(Entity entity) { return actor(entity, 0); }
    private static UUID actor(Entity entity, int depth) {
        if (entity == null || depth > 8) return null;
        var captured = CombatRuntime.originOf(entity);
        if (captured.isPresent()) return captured.get().actor().playerId();
        var monster = MonsterCombatRuntime.source(entity);
        if (monster.isPresent()) return monster.get().ownerId();
        if (entity instanceof ServerPlayer player) return player.getUUID();
        if (entity instanceof Projectile projectile && projectile.getOwner() != entity) {
            UUID owner = actor(projectile.getOwner(), depth + 1);
            if (owner != null) return owner;
        }
        if (entity instanceof OwnableEntity owned && owned.getRootOwner() instanceof ServerPlayer player) return player.getUUID();
        var reference = entity instanceof PactOwnerReference source ? source.hardrush$pactOwner()
                : entity instanceof OwnableEntity owned ? owned.getOwnerReference() : null;
        if (reference != null && entity.level() instanceof ServerLevel level) {
            var binding = BINDINGS.get(level.getServer());
            if (binding != null && binding.access.snapshot().filter(r -> r.participants().containsKey(reference.getUUID())).isPresent())
                return reference.getUUID();
        }
        return null;
    }
    private static UUID victim(Entity target) {
        return target instanceof ServerPlayer player ? player.getUUID() : MonsterRoles.isNativeBody(target) ? actor(target) : null;
    }
    private static boolean reject(UUID attacker, Entity target) {
        var victim = victim(target);
        if (attacker == null || victim == null || attacker.equals(victim) || !(target.level() instanceof ServerLevel level)) return false;
        var binding = BINDINGS.get(level.getServer());
        if (binding == null || !binding.access.formalLevel(level) || !protectedNow(binding)) return false;
        var round = binding.access.snapshot().orElseThrow();
        if (!round.participants().containsKey(victim)) return false;
        var player = level.getServer().getPlayerList().getPlayer(attacker);
        if (player != null) hint(binding, player);
        return true;
    }
    public static boolean reject(Entity source, Entity target) {
        UUID attacker = actor(source);
        if (attacker == null && source == null && target.level() instanceof ServerLevel level) {
            attacker = CombatRuntime.currentOrigin(level).map(origin -> origin.actor().playerId()).orElse(null);
        }
        return reject(attacker, target);
    }
    public static boolean rejectDamage(Entity target, DamageSource source) {
        if (!(target.level() instanceof ServerLevel level) || !BINDINGS.containsKey(level.getServer()) || victim(target) == null) return false;
        var origin = CombatRuntime.damageOrigin(level, source);
        UUID attacker = origin.map(value -> value.actor().playerId()).orElseGet(() -> {
            UUID direct = actor(source.getDirectEntity());
            return direct != null ? direct : actor(source.getEntity());
        });
        return reject(attacker, target);
    }
    private static void hint(Binding binding, ServerPlayer player) {
        long now = System.nanoTime() / 1_000_000;
        if (now - binding.blockedAt.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2) < 1_500) return;
        binding.blockedAt.put(player.getUUID(), now);
        var server = player.level().getServer();
        String text = display(binding, server).blocked();
        var router = LobbyInteractions.get(server).orElse(null);
        if (router != null) router.hints(player).offer("dusk-pact", LobbyHints.Priority.TARGET, text, now + 1_500);
        else player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(text)));
    }
    private static void sound(ServerPlayer player, net.minecraft.sounds.SoundEvent sound, float pitch) {
        player.connection.send(new ClientboundSoundPacket(net.minecraft.core.Holder.direct(sound), SoundSource.MASTER,
                player.getX(), player.getY(), player.getZ(), .5F, pitch, player.getRandom().nextLong()));
    }
    private static void title(ServerPlayer player, int fadeIn, int stay, int fadeOut, String title, String subtitle) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(title)));
    }
    public static void tick(MinecraftServer server) {
        var binding = BINDINGS.get(server);
        if (binding == null) return;
        var access = binding.access;
        var round = access.snapshot().orElse(null);
        if (round == null) { binding.tab = null; return; }
        if (!round.roundId().equals(binding.roundId)) {
            binding.bar.removeAllPlayers(); binding.viewers.clear(); binding.blockedAt.clear(); binding.introduced.clear();
            binding.roundId = round.roundId(); binding.wasProtected = false;
        }
        boolean protectedNow = protectedNow(binding);
        var display = protectedNow && !access.locked() ? display(binding, server) : null;
        binding.tab = display == null ? null : display.tab();
        boolean barShown = display != null && display.bar();
        var viewers = server.getPlayerList().getPlayers().stream().filter(player -> {
            var participant = round.participants().get(player.getUUID());
            return participant != null && (participant.role().isActiveHuman() || participant.role().isMonster())
                    && !player.isSpectator() && (access.formalLevel(player.level()) || participant.role().isMonster());
        }).toList();
        for (var old : List.copyOf(binding.bar.getPlayers())) if (!barShown || !viewers.contains(old)) binding.bar.removePlayer(old);
        binding.viewers.keySet().removeIf(player -> server.getPlayerList().getPlayer(player.getUUID()) != player);
        if (display != null) {
            if (barShown) {
                binding.bar.setName(Component.literal(display.text()));
                binding.bar.setColor(BossEvent.BossBarColor.valueOf(display.color().name()));
                binding.bar.setProgress(display.progress());
            }
            for (var player : viewers) {
                if (barShown) binding.bar.addPlayer(player);
                // Wait for the loading screen to close; one entry title per player and round, never on respawn or reconnect.
                if (player.connection.hasClientLoaded() && binding.introduced.add(player.getUUID()))
                    title(player, 10, 80, 20, DuskPact.TITLE, DuskPact.INTRO);
                Long previous = binding.viewers.put(player, display.seconds());
                if (previous != null && display.seconds() < previous && display.advancing()) {
                    if (previous > DuskPact.WARNING_SECONDS && display.seconds() <= DuskPact.WARNING_SECONDS)
                        sound(player, SoundEvents.NOTE_BLOCK_CHIME.value(), 1);
                    if (display.seconds() >= 1 && display.seconds() <= DuskPact.FINAL_SECONDS)
                        sound(player, SoundEvents.NOTE_BLOCK_HAT.value(), display.seconds() <= 3 ? 1.5F : 1);
                }
            }
        } else if (binding.wasProtected && !protectedNow && challenge(round) && access.overworld() != null && access.data().released()) {
            boolean hardcore = round.rules().mode() == dev.hardrush.core.config.GameMode.HARDCORE;
            for (var player : viewers) {
                title(player, 5, 50, 10, DuskPact.RELEASED, DuskPact.releasedSubtitle(hardcore));
                sound(player, SoundEvents.BELL_BLOCK, 1);
            }
        }
        binding.wasProtected = protectedNow;
    }
}
