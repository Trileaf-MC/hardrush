package dev.hardrush.revival;

import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.round.LooseTotem;
import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundDeath;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.core.round.TotemDrop;
import dev.hardrush.notice.Notices;
import dev.hardrush.round.RoundSession;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.component.DamageResistant;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;

/**
 * Revival totems lying in the world (docs/plan/复生图腾掉落.md, 1.1 to 1.3 and 2.3, 2.4): a hardcore human killed by
 * a human drops one at the moment of death; any human of the round may take it. The committed round's
 * {@code looseTotems} registry is the only proof a floor totem may be taken, and a floor totem is known by its drop
 * token alone, never by its entity object: a chunk unload removes the object and a portal replaces it.
 *
 * <p>A drop goes through two states. <em>Pending</em> (memory only): spawned at the death, its batch not committed yet;
 * touching it does nothing and the patrol leaves it alone. <em>Registered</em>: the committed snapshot lists its
 * token; it can be taken once {@link #PICKUP_DELAY_NANOS} of race time have passed since it was registered, until the
 * registry lets it expire. The entity never despawns on its own ({@code setUnlimitedLifetime}): vanilla's lifetime
 * counts entity ticks, which a faster tick rate or a paused race with chunks still simulating would run ahead of the
 * registry's race time, so the patrol alone removes it once its registration is gone or expired. A pending drop
 * whose death committed without registering it (vetoed on commit, a settling or round-ending batch, a failed commit,
 * another round) is removed, and only a registered drop is announced. Registration lives in the snapshot, so it
 * survives a restart or a checkpoint restore together with the world; a pending drop does not, and is swept.
 *
 * <p>Every floor totem refuses native pickup, hoppers and mobs ({@code setNeverPickUp}); a human takes one through a
 * committed {@code TOTEM_PICKUP} first, as camp coins do. Its gold outline comes from a team that exists in packets
 * only ({@value #TEAM}), whose members are the drops' entity UUIDs; the server scoreboard is never written.
 */
public final class TotemDrops {
    /** The packet-only team that gives floor totems their gold glow. */
    public static final String TEAM = "hardrush_totem_drop";
    /**
     * Like the 40 ticks of {@code Inventory.dropAll}, two seconds, but of race time since registration: nobody snatches
     * a drop the instant its owner falls, and the registry it counts from survives a restart or a reload.
     */
    static final long PICKUP_DELAY_NANOS = 2_000_000_000L;
    static final String PICKUP_TIP = "获得复生图腾 · 拿在手上阵亡时原地复生";
    private static final String PICKUP_TAUGHT = "revival_totem_pickup";
    private static final String DROP_TOLD = "revival_totem_drop";

    /** What the floor totems read from and ask of the round runtime. */
    public interface Access {
        /** The committed round projected to now ({@code RoundSession.view}): its registry and race clock decide. */
        Optional<RoundSnapshot> view();
        /** False once the session can no longer commit a pending death (failed, stopping): pending drops are void. */
        boolean open();
        /** {@link RevivalTotems#projectsHuman}: only a human whose inventory shows the balance may take a totem. */
        boolean collects(ServerPlayer player);
        /** Re-projects the taker's stack once the pickup is committed. */
        void refresh(ServerPlayer player);
        /** {@code RoundSession.requestLooseTotemPickup}. */
        void requestPickup(UUID round, UUID token, UUID player, int life, Consumer<RoundSession.Reply> reply);
    }

    /** A drop spawned at a death of {@code victim}'s human life {@code life} whose batch has not committed yet. */
    private record Pending(UUID round, UUID victim, int life) {}

    private static final Map<MinecraftServer, TotemDrops> INSTANCES = new IdentityHashMap<>();
    private final Access access;
    /** In death order, so an announcement names the first victim of its batch. */
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    /** Tokens whose pickup is being committed; a second toucher waits for the answer. */
    private final Set<UUID> claimed = new HashSet<>();
    private final Scoreboard scoreboard = new Scoreboard();
    private PlayerTeam team = goldTeam(scoreboard);
    /** Player to the connection that last received the team: a reconnect or a new round sends it again. */
    private final Map<UUID, Object> informed = new HashMap<>();
    private UUID round;

