package dev.hardrush.death;

import dev.hardrush.celebration.CelebrationRuntime;
import dev.hardrush.combat.CombatDepartures;
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
import net.minecraft.world.entity.LivingEntity;

/**
 * Death messages that say who was behind a monster (docs/plan/死亡消息.md). Vanilla still chooses every sentence and
 * translation key; while it builds one, the names of monster bodies, their companions and bag creatures are rewritten
 * ({@link #displayName}), so "被骷髅射杀" reads "被化身骷髅的Bob射杀" and the assisted-fall and ".player" variants follow
 * the same credit the revival totems and records use. Three sentences are replaced whole: a creeper body's own
 * detonation, a departure mid-fight, and a revenge.
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

    /** A death message being built: names are rewritten, and {@code victim}'s vanilla kill credit is {@code credited}. */
    private record Naming(MinecraftServer server, LivingEntity victim, LivingEntity credited) {}
    /** What a death settled before vanilla decided whether to show it. */
    private record Dying(Optional<CombatOrigin> credit, Optional<UUID> avenger) {}

    private static final Map<MinecraftServer, Access> INSTANCES = new IdentityHashMap<>();
    private static final Map<MinecraftServer, RevengeLedger> LEDGERS = new IdentityHashMap<>();
    private static final ThreadLocal<Naming> NAMING = new ThreadLocal<>();
    // Main thread only; an entry lives from the head of ServerPlayer.die to its return.
    private static final Map<ServerPlayer, Dying> DYING = new IdentityHashMap<>();

    private DeathNarration() {}

    public static void configure(MinecraftServer server, Access access) { INSTANCES.put(server, Objects.requireNonNull(access)); }
    public static void unconfigure(MinecraftServer server) { INSTANCES.remove(server); LEDGERS.remove(server); }

    /**
     * The head of {@code ServerPlayer.die}: resolves the kill credit and books the revenge ledger whether or not the
     * {@code showDeathMessages} rule lets vanilla build a message.
     */
    public static void dying(ServerPlayer victim, DamageSource source) {
        var credit = CombatRuntime.peekKillCredit(victim.level(), victim, source);
        DYING.put(victim, new Dying(credit, settle(victim, credit)));
    }

    /** The return of {@code ServerPlayer.die}. */
    public static void died(ServerPlayer victim) { DYING.remove(victim); }

    /** {@code ServerPlayer.die}'s death message, built by {@code vanilla} under monster naming. */
    public static Component deathMessage(ServerPlayer victim, DamageSource source, Supplier<Component> vanilla) {
        var server = victim.level().getServer();
        var dying = DYING.get(victim);
        var credit = dying == null ? CombatRuntime.peekKillCredit(victim.level(), victim, source) : dying.credit();
        return naming(server, victim, credit, () -> {
            // The igniter's own blast is the creeper's end, as vanilla words a creeper that blew up.
            if (MonsterCreeper.detonating(victim)) return Component.translatable("death.attack.explosion", victim.getDisplayName());
            // Before a revenge: leaving is what the others need to hear about, and what explains the death.
            if (CombatDepartures.departing(victim)) return departed(server, victim, credit);
            if (dying != null && dying.avenger().isPresent()) return revenge(server, dying.avenger().get(), victim, source);
            return vanilla.get();
        });
    }

    /**
     * A revival totem has just saved {@code player} in place from {@code source}, credited to {@code credit}: everybody
     * hears how close it was. The would-be killer is named as the death message would have named it.
     */
    public static void savedInPlace(ServerPlayer player, DamageSource source, Optional<CombatOrigin> credit) {
        var server = player.level().getServer();
        var line = naming(server, player, credit, () -> {
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
     * {@code entity.getKillCredit()} while its death message is built, or null for vanilla's own. Vanilla prefers the
     * last human who hit it over a later monster body, which is no player to vanilla; the round's credit decides
     * instead, so the sentence names whoever the revival totems and records credit.
     */
    public static LivingEntity killCredit(LivingEntity entity) {
        var naming = NAMING.get();
        return naming != null && naming.victim == entity ? naming.credited : null;
    }

    /**
     * The name {@code entity} goes by in a death message being built right now, or null for vanilla's own: a monster
     * body is "化身{形态}的{玩家}", a bag creature "{主人}收编的{生物}", a companion "{主人}召唤的{生物}".
     */
    public static Component displayName(Entity entity) {
        var naming = NAMING.get();
        if (naming == null || entity == null) return null;
        var server = naming.server;
        if (entity instanceof ServerPlayer player) {
            var form = MonsterRoles.currentForm(player);
            return form == null ? null : monster(form.type, player.getUUID(), player.getName().getString());
        }
        if (MonsterRoles.isNativeBody(entity)) return monster(entity.getType(), entity.getUUID(), name(server, entity.getUUID()));
        // A bag creature that follows its releaser is a companion too; the bag is what it came from.
        var releaser = MonsterSoulcatcher.releaser(entity);
        if (releaser.isPresent()) return owned(server, releaser.get(), "收编的", entity);
        var summoner = MonsterCompanions.owner(entity);
        return summoner == null ? null : owned(server, summoner, "召唤的", entity);
    }

    private static <T> T naming(MinecraftServer server, LivingEntity victim, Optional<CombatOrigin> credit, Supplier<T> body) {
        // An offline credited player has no entity to name; vanilla's own credit stands in.
        var credited = credit.map(origin -> (LivingEntity) server.getPlayerList().getPlayer(origin.actor().playerId())).orElse(null);
        var outer = NAMING.get();
        NAMING.set(new Naming(server, victim, credited));
        try { return body.get(); }
        finally { if (outer == null) NAMING.remove(); else NAMING.set(outer); }
    }

    /** "Alice在与化身骷髅的Bob交战时断线，按阵亡处理", in place of the {@code genericKill} sentence a departure would get. */
    private static Component departed(MinecraftServer server, ServerPlayer victim, Optional<CombatOrigin> credit) {
        LivingEntity killer = victim.getKillCredit();
        Component name = killer != null && killer != victim ? killer.getDisplayName()
                : credit.map(origin -> (Component) Component.literal(name(server, origin.actor().playerId()))).orElse(null);
        var line = Component.empty().append(victim.getDisplayName());
        return name == null ? line.append("在交战中断线，按阵亡处理")
                : line.append("在与").append(name).append("交战时断线，按阵亡处理");
    }

    /** Books {@code victim}'s competitive human death in the {@link RevengeLedger}; the avenger, if this death settles a debt. */
    private static Optional<UUID> settle(ServerPlayer victim, Optional<CombatOrigin> credit) {
        var server = victim.level().getServer();
        var access = INSTANCES.get(server);
        var round = access == null ? null : access.snapshot().orElse(null);
        if (round == null || CelebrationRuntime.successful(round) || !RoundWorldBundle.owns(victim.level())) return Optional.empty();
        var participant = round.participants().get(victim.getUUID());
        if (participant == null || participant.role() != ParticipantRole.HUMAN_ACTIVE) return Optional.empty();
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
