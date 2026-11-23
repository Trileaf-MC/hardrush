package dev.hardrush.death;

import dev.hardrush.celebration.CelebrationRuntime;
import dev.hardrush.combat.CombatRuntime;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.combat.RevengeLedger;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.monster.MonsterCompanions;
import dev.hardrush.monster.MonsterCreeper;
import dev.hardrush.monster.MonsterForm;
import dev.hardrush.monster.MonsterRoles;
import dev.hardrush.monster.MonsterSoulcatcher;
import dev.hardrush.world.RoundWorldBundle;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Death messages that say who was behind a monster (docs/plan/死亡消息.md). Vanilla still chooses every sentence and
 * translation key; while it builds one, the names of monster bodies, their companions and bag creatures are rewritten
 * ({@link #displayName}), so "被骷髅射杀" reads "被化身骷髅的Bob射杀" and the assisted-fall and ".player" variants follow
 * the same credit the revival totems and records use. Two sentences are replaced whole: a creeper body's own
 * detonation, and a revenge.
 *
 * <p>Main thread only. Revenge debts live in memory and are lost on a restart: they are flavour, never fairness.</p>
 *
 * TODO: [Refactor] The debt is booked when the message is built, before the round session accepts the death; a death
 * the session then refuses (a locked competition, a sealed terminal batch) still moves the ledger. Booking it from the
 * committed death batch would need the narration to wait for the commit, which vanilla's synchronous die() cannot.
 */
public final class DeathNarration {
    /** The round as death messages see it. */
    public interface Access { Optional<RoundSnapshot> snapshot(); }

    private static final Map<MinecraftServer, Access> INSTANCES = new IdentityHashMap<>();
    private static final Map<MinecraftServer, RevengeLedger> LEDGERS = new IdentityHashMap<>();
    private static final ThreadLocal<MinecraftServer> NAMING = new ThreadLocal<>();

    private DeathNarration() {}

    public static void configure(MinecraftServer server, Access access) { INSTANCES.put(server, Objects.requireNonNull(access)); }
    public static void unconfigure(MinecraftServer server) { INSTANCES.remove(server); LEDGERS.remove(server); }

    /** {@code ServerPlayer.die}'s death message, built by {@code vanilla} under monster naming. */
    public static Component deathMessage(ServerPlayer victim, DamageSource source, Supplier<Component> vanilla) {
        var server = victim.level().getServer();
        return naming(server, () -> {
            // The igniter's own blast is the creeper's end, as vanilla words a creeper that blew up.
            if (MonsterCreeper.detonating(victim)) return Component.translatable("death.attack.explosion", victim.getDisplayName());
            var avenger = settle(victim, source);
            if (avenger.isPresent()) return revenge(server, avenger.get(), victim, source);
            return vanilla.get();
        });
    }

    /**
     * A revival totem has just saved {@code player} in place from {@code source}: everybody hears how close it was. The
     * killer is named as the death message would have named it: the lethal hit's attacker, else the vanilla kill credit.
     */
    public static void savedInPlace(ServerPlayer player, DamageSource source) {
        var server = player.level().getServer();
        var line = naming(server, () -> {
            Entity killer = source.getEntity() != null ? source.getEntity()
                    : source.getDirectEntity() != null ? source.getDirectEntity() : player.getKillCredit();
            if (killer == player) killer = null;
            return killer == null
                    ? Component.empty().append(player.getDisplayName()).append("险些丧命，靠复生图腾挺了过来")
                    : Component.empty().append(player.getDisplayName()).append("险些被").append(killer.getDisplayName())
                            .append("杀死，靠复生图腾挺了过来");
        });
        server.getPlayerList().broadcastSystemMessage(line, false);
    }

    /**
     * The name {@code entity} goes by in a death message being built right now, or null for vanilla's own: a monster
     * body is "化身{形态}的{玩家}", a companion "{主人}召唤的{生物}", a bag creature "{主人}收编的{生物}".
     */
    public static Component displayName(Entity entity) {
        var server = NAMING.get();
        if (server == null || entity == null) return null;
        if (entity instanceof ServerPlayer player) {
            var form = MonsterRoles.currentForm(player);
            return form == null ? null : monster(form.type, player.getUUID(), player.getName().getString());
        }
        if (MonsterRoles.isNativeBody(entity)) return monster(entity.getType(), entity.getUUID(), name(server, entity.getUUID()));
        var summoner = MonsterCompanions.owner(entity);
        if (summoner != null) return owned(server, summoner, "召唤的", entity);
        var releaser = MonsterSoulcatcher.releaser(entity);
        return releaser.map(owner -> owned(server, owner, "收编的", entity)).orElse(null);
    }

    private static <T> T naming(MinecraftServer server, Supplier<T> body) {
        var outer = NAMING.get();
        NAMING.set(server);
        try { return body.get(); }
        finally { if (outer == null) NAMING.remove(); else NAMING.set(outer); }
    }

    /** Books {@code victim}'s competitive human death in the {@link RevengeLedger}; the avenger, if this death settles a debt. */
    private static Optional<UUID> settle(ServerPlayer victim, DamageSource source) {
        var server = victim.level().getServer();
        var access = INSTANCES.get(server);
        var round = access == null ? null : access.snapshot().orElse(null);
        if (round == null || CelebrationRuntime.successful(round) || !RoundWorldBundle.owns(victim.level())) return Optional.empty();
        var participant = round.participants().get(victim.getUUID());
        if (participant == null || participant.role() != ParticipantRole.HUMAN_ACTIVE) return Optional.empty();
        Optional<CombatOrigin> credit = CombatRuntime.peekKillCredit(victim.level(), victim, source);
        return LEDGERS.computeIfAbsent(server, ignored -> new RevengeLedger()).humanDied(round.roundId(), victim.getUUID(), credit);
    }

    /** "化身骷髅的Bob终于杀死了Alice：“这次我要夺回失去的一切！”", in place of this death's vanilla sentence. */
    private static Component revenge(MinecraftServer server, UUID avenger, ServerPlayer victim, DamageSource source) {
        Entity body = MonsterRoles.isNativeBody(source.getEntity()) && avenger.equals(source.getEntity().getUUID())
                ? source.getEntity() : null;
        Component name;
        if (body != null) name = body.getDisplayName();
        else {
            // A companion, a bag creature or a lingering cloud finished it: the avenger as it stands now.
            MonsterForm form = MonsterRoles.form(avenger);
            name = form == null ? Component.literal(name(server, avenger))
                    : monster(form.type, avenger, name(server, avenger));
        }
        return Component.empty().append(name).append("终于杀死了").append(victim.getDisplayName())
                .append("：“这次我要夺回失去的一切！”");
    }

    private static MutableComponent monster(EntityType<?> type, UUID playerId, String playerName) {
        var shown = Component.literal(playerName);
        return Component.literal("化身").append(type.getDescription()).append("的").append(shown)
                .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowEntity(
                        new HoverEvent.EntityTooltipInfo(type, playerId, shown))).withInsertion(playerName));
    }

    private static MutableComponent owned(MinecraftServer server, UUID owner, String relation, Entity entity) {
        return Component.literal(name(server, owner) + relation).append(entity.getName())
                .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowEntity(
                        new HoverEvent.EntityTooltipInfo(entity.getType(), entity.getUUID(), entity.getName()))));
    }

    /** A player's name, online or not: the round remembers every participant's. */
    private static String name(MinecraftServer server, UUID playerId) {
        var online = server.getPlayerList().getPlayer(playerId);
        if (online != null) return online.getName().getString();
        var access = INSTANCES.get(server);
        return Optional.ofNullable(access).flatMap(Access::snapshot)
                .map(round -> round.participants().get(playerId)).map(participant -> participant.name())
                .orElse("某位玩家");
    }
}