    private TotemDrops(Access access) { this.access = Objects.requireNonNull(access); }

    public static void configure(MinecraftServer server, Access access) { INSTANCES.put(server, new TotemDrops(access)); }
    public static void unconfigure(MinecraftServer server) { INSTANCES.remove(server); }

    private static PlayerTeam goldTeam(Scoreboard scoreboard) {
        var team = scoreboard.addPlayerTeam(TEAM);
        team.setColor(Optional.of(TeamColor.GOLD));
        return team;
    }

    /**
     * {@code AFTER_DEATH}, before the death is captured: whether this death drops a totem, judged by the same
     * {@link TotemDrop#qualifies} the engine applies on commit against the session's committed round. A death below the
     * world's minimum height (the void) drops nothing. The returned token is pending until {@link #captured}.
     */
    public static Optional<UUID> prepare(ServerPlayer victim, RoundSnapshot round, Optional<CombatOrigin> killCredit) {
        var drops = INSTANCES.get(victim.level().getServer());
        var participant = round.participants().get(victim.getUUID());
        if (drops == null || participant == null || victim.getY() < victim.level().getMinY()) return Optional.empty();
        var death = new RoundDeath(victim.getUUID(), participant.lifeSequence(), participant.monsterDeploymentSequence(),
                Optional.empty(), killCredit, false);
        if (!TotemDrop.qualifies(round, death)) return Optional.empty();
        var token = UUID.randomUUID();
        drops.pending.put(token, new Pending(round.roundId(), victim.getUUID(), participant.lifeSequence()));
        return Optional.of(token);
    }

    /**
     * After {@code captureDeath}: a captured death drops its totem at the body, in the same tick as vanilla's death
     * loot; a death the session did not take drops nothing. A commit that already ran inside the capture has decided.
     */
    public static void captured(ServerPlayer victim, UUID round, UUID token, boolean accepted) {
        var drops = INSTANCES.get(victim.level().getServer());
        if (drops == null) return;
        if (!accepted) { drops.pending.remove(token); return; }
        boolean registered = drops.access.view().filter(r -> r.looseTotems().containsKey(token)).isPresent();
        if (!drops.pending.containsKey(token) && !registered) return;
        drops.member(victim.level().getServer(), spawn(victim.level(), new Vec3(victim.getX(), victim.getEyeY() - 0.3, victim.getZ()), token, round));
    }

