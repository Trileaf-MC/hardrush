package dev.hardrush.revival;

import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.round.LooseTotem;
import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ParticipantRole;
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
 * Revival totems lying in the world (docs/plan/复生图腾掉落.md 2.3, 2.4; docs/plan/复生图腾守恒.md 1.1 to 1.5): every
 * human life that ends, and every revival totem that saves one, releases a totem that no monster earned, at
 * the moment of the death or the save ({@link TotemDrop#release}). Any human of the round or monster out hunting may
 * take it, except the very human life that released it ({@link LooseTotem#ownedBy}). The committed round's
 * {@code looseTotems} registry is the only proof a floor totem may be taken, and a floor totem is known by its drop
 * token alone, never by its entity object: a chunk unload removes the object and a portal replaces it.
 *
 * <p>A drop goes through two states. <em>Pending</em> (memory only): spawned at the death or the save, its event not
 * committed yet; touching it does nothing and the patrol leaves it alone. <em>Registered</em>: the committed snapshot lists its
 * token; it can be taken once {@link #PICKUP_DELAY_NANOS} of race time have passed since it was registered, until the
 * round ends. The entity never despawns on its own ({@code setUnlimitedLifetime}): vanilla's lifetime
 * counts entity ticks, which a faster tick rate or a paused race with chunks still simulating would run ahead of the
 * registry, so the patrol alone removes it once its registration is gone. A pending drop
 * whose death committed without registering it (vetoed on commit, a settling or round-ending batch, a failed commit,
 * another round) is removed, and only a registered drop is announced. Registration lives in the snapshot, so it
 * survives a restart or a checkpoint restore together with the world; a pending drop does not, and is swept.
 *
 * <p>Every floor totem refuses native pickup, hoppers and mobs ({@code setNeverPickUp}); a player takes one through a
 * committed {@code TOTEM_PICKUP} first, as camp coins do. Its gold outline comes from a team that exists in packets
 * only ({@value #TEAM}), whose members are the drops' entity UUIDs; the server scoreboard is never written. A drop
 * whose death was below the world lies where the fall began; a registered drop the void takes returns once to where it
 * was dropped, then to the dimension's arrival point ({@link #returnFromVoid}).
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
    private static final String FORCED_TOLD = "revival_totem_forced";
    private static final String OWN_TOLD = "revival_totem_own";
    static final String OWN_TIP = "这是你用掉的复生图腾，自己捡不回来";

    /** What the floor totems read from and ask of the round runtime. */
    public interface Access {
        /** The committed round projected to now ({@code RoundSession.view}): its registry and race clock decide. */
        Optional<RoundSnapshot> view();
        /** False once the session can no longer commit a pending death (failed, stopping): pending drops are void. */
        boolean open();
        /** {@link RevivalTotems#projectsHuman}: only a human whose inventory shows the balance may take a totem. */
        boolean collects(ServerPlayer player);
        /** A monster's body out hunting that may act now: the only monster that may take a totem. */
        boolean hunts(ServerPlayer player);
        /** Re-projects the taker's stack once the pickup is committed. */
        void refresh(ServerPlayer player);
        /** {@code RoundSession.requestLooseTotemPickup}. */
        void requestPickup(UUID round, UUID token, UUID player, int life, int sortie, Consumer<RoundSession.Reply> reply);
        /** {@code RoundSession.totemSaveDropInFlight}: the save that dropped {@code token} may still register it. */
        boolean saveInFlight(UUID token);
    }

    /** Where a floor totem lies, or is to lie. */
    public record Place(ServerLevel level, Vec3 at) {
        public Place { Objects.requireNonNull(level); Objects.requireNonNull(at); }
    }

    /**
     * A drop spawned at {@code place} at a death of {@code victim}'s human life {@code life}, or at a save of it
     * ({@code save}), whose event has not committed yet.
     */
    private record Pending(UUID round, UUID victim, int life, boolean save, Place place) {}

    private static final Map<MinecraftServer, TotemDrops> INSTANCES = new IdentityHashMap<>();
    private final Access access;
    /** In death order, so an announcement names the first victim of its batch. */
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    /** Where each prepared drop is to be spawned once its event is captured; a commit inside the capture keeps it. */
    private final Map<UUID, Place> unspawned = new HashMap<>();
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
     * {@code AFTER_DEATH}, before the death is captured: whether this death drops a totem in the world, judged by the
     * same {@link TotemDrop#release} the engine applies on commit against the session's committed round (a monster's
     * kill is paid instead). A death below the world drops where the fall began ({@link #place}). The returned token is
     * pending until {@link #captured}.
     */
    public static Optional<UUID> prepare(ServerPlayer victim, RoundSnapshot round, Optional<CombatOrigin> killCredit) {
        return prepare(victim, round, killCredit, false);
    }

    /**
     * In {@code TotemSave}, before the save is booked: whether this hit, which a revival totem is about to survive, drops
     * a totem, by the same {@link TotemDrop#release} as a death, against the round as the session sees it now. The
     * returned token is pending until {@link #captured} and stays so while its save is in flight.
     */
    public static Optional<UUID> prepareSave(ServerPlayer victim, Optional<CombatOrigin> killCredit) {
        var drops = INSTANCES.get(victim.level().getServer());
        var round = drops == null ? null : drops.access.view().orElse(null);
        return round == null ? Optional.empty() : prepare(victim, round, killCredit, true);
    }

    private static Optional<UUID> prepare(ServerPlayer victim, RoundSnapshot round, Optional<CombatOrigin> killCredit, boolean save) {
        var drops = INSTANCES.get(victim.level().getServer());
        var participant = round.participants().get(victim.getUUID());
        if (drops == null || participant == null) return Optional.empty();
        var release = TotemDrop.release(round, victim.getUUID(), killCredit).orElse(null);
        if (!(release instanceof TotemDrop.Release.Dropped)) return Optional.empty();
        var token = UUID.randomUUID();
        var place = place(victim);
        drops.pending.put(token, new Pending(round.roundId(), victim.getUUID(), participant.lifeSequence(), save, place));
        drops.unspawned.put(token, place);
        return Optional.of(token);
    }

    /**
     * Where a totem released by {@code victim} lies: at the body, unless nothing below it would ever stop the totem (a
     * death below the world, or in mid-air over the void, as when shot or knocked off the End island). Then it lies
     * where vanilla's {@code startingToFallPosition} says the fall began, on the nearest standing ground there. Without a
     * recorded start (a glide, a teleport), a death below the world drops at the dimension's arrival point
     * ({@link #arrival}), one in mid-air at the body, which the void then returns ({@link #returnFromVoid}).
     */
    static Place place(ServerPlayer victim) {
        var level = victim.level();
        var body = new Place(level, new Vec3(victim.getX(), victim.getEyeY() - 0.3, victim.getZ()));
        boolean below = victim.getY() < level.getMinY();
        if (!below && !overVoid(level, victim.blockPosition())) return body;
        var start = ((dev.hardrush.revival.mixin.TotemFallStartAccessor) victim).hardrush$fallStart();
        if (start == null || start.y < level.getMinY()) return below ? arrival(level) : body;
        return new Place(level, ground(level, start).orElse(start));
    }

    /** Whether nothing solid and no fluid lies in the column from {@code at} down to the world's floor. */
    private static boolean overVoid(ServerLevel level, net.minecraft.core.BlockPos at) {
        if (!level.isLoaded(at)) return false;
        var cursor = at.mutable();
        for (int y = Math.min(at.getY(), level.getMaxY()); y >= level.getMinY(); y--) {
            cursor.setY(y);
            var state = level.getBlockState(cursor);
            if (!state.getCollisionShape(level, cursor).isEmpty() || !state.getFluidState().isEmpty()) return false;
        }
        return true;
    }

    /** The standing spot closest to {@code near}, within two blocks across and three down; empty over open air. */
    private static Optional<Vec3> ground(ServerLevel level, Vec3 near) {
        var origin = net.minecraft.core.BlockPos.containing(near);
        Vec3 best = null;
        for (int dy = 1; dy >= -3; dy--) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            var feet = origin.offset(dx, dy, dz);
            var floor = feet.below();
            if (!level.isLoaded(feet) || !level.getBlockState(floor).isFaceSturdy(level, floor, net.minecraft.core.Direction.UP)
                    || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) continue;
            var at = Vec3.atBottomCenterOf(feet);
            if (best == null || at.distanceToSqr(near) < best.distanceToSqr(near)) best = at;
        }
        return Optional.ofNullable(best);
    }

    /**
     * The dimension's arrival point, always standing ground: the End's obsidian platform, otherwise the round's spawn
     * (a round level answers its bundle's respawn data, which lies in the round's Overworld).
     */
    static Place arrival(ServerLevel level) {
        if (dev.hardrush.world.RoundWorldBundle.semanticDimension(level).equals(net.minecraft.world.level.Level.END)) {
            return new Place(level, Vec3.atBottomCenterOf(ServerLevel.END_SPAWN_POINT));
        }
        var spawn = level.getRespawnData();
        var target = level.getServer().getLevel(spawn.dimension());
        return new Place(target == null ? level : target, Vec3.atBottomCenterOf(spawn.pos()));
    }

    /**
     * After {@code captureDeath} or {@code captureTotemSave}: a captured death drops its totem at the body, in the same
     * tick as vanilla's death loot, and a booked save at the saved human's feet as its totem pops; a death the session
     * did not take, or a save it refused, drops nothing. A commit that already ran inside the capture has decided.
     */
    public static void captured(ServerPlayer victim, UUID round, UUID token, boolean accepted) {
        var drops = INSTANCES.get(victim.level().getServer());
        if (drops == null) return;
        var place = drops.unspawned.remove(token);
        if (!accepted) { drops.pending.remove(token); return; }
        boolean registered = drops.access.view().filter(r -> r.looseTotems().containsKey(token)).isPresent();
        if (place == null || !drops.pending.containsKey(token) && !registered) return;
        drops.member(victim.level().getServer(), spawn(place, token, round));
    }

    /** The floor totem itself: fireproof, invulnerable, never picked up natively, named and glowing. */
    static ItemEntity spawn(Place place, UUID token, UUID round) {
        var level = place.level();
        var at = place.at();
        var stack = RevivalTotems.dropped(token, round, place);
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
        var killed = new ArrayList<LooseTotem>();
        var forced = new ArrayList<LooseTotem>();
        var unclaimed = new ArrayList<Pending>();
        var doomed = new HashSet<UUID>();
        for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            var drop = entry.getValue();
            var totem = drop.round().equals(after.roundId()) ? after.looseTotems().get(entry.getKey()) : null;
            if (totem != null && totem.source() == LooseTotem.Source.UNCLAIMED) unclaimed.add(drop);
            else if (totem != null) (drop.save() ? forced : killed).add(totem);
            else if (waiting(after, entry.getKey(), drop)) continue;
            else doomed.add(entry.getKey());
            it.remove();
        }
        if (!doomed.isEmpty()) removeLoaded(server, doomed);
        if (!killed.isEmpty()) announce(server, after, killed, false);
        if (!forced.isEmpty()) announce(server, after, forced, true);
        if (!unclaimed.isEmpty()) announceUnclaimed(server, after, unclaimed);
    }

    /**
     * The pending event has not committed yet. A death's: its human life is still the committed one of a live round. A
     * save's: the session still holds its event, which releases it before any listener sees the commit. A recovery
     * drops every queued event, so a round outside RUNNING and RESCUE can no longer register the token.
     */
    private boolean waiting(RoundSnapshot round, UUID token, Pending drop) {
        var victim = round == null ? null : round.participants().get(drop.victim());
        if (victim == null || !access.open() || !round.roundId().equals(drop.round())
                || round.phase() != dev.hardrush.core.round.RoundPhase.RUNNING
                && round.phase() != dev.hardrush.core.round.RoundPhase.RESCUE) return false;
        return drop.save() ? access.saveInFlight(token)
                : victim.role() == ParticipantRole.HUMAN_ACTIVE && victim.lifeSequence() == drop.life();
    }

    private static void removeLoaded(MinecraftServer server, Set<UUID> tokens) {
        for (var level : server.getAllLevels()) {
            for (var item : level.getEntities(EntityTypeTest.forClass(ItemEntity.class),
                    item -> RevivalTotems.dropToken(item.getItem()).filter(tokens::contains).isPresent())) item.discard();
        }
    }

    /**
     * T1 once per player and round, to everyone of the round still playing (humans and monsters, connected, a player
     * still entering included) except the commit's attackers and victims and administrative spectators. The attacker's
     * name stays out: the native death message already names both, this line only adds that something now lies there.
     * Drops from deaths ({@link #DROP_TOLD}) and from forced-out totems ({@link #FORCED_TOLD}) are each told once.
     */
    private void announce(MinecraftServer server, RoundSnapshot after, List<LooseTotem> drops, boolean forced) {
        var involved = new HashSet<UUID>();
        var victims = new LinkedHashSet<UUID>();
        for (var drop : drops) { involved.add(drop.victim()); drop.killer().ifPresent(involved::add); victims.add(drop.victim()); }
        var first = name(after, victims.iterator().next());
        String line = forced ? forcedLine(first, victims.size()) : killedLine(first, victims.size());
        for (var player : audience(server, after)) {
            if (involved.contains(player.getUUID())) continue;
            if (Notices.firstInRound(player, after.roundId(), forced ? FORCED_TOLD : DROP_TOLD)) Notices.hint(player, line);
        }
    }

    /**
     * An unclaimed drop (an accident, a suicide, the environment forcing a totem out) tells everyone of the round still
     * playing where it lies, every time (守恒 1.5): one chat line per drop, the victim included, since its own drop is
     * worth fetching once that life is over. The death message, or the in-place revival's line, already says what
     * happened; this line only adds where, so a death and a save read alike.
     */
    private void announceUnclaimed(MinecraftServer server, RoundSnapshot after, List<Pending> drops) {
        var lines = drops.stream().map(drop -> unclaimedLine(name(after, drop.victim()), drop.place())).toList();
        for (var player : audience(server, after)) lines.forEach(line -> Notices.personal(player, line));
    }

    /** Everyone of the round still playing: humans and monsters, connected (one still entering included), not excluded. */
    private static List<ServerPlayer> audience(MinecraftServer server, RoundSnapshot after) {
        var players = new ArrayList<ServerPlayer>();
        for (var p : after.participants().values()) {
            if (!p.connected() || p.role().isExcluded()) continue;
            var player = server.getPlayerList().getPlayer(p.id());
            if (player != null && !dev.hardrush.player.PlayerProfiles.isAdministrativeSpectator(player)) players.add(player);
        }
        return players;
    }

    private static String name(RoundSnapshot round, UUID id) {
        return Optional.ofNullable(round.participants().get(id)).map(Participant::name).orElse("一名人类");
    }

    /** The void sent a registered drop to the arrival point, far from where it was told to lie: everyone hears again. */
    static String movedLine(String victim, Place place) {
        var at = net.minecraft.core.BlockPos.containing(place.at());
        return Notices.name(victim) + " 留下的复生图腾被送到了 " + dimension(place.level())
                + " " + Notices.key("(" + at.getX() + ", " + at.getY() + ", " + at.getZ() + ")");
    }

    static String unclaimedLine(String victim, Place place) {
        var at = net.minecraft.core.BlockPos.containing(place.at());
        return Notices.name(victim) + " 留下的复生图腾在 " + dimension(place.level())
                + " " + Notices.key("(" + at.getX() + ", " + at.getY() + ", " + at.getZ() + ")");
    }

    private static String dimension(ServerLevel level) {
        var semantic = dev.hardrush.world.RoundWorldBundle.semanticDimension(level);
        if (semantic.equals(net.minecraft.world.level.Level.NETHER)) return "下界";
        if (semantic.equals(net.minecraft.world.level.Level.END)) return "末地";
        return "主世界";
    }

    static String killedLine(String first, int victims) {
        return victims == 1 ? Notices.key(first) + " 被人类击杀，掉落了复生图腾"
                : Notices.key(first) + " 等 " + Notices.key(victims) + " 人被人类击杀，掉落了复生图腾";
    }

    static String forcedLine(String first, int victims) {
        return victims == 1 ? Notices.key(first) + " 被人类逼出复生图腾，掉落了 1 个"
                : Notices.key(first) + " 等 " + Notices.key(victims) + " 人被人类逼出复生图腾，各掉落了 1 个";
    }

    /**
     * {@code ItemEntity.playerTouch} on any floor revival totem, after native pickup was cancelled: a drop registered at
     * least {@link #PICKUP_DELAY_NANOS} of race time ago and not being claimed is taken by a human whose inventory shows
     * the balance and has room for one more (the stack it already holds, a balance already beyond a full stack, or a
     * free slot; otherwise, like vanilla, nothing happens), or by a monster out hunting, which needs no room: its balance
     * shows at the altar. The human life that released it is told once a round that it never takes it back. The entity
     * is removed only after the commit.
     */
    public static void touch(ServerPlayer player, ItemEntity item) {
        var drops = INSTANCES.get(player.level().getServer());
        if (drops != null) drops.pickup(player, item);
    }

    private void pickup(ServerPlayer player, ItemEntity item) {
        var token = RevivalTotems.dropToken(item.getItem()).orElse(null);
        if (token == null || pending.containsKey(token) || claimed.contains(token)) return;
        if (!player.isAlive() || player.isSpectator()) return;
        // The gates other playerTouch mixins apply (a frozen competition, a protected lobby body, a monster that may
        // not act) are all inside the projection's PROJECT state for a human (unlocked competition, verified round body
        // of an active human) and inside hunts() for a monster, so the outcome does not depend on which HEAD injection
        // runs first.
        var r = access.view().orElse(null);
        var registered = r == null ? null : r.looseTotem(token).orElse(null);
        if (registered == null || r.challengeNanos() - registered.droppedAtChallengeNanos() < PICKUP_DELAY_NANOS) return;
        var p = r.participants().get(player.getUUID());
        if (p == null || !p.online()) return;
        if (registered.ownedBy(p)) {
            if (Notices.firstInRound(player, r.roundId(), OWN_TOLD)) Notices.refuse(player, OWN_TIP);
            return;
        }
        boolean human = p.role() == ParticipantRole.HUMAN_ACTIVE;
        if (human ? !access.collects(player) || !room(player, p)
                : p.role() != ParticipantRole.MONSTER_DEPLOYED || !access.hunts(player)) return;
        claimed.add(token);
        UUID roundId = r.roundId();
        access.requestPickup(roundId, token, p.id(), p.lifeSequence(), p.monsterDeploymentSequence(), reply -> {
            claimed.remove(token);
            if (!reply.success()) return;
            // A stale object (unloaded or carried through a portal meanwhile) is left to the patrol: its token is gone.
            if (!item.isRemoved()) { player.take(item, 1); item.discard(); }
            access.refresh(player);
            // A monster's gain is told like a kill's, with the altar (LobbyRuntime).
            if (human && Notices.firstInRound(player, roundId, PICKUP_TAUGHT)) Notices.hint(player, PICKUP_TIP);
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

    /**
     * {@code Entity.onBelowWorld} of a floor totem: a drop still pending or registered is not destroyed but returned,
     * the first time to where it was dropped, after that (it was dropped over open air) to the dimension's arrival
     * point, which is normally standing ground (the End platform can be mined away; vanilla rebuilds it when a player
     * arrives). Reaching the arrival point is told again, with its coordinates, to everyone of the round still playing:
     * whoever followed the first coordinates would find nothing. Anything else falls as vanilla's void decides. True
     * when it was returned.
     */
    public static boolean returnFromVoid(ItemEntity item) {
        if (!(item.level() instanceof ServerLevel level)) return false;
        var drops = INSTANCES.get(level.getServer());
        var token = RevivalTotems.dropToken(item.getItem()).orElse(null);
        if (drops == null || token == null) return false;
        var round = drops.access.view().orElse(null);
        var registered = round == null ? null : round.looseTotems().get(token);
        if (registered == null && !drops.pending.containsKey(token)) return false;
        var first = RevivalTotems.returnedFromVoid(item.getItem()) ? Optional.<Place>empty()
                : RevivalTotems.dropPlace(level.getServer(), item.getItem());
        var target = first.orElseGet(() -> arrival(level));
        if (first.isEmpty() && registered != null) {
            var line = movedLine(name(round, registered.victim()), target);
            for (var player : audience(level.getServer(), round)) Notices.personal(player, line);
        }
        RevivalTotems.markReturnedFromVoid(item.getItem());
        item.setDeltaMovement(Vec3.ZERO);
        item.resetFallDistance();
        if (target.level() == level) item.teleportTo(target.at().x, target.at().y, target.at().z);
        else item.teleport(new net.minecraft.world.level.portal.TeleportTransition(target.level(), target.at(), Vec3.ZERO,
                item.getYRot(), item.getXRot(), net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
        return true;
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
     * Once a second over every loaded world: a floor revival totem stays only while it is pending or registered. Copies without a token (a projected copy that slipped out) and drops of another round go too, so
     * a new round's empty registry clears the last round's drops.
     */
    static void patrol(MinecraftServer server) {
        var drops = INSTANCES.get(server);
        if (drops != null) drops.sweep(server);
    }

    private void sweep(MinecraftServer server) {
        var r = access.view().orElse(null);
        pending.entrySet().removeIf(entry -> !waiting(r, entry.getKey(), entry.getValue()));
        // A drop is spawned in the same call that captured its event; one left here was never captured (that call threw).
        unspawned.keySet().retainAll(pending.keySet());
        for (var level : server.getAllLevels()) {
            for (var item : level.getEntities(EntityTypeTest.forClass(ItemEntity.class), item -> RevivalTotems.is(item.getItem()))) {
                var token = RevivalTotems.dropToken(item.getItem()).orElse(null);
                if (token != null && (pending.containsKey(token) || r != null && r.looseTotem(token).isPresent())) member(server, item);
                else item.discard();
            }
        }
    }
}