    /** The floor totem itself: fireproof, invulnerable, never picked up natively, named and glowing. */
    static ItemEntity spawn(ServerLevel level, Vec3 at, UUID token, UUID round) {
        var stack = RevivalTotems.dropped(token, round);
        stack.set(DataComponents.DAMAGE_RESISTANT, new DamageResistant(
                level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypeTags.IS_FIRE)));
        var item = new ItemEntity(level, at.x, at.y, at.z, stack);
        item.setNeverPickUp();
        item.setUnlimitedLifetime();
        item.setPermanentlyInvulnerable(true);
        item.setCustomName(Component.literal("复生图腾").withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.GOLD)));
        item.setCustomNameVisible(true);
        item.setGlowingTag(true);
        level.addFreshEntity(item);
        return item;
    }

    /**
     * Every commit settles pending drops: registered ones become takeable and are announced; those whose death
     * committed without registering them, or whose round is gone, are removed.
     */
    public static void onCommitted(MinecraftServer server, RoundSnapshot before, RoundSnapshot after) {
        var drops = INSTANCES.get(server);
        if (drops != null) drops.settle(server, after);
    }

    private void settle(MinecraftServer server, RoundSnapshot after) {
        var registered = new ArrayList<LooseTotem>();
        var doomed = new HashSet<UUID>();
        for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            var totem = entry.getValue().round().equals(after.roundId()) ? after.looseTotems().get(entry.getKey()) : null;
            if (totem != null) registered.add(totem);
            else if (waiting(after, entry.getValue())) continue;
            else doomed.add(entry.getKey());
            it.remove();
        }
        if (!doomed.isEmpty()) removeLoaded(server, doomed);
        if (!registered.isEmpty()) announce(server, after, registered);
    }

    /**
     * The pending death has not committed yet: its human life is still the committed one of a live round. A recovery
     * drops every queued death, so a round outside RUNNING and RESCUE can no longer register the token.
     */
    private boolean waiting(RoundSnapshot round, Pending drop) {
        var victim = round == null ? null : round.participants().get(drop.victim());
        return victim != null && access.open() && round.roundId().equals(drop.round())
                && (round.phase() == dev.hardrush.core.round.RoundPhase.RUNNING || round.phase() == dev.hardrush.core.round.RoundPhase.RESCUE)
                && victim.role() == ParticipantRole.HUMAN_ACTIVE && victim.lifeSequence() == drop.life();
    }

    private static void removeLoaded(MinecraftServer server, Set<UUID> tokens) {
        for (var level : server.getAllLevels()) {
            for (var item : level.getEntities(EntityTypeTest.forClass(ItemEntity.class),
                    item -> RevivalTotems.dropToken(item.getItem()).filter(tokens::contains).isPresent())) item.discard();
        }
    }

    /**
     * T1 once per player and round, to everyone of the round still playing (humans and monsters, connected, a player
     * still entering included) except the batch's killers and victims and administrative spectators. The killer's name
     * stays out: the native death message already names both, this line only adds that something now lies there.
     */
    private void announce(MinecraftServer server, RoundSnapshot after, List<LooseTotem> drops) {
        var involved = new HashSet<UUID>();
        var victims = new LinkedHashSet<UUID>();
        for (var drop : drops) { involved.add(drop.victim()); involved.add(drop.killer()); victims.add(drop.victim()); }
        var first = Optional.ofNullable(after.participants().get(victims.iterator().next())).map(Participant::name).orElse("一名人类");
        String line = victims.size() == 1 ? Notices.key(first) + " 被人类击杀，掉落了复生图腾"
                : Notices.key(first) + " 等 " + Notices.key(victims.size()) + " 人被人类击杀，掉落了复生图腾";
        for (var p : after.participants().values()) {
            if (involved.contains(p.id()) || !p.connected() || p.role().isExcluded()) continue;
            var player = server.getPlayerList().getPlayer(p.id());
            if (player == null || dev.hardrush.player.PlayerProfiles.isAdministrativeSpectator(player)) continue;
            if (Notices.firstInRound(player, after.roundId(), DROP_TOLD)) Notices.hint(player, line);
        }
    }

    /**
     * {@code ItemEntity.playerTouch} on any floor revival totem, after native pickup was cancelled: a drop registered at
     * least {@link #PICKUP_DELAY_NANOS} of race time ago and unclaimed is taken by a human whose inventory shows the
     * balance and has room for one more (the stack it already holds, a balance already beyond a full stack, or a free
     * slot; otherwise, like vanilla, nothing happens). The entity is removed only after the commit.
     */
    public static void touch(ServerPlayer player, ItemEntity item) {
        var drops = INSTANCES.get(player.level().getServer());
        if (drops != null) drops.pickup(player, item);
    }

    private void pickup(ServerPlayer player, ItemEntity item) {
        var token = RevivalTotems.dropToken(item.getItem()).orElse(null);
        if (token == null || pending.containsKey(token) || claimed.contains(token)) return;
        if (!player.isAlive() || player.isSpectator()) return;
        // The gates other playerTouch mixins apply (a frozen competition, a protected lobby body, a monster) are all
        // inside the projection's PROJECT state (unlocked competition, verified round body of an active human), so
        // the outcome does not depend on which HEAD injection runs first.
        var r = access.view().orElse(null);
        var registered = r == null ? null : r.liveLooseTotem(token).orElse(null);
        if (registered == null || r.challengeNanos() - registered.droppedAtChallengeNanos() < PICKUP_DELAY_NANOS) return;
        var p = r.participants().get(player.getUUID());
        if (p == null || p.role() != ParticipantRole.HUMAN_ACTIVE || !p.online() || !access.collects(player) || !room(player, p)) return;
        claimed.add(token);
        UUID roundId = r.roundId();
        access.requestPickup(roundId, token, p.id(), p.lifeSequence(), reply -> {
            claimed.remove(token);
            if (!reply.success()) return;
            // A stale object (unloaded or carried through a portal meanwhile) is left to the patrol: its token is gone.
            if (!item.isRemoved()) { player.take(item, 1); item.discard(); }
            access.refresh(player);
            if (Notices.firstInRound(player, roundId, PICKUP_TAUGHT)) Notices.hint(player, PICKUP_TIP);
        });
    }

    /** Whether the projection has room for one more totem (design 1.3). */
    private static boolean room(ServerPlayer player, Participant p) {
        if (p.revivalCharges() >= RevivalTotems.MAX_STACK) return true;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getNonEquipmentItems().size(); i++) {
            var stack = inventory.getItem(i);
            if (RevivalTotems.is(stack) && stack.getCount() < stack.getMaxStackSize()) return true;
        }
        var offhand = inventory.getItem(Inventory.SLOT_OFFHAND);
        if (RevivalTotems.is(offhand) && offhand.getCount() < offhand.getMaxStackSize()) return true;
        return inventory.getFreeSlot() >= 0;
    }

    /** {@code HopperBlockEntity.addItem(Container, ItemEntity)}, which hopper minecarts share: never a revival totem. */
    public static boolean hopperRefuses(ItemEntity item) { return RevivalTotems.is(item.getItem()); }

    public static void tick(MinecraftServer server) {
        var drops = INSTANCES.get(server);
        if (drops == null) return;
        drops.inform(server);
        if (server.getTickCount() % 20 == 0) drops.patrol(server);
    }

    /** Sends the glow team to whoever does not have it for this connection and round yet. */
    private void inform(MinecraftServer server) {
        var current = access.view().map(RoundSnapshot::roundId).orElse(null);
        if (!Objects.equals(current, round)) {
            round = current;
            claimed.clear();
            for (var player : server.getPlayerList().getPlayers()) {
                if (informed.get(player.getUUID()) == player.connection) player.connection.send(ClientboundSetPlayerTeamPacket.createRemovePacket(team));
            }
            scoreboard.removePlayerTeam(team);
            team = goldTeam(scoreboard);
            informed.clear();
        }
        informed.keySet().retainAll(server.getPlayerList().getPlayers().stream().map(ServerPlayer::getUUID).toList());
        for (var player : server.getPlayerList().getPlayers()) {
            if (informed.get(player.getUUID()) == player.connection) continue;
            player.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
            informed.put(player.getUUID(), player.connection);
        }
    }

    /** Puts a floor totem into the glow team, telling everyone who already has the team. */
    private void member(MinecraftServer server, ItemEntity item) {
        var name = item.getScoreboardName();
        if (team.getPlayers().contains(name)) return;
        scoreboard.addPlayerToTeam(name, team);
        var join = ClientboundSetPlayerTeamPacket.createPlayerPacket(team, name, ClientboundSetPlayerTeamPacket.Action.ADD);
        for (var player : server.getPlayerList().getPlayers()) {
            if (informed.get(player.getUUID()) == player.connection) player.connection.send(join);
        }
    }

    /**
     * Once a second over every loaded world: a floor revival totem stays only while it is pending or registered and
     * not expired. Copies without a token (a projected copy that slipped out) and drops of another round go too, so
     * a new round's empty registry clears the last round's drops.
     */
    static void patrol(MinecraftServer server) {
        var drops = INSTANCES.get(server);
        if (drops != null) drops.sweep(server);
    }

    private void sweep(MinecraftServer server) {
        var r = access.view().orElse(null);
        pending.values().removeIf(drop -> !waiting(r, drop));
        for (var level : server.getAllLevels()) {
            for (var item : level.getEntities(EntityTypeTest.forClass(ItemEntity.class), item -> RevivalTotems.is(item.getItem()))) {
                var token = RevivalTotems.dropToken(item.getItem()).orElse(null);
                if (token != null && (pending.containsKey(token) || r != null && r.liveLooseTotem(token).isPresent())) member(server, item);
                else item.discard();
            }
        }
    }
}
